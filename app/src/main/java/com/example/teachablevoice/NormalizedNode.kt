package com.example.teachablevoice

import android.graphics.Rect

/**
 * Represents the normalized type of a UI component.
 */
enum class NodeType {
    Text, Button, TextField, Image, Checkbox, List, Card, ScrollableContainer, Dropdown, Unknown
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
    val bounds: Rect,
    val enabled: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val actions: List<String>,
    val children: List<NormalizedNode>
)
