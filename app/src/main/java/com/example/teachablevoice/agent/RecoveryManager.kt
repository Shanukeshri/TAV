package com.example.teachablevoice.agent

import com.example.teachablevoice.bridge.AutomationBridge

class RecoveryManager(private val bridge: AutomationBridge, private val state: AgentState) {
    suspend fun attemptRecovery(): Boolean {
        if (state.backtracks >= 5) return false // Max backtracks reached
        
        // Attempt to go back
        val result = bridge.back()
        if (result == com.example.teachablevoice.bridge.Result.SUCCESS) {
            state.backtracks++
            return true
        }
        
        return false
    }
}
