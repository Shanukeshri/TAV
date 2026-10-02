package com.example.teachablevoice.voice

import android.util.Log

/**
 * Detects the wake phrase "Hey start listening" from speech text.
 *
 * This is Stage A: the wake word is detected from STT output rather than
 * a dedicated always-on hotword detector. The VoiceInputManager captures
 * speech, and this class checks if the result matches the wake phrase.
 *
 * Stage B (future): Replace with a lightweight, always-on wake-word detector
 * that runs independently of SpeechRecognizer (e.g., Porcupine, Snowboy,
 * or Android's VoiceInteractionService).
 *
 * Architecture:
 *   STT result → WakeWordManager.isWakePhrase() → true → VoiceInputManager.startListening()
 *
 * The wake phrase is a LOCAL control command. It is NOT sent to Gemini.
 */
class WakeWordManager {

    companion object {
        private const val TAG = "WakeWordManager"

        /**
         * The canonical wake phrase.
         * Matching is fuzzy to handle STT variations like
         * "hey start listening", "he start listening", "hey start listen", etc.
         */
        private val WAKE_KEYWORDS = listOf("hey", "start", "listening")

        /**
         * Minimum number of wake keywords that must be present
         * for a fuzzy match (2 out of 3 = 66% threshold).
         */
        private const val MIN_KEYWORD_MATCHES = 2
    }

    private var listener: WakeWordListener? = null

    fun setListener(listener: WakeWordListener) {
        this.listener = listener
    }

    /**
     * Check if the given text matches the wake phrase.
     *
     * Uses fuzzy matching because STT often misinterprets short phrases:
     * - "hey start listening" → exact match
     * - "hey start listen" → close enough
     * - "he start listening" → close enough
     * - "open Amazon" → not a wake phrase
     */
    fun isWakePhrase(text: String): Boolean {
        val lower = text.lowercase().trim()

        // Exact match
        if (lower == "hey start listening") {
            Log.i(TAG, "Exact wake phrase detected: '$text'")
            return true
        }

        // Fuzzy match: count how many wake keywords appear in the text
        val matchCount = WAKE_KEYWORDS.count { keyword ->
            lower.contains(keyword)
        }

        val isWake = matchCount >= MIN_KEYWORD_MATCHES
        if (isWake) {
            Log.i(TAG, "Fuzzy wake phrase detected ($matchCount/$MIN_KEYWORD_MATCHES keywords): '$text'")
        }
        return isWake
    }

    /**
     * Process STT output: if it's a wake phrase, notify the listener
     * and return true. Otherwise return false so the caller can route
     * the text to the agent.
     */
    fun processUtterance(text: String): Boolean {
        return if (isWakePhrase(text)) {
            listener?.onWakeWordDetected()
            true
        } else {
            false
        }
    }

    /**
     * Listener for wake word events.
     */
    interface WakeWordListener {
        fun onWakeWordDetected()
    }
}
