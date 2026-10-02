package com.example.teachablevoice.agent

class LoopDetector(private val agentState: AgentState) {
    fun isLooping(): Boolean {
        if (agentState.history.size < 4) return false
        
        // Simple loop detection: A -> B -> A -> B
        val last4 = agentState.history.takeLast(4)
        if (last4.size == 4) {
            val state1 = last4[0].fromState
            val state2 = last4[1].fromState
            val state3 = last4[2].fromState
            val state4 = last4[3].fromState
            
            if (state1 == state3 && state2 == state4 && state1 != state2) {
                return true
            }
        }
        
        // Repeated NO_CHANGE actions check
        val last2 = agentState.history.takeLast(2)
        if (last2.size == 2 && last2.all { it.result == TransitionResult.NO_STATE_CHANGE }) {
            return true
        }
        
        return false
    }
}
