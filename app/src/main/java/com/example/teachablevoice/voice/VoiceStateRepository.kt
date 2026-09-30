package com.example.teachablevoice.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared observable state for the voice system.
 *
 * This follows the same pattern as AgentStateRepository:
 * a singleton that both the VoiceForegroundService and
 * MirrorAccessibilityService can observe.
 *
 * Responsibilities:
 *   VoiceForegroundService → writes state (WAKE_LISTENING, COMMAND_LISTENING, etc.)
 *   MirrorAccessibilityService → reads state → shows/hides VoiceListeningOverlayManager
 *
 * This cleanly separates:
 *   - Microphone ownership (VoiceForegroundService)
 *   - Overlay ownership (MirrorAccessibilityService via TYPE_ACCESSIBILITY_OVERLAY)
 */

/**
 * Voice listener state machine states.
 */
enum class VoiceListenerState {
    /** Not running. Voice service is stopped. */
    STOPPED,

    /** Listening only for wake phrase "Hey start listening". Non-wake speech ignored. */
    WAKE_LISTENING,

    /** Wake phrase detected. Capturing the actual user command. */
    COMMAND_LISTENING,

    /** Command captured. Routing through Gemini to resolve target app + goal. */
    PROCESSING,

    /** Agent has been launched. Waiting for completion before returning to WAKE_LISTENING. */
    AGENT_LAUNCHED
}

/**
 * Immutable snapshot of voice system state.
 */
data class VoiceStateData(
    val state: VoiceListenerState = VoiceListenerState.STOPPED,
    val partialText: String = "",
    val lastTranscription: String = "",
    val routedApp: String = "",
    val routedTask: String = "",
    val errorMessage: String = "",
    val isServiceRunning: Boolean = false
)

/**
 * Mutable holder that emits VoiceStateData via StateFlow.
 */
class VoiceState {
    private val _stateFlow = MutableStateFlow(VoiceStateData())
    val stateFlow: StateFlow<VoiceStateData> = _stateFlow.asStateFlow()

    val current: VoiceStateData get() = _stateFlow.value

    fun transitionTo(newState: VoiceListenerState) {
        _stateFlow.update { it.copy(state = newState, errorMessage = "") }
    }

    fun updatePartialText(text: String) {
        _stateFlow.update { it.copy(partialText = text) }
    }

    fun setTranscription(text: String) {
        _stateFlow.update { it.copy(lastTranscription = text, partialText = "") }
    }

    fun setRouting(app: String, task: String) {
        _stateFlow.update { it.copy(routedApp = app, routedTask = task) }
    }

    fun setError(message: String) {
        _stateFlow.update { it.copy(state = VoiceListenerState.WAKE_LISTENING, errorMessage = message) }
    }

    fun setServiceRunning(running: Boolean) {
        _stateFlow.update { it.copy(isServiceRunning = running) }
    }

    fun reset() {
        _stateFlow.value = VoiceStateData()
    }
}

/**
 * Singleton repository — same pattern as AgentStateRepository.
 */
object VoiceStateRepository {
    val globalState = VoiceState()
}
