package com.example.teachablevoice

import android.graphics.Rect

/**
 * Represents the normalized type of a UI component.
 */
enum class NodeType {
    Text, Button, TextField, Image, Checkbox, List, Card, ScrollableContainer, Dropdown, Toggle, Unknown
}

/**
 * A simplified, serializable representation of an AccessibilityNodeInfo.
 * This decouples our automation logic from raw Android API objects.
 */
data class NormalizedNode(
    val id: String,
    val type: NodeType,
    val label: String,
    val text: String,
    val contentDescription: String,
    val hint: String,
    val className: String,
    val resourceId: String,
    val bounds: Rect,
    val enabled: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val longClickable: Boolean,
    val actions: List<String>,
    val children: List<NormalizedNode>
) {
    /** Whether this node represents a meaningful interactive or content element */
    val isMeaningful: Boolean
        get() = type != NodeType.Unknown ||
                label.isNotEmpty() ||
                text.isNotEmpty() ||
                contentDescription.isNotEmpty() ||
                clickable || editable || scrollable || longClickable

    /** Whether this node has visible bounds (non-zero area) */
    val hasVisibleBounds: Boolean
        get() = bounds.width() > 0 && bounds.height() > 0

    /**
     * Stable hash of the tree that does NOT include ephemeral node ids.
     * Used by waitForStableUi so identity is about content, not n17-style ids.
     */
    fun semanticHash(): String = buildString { appendSemantic(this) }.hashCode().toString(16)

    private fun appendSemantic(out: StringBuilder) {
        out.append(resourceId).append('|')
            .append(className).append('|')
            .append(type.name).append('|')
            .append(text).append('|')
            .append(contentDescription).append('|')
            .append(clickable).append('|')
            .append(editable).append('|')
            .append(scrollable)
        out.append('[')
        children.forEach { it.appendSemantic(out) }
        out.append(']')
    }
}
