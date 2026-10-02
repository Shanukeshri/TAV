package com.example.teachablevoice

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts and recursively traverses raw AccessibilityNodeInfo trees, converting them
 * into standardized NormalizedNode trees.
 */
class UIExtractor {

    private var idCounter = 0

    /**
     * Extracts a full tree starting from the given root node.
     */
    fun extract(rootNode: AccessibilityNodeInfo?): NormalizedNode? {
        if (rootNode == null) return null
        
        // Reset the node ID counter for each fresh snapshot extraction
        idCounter = 0
        
        return traverseAndExtract(rootNode, 0)
    }

    private fun traverseAndExtract(node: AccessibilityNodeInfo?, depth: Int): NormalizedNode? {
        if (node == null) return null
        
        // Prevent infinite recursion or excessively deep traversal
        if (depth > 50) {
            Log.w("TAV_ACCESSIBILITY", "Max depth reached in UIExtractor")
            return null
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 1. Generate a stable, unique ID for this specific snapshot traversal
        val id = "node_${idCounter++}"
        
        // 2. Register the relationship: node ID -> real AccessibilityNodeInfo
        // We obtain a copy of the node to ensure it remains valid even if the system recycles the original.
        val storedNode = AccessibilityNodeInfo.obtain(node) ?: node
        AccessibilityNodeRegistry.registerNode(id, storedNode)

        val type = UINormalizer.determineType(node)
        
        val text = node.text?.toString() ?: ""
        val contentDescription = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName?.toString() ?: ""
        
        // Extract hint gracefully handling API level differences
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString() ?: ""
        } else {
            ""
        }
        
        // Compute a unified label
        val label = if (text.isNotEmpty()) text else contentDescription

        // Extract available actions
        val actions = node.actionList?.map { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
                AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
                else -> action.label?.toString() ?: action.id.toString()
            }
        } ?: emptyList()

        val children = mutableListOf<NormalizedNode>()
        val childCount = node.childCount
        
        if (childCount > 0) {
            for (i in 0 until childCount) {
                try {
                    val childNode = node.getChild(i)
                    if (childNode != null) {
                        val normalizedChild = traverseAndExtract(childNode, depth + 1)
                        if (normalizedChild != null) {
                            children.add(normalizedChild)
                        }
                        // We safely recycle the childNode here because we already created 
                        // a fresh copy via AccessibilityNodeInfo.obtain() for the registry
                        childNode.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("TAV_ACCESSIBILITY", "Error accessing child node at index $i", e)
                }
            }
        }

        return NormalizedNode(
            id = id,
            type = type,
            label = label,
            text = text,
            contentDescription = contentDescription,
            hint = hint,
            className = className,
            resourceId = resourceId,
            bounds = bounds,
            enabled = node.isEnabled,
            clickable = node.isClickable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            scrollable = node.isScrollable,
            checked = node.isChecked,
            selected = node.isSelected,
            longClickable = node.isLongClickable,
            actions = actions,
            children = children
        )
    }
}
