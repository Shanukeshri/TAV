package com.example.teachablevoice.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.teachablevoice.agent.AgentOverlayManager
import com.example.teachablevoice.voice.VoiceListeningOverlayManager
import com.example.teachablevoice.core.VoiceListenerState
import com.example.teachablevoice.core.VoiceStateRepository
import kotlinx.coroutines.flow.collectLatest

class MirrorAccessibilityService : AccessibilityService() {

    private val uiExtractor = UIExtractor()

    // Agent overlay manager — shows translucent panel on top of target app
    private var overlayManager: AgentOverlayManager? = null

    // Voice overlay manager — shows listening/processing state on top of any app
    // Driven by VoiceStateRepository (written by VoiceForegroundService)
    // This service does NOT own the microphone — it only draws the overlay.
    private var voiceOverlayManager: VoiceListeningOverlayManager? = null
    private var voiceObserverJob: Job? = null

    // Throttle: minimum time between extractions (ms)
    private var lastExtractionTime = 0L
    private val MIN_EXTRACTION_INTERVAL_MS = 400L

    // Track the foreground package we're mirroring
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service: Connected")

        _isServiceRunning.value = true

        // Initialize and start the agent overlay manager
        overlayManager = AgentOverlayManager(this)
        overlayManager?.startObserving()

        // Initialize voice overlay manager and observe VoiceStateRepository.
        // The microphone is owned by VoiceForegroundService (separate service).
        // This AccessibilityService only draws the overlay via TYPE_ACCESSIBILITY_OVERLAY
        // based on voice state changes.
        voiceOverlayManager = VoiceListeningOverlayManager(this)
        startObservingVoiceState()

        // Observe rich interaction commands from the Mirror UI
        serviceScope.launch {
            MirrorInteractionController.commands.collect { command ->
                handleCommand(command)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Command Routing
    // ──────────────────────────────────────────────

    private fun handleCommand(command: InteractionCommand) {
        when (command) {
            is InteractionCommand.Click -> handleAction(command.nodeId, AccessibilityNodeInfo.ACTION_CLICK, "Click")
            is InteractionCommand.LongClick -> handleAction(command.nodeId, AccessibilityNodeInfo.ACTION_LONG_CLICK, "LongClick")
            is InteractionCommand.ScrollForward -> handleAction(command.nodeId, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "ScrollForward")
            is InteractionCommand.ScrollBackward -> handleAction(command.nodeId, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "ScrollBackward")
            is InteractionCommand.SetText -> handleSetText(command.nodeId, command.text)
            is InteractionCommand.Enter -> handleAction(command.nodeId, android.R.id.accessibilityActionImeEnter, "Enter")
            is InteractionCommand.ToggleCheck -> handleAction(command.nodeId, AccessibilityNodeInfo.ACTION_CLICK, "Toggle")
            is InteractionCommand.CoordinateTap -> handleCoordinateTap(command.screenX, command.screenY)
            is InteractionCommand.CoordinateLongPress -> handleCoordinateLongPress(command.screenX, command.screenY)
            is InteractionCommand.CoordinateSwipe -> handleCoordinateSwipe(command)
            is InteractionCommand.GlobalBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is InteractionCommand.GlobalHome -> performGlobalAction(GLOBAL_ACTION_HOME)
            is InteractionCommand.GlobalRecents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is InteractionCommand.GlobalNotifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    // ──────────────────────────────────────────────
    // Coordinate-based interactions (screenshot mode)
    // ──────────────────────────────────────────────

    /**
     * Handles a tap at real screen coordinates.
     * 1. Finds the deepest accessibility node at that position
     * 2. If clickable → performAction(CLICK)
     * 3. If no clickable node → dispatch a gesture tap at those coordinates
     */
    private fun handleCoordinateTap(screenX: Float, screenY: Float) {
        val snapshot = UiMirrorRepository.snapshotFlow.value ?: return
        if (isIgnoredPackage(snapshot.packageName)) return

        val targetNode = findDeepestNodeAtPosition(snapshot.rootNode, screenX.toInt(), screenY.toInt())

        if (targetNode != null) {
            val realNode = AccessibilityNodeRegistry.getNode(targetNode.id)
            if (realNode != null && realNode.isEnabled) {
                // If the node is editable (text field), show a text input dialog
                // instead of clicking (which would bring the other app to the foreground)
                if (targetNode.editable || realNode.isEditable) {
                    val currentText = targetNode.text.ifEmpty { realNode.text?.toString() ?: "" }
                    val hint = targetNode.hint.ifEmpty {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            realNode.hintText?.toString() ?: ""
                        } else ""
                    }
                    MirrorInteractionController.requestTextInput(targetNode.id, currentText, hint)
                    Log.d(TAG_INTERACTION, "CoordinateTap editable node=${targetNode.id}, showing text input dialog")
                    return
                }

                // Try click action for non-editable nodes
                val hasClick = realNode.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true
                if (hasClick) {
                    val result = try {
                        realNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.e(TAG_INTERACTION, "CoordinateTap node click failed: ${e.message}")
                        false
                    }
                    Log.d(TAG_INTERACTION, "CoordinateTap node=${targetNode.id} type=${targetNode.type} click=$result at ($screenX, $screenY)")
                    return
                }
            }
        }

        // Fallback: dispatch a real gesture tap at these coordinates
        Log.d(TAG_INTERACTION, "CoordinateTap gesture at ($screenX, $screenY)")
        dispatchTapGesture(screenX, screenY)
    }

    private fun handleCoordinateLongPress(screenX: Float, screenY: Float) {
        val snapshot = UiMirrorRepository.snapshotFlow.value ?: return
        if (isIgnoredPackage(snapshot.packageName)) return

        val targetNode = findDeepestNodeAtPosition(snapshot.rootNode, screenX.toInt(), screenY.toInt())
        if (targetNode != null) {
            val realNode = AccessibilityNodeRegistry.getNode(targetNode.id)
            if (realNode != null) {
                val hasLongClick = realNode.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK } == true
                if (hasLongClick) {
                    try {
                        realNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        Log.d(TAG_INTERACTION, "CoordinateLongPress node=${targetNode.id} at ($screenX, $screenY)")
                        return
                    } catch (e: Exception) {
                        Log.e(TAG_INTERACTION, "CoordinateLongPress failed: ${e.message}")
                    }
                }
            }
        }

        // Fallback: long press gesture (500ms duration)
        dispatchTapGesture(screenX, screenY, durationMs = 500)
    }

    private fun handleCoordinateSwipe(cmd: InteractionCommand.CoordinateSwipe) {
        val snapshot = UiMirrorRepository.snapshotFlow.value ?: return
        if (isIgnoredPackage(snapshot.packageName)) return

        // Try to find a scrollable container at the start position
        val scrollableNode = findScrollableAtPosition(snapshot.rootNode, cmd.startX.toInt(), cmd.startY.toInt())
        if (scrollableNode != null) {
            val realNode = AccessibilityNodeRegistry.getNode(scrollableNode.id)
            if (realNode != null) {
                // Determine scroll direction from swipe
                val isSwipeUp = cmd.endY < cmd.startY  // Finger moves up = scroll forward (down in content)
                val action = if (isSwipeUp) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                             else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                try {
                    realNode.performAction(action)
                    Log.d(TAG_INTERACTION, "CoordinateSwipe scroll ${if (isSwipeUp) "forward" else "backward"} node=${scrollableNode.id}")
                    return
                } catch (e: Exception) {
                    Log.e(TAG_INTERACTION, "CoordinateSwipe scroll failed: ${e.message}")
                }
            }
        }

        // Fallback: dispatch a real swipe gesture
        dispatchSwipeGesture(cmd.startX, cmd.startY, cmd.endX, cmd.endY, cmd.durationMs)
    }

    // ──────────────────────────────────────────────
    // Gesture dispatch (for areas without accessibility nodes)
    // ──────────────────────────────────────────────

    private fun dispatchTapGesture(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG_INTERACTION, "Gesture tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG_INTERACTION, "Gesture tap cancelled at ($x, $y)")
            }
        }, null)
    }

    private fun dispatchSwipeGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG_INTERACTION, "Gesture swipe completed ($startX,$startY)->($endX,$endY)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG_INTERACTION, "Gesture swipe cancelled")
            }
        }, null)
    }

    // ──────────────────────────────────────────────
    // Node-at-position search
    // ──────────────────────────────────────────────

    /**
     * Finds the deepest (smallest area) node whose bounds contain the given point.
     * This gives us the most specific interactive element at a screen position.
     */
    private fun findDeepestNodeAtPosition(root: NormalizedNode, x: Int, y: Int): NormalizedNode? {
        var best: NormalizedNode? = null
        var bestArea = Int.MAX_VALUE

        fun search(node: NormalizedNode) {
            if (node.bounds.contains(x, y)) {
                val area = node.bounds.width() * node.bounds.height()
                if (area in 1 until bestArea) {
                    // Prefer interactive nodes over passive ones
                    if (node.clickable || node.editable || node.longClickable || best == null) {
                        best = node
                        bestArea = area
                    }
                }
            }
            for (child in node.children) {
                search(child)
            }
        }

        search(root)
        return best
    }

    /**
     * Finds the nearest scrollable container at or around the given position.
     */
    private fun findScrollableAtPosition(root: NormalizedNode, x: Int, y: Int): NormalizedNode? {
        var best: NormalizedNode? = null
        var bestArea = Int.MAX_VALUE

        fun search(node: NormalizedNode) {
            if (node.scrollable && node.bounds.contains(x, y)) {
                val area = node.bounds.width() * node.bounds.height()
                if (area in 1 until bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (child in node.children) {
                search(child)
            }
        }

        search(root)
        return best
    }

    // ──────────────────────────────────────────────
    // Node-ID-based actions (tree mode)
    // ──────────────────────────────────────────────

    private fun findNodeByBounds(root: com.example.teachablevoice.bridge.NormalizedNode, bounds: android.graphics.Rect): com.example.teachablevoice.bridge.NormalizedNode? {
        if (root.bounds == bounds) return root
        for (child in root.children) {
            val found = findNodeByBounds(child, bounds)
            if (found != null) return found
        }
        return null
    }

    private fun handleAction(nodeId: String, actionId: Int, actionName: String) {
        val currentSnapshot = UiMirrorRepository.snapshotFlow.value
        if (currentSnapshot == null) {
            Log.e(TAG_INTERACTION, "$actionName failed: $nodeId — no snapshot")
            return
        }
        if (isIgnoredPackage(currentSnapshot.packageName)) return

        val node = AccessibilityNodeRegistry.getNode(nodeId)
        if (node == null) {
            Log.e(TAG_INTERACTION, "$actionName failed: $nodeId — node not found")
            return
        }
        if (!node.isEnabled) {
            Log.e(TAG_INTERACTION, "$actionName failed: $nodeId — disabled")
            return
        }

        val result = try {
            node.performAction(actionId)
        } catch (e: Exception) {
            Log.e(TAG_INTERACTION, "$actionName failed: $nodeId — stale: ${e.message}")
            return
        }
        Log.d(TAG_INTERACTION, "$actionName($nodeId) = $result")
    }

    private fun handleSetText(nodeId: String, text: String) {
        val currentSnapshot = UiMirrorRepository.snapshotFlow.value
        if (currentSnapshot == null || isIgnoredPackage(currentSnapshot.packageName)) return

        val node = AccessibilityNodeRegistry.getNode(nodeId) ?: return
        // NOTE: Do NOT call ACTION_FOCUS — it brings the other app to the foreground

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG_INTERACTION, "SetText failed: $nodeId — ${e.message}")
            return
        }
        Log.d(TAG_INTERACTION, "SetText($nodeId) = $result, text='$text'")
    }

    // ──────────────────────────────────────────────
    // Screenshot capture (API 30+)
    // ──────────────────────────────────────────────

    private fun captureScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            try {
                                val hwBuffer = result.hardwareBuffer
                                val colorSpace = result.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (hwBitmap != null) {
                                    // Convert to software bitmap for Compose rendering
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    if (softBitmap != null) {
                                        UiMirrorRepository.updateScreenshot(softBitmap)
                                    }
                                    hwBitmap.recycle()
                                }
                                hwBuffer.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot processing failed: ${e.message}")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot failed: errorCode=$errorCode")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot call failed: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────
    // Event handling & tree extraction
    // ──────────────────────────────────────────────

    private fun isIgnoredPackage(pkg: String): Boolean {
        return pkg == "com.example.teachablevoice" ||
               pkg == "com.android.systemui" ||
               pkg.startsWith("com.android.launcher")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var extractionJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        if (isIgnoredPackage(packageName)) return

        // ── Teach Mode Interaction Capture ──
        val isTeachMode = com.example.teachablevoice.agent.AgentStateRepository.globalState.currentGoal?.intent == "TEACH"
        
        if (isTeachMode && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val windowType = event.source?.window?.type
            val isKeyboard = windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD || 
                             packageName.contains("inputmethod") || 
                             packageName.contains("keyboard") || 
                             packageName.contains("gboard") || 
                             packageName.contains("swiftkey")
            
            if (isKeyboard) {
                serviceScope.launch {
                    val bridge = AutomationBridgeImpl(this@MirrorAccessibilityService)
                    val uiState = bridge.getUiState()
                    val action = com.example.teachablevoice.agent.AgentAction(
                        action = com.example.teachablevoice.agent.ActionType.ENTER,
                        elementId = "keyboard_enter"
                    )
                    com.example.teachablevoice.teach.UserActionObserver.emit(action, uiState)
                    Log.d(TAG_INTERACTION, "Captured soft keyboard ENTER in teach mode")
                }
                return
            }
        }

        val snapshot = UiMirrorRepository.snapshotFlow.value
        if (snapshot != null && snapshot.packageName == packageName) {
            val isTeachMode = com.example.teachablevoice.agent.AgentStateRepository.globalState.currentGoal?.intent == "TEACH"
            if (isTeachMode) {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    val source = event.source
                    if (source != null) {
                        serviceScope.launch {
                            val bridge = AutomationBridgeImpl(this@MirrorAccessibilityService)
                            val uiState = bridge.getUiState()
                            val bounds = android.graphics.Rect()
                            source.getBoundsInScreen(bounds)

                            val actionType = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
                                com.example.teachablevoice.agent.ActionType.CLICK
                            else
                                com.example.teachablevoice.agent.ActionType.INPUT

                            val value = if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) event.text.joinToString("") else null

                            val matchedNode = findNodeByBounds(snapshot.rootNode, bounds)
                            val elementId = matchedNode?.id ?: "unknown"

                            val action = com.example.teachablevoice.agent.AgentAction(
                                action = actionType,
                                elementId = elementId,
                                value = value
                            )

                            com.example.teachablevoice.teach.UserActionObserver.emit(action, uiState)
                        }
                    }
                }
            }
        }

        // ── THROTTLE ──
        val now = System.currentTimeMillis()
        val isAppSwitch = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                          packageName != lastForegroundPackage

        if (!isAppSwitch && (now - lastExtractionTime) < MIN_EXTRACTION_INTERVAL_MS) return

        if (isAppSwitch) {
            lastForegroundPackage = packageName
            Log.d(TAG, "App switched to: $packageName")
        }

        lastExtractionTime = now


        // ── Find the best root node ──
        val bestRoot = findBestRootNode(packageName)

        if (bestRoot != null) {
            extractionJob?.cancel()

            extractionJob = serviceScope.launch {
                try {
                    AccessibilityNodeRegistry.clear()
                    val normalizedTree = uiExtractor.extract(bestRoot)

                    if (normalizedTree != null) {
                        UiMirrorRepository.updateSnapshot(
                            UiSnapshot(
                                packageName = packageName,
                                rootNode = normalizedTree,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        logTreeSummary(normalizedTree, packageName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction error", e)
                } finally {
                    try { bestRoot.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Finds the best root node across all windows for the target package.
     */
    private fun findBestRootNode(targetPackage: String): AccessibilityNodeInfo? {
        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                var bestRoot: AccessibilityNodeInfo? = null
                var bestChildCount = -1

                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.root
                        if (root != null) {
                            val rootPkg = root.packageName?.toString() ?: ""
                            if (rootPkg == targetPackage) {
                                val childCount = countChildrenShallow(root)
                                if (childCount > bestChildCount) {
                                    bestRoot?.recycle()
                                    bestRoot = root
                                    bestChildCount = childCount
                                } else {
                                    root.recycle()
                                }
                            } else {
                                root.recycle()
                            }
                        }
                    }
                }

                if (bestRoot != null) return bestRoot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Window enumeration failed", e)
        }

        return try { rootInActiveWindow } catch (e: Exception) { null }
    }

    private fun countChildrenShallow(node: AccessibilityNodeInfo): Int {
        var count = node.childCount
        for (i in 0 until node.childCount.coerceAtMost(10)) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    count += child.childCount
                    child.recycle()
                }
            } catch (_: Exception) {}
        }
        return count
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
        overlayManager?.stopObserving()
        overlayManager = null
        voiceObserverJob?.cancel()
        voiceObserverJob = null
        voiceOverlayManager?.hide()
        voiceOverlayManager = null
        _isServiceRunning.value = false
    }

    // ──────────────────────────────────────────────
    // Voice overlay observation
    // ──────────────────────────────────────────────

    /**
     * Observes VoiceStateRepository and drives VoiceListeningOverlayManager.
     *
     * Separation of concerns:
     *   VoiceForegroundService → owns microphone, STT, wake word → writes VoiceStateRepository
     *   MirrorAccessibilityService → reads VoiceStateRepository → controls voice overlay
     *
     * This service can draw overlays via TYPE_ACCESSIBILITY_OVERLAY without
     * needing SYSTEM_ALERT_WINDOW. The voice service cannot draw overlays.
     */
    private fun startObservingVoiceState() {
        voiceObserverJob?.cancel()
        voiceObserverJob = serviceScope.launch {
            VoiceStateRepository.globalState.stateFlow.collectLatest { voiceState ->
                val overlay = voiceOverlayManager ?: return@collectLatest

                when (voiceState.state) {
                    VoiceListenerState.IDLE,
                    VoiceListenerState.DONE -> {
                        // No overlay needed during wake listening or when stopped
                        overlay.hide()
                    }
                    VoiceListenerState.LISTENING -> {
                        overlay.showListening()
                        if (voiceState.partialText.isNotEmpty()) {
                            overlay.updatePartialText(voiceState.partialText)
                        }
                    }
                    VoiceListenerState.TRANSCRIBING,
                    VoiceListenerState.PROCESSING -> {
                        overlay.showProcessing(voiceState.lastTranscription)
                    }
                    VoiceListenerState.AGENT_RUNNING -> {
                        if (voiceState.routedApp.isNotEmpty()) {
                            overlay.showRouting(voiceState.routedApp, voiceState.routedTask)
                        }
                        // Hide voice overlay after brief delay — agent overlay takes over
                        overlay.hideAfterDelay(2000)
                    }
                    VoiceListenerState.ERROR -> {
                        overlay.showError(voiceState.errorMessage)
                    }
                }

                // Show error overlay if present
                if (voiceState.errorMessage.isNotEmpty()) {
                    overlay.showError(voiceState.errorMessage)
                }
            }
        }
    }

    private fun logTreeSummary(root: NormalizedNode, pkg: String) {
        var totalNodes = 0
        var clickable = 0

        fun countNodes(node: NormalizedNode) {
            totalNodes++
            if (node.clickable) clickable++
            for (child in node.children) countNodes(child)
        }

        countNodes(root)
        Log.d(TAG, "[$pkg] Extracted: ${totalNodes}nodes, ${clickable}clickable")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service: Interrupted")
    }

    companion object {
        private const val TAG = "TAV_ACCESSIBILITY"
        private const val TAG_INTERACTION = "TAV_INTERACTION"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }
}
