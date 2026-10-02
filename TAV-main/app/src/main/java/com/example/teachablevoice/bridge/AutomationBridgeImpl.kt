package com.example.teachablevoice.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.Context
import android.util.Log
import com.example.teachablevoice.MirrorInteractionController
import com.example.teachablevoice.UiMirrorRepository
import com.example.teachablevoice.agent.UiElement
import com.example.teachablevoice.agent.UiState
import kotlinx.coroutines.delay
import com.example.teachablevoice.launchApp

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
        
        fun traverse(node: com.example.teachablevoice.NormalizedNode) {
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
        val initialHash = currentSemanticHash()
        val endTime = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endTime) {
            delay(100)
            if (currentSemanticHash() != initialHash) {
                return waitForStableUi(timeoutMs = 1_500, stableForMs = 300)
            }
        }
        return getUiState()
    }

    override suspend fun waitForStableUi(timeoutMs: Long, stableForMs: Long): UiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastHash = currentSemanticHash()
        var lastChange = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            delay(80)
            val now = System.currentTimeMillis()
            val hash = currentSemanticHash()
            val eventChange = UiMirrorRepository.lastContentChangeAt
            if (hash != lastHash || eventChange > lastChange) {
                lastHash = hash
                lastChange = maxOf(now, eventChange)
            } else if (now - lastChange >= stableForMs) {
                return getUiState()
            }
        }
        return getUiState()
    }

    private fun currentSemanticHash(): String {
        val snapshot = UiMirrorRepository.snapshotFlow.value
        return snapshot?.rootNode?.semanticHash() ?: UiMirrorRepository.lastSemanticHash
    }
}
