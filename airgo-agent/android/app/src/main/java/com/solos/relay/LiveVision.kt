package com.solos.relay

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.solosglasses.solosairgosdk.core.camera.VideoCodec
import com.solosglasses.solosairgosdk.core.camera.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Semi-live vision: the glasses' RTSP video stream, turned into a rolling buffer of
 * compass-tagged JPEG frames.
 *
 * The camera streams H.264 at rtsp://<glasses>:554/live1 (Wi-Fi only). FFmpeg - the
 * ffmpeg-kit build shipped with the Solos SDK - reads it and writes FPS frames a second
 * to a cache folder; a watcher picks each one up, tags it with the heading at that moment
 * and keeps the last BUFFER_SECONDS. No recording, no finalising, no download: a look is
 * instant and a look-around costs only the seconds the user spends turning.
 */
class LiveVision(private val context: Context, private val relay: GlassesRelay) {

    data class LiveFrame(val jpeg: ByteArray, val atMs: Long, val facing: HeadTracker.Reading?)

    companion object {
        private const val TAG = "LiveVision"
        private const val FPS = 2
        private const val BUFFER_SECONDS = 20
        private const val WIDTH = 640
        /**
         * How far a frame lags reality when we pick it up: camera encode + RTSP + FFmpeg +
         * our file polling. Frames get the heading from this long before arrival, so the
         * direction matches what was filmed, not where the head is by the time we see it.
         * An estimate - raise it if tags look "late" when turning.
         */
        const val LATENCY_MS = 700L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = ArrayDeque<LiveFrame>()
    private val dir = File(context.cacheDir, "live")
    private var session: FFmpegSession? = null
    private var watcher: Job? = null

    @Volatile var running = false
        private set
    @Volatile var status: String = "off"
        private set
    @Volatile private var received = 0
    @Volatile private var startedAt = 0L

    /** Frames per second actually arriving (for the log/screen). */
    val fps: Double
        get() = if (running && startedAt > 0) received * 1000.0 / (System.currentTimeMillis() - startedAt) else 0.0

    suspend fun start(): Boolean {
        if (running) return true
        val g = relay.glasses ?: return fail("glasses not connected")
        if (!relay.wifiConnected) return fail("needs Wi-Fi - connect the glasses to a network first")
        if (g.camera == null && !relay.ensureCamera()) return fail("camera not connected")
        val cam = g.camera ?: return fail("camera not connected")
        val supported = runCatching { cam.supportedVideoResolutions }.getOrNull().orEmpty()
        if (supported.isEmpty()) return fail("this camera does not stream video")
        val res = supported.minBy { r ->
            r.name.split('_').let { runCatching { it[1].toInt() * it[2].toInt() }.getOrDefault(Int.MAX_VALUE) }
        }

        status = "starting stream..."
        val uri = runCatching {
            cam.startVideoStream(
                VideoConfiguration(res, VideoCodec.H264, isLEDIndicationEnabled = true,
                    isSoundIndicationEnabled = false)
            )
        }.getOrElse { return fail("stream start failed: ${SolosKey.explain(it)}") }
        Log.i(TAG, "stream at $uri (${res.name})")

        dir.deleteRecursively(); dir.mkdirs()
        synchronized(frames) { frames.clear() }
        received = 0
        startedAt = System.currentTimeMillis()
        runCatching { FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING) }

        // Low-latency read of the RTSP stream, FPS frames a second, scaled down to what
        // Gemini needs. TCP transport: Wi-Fi drops UDP packets and smears frames.
        val args = arrayOf(
            "-hide_banner", "-loglevel", "warning",
            "-rtsp_transport", "tcp",
            "-fflags", "nobuffer", "-flags", "low_delay",
            "-i", uri.toString(),
            "-vf", "fps=$FPS,scale=$WIDTH:-2",
            "-q:v", "6",
            "-f", "image2", "${dir.absolutePath}/f%07d.jpg",
        )
        session = FFmpegKit.executeWithArgumentsAsync(args) { s ->
            Log.i(TAG, "ffmpeg ended: rc=${s.returnCode} ${s.failStackTrace ?: ""}")
            if (running) {
                status = "stream ended (rc=${s.returnCode})"
                relay.notify("live view: stream ended - ${s.logsAsString.takeLast(200)}")
                running = false
            }
        }
        running = true
        status = "live"
        watcher = scope.launch { watch() }
        relay.notify("live view: started ${res.name} -> $FPS fps frames")
        return true
    }

    private suspend fun watch() {
        while (scope.isActive && running) {
            val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
            // The newest file may still be being written; take all but the last.
            for (f in files.dropLast(1)) {
                val bytes = runCatching { f.readBytes() }.getOrNull()
                f.delete()
                if (bytes == null || bytes.size < 1000) continue
                received++
                synchronized(frames) {
                    val filmedAt = System.currentTimeMillis() - LATENCY_MS
                    val facing = relay.head?.at(filmedAt) ?: relay.head?.fresh
                    frames.addLast(LiveFrame(bytes, filmedAt, facing))
                    while (frames.size > FPS * BUFFER_SECONDS) frames.removeFirst()
                }
            }
            delay(120)
        }
    }

    /** The newest frame, if it is at most [maxAgeMs] old. */
    fun latest(maxAgeMs: Long = 1_500 + LATENCY_MS): LiveFrame? = synchronized(frames) {
        frames.lastOrNull()?.takeIf { System.currentTimeMillis() - it.atMs <= maxAgeMs }
    }

    /** Frames that arrived at or after [sinceMs] (wall clock). */
    fun since(sinceMs: Long): List<LiveFrame> = synchronized(frames) { frames.filter { it.atMs >= sinceMs } }

    suspend fun stop() {
        if (!running && session == null) return
        running = false
        watcher?.cancel()
        session?.let { runCatching { FFmpegKit.cancel(it.sessionId) } }
        session = null
        runCatching { relay.glasses?.camera?.stopVideoStream() }
        synchronized(frames) { frames.clear() }
        dir.deleteRecursively()
        status = "off"
        relay.notify("live view: stopped")
    }

    private fun fail(why: String): Boolean {
        status = "off - $why"
        Log.w(TAG, why)
        relay.notify("live view: $why")
        return false
    }
}
