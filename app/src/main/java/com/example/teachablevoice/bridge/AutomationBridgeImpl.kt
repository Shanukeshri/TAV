package com.example.teachablevoice.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.Context
import android.util.Log

import com.example.teachablevoice.agent.UiElement
import com.example.teachablevoice.agent.UiState
import kotlinx.coroutines.delay
import com.example.teachablevoice.ui.launchApp

class AutomationBridgeImpl(private val context: Context) : AutomationBridge {

    override suspend fun openApp(packageName: String): Result {
        Log.d("AutomationBridge", "Opening app $packageName")
        try {
            launchApp(context, packageName)
            return Result.SUCCESS
        } catch (e: Exception) {
            Log.e("AutomationBridge", "Failed to open app", e)
            return Result.FAILURE
        }
    }

    override suspend fun getUiState(): UiState {
        val snapshot = UiMirrorRepository.snapshotFlow.value
            ?: return UiState("Unknown", "Unknown", emptyList(), false)

        val elements = mutableListOf<UiElement>()
        var hasScrollable = false
        
        fun traverse(node: NormalizedNode) {
            if (node.scrollable) hasScrollable = true
            
            if (node.isMeaningful) {
                val hasText = node.text.isNotBlank() || node.contentDescription.isNotBlank() || node.label.isNotBlank()
                val isInteractive = node.clickable || node.editable || node.scrollable
                
                if (hasText || isInteractive) {
                    val relevantActions = node.actions.filter { 
                        it.contains("CLICK") || it.contains("SET_TEXT") || it.contains("SCROLL") 
                    }
                    val displayLabel = node.text.ifBlank { node.contentDescription }.ifBlank { node.label }.take(50)
                    
                    elements.add(UiElement(
                        id = node.id,
                        role = node.type.name.lowercase(),
                        label = displayLabel,
                        hint = node.hint,
                        isEditable = node.editable,
                        actions = relevantActions
                    ))
                }
            }
            node.children.forEach { traverse(it) }
        }
        
        traverse(snapshot.rootNode)
        
        return UiState(
            app = snapshot.packageName,
            elements = elements,
            scrollable = hasScrollable
        )
    }

    override suspend fun click(elementId: String): Result {
        MirrorInteractionController.requestClick(elementId)
        return Result.SUCCESS
    }

    override suspend fun input(elementId: String, value: String): Result {
        MirrorInteractionController.requestSetText(elementId, value)
        return Result.SUCCESS
    }

    override suspend fun scroll(containerId: String?, direction: Direction): Result {
        val id = containerId ?: return Result.FAILURE
        when (direction) {
            Direction.DOWN -> MirrorInteractionController.requestScrollForward(id)
            Direction.UP -> MirrorInteractionController.requestScrollBackward(id)
            else -> return Result.FAILURE
        }
        return Result.SUCCESS
    }

    override suspend fun back(): Result {
        MirrorInteractionController.requestGlobalBack()
        return Result.SUCCESS
    }

    override suspend fun home(): Result {
        MirrorInteractionController.requestGlobalHome()
        return Result.SUCCESS
    }

    override suspend fun recents(): Result {
        MirrorInteractionController.requestGlobalRecents()
        return Result.SUCCESS
    }

    override suspend fun notifications(): Result {
        MirrorInteractionController.requestGlobalNotifications()
        return Result.SUCCESS
    }

    override suspend fun longClick(elementId: String): Result {
        MirrorInteractionController.requestLongClick(elementId)
        return Result.SUCCESS
    }

    override suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Result {
        MirrorInteractionController.requestCoordinateSwipe(startX, startY, endX, endY)
        return Result.SUCCESS
    }

    override suspend fun waitForUiChange(timeoutMs: Long): UiState {
        // Simplistic wait for demonstration. In reality, observe snapshotFlow for changes.
        val initialState = getUiState()
        val endTime = System.currentTimeMillis() + timeoutMs
        
        while (System.currentTimeMillis() < endTime) {
            delay(500)
            val currentState = getUiState()
            if (currentState.fingerprint != initialState.fingerprint) {
                return currentState
            }
        }
        return getUiState()
    }
}
