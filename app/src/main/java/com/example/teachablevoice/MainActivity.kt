package com.example.teachablevoice

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import com.example.teachablevoice.bridge.*
import com.example.teachablevoice.ui.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.teachablevoice.ui.theme.TeachableVoiceAutomationTheme
import com.example.teachablevoice.core.VoiceStateRepository
import com.example.teachablevoice.core.VoiceListenerState
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ComponentName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// ──────────────────────────────────────────────
// Color Palette
// ──────────────────────────────────────────────
private val BgGradientTop = Color(0xFF0A0A14)
private val BgGradientBottom = Color(0xFF12121F)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val AccentCyan = Color(0xFF18FFFF)
private val CardBg = Color(0xFF16162A)
private val CardBorder = Color(0xFF2A2A45)
private val TextPrimary = Color(0xFFF0F0FF)
private val TextSecondary = Color(0xFFA0A0C0)
private val TextMuted = Color(0xFF606080)
private val SuccessGreen = Color(0xFF66BB6A)
private val ErrorRed = Color(0xFFEF5350)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.teachablevoice.model.ModelManager.init(applicationContext)
        // Start App

        
        enableEdgeToEdge()
        setContent {
            TeachableVoiceAutomationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgGradientTop) {
                    MirrorApp()
                }
            }
        }
    }
}

fun countTotalNodes(node: NormalizedNode): Int {
    var count = 1
    for (child in node.children) count += countTotalNodes(child)
    return count
}

/** View mode for the mirror */
enum class MirrorViewMode { Screenshot, Tree }

/** Top-level screen navigation */
enum class AppScreen { Mirror, Apps, Model, Debug }

@Composable
fun MirrorApp() {
    val isServiceRunning by MirrorAccessibilityService.isServiceRunning.collectAsState()
    val snapshot by UiMirrorRepository.snapshotFlow.collectAsState()
    val screenshot by UiMirrorRepository.screenshotFlow.collectAsState()
    var viewMode by remember { mutableStateOf(MirrorViewMode.Screenshot) }
    var showOverlay by remember { mutableStateOf(true) }
    var currentScreen by remember { mutableStateOf(AppScreen.Mirror) }
    val context = LocalContext.current

    // Voice service state
    val voiceState by VoiceStateRepository.globalState.stateFlow.collectAsState()
    val isVoiceActive = voiceState.isServiceRunning

    // RECORD_AUDIO permission (still needed for the session to work)
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    // Observe text input requests (from editable field taps in screenshot mode)
    val textInputRequest by MirrorInteractionController.textInputRequest.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgGradientTop, BgGradientBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            MirrorHeader(isServiceRunning, snapshot, viewMode, showOverlay,
                currentScreen = currentScreen,
                onViewModeToggle = {
                    viewMode = if (viewMode == MirrorViewMode.Screenshot) MirrorViewMode.Tree
                    else MirrorViewMode.Screenshot
                },
                onOverlayToggle = { showOverlay = !showOverlay }
            )

            // ── Voice Service Toggle Bar ──
            VoiceServiceBar(
                isVoiceActive = isVoiceActive,
                voiceState = voiceState.state,
                hasAudioPermission = hasAudioPermission,
                onToggleVoice = {
                    if (!hasAudioPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        // Open Android settings to set the default assistant
                        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                }
            )

            // Main content area
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    AppScreen.Mirror -> {
                        when {
                            !isServiceRunning -> ServiceSetupScreen()
                            snapshot == null -> WaitingScreen()
                            else -> MirrorScreen(snapshot!!, screenshot, viewMode, showOverlay)
                        }
                    }
                    AppScreen.Apps -> {
                        AppLauncherScreen()
                    }
                    AppScreen.Model -> {
                        ModelChatScreen()
                    }
                    AppScreen.Debug -> {
                        com.example.teachablevoice.agent.AgentDebugScreen()
                    }
                }
            }

            // Bottom navigation bar
            BottomNavBar(
                currentScreen = currentScreen,
                onScreenChange = { currentScreen = it }
            )
        }

        // Text input dialog overlay (shown when user taps an editable field)
        textInputRequest?.let { request ->
            MirrorTextInputDialog(
                request = request,
                onSubmit = { text ->
                    MirrorInteractionController.requestSetText(request.nodeId, text)
                    MirrorInteractionController.clearTextInputRequest()
                },
                onDismiss = {
                    MirrorInteractionController.clearTextInputRequest()
                }
            )
        }

        // Goal input is now in the Agent tab (AgentDebugScreen)
    }
}

/**
 * Voice service toggle bar — shown at the top of the app.
 * Directs the user to Android Settings to set TAV as the default assistant.
 */
@Composable
fun VoiceServiceBar(
    isVoiceActive: Boolean,
    voiceState: VoiceListenerState,
    hasAudioPermission: Boolean,
    onToggleVoice: () -> Unit
) {
    val barColor = when {
        isVoiceActive && voiceState == VoiceListenerState.LISTENING -> Color(0xFF66BB6A)
        isVoiceActive && voiceState == VoiceListenerState.PROCESSING -> Color(0xFF448AFF)
        isVoiceActive -> AccentPurple
        else -> Color(0xFF2A2A45)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(barColor.copy(alpha = 0.15f))
            .border(1.dp, barColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable { onToggleVoice() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isVoiceActive) "🎤" else "🔇",
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    !hasAudioPermission -> "Voice — tap to grant microphone permission"
                    isVoiceActive -> "TAV is Default Assistant — say \"Hey start listening\""
                    else -> "Voice — tap to set TAV as Default Assistant"
                },
                color = if (isVoiceActive) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (isVoiceActive) {
                Text(
                    text = when (voiceState) {
                        VoiceListenerState.IDLE -> "Waiting for wake phrase..."
                        VoiceListenerState.LISTENING -> "Listening for command..."
                        VoiceListenerState.TRANSCRIBING, VoiceListenerState.PROCESSING -> "Processing..."
                        VoiceListenerState.AGENT_RUNNING -> "Agent running..."
                        else -> ""
                    },
                    color = barColor,
                    fontSize = 10.sp
                )
            }
        }
        Text(
            text = if (isVoiceActive) "ACTIVE" else "SET",
            color = if (isVoiceActive) SuccessGreen else AccentPurple,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A floating text input dialog that appears when the user taps an editable field
 * in the mirrored app. This lets the user type within Mirror UI and pushes
 * the text to the real app via ACTION_SET_TEXT without switching apps.
 */
@Composable
fun MirrorTextInputDialog(
    request: TextInputRequest,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember(request.nodeId) { mutableStateOf(request.currentText) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .border(1.dp, AccentPurple.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable { /* absorb clicks so they don't dismiss */ }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("✏️ Enter Text", color = TextPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)

            if (request.hint.isNotEmpty()) {
                Text("Hint: ${request.hint}", color = TextMuted, fontSize = 12.sp)
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = CardBorder,
                    cursorColor = AccentPurple,
                    focusedContainerColor = Color(0xFF1E1E30),
                    unfocusedContainerColor = Color(0xFF1E1E30)
                ),
                placeholder = {
                    Text(request.hint.ifEmpty { "Type here…" },
                        color = TextMuted, fontSize = 14.sp)
                },
                shape = RoundedCornerShape(10.dp),
                singleLine = false,
                maxLines = 4
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Cancel
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(CardBorder)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cancel", color = TextSecondary, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                }

                // Send
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(listOf(AccentPurple, AccentBlue)))
                        .clickable { onSubmit(inputText) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Send ✓", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BottomNavBar(currentScreen: AppScreen, onScreenChange: (AppScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .border(width = 1.dp, color = CardBorder)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem(
            icon = "🔮",
            label = "Mirror",
            isSelected = currentScreen == AppScreen.Mirror,
            onClick = { onScreenChange(AppScreen.Mirror) }
        )
        BottomNavItem(
            icon = "📱",
            label = "Apps",
            isSelected = currentScreen == AppScreen.Apps,
            onClick = { onScreenChange(AppScreen.Apps) }
        )
        BottomNavItem(
            icon = "🤖",
            label = "Model",
            isSelected = currentScreen == AppScreen.Model,
            onClick = { onScreenChange(AppScreen.Model) }
        )
        BottomNavItem(
            icon = "🐛",
            label = "Agent",
            isSelected = currentScreen == AppScreen.Debug,
            onClick = { onScreenChange(AppScreen.Debug) }
        )
    }
}

@Composable
fun BottomNavItem(icon: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) AccentPurple.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (isSelected) AccentPurple else TextMuted

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label, color = textColor, fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ──────────────────────────────────────────────
// Header Bar
// ──────────────────────────────────────────────

@Composable
fun MirrorHeader(
    isServiceActive: Boolean,
    snapshot: UiSnapshot?,
    viewMode: MirrorViewMode,
    showOverlay: Boolean,
    currentScreen: AppScreen = AppScreen.Mirror,
    onViewModeToggle: () -> Unit,
    onOverlayToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mirror UI", color = TextPrimary, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                if (snapshot != null) {
                    Text(snapshot.packageName, color = AccentCyan, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusPill(isServiceActive)
        }

        // View mode controls — only show when mirroring is active and on Mirror tab
        if (isServiceActive && snapshot != null && currentScreen == AppScreen.Mirror) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Screenshot / Tree toggle
                ModeChip(
                    text = "📸 Screenshot",
                    isActive = viewMode == MirrorViewMode.Screenshot,
                    onClick = { if (viewMode != MirrorViewMode.Screenshot) onViewModeToggle() }
                )
                ModeChip(
                    text = "🌳 Tree",
                    isActive = viewMode == MirrorViewMode.Tree,
                    onClick = { if (viewMode != MirrorViewMode.Tree) onViewModeToggle() }
                )

                Spacer(modifier = Modifier.weight(1f))

                // Overlay toggle (only in screenshot mode)
                if (viewMode == MirrorViewMode.Screenshot) {
                    ModeChip(
                        text = if (showOverlay) "🔍 Overlay ON" else "🔍 Overlay OFF",
                        isActive = showOverlay,
                        onClick = onOverlayToggle
                    )
                }
            }
        }
    }
}

@Composable
fun ModeChip(text: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) AccentPurple.copy(alpha = 0.25f) else CardBg)
            .border(
                1.dp,
                if (isActive) AccentPurple.copy(alpha = 0.6f) else CardBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = if (isActive) TextPrimary else TextMuted,
            fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusPill(isActive: Boolean) {
    val dotColor = if (isActive) SuccessGreen else ErrorRed
    val label = if (isActive) "Active" else "Inactive"
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape)
            .background(if (isActive) dotColor.copy(alpha = pulseAlpha) else dotColor))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = if (isActive) SuccessGreen else ErrorRed,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ──────────────────────────────────────────────
// Service Setup Screen
// ──────────────────────────────────────────────

@Composable
fun ServiceSetupScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "float")
        val offsetY by infiniteTransition.animateFloat(
            initialValue = -8f, targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "float_y"
        )
        Text("🔮", fontSize = 64.sp, modifier = Modifier.offset(y = offsetY.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Enable Accessibility Service", color = TextPrimary, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Mirror UI needs accessibility access to read and interact with other apps.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(32.dp))

        SetupStepCard("1", "Open Accessibility Settings", "Tap the button below")
        Spacer(modifier = Modifier.height(8.dp))
        SetupStepCard("2", "Find \"Mirror UI\"", "Under Downloaded/Installed services")
        Spacer(modifier = Modifier.height(8.dp))
        SetupStepCard("3", "Toggle it ON", "Confirm the permission dialog")
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(AccentPurple, AccentBlue)))
                .clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Open Accessibility Settings", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun SetupStepCard(stepNumber: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentPurple),
            contentAlignment = Alignment.Center) {
            Text(stepNumber, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

// ──────────────────────────────────────────────
// Waiting Screen
// ──────────────────────────────────────────────

@Composable
fun WaitingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📱", fontSize = 56.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Waiting for a third-party app…", color = TextPrimary, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Switch to another app to capture and mirror its UI here.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(modifier = Modifier.height(32.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = AccentPurple, trackColor = CardBorder)
        Spacer(modifier = Modifier.height(40.dp))

        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)).padding(16.dp)) {
            Text("💡 Tips", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp))
            TipItem("Open any app to start mirroring")
            TipItem("Screenshot mode shows the exact UI with tap forwarding")
            TipItem("Tree mode shows structured elements with rich controls")
            TipItem("Toggle the overlay to see interactive zones")
        }
    }
}

@Composable
fun TipItem(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = AccentPurple, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
        Text(text, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

// ──────────────────────────────────────────────
// Mirror Screen (actively mirroring)
// ──────────────────────────────────────────────

@Composable
fun MirrorScreen(
    snapshot: UiSnapshot,
    screenshot: Bitmap?,
    viewMode: MirrorViewMode,
    showOverlay: Boolean
) {
    val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
    val totalCaptured = remember(snapshot.rootNode) { countTotalNodes(snapshot.rootNode) }
    var renderedNodesCount by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Info bar
        Row(
            modifier = Modifier.fillMaxWidth().background(CardBg.copy(alpha = 0.4f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🕐 $date", color = TextMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Nodes", "$totalCaptured", AccentBlue)
                if (viewMode == MirrorViewMode.Tree) {
                    StatChip("Shown", "$renderedNodesCount", AccentPurple)
                }
                if (screenshot != null) {
                    StatChip("📸", "${screenshot.width}×${screenshot.height}", AccentCyan)
                }
            }
        }

        // Mirror content
        when (viewMode) {
            MirrorViewMode.Screenshot -> {
                if (screenshot != null && !screenshot.isRecycled) {
                    ScreenshotMirrorView(
                        screenshot = screenshot,
                        rootNode = snapshot.rootNode,
                        showOverlay = showOverlay
                    )
                } else {
                    // No screenshot available — show message
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📸", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No screenshot captured yet",
                            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Switch to the target app and back to capture a screenshot.\nRequires Android 11+ (API 30).",
                            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                            lineHeight = 20.sp)
                    }
                }
            }
            MirrorViewMode.Tree -> {
                TreeMirrorView(
                    rootNode = snapshot.rootNode,
                    onRenderCount = { renderedNodesCount = it },
                    onNodeClicked = { MirrorInteractionController.requestClick(it) }
                )
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text("$label: ", color = TextMuted, fontSize = 10.sp)
        Text(value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}