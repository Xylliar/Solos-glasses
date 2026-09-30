package com.solos.relay

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records what the user says and hands back raw audio (WAV) for Gemini to understand
 * directly - no speech-to-text step in between, so nothing is lost to a bad transcript.
 *
 * Source: the glasses' own microphone over Bluetooth HFP when it can be routed. The SDK
 * demo describes it as optimised for close-range speech with surrounding noise
 * suppressed, which is the whole point: the phone mic sits in a pocket. If HFP won't
 * route, falls back to the phone mic.
 *
 * Endpointing is a simple energy detector: wait for speech, stop after a short silence.
 */
class VoiceCapture(private val context: Context) {

    data class Clip(val wav: ByteArray, val seconds: Double, val source: String)

    companion object {
        private const val TAG = "VoiceCapture"
        private const val RATE = 16_000
        private const val FRAME_MS = 30
        private const val SILENCE_END_MS = 900      // this much quiet after speech = done
        private const val MAX_SPEECH_MS = 15_000
    }

    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Beep, then record one utterance.
     *
     * @param waitForSpeechMs how long to wait for the user to START talking
     * @return the clip, or null if they said nothing (or recording is impossible)
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(waitForSpeechMs: Long = 5_000): Clip? = withContext(Dispatchers.IO) {
        val glassesMic = routeToGlassesMic()
        val source = if (glassesMic) "glasses mic (HFP)" else "phone mic"
        val frame = RATE * FRAME_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                if (glassesMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, frame * 2 * 4),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed")
            rec?.release()
            releaseRoute()
            return@withContext null
        }

        val pcm = ByteArrayOutputStream()
        try {
            rec.startRecording()
            // "Speak now". On the call stream while HFP is up, so it plays in the glasses.
            runCatching {
                ToneGenerator(if (glassesMic) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 90)
                    .startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            }

            val buf = ShortArray(frame)
            var noise = 0.0
            var noiseFrames = 0
            var speaking = false
            var speechMs = 0
            var quietMs = 0
            var waitedMs = 0
            // Skip the beep itself, then learn the noise floor for a few frames.
            val skipFrames = 250 / FRAME_MS

            var i = 0
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                i++
                val rms = rms(buf, n)
                if (i <= skipFrames) continue
                if (noiseFrames < 6) { noise += rms; noiseFrames++; continue }
                val floor = noise / noiseFrames
                val threshold = max(floor * 2.5, 350.0)

                if (!speaking) {
                    waitedMs += FRAME_MS
                    if (rms > threshold) {
                        speaking = true
                        Log.i(TAG, "speech start (rms ${rms.toInt()} > ${threshold.toInt()})")
                    } else if (waitedMs > waitForSpeechMs) {
                        Log.i(TAG, "no speech within ${waitForSpeechMs}ms")
                        return@withContext null
                    } else continue
                }
                pcm.write(toBytes(buf, n))
                speechMs += FRAME_MS
                quietMs = if (rms > threshold) 0 else quietMs + FRAME_MS
                if (quietMs >= SILENCE_END_MS || speechMs >= MAX_SPEECH_MS) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            releaseRoute()
        }

        val bytes = pcm.toByteArray()
        val seconds = bytes.size / 2.0 / RATE
        if (seconds < 0.4) return@withContext null
        Clip(wav(bytes), seconds, source)
    }

    // ---------------------------------------------------------------- routing

    private fun routeToGlassesMic(): Boolean = runCatching {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val sco = audio.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return@runCatching false.also { Log.i(TAG, "no Bluetooth HFP device - using phone mic") }
        val ok = audio.setCommunicationDevice(sco)
        if (ok) {
            // The SCO link takes a moment to come up after the request.
            runCatching { Thread.sleep(400) }
        }
        Log.i(TAG, "HFP route to ${sco.productName}: $ok")
        ok
    }.getOrElse { Log.w(TAG, "HFP routing failed", it); false }

    /** Hand audio back to A2DP so the TTS voice plays normally again. */
    private fun releaseRoute() {
        runCatching { audio.clearCommunicationDevice() }
        runCatching { audio.mode = AudioManager.MODE_NORMAL }
    }

    // ---------------------------------------------------------------- helpers

    private fun rms(b: ShortArray, n: Int): Double {
        var sum = 0.0
        for (k in 0 until n) sum += b[k].toDouble() * b[k]
        return sqrt(sum / n)
    }

    private fun toBytes(b: ShortArray, n: Int): ByteArray {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) bb.putShort(b[k])
        return bb.array()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
