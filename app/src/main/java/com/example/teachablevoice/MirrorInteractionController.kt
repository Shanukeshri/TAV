package com.example.teachablevoice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A lightweight shared event mechanism to safely pass interaction requests (like clicks)
 * from the Mirror UI (MainActivity) to the AccessibilityService.
 */
object MirrorInteractionController {
    private val _clickRequests = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val clickRequests: SharedFlow<String> = _clickRequests.asSharedFlow()

    fun requestClick(nodeId: String) {
        _clickRequests.tryEmit(nodeId)
    }
}
