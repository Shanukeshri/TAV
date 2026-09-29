package com.example.teachablevoice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Sealed class representing all possible interaction commands
 * that can be forwarded from the Mirror UI to the real app.
 */
sealed class InteractionCommand {
    data class Click(val nodeId: String) : InteractionCommand()
    data class LongClick(val nodeId: String) : InteractionCommand()
    data class SetText(val nodeId: String, val text: String) : InteractionCommand()
    data class ScrollForward(val nodeId: String) : InteractionCommand()
    data class ScrollBackward(val nodeId: String) : InteractionCommand()
    data class ToggleCheck(val nodeId: String) : InteractionCommand()

    /** Tap at real screen coordinates — finds the deepest node and clicks it */
    data class CoordinateTap(val screenX: Float, val screenY: Float) : InteractionCommand()
    /** Long-press at real screen coordinates */
    data class CoordinateLongPress(val screenX: Float, val screenY: Float) : InteractionCommand()
    /** Swipe gesture — mapped to scroll action on scrollable container at start position */
    data class CoordinateSwipe(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val durationMs: Long = 300
    ) : InteractionCommand()
}

/**
 * A lightweight shared event mechanism to safely pass interaction requests
 * from the Mirror UI (MainActivity) to the AccessibilityService.
 */
object MirrorInteractionController {
    private val _clickRequests = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val clickRequests: SharedFlow<String> = _clickRequests.asSharedFlow()

    private val _commands = MutableSharedFlow<InteractionCommand>(extraBufferCapacity = 20)
    val commands: SharedFlow<InteractionCommand> = _commands.asSharedFlow()

    fun requestClick(nodeId: String) {
        _clickRequests.tryEmit(nodeId)
        _commands.tryEmit(InteractionCommand.Click(nodeId))
    }

    fun requestLongClick(nodeId: String) {
        _commands.tryEmit(InteractionCommand.LongClick(nodeId))
    }

    fun requestSetText(nodeId: String, text: String) {
        _commands.tryEmit(InteractionCommand.SetText(nodeId, text))
    }

    fun requestScrollForward(nodeId: String) {
        _commands.tryEmit(InteractionCommand.ScrollForward(nodeId))
    }

    fun requestScrollBackward(nodeId: String) {
        _commands.tryEmit(InteractionCommand.ScrollBackward(nodeId))
    }

    fun requestToggle(nodeId: String) {
        _commands.tryEmit(InteractionCommand.ToggleCheck(nodeId))
    }

    fun requestCoordinateTap(screenX: Float, screenY: Float) {
        _commands.tryEmit(InteractionCommand.CoordinateTap(screenX, screenY))
    }

    fun requestCoordinateLongPress(screenX: Float, screenY: Float) {
        _commands.tryEmit(InteractionCommand.CoordinateLongPress(screenX, screenY))
    }

    fun requestCoordinateSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ) {
        _commands.tryEmit(InteractionCommand.CoordinateSwipe(startX, startY, endX, endY, durationMs))
    }
}
