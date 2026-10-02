package com.example.teachablevoice.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared observable state for the voice system.
 */

enum class VoiceListenerState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    PROCESSING,
    AGENT_RUNNING,
    DONE,
    ERROR
}

data class VoiceStateData(
    val state: VoiceListenerState = VoiceListenerState.IDLE,
    val partialText: String = "",
    val lastTranscription: String = "",
    val routedApp: String = "",
    val routedTask: String = "",
    val errorMessage: String = "",
    val isServiceRunning: Boolean = false
)

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
        _stateFlow.update { it.copy(state = VoiceListenerState.ERROR, errorMessage = message) }
    }

    fun setServiceRunning(running: Boolean) {
        _stateFlow.update { it.copy(isServiceRunning = running) }
    }

    fun reset() {
        _stateFlow.value = VoiceStateData()
    }
}

object VoiceStateRepository {
    val globalState = VoiceState()
}
