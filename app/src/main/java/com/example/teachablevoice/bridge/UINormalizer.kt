package com.example.teachablevoice.bridge

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Normalizes a raw Android AccessibilityNodeInfo into a logical NodeType.
 */
object UINormalizer {

    fun determineType(node: AccessibilityNodeInfo): NodeType {
        val className = node.className?.toString() ?: ""
        
        // 1. Text Fields
        if (node.isEditable || className.contains("EditText")) {
            return NodeType.TextField
        }
        
        // 2. Toggles (Switch, ToggleButton)
        if (className.contains("Switch") || className.contains("ToggleButton")) {
            return NodeType.Toggle
        }
        
        // 3. Checkboxes / RadioButtons
        if (node.isCheckable || className.contains("CheckBox") || className.contains("RadioButton")) {
            return NodeType.Checkbox
        }
        
        // 4. Dropdowns
        if (className.contains("Spinner") || className.contains("AutoCompleteTextView")) {
            return NodeType.Dropdown
        }
        
        // 5. Buttons
        if (className.contains("Button")) {
            return NodeType.Button
        }
        
        // 6. Images
        if (className.contains("ImageView")) {
            // An image that is clickable usually acts as a button
            return if (node.isClickable) NodeType.Button else NodeType.Image
        }
        
        // 7. Scrollable containers
        if (node.isScrollable) {
            if (className.contains("ListView") || className.contains("RecyclerView") || className.contains("GridView")) {
                return NodeType.List
            }
            return NodeType.ScrollableContainer
        }
        
        val hasText = !node.text.isNullOrEmpty()
        
        // 8. Text elements
        if (hasText) {
            // If it has text but is clickable, it might be acting as a button
            return if (node.isClickable) NodeType.Button else NodeType.Text
        }
        
        // 9. Generic clickable elements (Cards, layout wrappers)
        if (node.isClickable) {
            return NodeType.Card
        }
        
        // 10. Unknown
        return NodeType.Unknown
    }
}
