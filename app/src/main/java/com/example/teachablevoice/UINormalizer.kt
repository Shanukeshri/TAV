package com.example.teachablevoice

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
        
        // 2. Checkboxes / Switches
        if (node.isCheckable || className.contains("CheckBox") || className.contains("Switch") || className.contains("RadioButton")) {
            return NodeType.Checkbox
        }
        
        // 3. Dropdowns
        if (className.contains("Spinner") || className.contains("AutoCompleteTextView")) {
            return NodeType.Dropdown
        }
        
        // 4. Buttons
        if (className.contains("Button")) {
            // Includes ImageButton, FloatingActionButton, etc.
            if (className.contains("ImageButton") || className.contains("FloatingActionButton")) {
                return NodeType.Button
            }
            return NodeType.Button
        }
        
        // 5. Images
        if (className.contains("ImageView")) {
            // An image that is clickable usually acts as a button
            return if (node.isClickable) NodeType.Button else NodeType.Image
        }
        
        // 6. Scrollable containers
        if (node.isScrollable) {
            if (className.contains("ListView") || className.contains("RecyclerView") || className.contains("GridView")) {
                return NodeType.List
            }
            return NodeType.ScrollableContainer
        }
        
        val hasText = !node.text.isNullOrEmpty()
        
        // 7. Text elements
        if (hasText) {
            // If it has text but is clickable, it might be acting as a button
            return if (node.isClickable) NodeType.Button else NodeType.Text
        }
        
        // 8. Generic clickable elements (Cards, layout wrappers)
        if (node.isClickable) {
            return NodeType.Card
        }
        
        // 9. Unknown
        return NodeType.Unknown
    }
}
