package com.example.teachablevoice.voice

/**
 * Callback interface for voice input events.
 *
 * This interface decouples STT from the agent pipeline.
 * Any component that consumes speech results implements this interface.
 *
 * Architecture:
 *   VoiceInputManager → VoiceInputListener → AgentRequestRouter → AgentForegroundService
 */
interface VoiceInputListener {
    /** Called when the STT engine produces a final transcription. */
    fun onSpeechResult(text: String)

    /** Called when the microphone starts capturing. */
    fun onListeningStarted()

    /** Called when the microphone stops (either explicitly or due to silence). */
    fun onListeningStopped()

    /** Called when STT encounters an error. */
    fun onSpeechError(error: String)

    /** Called with partial (interim) transcription for live feedback. */
    fun onPartialResult(partialText: String) {}
}
