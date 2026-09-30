package com.solos.relay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's built-in speech recognition, wrapped as one suspend call.
 *
 * Uses the phone's microphone rather than the glasses'. The SDK's own mic runs over BLE
 * and cannot overlap with playback, so pulling audio from there mid-conversation fights
 * the hardware; the phone mic in a pocket is good enough for short commands and keeps
 * the glasses free to speak.
 */
class SpeechInput(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInput"
    }

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listen for up to [seconds] and return what was said, or null.
     *
     * SpeechRecognizer is main-thread only, so the whole thing hops there and the caller
     * just suspends.
     */
    suspend fun listen(seconds: Int): String? = withContext(Dispatchers.Main) {
        if (!available) {
            Log.w(TAG, "no recognition service on this device")
            return@withContext null
        }
        // `seconds` is only an upper bound. Normally the recogniser returns as soon as
        // the user stops talking, well before this fires.
        withTimeoutOrNull(seconds.coerceIn(3, 30) * 1000L + 5000L) { recognizeOnce() }
    }

    private suspend fun recognizeOnce(): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val settled = AtomicBoolean(false)

        fun finish(value: String?) {
            if (settled.compareAndSet(false, true)) {
                runCatching { recognizer.destroy() }
                if (cont.isActive) cont.resumeWith(Result.success(value))
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val said = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                finish(said?.takeIf { it.isNotBlank() })
            }

            override fun onError(error: Int) {
                Log.w(TAG, "recognition error $error")
                finish(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "end of speech") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        cont.invokeOnCancellation {
            runCatching { recognizer.cancel() }
            finish(null)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Follows the conversation language; set_language switches it mid-task.
            val tag = Lang.current.locale.toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Endpointing. Without these the recogniser sits on a long default silence
            // window and feels broken - you stop talking and nothing happens.
            // They are hints; most recognisers honour them.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                800L,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }

        runCatching { recognizer.startListening(intent) }
            .onFailure { Log.e(TAG, "startListening failed", it); finish(null) }
    }
}
