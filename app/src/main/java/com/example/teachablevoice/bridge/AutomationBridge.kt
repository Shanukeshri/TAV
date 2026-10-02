package com.example.teachablevoice.bridge

import com.example.teachablevoice.ui.LaunchableApp
import com.example.teachablevoice.agent.UiState

enum class Direction { UP, DOWN, LEFT, RIGHT }
enum class Result { SUCCESS, FAILURE }

interface AutomationBridge {
    suspend fun openApp(packageName: String): Result
    suspend fun getUiState(): UiState
    suspend fun click(elementId: String): Result
    suspend fun input(elementId: String, value: String): Result
    suspend fun scroll(containerId: String?, direction: Direction): Result
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Result
    suspend fun longClick(elementId: String): Result
    suspend fun back(): Result
    suspend fun home(): Result
    suspend fun recents(): Result
    suspend fun notifications(): Result
    suspend fun waitForUiChange(timeoutMs: Long = 5000): UiState
}
