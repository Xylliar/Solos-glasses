package com.solos.relay

import android.util.Log
import com.solosglasses.solosairgosdk.core.SolosGlasses
import com.solosglasses.solosairgosdk.core.sensor.AbsoluteOrientationData
import com.solosglasses.solosairgosdk.core.sensor.AbsoluteOrientationSensorConfig
import com.solosglasses.solosairgosdk.core.sensor.AbsoluteOrientationSensorListener
import com.solosglasses.solosairgosdk.core.sensor.MagnetometerCalibrationAccuracy
import com.solosglasses.solosairgosdk.core.sensor.MagnetometerCalibrationState
import com.solosglasses.solosairgosdk.core.system.CalibrationData
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which way the user's head is pointing, from the glasses' e-compass
 * (the SDK's AbsoluteOrientationSensor - the same one behind the demo app's
 * "3D Orientation" screen).
 *
 * This is direction only. There is still no position: the glasses know which way you
 * face, never where you are. Navigation pairs this with the camera - a sign or booth
 * number fixes the position, the heading says which way to go from it.
 *
 * Compasses indoors are unreliable (steel, electronics), so every reading carries the
 * SDK's own calibration state and accuracy, and [Reading.reliable] says whether to trust
 * it. Never steer someone on an unreliable heading without saying so.
 */
class HeadTracker(private val glasses: SolosGlasses) : AbsoluteOrientationSensorListener {

    data class Reading(
        /** 0-360, 0 = north, 90 = east. Increases turning right (clockwise). */
        val yaw: Double,
        /** -90..90, positive looking up. */
        val pitch: Double,
        val state: MagnetometerCalibrationState,
        val accuracy: MagnetometerCalibrationAccuracy,
        val progress: Int,
        val atMs: Long,
    ) {
        val reliable: Boolean
            get() = state == MagnetometerCalibrationState.CALIBRATED &&
                accuracy != MagnetometerCalibrationAccuracy.LOW

        val compass: String get() = compassPoint(yaw)

        fun describe(): String {
            val quality = when {
                state == MagnetometerCalibrationState.MAGNETIC_INTERFERENCE ->
                    "UNRELIABLE - magnetic interference"
                state == MagnetometerCalibrationState.UNCALIBRATED ->
                    "UNRELIABLE - compass not calibrated yet ($progress%)"
                else -> "accuracy $accuracy"
            }
            return "heading ${yaw.roundToInt()} degrees ($compass), " +
                "pitch ${pitch.roundToInt()} degrees, $quality"
        }
    }

    @Volatile var latest: Reading? = null
        private set

    /** Fresh = updated in the last few seconds. A stale reading means the stream died. */
    val fresh: Reading?
        get() = latest?.takeIf { System.currentTimeMillis() - it.atMs < 3_000 }

    @Volatile var running = false
        private set

    /** Last few seconds of readings, so a video frame can get the heading of the moment it was filmed. */
    private val history = ArrayDeque<Reading>()

    /** The reading closest to [timeMs] (wall clock), from the last ~10 s. */
    fun at(timeMs: Long): Reading? = synchronized(history) {
        history.minByOrNull { kotlin.math.abs(it.atMs - timeMs) }
            ?.takeIf { kotlin.math.abs(it.atMs - timeMs) < 1_000 }
    }

    // Reused across restarts so the user doesn't have to redo the figure-eight.
    @Volatile private var calibration: CalibrationData? = null
    private val lock = Mutex()

    companion object {
        private const val TAG = "HeadTracker"

        fun compassPoint(yaw: Double): String {
            val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return points[(((yaw % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        }

        /** Signed turn from [from] to [to], -180..180. Positive = turn right. */
        fun turn(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

        fun normalize(deg: Double): Double = (deg % 360 + 360) % 360
    }

    suspend fun start() = lock.withLock {
        if (running) return@withLock
        val sensor = glasses.sensor?.absoluteOrientationSensor
            ?: throw IllegalStateException("these glasses have no orientation sensor")
        sensor.addListener(this)
        val cal = calibration
        if (cal != null) sensor.start(AbsoluteOrientationSensorConfig(cal)) else sensor.start()
        running = true
        Log.i(TAG, "orientation sensor started (saved calibration: ${cal != null})")
    }

    /** After a link loss the stream is dead even though we think it's running. */
    suspend fun restart() {
        if (!running) return
        stop()
        start()
    }

    suspend fun stop() = lock.withLock {
        if (!running) return@withLock
        val sensor = glasses.sensor?.absoluteOrientationSensor
        runCatching { sensor?.removeListener(this) }
        runCatching { sensor?.stop() }
        running = false
        Log.i(TAG, "orientation sensor stopped")
    }

    override fun onAbsoluteOrientationChanged(data: AbsoluteOrientationData) {
        val cal = data.magnetometerCalibrationStatus
        val r = Reading(
            yaw = normalize(data.eulerAngles.yaw),
            pitch = data.eulerAngles.pitch,
            state = cal.calibrationState,
            accuracy = cal.calibrationAccuracy,
            progress = cal.calibrationProgress,
            atMs = System.currentTimeMillis(),
        )
        latest = r
        synchronized(history) {
            history.addLast(r)
            while (history.isNotEmpty() && r.atMs - history.first().atMs > 10_000) history.removeFirst()
        }
    }

    override fun onMagnetometerCalibrationUpdated(calibrationData: CalibrationData) {
        calibration = calibrationData
        Log.d(TAG, "calibration updated")
    }
}

/**
 * Closed-loop "turn your head until I say stop", run entirely on the phone.
 *
 * This cannot go through the LLM: one round trip is a couple of seconds, and a head
 * turns 90 degrees in half a second. The model picks the target heading; this loop does
 * the steering at sensor rate and only reports back when it is over.
 */
class Steering(
    private val head: HeadTracker,
    private val say: (String) -> Unit,
) {
    sealed class Outcome {
        data class Aligned(val reading: HeadTracker.Reading) : Outcome()
        data class Interrupted(val action: String, val reading: HeadTracker.Reading?) : Outcome()
        data class TimedOut(val reading: HeadTracker.Reading?) : Outcome()
        data class NoSignal(val why: String) : Outcome()
    }

    /**
     * @param target        absolute compass heading to face
     * @param tolerance     degrees either side that counts as "facing it"
     * @param pollAction    returns a bound gesture's action if the user fired one, else null
     */
    suspend fun steer(
        target: Double,
        tolerance: Double,
        timeoutMs: Long,
        pollAction: () -> String?,
    ): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastWord: String? = null
        var lastSpokenAt = 0L
        var insideSince = 0L

        while (System.currentTimeMillis() < deadline) {
            pollAction()?.let { return Outcome.Interrupted(it, head.fresh) }

            val r = head.fresh
                ?: return Outcome.NoSignal("no heading data from the glasses")

            val d = HeadTracker.turn(r.yaw, target)
            val now = System.currentTimeMillis()

            if (abs(d) <= tolerance) {
                if (insideSince == 0L) insideSince = now
                // Hold briefly so a head swinging through the target doesn't count.
                if (now - insideSince >= 400) {
                    say(Lang.current.steer("stop"))
                    return Outcome.Aligned(r)
                }
            } else {
                insideSince = 0L
                val word = Lang.current.steer(when {
                    abs(d) > 135 -> "around"
                    abs(d) > 45 -> if (d > 0) "right" else "left"
                    else -> if (d > 0) "little_right" else "little_left"
                })
                // Speak on change, or repeat every 1.5s so silence never means "stuck".
                if (word != lastWord || now - lastSpokenAt > 1_500) {
                    say(word)
                    lastWord = word
                    lastSpokenAt = now
                }
            }
            delay(80)
        }
        return Outcome.TimedOut(head.fresh)
    }
}
