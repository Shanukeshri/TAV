package com.example.teachablevoice.voice

/**
 * State machine for the voice-enabled agent flow.
 *
 * States:
 *   IDLE → LISTENING → PROCESSING → RUNNING_AGENT → DONE → IDLE
 *                                                  → ERROR → IDLE
 *
 * Architecture:
 *   IDLE:          TAV is waiting. No microphone active.
 *   LISTENING:     Microphone active, waiting for user speech.
 *   PROCESSING:    STT complete, Gemini is parsing the request.
 *   RUNNING_AGENT: AgentController is executing the task.
 *   DONE:          Task completed successfully.
 *   ERROR:         Something went wrong.
 */
enum class VoiceAgentState {
    /** TAV is idle, waiting for "Start Listening" press or wake phrase. */
    IDLE,

    /** Microphone active, STT running, waiting for speech. */
    LISTENING,

    /** Speech captured, routing through Gemini request parser. */
    PROCESSING,

    /** AgentController is executing actions on the target app. */
    RUNNING_AGENT,

    /** Task completed successfully. */
    DONE,

    /** An error occurred. */
    ERROR
}
