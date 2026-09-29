package com.example.teachablevoice

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A flattened representation of a node for LazyColumn rendering.
 */
data class RenderableNode(val node: NormalizedNode, val depth: Int)

/**
 * Flattens and filters the tree BEFORE Compose renders it.
 * This prevents main thread freezes (ANRs) by eliminating expensive recursive Composable nesting
 * and discarding meaningless containers.
 */
fun preprocessTree(root: NormalizedNode, maxNodes: Int = 50, maxDepth: Int = 10): List<RenderableNode> {
    val result = mutableListOf<RenderableNode>()
    
    fun traverse(node: NormalizedNode, depth: Int) {
        if (result.size >= maxNodes) return
        if (depth > maxDepth) return
        
        val isUsefulInteractive = node.type in listOf(
            NodeType.Button, NodeType.TextField, NodeType.Checkbox, 
            NodeType.Image, NodeType.Dropdown
        )
        val hasTextContent = node.text.isNotEmpty() || node.contentDescription.isNotEmpty()
        val isMeaningfulContainer = node.type in listOf(NodeType.ScrollableContainer, NodeType.List)
        
        // Skip purely visual structural containers that have no text/interaction
        val shouldInclude = isUsefulInteractive || hasTextContent || isMeaningfulContainer
        
        if (shouldInclude) {
            result.add(RenderableNode(node, depth))
        }
        
        for (child in node.children) {
            if (result.size >= maxNodes) break
            traverse(child, depth + 1)
        }
    }
    
    traverse(root, 0)
    return result
}

@Composable
fun MirrorRenderer(rootNode: NormalizedNode, onRenderCount: (Int) -> Unit, onNodeClicked: (String) -> Unit) {
    // 1. Calculate the flat list ONCE per snapshot, outside the Composition layout phase
    val renderableNodes = remember(rootNode) {
        preprocessTree(rootNode, maxNodes = 50, maxDepth = 10)
    }
    
    // 2. Report the actual rendered count
    LaunchedEffect(renderableNodes.size) {
        onRenderCount(renderableNodes.size)
    }

    // 3. Render using a true LazyColumn with flat items.
    // Each item indents itself based on depth to visually recreate the hierarchy.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(renderableNodes) { renderable ->
            FlatRenderNode(renderable.node, renderable.depth, onNodeClicked)
        }
    }
}

@Composable
fun FlatRenderNode(node: NormalizedNode, depth: Int, onNodeClicked: (String) -> Unit) {
    // Indentation based on tree depth
    Box(modifier = Modifier.padding(start = (depth * 8).dp, top = 2.dp, bottom = 2.dp)) {
        when (node.type) {
            NodeType.Text -> {
                Text(
                    text = node.label.ifEmpty { "Empty Text" },
                    modifier = Modifier.padding(4.dp),
                    color = Color.Black
                )
            }
            NodeType.Button -> {
                Button(
                    onClick = { onNodeClicked(node.id) },
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(text = node.label.ifEmpty { "Button" })
                }
            }
            NodeType.TextField -> {
                var textValue by remember { mutableStateOf(node.label) }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text(node.hint.ifEmpty { "Text Field" }) },
                    modifier = Modifier.padding(4.dp).fillMaxWidth()
                )
            }
            NodeType.Checkbox -> {
                var isChecked by remember { mutableStateOf(node.checked) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { isChecked = it }
                    )
                    if (node.label.isNotEmpty()) {
                        Text(text = node.label)
                    }
                }
            }
            NodeType.Image -> {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(1.dp, Color.Gray)
                        .padding(8.dp)
                ) {
                    Text(text = "🖼️ Image: ${node.label.ifEmpty { "no description" }}", color = Color.Gray)
                }
            }
            NodeType.Dropdown -> {
                OutlinedButton(
                    onClick = { /* Interactions disabled */ },
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(text = "▼ ${node.label.ifEmpty { "Dropdown" }}")
                }
            }
            NodeType.ScrollableContainer, NodeType.List -> {
                Text(
                    text = "[Scrollable Content Container]",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(4.dp),
                    color = Color.DarkGray
                )
            }
            NodeType.Card, NodeType.Unknown -> {
                // If it slipped through filtering due to text, display the text
                Text(
                    text = "[Container] ${node.label}",
                    modifier = Modifier.padding(4.dp),
                    color = Color.Gray
                )
            }
        }
    }
}
