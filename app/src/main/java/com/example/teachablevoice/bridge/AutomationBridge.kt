package com.example.teachablevoice.bridge

import com.example.teachablevoice.agent.UiState
import com.example.teachablevoice.LaunchableApp

enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class Result { SUCCESS, FAILURE }

interface AutomationBridge {
    suspend fun openApp(packageName: String): Result
    suspend fun getUiState(): UiState
    suspend fun click(elementId: String): Result
    suspend fun input(elementId: String, value: String): Result
    suspend fun scroll(containerId: String?, direction: Direction): Result
    suspend fun back(): Result
    suspend fun waitForUiChange(timeoutMs: Long = 5000): UiState
}
