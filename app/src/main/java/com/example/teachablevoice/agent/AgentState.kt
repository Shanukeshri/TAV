package com.example.teachablevoice.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TransitionResult {
    SUCCESS,
    NO_STATE_CHANGE,
    FAILED
}

data class Transition(
    val fromState: String,
    val action: AgentAction,
    val toState: String?,
    val result: TransitionResult
)

/** A human-readable log entry shown in the Agent Debug screen */
data class AgentLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,       // e.g. "OPEN_APP", "CLICK", "MODEL", "ERROR", "RESOLVE"
    val message: String,
    val isError: Boolean = false
)

enum class AgentStatus {
    IDLE, RUNNING, COMPLETED, ERROR, ASKING
}

data class AgentStateData(
    val stepCount: Int = 0,
    val backtracks: Int = 0,
    val history: List<Transition> = emptyList(),
    val visitedStates: Set<String> = emptySet(),
    val isRunning: Boolean = false,
    val currentGoal: Goal? = null,
    val status: AgentStatus = AgentStatus.IDLE,
    val statusMessage: String = "",
    val log: List<AgentLogEntry> = emptyList(),
    val resolvedApp: String = ""
)

class AgentState {
    private val _stateFlow = MutableStateFlow(AgentStateData())
    val stateFlow: StateFlow<AgentStateData> = _stateFlow.asStateFlow()

    var stepCount: Int
        get() = _stateFlow.value.stepCount
        set(value) = _stateFlow.update { it.copy(stepCount = value) }

    var backtracks: Int
        get() = _stateFlow.value.backtracks
        set(value) = _stateFlow.update { it.copy(backtracks = value) }

    var isRunning: Boolean
        get() = _stateFlow.value.isRunning
        set(value) = _stateFlow.update { it.copy(isRunning = value) }

    var currentGoal: Goal?
        get() = _stateFlow.value.currentGoal
        set(value) = _stateFlow.update { it.copy(currentGoal = value) }

    var status: AgentStatus
        get() = _stateFlow.value.status
        set(value) = _stateFlow.update { it.copy(status = value) }

    var statusMessage: String
        get() = _stateFlow.value.statusMessage
        set(value) = _stateFlow.update { it.copy(statusMessage = value) }

    var resolvedApp: String
        get() = _stateFlow.value.resolvedApp
        set(value) = _stateFlow.update { it.copy(resolvedApp = value) }

    val history: List<Transition>
        get() = _stateFlow.value.history
        
    val visitedStates: Set<String>
        get() = _stateFlow.value.visitedStates

    val log: List<AgentLogEntry>
        get() = _stateFlow.value.log
    
    fun recordTransition(transition: Transition) {
        _stateFlow.update { current ->
            val newHistory = current.history + transition
            val newVisited = if (transition.toState != null) {
                current.visitedStates + transition.toState
            } else {
                current.visitedStates
            }
            current.copy(history = newHistory, visitedStates = newVisited)
        }
    }

    fun appendLog(tag: String, message: String, isError: Boolean = false) {
        val samTag = "SAM_$tag"
        if (isError) {
            android.util.Log.e(samTag, message)
        } else {
            android.util.Log.i(samTag, message)
        }

        _stateFlow.update { current ->
            current.copy(log = current.log + AgentLogEntry(
                tag = tag,
                message = message,
                isError = isError
            ))
        }
    }

    fun reset() {
        _stateFlow.value = AgentStateData()
    }
}

object AgentStateRepository {
    val globalState = AgentState()
}
