package com.example.teachablevoice.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Manages Android Speech-to-Text via SpeechRecognizer.
 *
 * This class is purely an input layer — it converts microphone audio to text
 * and forwards results through [VoiceInputListener]. It does NOT interpret
 * the text, route to Gemini, or interact with the agent.
 *
 * Architecture:
 *   Microphone → SpeechRecognizer → VoiceInputManager → VoiceInputListener
 *
 * Usage:
 *   val manager = VoiceInputManager(context)
 *   manager.setListener(myListener)
 *   manager.startListening()
 *   // ... later
 *   manager.stopListening()
 *   manager.destroy()
 *
 * Important Android considerations:
 * - Requires RECORD_AUDIO runtime permission (caller must request it).
 * - SpeechRecognizer must be created/used on the main thread.
 * - If used from a foreground service, the service must declare
 *   foregroundServiceType="microphone" on Android 14+.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: VoiceInputListener? = null
    private var isListening = false
    private var consecutiveErrors = 0

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    fun setListener(listener: VoiceInputListener) {
        this.listener = listener
    }

    /**
     * Begin capturing audio and converting to text.
     * Caller is responsible for ensuring RECORD_AUDIO permission is granted.
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring startListening()")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            listener?.onSpeechError("Speech recognition not available on this device")
            return
        }

        consecutiveErrors = 0
        createRecognizerAndStart()
    }

    /**
     * Stop listening and release the recognizer.
     */
    fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognizer: ${e.message}")
        }
        listener?.onListeningStopped()
    }

    /**
     * Release all resources. Call from onDestroy().
     */
    fun destroy() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying recognizer: ${e.message}")
        }
        speechRecognizer = null
        listener = null
    }

    fun isCurrentlyListening(): Boolean = isListening

    // ──────────────────────────────────────────────
    // Internal: Recognizer lifecycle
    // ──────────────────────────────────────────────

    private fun createRecognizerAndStart() {
        // Destroy previous instance if any
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
            recognizer.setRecognitionListener(createRecognitionListener())

            val intent = createRecognizerIntent()
            recognizer.startListening(intent)
            isListening = true
            listener?.onListeningStarted()
            Log.i(TAG, "Speech recognition started")
        }
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Return partial results for live UI feedback
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Use device's default language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            // Maximum number of alternative results
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Don't stop after brief silence — allow natural pauses in speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    }

    // ──────────────────────────────────────────────
    // RecognitionListener implementation
    // ──────────────────────────────────────────────

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                consecutiveErrors = 0
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Could be used for a microphone level indicator
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer — not needed for basic STT
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking")
            }

            override fun onError(error: Int) {
                val errorMessage = mapSpeechError(error)
                Log.e(TAG, "Speech error: $errorMessage (code=$error)")

                when (error) {
                    // Recoverable errors — auto-restart if still in listening mode
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        consecutiveErrors++
                        if (isListening && consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                            Log.i(TAG, "Recoverable error, restarting... (attempt $consecutiveErrors)")
                            restartRecognizer()
                        } else {
                            isListening = false
                            listener?.onSpeechError(errorMessage)
                            listener?.onListeningStopped()
                        }
                    }
                    // Non-recoverable errors
                    else -> {
                        isListening = false
                        listener?.onSpeechError(errorMessage)
                        listener?.onListeningStopped()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestResult = matches?.firstOrNull()

                if (!bestResult.isNullOrBlank()) {
                    Log.i(TAG, "Final result: $bestResult")
                    listener?.onSpeechResult(bestResult)
                } else {
                    Log.w(TAG, "Empty speech result")
                }

                // After receiving a final result, if we're still in listening mode,
                // restart to capture the next utterance
                if (isListening) {
                    restartRecognizer()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    listener?.onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Speech event: $eventType")
            }
        }
    }

    /**
     * Restart the recognizer after a result or recoverable error.
     * Small delay to avoid rapid cycling.
     */
    private fun restartRecognizer() {
        try {
            speechRecognizer?.cancel()
            val intent = createRecognizerIntent()
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Recognizer restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart recognizer: ${e.message}")
            isListening = false
            listener?.onSpeechError("Failed to restart speech recognition")
            listener?.onListeningStopped()
        }
    }

    private fun mapSpeechError(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
            else -> "Unknown error (code=$errorCode)"
        }
    }
}
