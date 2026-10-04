package com.example.teachablevoice.teach

import com.example.teachablevoice.agent.AgentAction
import com.example.teachablevoice.agent.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object UserActionObserver {
    private val _actions = MutableSharedFlow<Pair<AgentAction, UiState>>(extraBufferCapacity = 50)
    val actions = _actions.asSharedFlow()

    fun emit(action: AgentAction, state: UiState) {
        _actions.tryEmit(Pair(action, state))
    }
}
