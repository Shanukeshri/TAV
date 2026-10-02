package com.example.teachablevoice.bridge

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Maintains a thread-safe mapping between normalized mirror node IDs and
 * the actual AccessibilityNodeInfo objects from the third-party app.
 *
 * This registry is rebuilt every time the AccessibilityService captures a
 * new UI tree. Old nodes are recycled to prevent memory leaks.
 *
 * Thread safety: Both the extraction coroutine (which writes) and the
 * click-handling coroutine (which reads) can access the map concurrently,
 * so all operations are synchronized.
 */
object AccessibilityNodeRegistry {
    private val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()

    /**
     * Clears the current registry and recycles all stored AccessibilityNodeInfo copies.
     */
    @Synchronized
    fun clear() {
        for (node in nodeMap.values) {
            try {
                node.recycle()
            } catch (_: Exception) {
                // Ignore recycling errors for already-stale nodes
            }
        }
        nodeMap.clear()
    }

    /**
     * Registers a mapping between a generated ID and its corresponding
     * AccessibilityNodeInfo (should be obtained via AccessibilityNodeInfo.obtain()).
     */
    @Synchronized
    fun registerNode(id: String, node: AccessibilityNodeInfo) {
        nodeMap[id] = node
    }

    /**
     * Retrieves the mapped AccessibilityNodeInfo for a given node ID.
     * Returns null if the node was never registered or has been cleared.
     */
    @Synchronized
    fun getNode(id: String): AccessibilityNodeInfo? {
        return nodeMap[id]
    }

    /**
     * Returns the current number of registered nodes (useful for diagnostics).
     */
    @Synchronized
    fun size(): Int = nodeMap.size
}
