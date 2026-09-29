package com.example.teachablevoice

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ──────────────────────────────────────────────
// Color palette
// ──────────────────────────────────────────────
private val MirrorBgGradientStart = Color(0xFF0F0F1A)
private val MirrorBgGradientEnd = Color(0xFF1A1A2E)
private val NodeButtonColor = Color(0xFF6C63FF)
private val NodeTextFieldBg = Color(0xFF1E1E30)
private val NodeTextFieldBorder = Color(0xFF3D3D5C)
private val NodeTextColor = Color(0xFFE0E0F0)
private val NodeCheckboxColor = Color(0xFF4CAF50)
private val NodeToggleOnColor = Color(0xFF7C4DFF)
private val NodeToggleOffColor = Color(0xFF424242)
private val NodeImageBg = Color(0xFF2A2A3D)
private val NodeDropdownBg = Color(0xFF252540)
private val NodeCardBg = Color(0xFF1C1C30)
private val NodeScrollableBg = Color(0xFF16162A)
private val NodeBorderDefault = Color(0xFF2E2E4A)
private val AccentGlow = Color(0xFF7C4DFF)
private val TextMuted = Color(0xFF606080)

// Overlay colors for screenshot mode
private val OverlayClickable = Color(0x336C63FF)
private val OverlayEditable = Color(0x334CAF50)
private val OverlayScrollable = Color(0x33FF9800)
private val OverlayBorderClickable = Color(0x886C63FF)
private val OverlayBorderEditable = Color(0x884CAF50)
private val OverlayTapFeedback = Color(0x55FFFFFF)

// ══════════════════════════════════════════════════
// SCREENSHOT MIRROR VIEW
// Pixel-perfect screenshot with interactive overlay
// ══════════════════════════════════════════════════

@Composable
fun ScreenshotMirrorView(
    screenshot: Bitmap,
    rootNode: NormalizedNode?,
    showOverlay: Boolean
) {
    val imageBitmap = remember(screenshot) { screenshot.asImageBitmap() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTapPos by remember { mutableStateOf<Offset?>(null) }
    var tapFeedbackAlpha by remember { mutableStateOf(0f) }

    // Compute scale factors
    val scaleX = if (viewSize.width > 0) screenshot.width.toFloat() / viewSize.width.toFloat() else 1f
    val scaleY = if (viewSize.height > 0) screenshot.height.toFloat() / viewSize.height.toFloat() else 1f

    // Collect interactive nodes for overlay
    val interactiveNodes = remember(rootNode) {
        if (rootNode == null) emptyList()
        else collectInteractiveNodes(rootNode)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                viewSize = coordinates.size
            }
    ) {
        // Background screenshot
        Image(
            bitmap = imageBitmap,
            contentDescription = "Mirror of target app",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // Interactive overlay (touch targets + highlights)
        if (showOverlay && viewSize.width > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (node in interactiveNodes) {
                    val left = node.bounds.left.toFloat() / scaleX
                    val top = node.bounds.top.toFloat() / scaleY
                    val width = node.bounds.width().toFloat() / scaleX
                    val height = node.bounds.height().toFloat() / scaleY

                    if (width > 2 && height > 2) {
                        val fillColor = when {
                            node.editable -> OverlayEditable
                            node.scrollable -> OverlayScrollable
                            node.clickable || node.longClickable -> OverlayClickable
                            else -> Color.Transparent
                        }
                        val borderColor = when {
                            node.editable -> OverlayBorderEditable
                            node.clickable || node.longClickable -> OverlayBorderClickable
                            else -> Color.Transparent
                        }

                        if (fillColor != Color.Transparent) {
                            drawRect(
                                color = fillColor,
                                topLeft = Offset(left, top),
                                size = Size(width, height)
                            )
                            drawRect(
                                color = borderColor,
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }
                }

                // Tap feedback ripple
                lastTapPos?.let { pos ->
                    drawCircle(
                        color = OverlayTapFeedback,
                        radius = 30f,
                        center = pos
                    )
                }
            }
        }

        // Touch handler — captures taps and swipes, forwards to real app
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(scaleX, scaleY) {
                    detectTapGestures(
                        onTap = { offset ->
                            lastTapPos = offset
                            val realX = offset.x * scaleX
                            val realY = offset.y * scaleY
                            MirrorInteractionController.requestCoordinateTap(realX, realY)
                        },
                        onLongPress = { offset ->
                            val realX = offset.x * scaleX
                            val realY = offset.y * scaleY
                            MirrorInteractionController.requestCoordinateLongPress(realX, realY)
                        }
                    )
                }
                .pointerInput(scaleX, scaleY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var totalDragX = 0f
                        var totalDragY = 0f
                        var isDrag = false

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val delta = change.positionChange()
                            totalDragX += delta.x
                            totalDragY += delta.y
                            if (abs(totalDragX) > 30 || abs(totalDragY) > 30) {
                                isDrag = true
                            }
                        } while (event.changes.any { it.pressed })

                        if (isDrag && abs(totalDragY) > 50) {
                            val realStartX = startPos.x * scaleX
                            val realStartY = startPos.y * scaleY
                            val realEndX = (startPos.x + totalDragX) * scaleX
                            val realEndY = (startPos.y + totalDragY) * scaleY
                            MirrorInteractionController.requestCoordinateSwipe(
                                realStartX, realStartY,
                                realEndX, realEndY,
                                durationMs = 300
                            )
                        }
                    }
                }
        )
    }
}

/**
 * Collects all interactive nodes from the tree for overlay rendering.
 */
private fun collectInteractiveNodes(root: NormalizedNode): List<NormalizedNode> {
    val result = mutableListOf<NormalizedNode>()

    fun traverse(node: NormalizedNode) {
        if (node.clickable || node.editable || node.scrollable || node.longClickable) {
            if (node.bounds.width() > 0 && node.bounds.height() > 0) {
                result.add(node)
            }
        }
        for (child in node.children) {
            traverse(child)
        }
    }

    traverse(root)
    return result
}


// ══════════════════════════════════════════════════
// TREE MIRROR VIEW (fallback / alternative)
// Structured component-based rendering
// ══════════════════════════════════════════════════

data class RenderableNode(val node: NormalizedNode, val depth: Int)

/**
 * Collects text from child nodes to build a composite label for containers
 * that don't have their own text (like restaurant cards in Zomato).
 */
fun collectChildTexts(node: NormalizedNode, maxDepth: Int = 3): String {
    if (maxDepth <= 0) return ""
    val texts = mutableListOf<String>()
    for (child in node.children) {
        val childText = child.text.trim()
        val childDesc = child.contentDescription.trim()
        if (childText.isNotEmpty()) texts.add(childText)
        else if (childDesc.isNotEmpty()) texts.add(childDesc)
        if (texts.size < 4) {
            val deeper = collectChildTexts(child, maxDepth - 1)
            if (deeper.isNotEmpty()) texts.add(deeper)
        }
    }
    return texts.filter { it.isNotEmpty() }.distinct().take(4).joinToString(" · ")
}

fun preprocessTree(root: NormalizedNode, maxNodes: Int = 200, maxDepth: Int = 25): List<RenderableNode> {
    val result = mutableListOf<RenderableNode>()
    val seenTexts = mutableSetOf<String>()

    fun traverse(node: NormalizedNode, depth: Int) {
        if (result.size >= maxNodes) return
        if (depth > maxDepth) return

        if (node.bounds.width() <= 0 || node.bounds.height() <= 0) {
            for (child in node.children) {
                if (result.size >= maxNodes) break
                traverse(child, depth)
            }
            return
        }

        val isInteractive = node.type in listOf(
            NodeType.Button, NodeType.TextField, NodeType.Checkbox,
            NodeType.Dropdown, NodeType.Toggle
        )
        val isScrollable = node.type in listOf(NodeType.ScrollableContainer, NodeType.List)
        val displayText = node.label.trim()
        val hasUniqueText = displayText.isNotEmpty() && displayText !in seenTexts

        // Show ALL images, not just ones with descriptions
        val isImage = node.type == NodeType.Image

        // Clickable cards/containers: ALWAYS include, even without direct text
        // (they contain child text like restaurant names, ratings, etc.)
        val isClickableContainer = (node.type == NodeType.Card || node.type == NodeType.Unknown) &&
                (node.clickable || node.longClickable)

        // Card with its own text
        val isMeaningfulCard = node.type == NodeType.Card && displayText.isNotEmpty()

        val isMeaningfulText = node.type == NodeType.Text && hasUniqueText

        val shouldInclude = isInteractive || isScrollable || isImage ||
                isClickableContainer || isMeaningfulCard || isMeaningfulText

        if (shouldInclude) {
            result.add(RenderableNode(node, depth))
            if (displayText.isNotEmpty()) seenTexts.add(displayText)

            // For clickable containers, mark all child texts as seen
            // so we don't duplicate them as separate nodes
            if (isClickableContainer && displayText.isEmpty()) {
                fun markChildTexts(n: NormalizedNode) {
                    val t = n.text.trim()
                    if (t.isNotEmpty()) seenTexts.add(t)
                    for (c in n.children) markChildTexts(c)
                }
                markChildTexts(node)
            }
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
fun TreeMirrorView(
    rootNode: NormalizedNode,
    onRenderCount: (Int) -> Unit,
    onNodeClicked: (String) -> Unit
) {
    val renderableNodes = remember(rootNode) {
        preprocessTree(rootNode, maxNodes = 200, maxDepth = 25)
    }

    LaunchedEffect(renderableNodes.size) {
        onRenderCount(renderableNodes.size)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MirrorBgGradientStart, MirrorBgGradientEnd)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        renderableNodes.forEach { renderable ->
            MirrorNodeCard(renderable.node, renderable.depth, onNodeClicked)
        }
        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ──────────────────────────────────────────────
// Individual Node Card Renderers (Tree Mode)
// ──────────────────────────────────────────────

@Composable
fun MirrorNodeCard(node: NormalizedNode, depth: Int, onNodeClicked: (String) -> Unit) {
    val indent = (depth * 12).coerceAtMost(60).dp
    Box(modifier = Modifier.padding(start = indent)) {
        when (node.type) {
            NodeType.Text -> MirrorTextNode(node)
            NodeType.Button -> MirrorButtonNode(node, onNodeClicked)
            NodeType.TextField -> MirrorTextFieldNode(node)
            NodeType.Checkbox -> MirrorCheckboxNode(node)
            NodeType.Toggle -> MirrorToggleNode(node)
            NodeType.Image -> MirrorImageNode(node, onNodeClicked)
            NodeType.Dropdown -> MirrorDropdownNode(node, onNodeClicked)
            NodeType.ScrollableContainer, NodeType.List -> MirrorScrollableNode(node)
            NodeType.Card -> MirrorCardNode(node, onNodeClicked)
            NodeType.Unknown -> {
                // Clickable unknowns are treated as cards (tappable containers)
                if (node.clickable || node.longClickable) {
                    MirrorCardNode(node, onNodeClicked)
                } else if (node.label.isNotEmpty()) {
                    MirrorTextNode(node)
                }
            }
        }
    }
}

@Composable
fun MirrorTextNode(node: NormalizedNode) {
    val text = node.label.ifEmpty { node.contentDescription.ifEmpty { node.text } }
    if (text.isEmpty()) return
    Text(text, color = NodeTextColor, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        maxLines = 5, overflow = TextOverflow.Ellipsis)
}

@Composable
fun MirrorButtonNode(node: NormalizedNode, onNodeClicked: (String) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(NodeButtonColor, NodeButtonColor.copy(alpha = 0.8f))))
            .pointerInput(node.id) {
                detectTapGestures(
                    onTap = { onNodeClicked(node.id) },
                    onLongPress = { MirrorInteractionController.requestLongClick(node.id) }
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(node.label.ifEmpty { "Button" }, color = Color.White, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MirrorTextFieldNode(node: NormalizedNode) {
    var textValue by remember(node.id) { mutableStateOf(node.text) }
    LaunchedEffect(textValue) {
        if (textValue != node.text) {
            kotlinx.coroutines.delay(500)
            MirrorInteractionController.requestSetText(node.id, textValue)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (node.hint.isNotEmpty()) {
            Text(node.hint, color = NodeTextColor.copy(alpha = 0.5f), fontSize = 11.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        BasicTextField(
            value = textValue, onValueChange = { textValue = it },
            textStyle = TextStyle(color = NodeTextColor, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(NodeTextFieldBg)
                .border(1.dp, NodeTextFieldBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (textValue.isEmpty()) {
                    Text(node.hint.ifEmpty { "Enter text…" }, color = NodeTextColor.copy(alpha = 0.3f), fontSize = 14.sp)
                }
                inner()
            }
        )
    }
}

@Composable
fun MirrorCheckboxNode(node: NormalizedNode) {
    var isChecked by remember(node.id) { mutableStateOf(node.checked) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(NodeCardBg)
            .clickable { isChecked = !isChecked; MirrorInteractionController.requestToggle(node.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Checkbox(checked = isChecked, onCheckedChange = { isChecked = it; MirrorInteractionController.requestToggle(node.id) },
            colors = CheckboxDefaults.colors(checkedColor = NodeCheckboxColor, uncheckedColor = NodeBorderDefault, checkmarkColor = Color.White))
        Spacer(modifier = Modifier.width(8.dp))
        if (node.label.isNotEmpty()) Text(node.label, color = NodeTextColor, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MirrorToggleNode(node: NormalizedNode) {
    var isOn by remember(node.id) { mutableStateOf(node.checked) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(NodeCardBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (node.label.isNotEmpty()) Text(node.label, color = NodeTextColor, fontSize = 14.sp,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Switch(checked = isOn, onCheckedChange = { isOn = it; MirrorInteractionController.requestToggle(node.id) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NodeToggleOnColor,
                uncheckedThumbColor = Color.Gray, uncheckedTrackColor = NodeToggleOffColor))
    }
}

@Composable
fun MirrorImageNode(node: NormalizedNode, onNodeClicked: ((String) -> Unit)? = null) {
    val desc = node.label.ifEmpty { node.contentDescription.ifEmpty { "Image" } }
    val isClickable = node.clickable || node.longClickable
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NodeImageBg)
        .border(1.dp, if (isClickable) OverlayBorderClickable else NodeBorderDefault, RoundedCornerShape(10.dp))
        .then(if (isClickable && onNodeClicked != null) Modifier.clickable { onNodeClicked(node.id) } else Modifier)
        .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🖼️", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(desc, color = NodeTextColor.copy(alpha = 0.7f), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isClickable) {
                    Text("Tap to interact", color = AccentGlow.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun MirrorDropdownNode(node: NormalizedNode, onNodeClicked: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NodeDropdownBg)
        .border(1.dp, NodeBorderDefault, RoundedCornerShape(10.dp)).clickable { onNodeClicked(node.id) }
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(node.label.ifEmpty { "Dropdown" }, color = NodeTextColor, fontSize = 14.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", color = AccentGlow, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MirrorScrollableNode(node: NormalizedNode) {
    // Gather summary info about children
    val childCount = node.children.size
    val itemSummaries = remember(node.id) {
        collectScrollableChildSummaries(node, maxItems = 5)
    }
    val classHint = remember(node.className) {
        when {
            node.className.contains("RecyclerView") -> "RecyclerView"
            node.className.contains("ListView") -> "ListView"
            node.className.contains("GridView") -> "GridView"
            node.className.contains("ScrollView") -> "ScrollView"
            node.className.contains("ViewPager") -> "ViewPager"
            else -> ""
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NodeScrollableBg)
            .border(1.dp, AccentGlow.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("↕", color = AccentGlow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (node.type == NodeType.List) "Scrollable List" else "Scrollable Area",
                        color = NodeTextColor.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (classHint.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "($classHint)",
                            color = AccentGlow.copy(alpha = 0.5f), fontSize = 10.sp
                        )
                    }
                }
                Text(
                    "$childCount items visible",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }

        // Item previews
        if (itemSummaries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF111122))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemSummaries.forEachIndexed { index, summary ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${index + 1}.",
                            color = AccentGlow.copy(alpha = 0.5f), fontSize = 11.sp,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            summary,
                            color = NodeTextColor.copy(alpha = 0.75f), fontSize = 11.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
                        )
                    }
                }
                if (childCount > 5) {
                    Text(
                        "… and ${childCount - 5} more items",
                        color = TextMuted, fontSize = 10.sp,
                        modifier = Modifier.padding(start = 18.dp)
                    )
                }
            }
        }

        // Scroll buttons
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionChip("▲ Up") { MirrorInteractionController.requestScrollBackward(node.id) }
            SmallActionChip("▼ Down") { MirrorInteractionController.requestScrollForward(node.id) }
        }
    }
}

/**
 * Collects summary text for each direct child of a scrollable container.
 * For each child, it gathers all text content recursively to build a meaningful description.
 */
private fun collectScrollableChildSummaries(node: NormalizedNode, maxItems: Int = 5): List<String> {
    val summaries = mutableListOf<String>()
    for (child in node.children) {
        if (summaries.size >= maxItems) break
        val texts = mutableListOf<String>()
        fun gatherTexts(n: NormalizedNode, depth: Int) {
            if (depth > 4) return
            val t = n.text.trim()
            val d = n.contentDescription.trim()
            if (t.isNotEmpty() && t !in texts) texts.add(t)
            else if (d.isNotEmpty() && d !in texts) texts.add(d)
            for (c in n.children) {
                if (texts.size >= 6) break
                gatherTexts(c, depth + 1)
            }
        }
        gatherTexts(child, 0)
        val summary = texts.take(4).joinToString(" · ")
        if (summary.isNotEmpty()) {
            summaries.add(summary)
        } else {
            // Even without text, show the type info
            val typeHint = when {
                child.clickable -> "Tappable item"
                child.type == NodeType.Image -> "Image"
                child.children.isNotEmpty() -> "Container (${child.children.size} elements)"
                else -> null
            }
            if (typeHint != null) summaries.add(typeHint)
        }
    }
    return summaries
}

@Composable
fun SmallActionChip(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AccentGlow.copy(alpha = 0.15f))
        .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = AccentGlow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MirrorCardNode(node: NormalizedNode, onNodeClicked: (String) -> Unit) {
    // Derive label: use direct text, or collect from children
    val directLabel = node.label.ifEmpty { node.contentDescription }
    val compositeLabel = remember(node.id) {
        if (directLabel.isNotEmpty()) directLabel
        else collectChildTexts(node, maxDepth = 3)
    }
    // Skip only if truly empty after checking children
    if (compositeLabel.isEmpty()) return

    val isClickable = node.clickable || node.longClickable
    val borderColor = if (isClickable) AccentGlow.copy(alpha = 0.4f) else NodeBorderDefault

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NodeCardBg)
        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
        .pointerInput(node.id) {
            detectTapGestures(
                onTap = { onNodeClicked(node.id) },
                onLongPress = { MirrorInteractionController.requestLongClick(node.id) }
            )
        }
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Column {
            if (isClickable) {
                Text("⚡ Tap to open", color = AccentGlow.copy(alpha = 0.5f), fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(compositeLabel, color = NodeTextColor, fontSize = 14.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        }
    }
}
