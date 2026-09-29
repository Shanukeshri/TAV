package com.example.teachablevoice

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MirrorAccessibilityService : AccessibilityService() {

    private val uiExtractor = UIExtractor()
    private val enableDetailedLogging = false // Keep false by default as requested

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service: Connected")

        // Observe click requests from the Mirror UI
        serviceScope.launch {
            MirrorInteractionController.clickRequests.collect { nodeId ->
                handleMirrorClick(nodeId)
            }
        }
    }

    private fun handleMirrorClick(nodeId: String) {
        val currentSnapshot = UiMirrorRepository.snapshotFlow.value
        
        if (currentSnapshot == null) {
            Log.e("TAV_INTERACTION", "Click request failed: $nodeId\nReason: no snapshot available")
            return
        }

        val targetPackage = currentSnapshot.packageName
        
        // 9. IMPORTANT PACKAGE SAFETY
        if (targetPackage == "com.example.teachablevoice" || targetPackage == "com.android.systemui") {
            Log.e("TAV_INTERACTION", "Click request failed: $nodeId\nReason: target package $targetPackage is ignored")
            return
        }
        
        Log.d("TAV_INTERACTION", "Click request: $nodeId\nTarget package: $targetPackage")
        
        val node = AccessibilityNodeRegistry.getNode(nodeId)
        if (node == null) {
            Log.e("TAV_INTERACTION", "Click request failed: $nodeId\nReason: node not found in registry")
            return
        }
        
        Log.d("TAV_INTERACTION", """
            Mapped node: ${node.className}
            Clickable: ${node.isClickable}
            Enabled: ${node.isEnabled}
        """.trimIndent())

        if (!node.isEnabled) {
            Log.e(
                "TAV_INTERACTION",
                "Click request failed: $nodeId\nReason: node is not enabled"
            )
            return
        }

        val hasClickAction = node.actionList?.any {
            it.id == AccessibilityNodeInfo.ACTION_CLICK
        } == true

        if (!hasClickAction) {
            Log.e(
                "TAV_INTERACTION",
                "Click request failed: $nodeId\nReason: ACTION_CLICK is not available"
            )
            return
        }
        
        // Perform the click. Wrapped in try/catch because the real node may have
        // become stale if the third-party app changed between capture and click.
        val result = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e("TAV_INTERACTION", "Click request failed: $nodeId\nReason: stale node exception: ${e.message}")
            return
        }
        Log.d("TAV_INTERACTION", "ACTION_CLICK result: $result")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var extractionJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"
        val eventType = AccessibilityEvent.eventTypeToString(event.eventType)
        
        // Filter packages EARLY before doing any heavy lifting or requesting the window root
        if (packageName == "com.example.teachablevoice" || packageName == "com.android.systemui") {
            Log.d(TAG, "Ignoring package: $packageName")
            return
        }

        Log.d(TAG, "Event type: $eventType, Package: $packageName")

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root node", e)
            null
        }

        if (rootNode != null) {
            // Cancel previous extraction job to prevent unlimited simultaneous background work
            extractionJob?.cancel()
            
            extractionJob = serviceScope.launch {
                try {
                    // Rebuild the mapping from the current accessibility tree: clear old nodes first
                    AccessibilityNodeRegistry.clear()
                    
                    // Extract and normalize the tree OFF the main thread
                    val normalizedTree = uiExtractor.extract(rootNode)
                    
                    if (normalizedTree != null) {
                        // Push the snapshot to the repository
                        UiMirrorRepository.updateSnapshot(
                            UiSnapshot(
                                packageName = packageName,
                                rootNode = normalizedTree,
                                timestamp = System.currentTimeMillis()
                            )
                        )

                        // Log concise summary
                        logTreeSummary(normalizedTree)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during tree extraction", e)
                } finally {
                    // Recycle root node when done
                    rootNode.recycle()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
    }

    private fun logTreeSummary(root: NormalizedNode) {
        var totalNodes = 0
        var buttons = 0
        var textFields = 0
        var texts = 0
        var images = 0
        var scrollables = 0
        
        fun countNodes(node: NormalizedNode) {
            totalNodes++
            when (node.type) {
                NodeType.Button -> buttons++
                NodeType.TextField -> textFields++
                NodeType.Text -> texts++
                NodeType.Image -> images++
                NodeType.ScrollableContainer, NodeType.List -> scrollables++
                else -> {}
            }
            for (child in node.children) {
                countNodes(child)
            }
        }
        
        countNodes(root)
        
        Log.d(TAG, """
            UI tree extracted: $totalNodes nodes
            Buttons: $buttons
            TextFields: $textFields
            Text: $texts
            Images: $images
            Scrollable: $scrollables
        """.trimIndent())
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null) return
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "Max depth reached, stopping traversal")
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val actions = node.actionList?.joinToString { action -> 
            val actionName = when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
                else -> action.label?.toString() ?: action.id.toString()
            }
            actionName
        } ?: "none"

        Log.d(TAG, """
            Node(depth=$depth):
            - className: ${node.className}
            - text: ${node.text}
            - contentDescription: ${node.contentDescription}
            - viewIdResourceName: ${node.viewIdResourceName}
            - boundsInScreen: $bounds
            - isClickable: ${node.isClickable}
            - isEditable: ${node.isEditable}
            - isEnabled: ${node.isEnabled}
            - isFocusable: ${node.isFocusable}
            - isScrollable: ${node.isScrollable}
            - isChecked: ${node.isChecked}
            - isSelected: ${node.isSelected}
            - available actions: $actions
        """.trimIndent())

        val childCount = node.childCount
        if (childCount > 0) {
            for (i in 0 until childCount) {
                val childNode = node.getChild(i)
                traverseNode(childNode, depth + 1)
                childNode?.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service: Interrupted")
    }

    companion object {
        private const val TAG = "TAV_ACCESSIBILITY"
        private const val MAX_DEPTH = 50
    }
}
