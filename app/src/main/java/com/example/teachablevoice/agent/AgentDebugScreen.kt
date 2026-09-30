package com.example.teachablevoice.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.teachablevoice.voice.VoiceAgentCoordinator
import com.example.teachablevoice.voice.VoiceAgentState

private val AccentPurple = Color(0xFF9C27B0)
private val AccentBlue = Color(0xFF448AFF)
private val AccentCyan = Color(0xFF18FFFF)
private val MicActive = Color(0xFF66BB6A)
private val MicError = Color(0xFFEF5350)

@Composable
fun AgentDebugScreen() {
    val agentState by AgentStateRepository.globalState.stateFlow.collectAsState()
    val context = LocalContext.current
    var objective by remember { mutableStateOf("") }

    // ── Voice coordinator (singleton per composition) ──
    val voiceCoordinator = remember { VoiceAgentCoordinator(context) }
    val voiceState by voiceCoordinator.state.collectAsState()
    val partialText by voiceCoordinator.partialText.collectAsState()
    val lastTranscription by voiceCoordinator.lastTranscription.collectAsState()
    val voiceError by voiceCoordinator.errorMessage.collectAsState()

    // Clean up on dispose
    DisposableEffect(Unit) {
        onDispose { voiceCoordinator.destroy() }
    }

    // ── RECORD_AUDIO permission ──
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
        if (granted) {
            voiceCoordinator.startListening()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ── Goal input + voice toggle ──
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E30)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Text input row
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text("What is your goal?", color = Color.Gray) },
                    placeholder = { Text("e.g. Open Amazon and find milk", color = Color.DarkGray) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentPurple,
                        unfocusedBorderColor = Color.DarkGray
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // RUN AGENT (typed command)
                    Button(
                        onClick = {
                            if (objective.isNotBlank()) {
                                voiceCoordinator.processCommand(objective)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        enabled = !agentState.isRunning && objective.isNotBlank()
                    ) {
                        Text(
                            if (agentState.isRunning) "RUNNING…" else "RUN AGENT",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // VOICE button
                    VoiceMicButton(
                        voiceState = voiceState,
                        hasPermission = hasAudioPermission,
                        isAgentRunning = agentState.isRunning,
                        onStartListening = {
                            if (hasAudioPermission) {
                                voiceCoordinator.startListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onStopListening = { voiceCoordinator.stopListening() }
                    )

                    // CLEAR
                    OutlinedButton(
                        onClick = {
                            AgentStateRepository.globalState.reset()
                            voiceCoordinator.stopListening()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                    ) {
                        Text("CLEAR")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Voice state indicator ──
        VoiceStateBar(
            voiceState = voiceState,
            partialText = partialText,
            lastTranscription = lastTranscription,
            errorMessage = voiceError
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Status bar ──
        val statusColor = when (agentState.status) {
            AgentStatus.IDLE -> Color.Gray
            AgentStatus.RUNNING -> Color(0xFF42A5F5)
            AgentStatus.COMPLETED -> Color(0xFF66BB6A)
            AgentStatus.ERROR -> Color(0xFFEF5350)
            AgentStatus.ASKING -> Color(0xFFFFCA28)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = agentState.status.name,
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (agentState.statusMessage.isNotEmpty()) {
                Text(" — ", color = Color.Gray, fontSize = 12.sp)
                Text(agentState.statusMessage, color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (agentState.resolvedApp.isNotEmpty()) {
                Text(agentState.resolvedApp.substringAfterLast("."), color = Color.Gray, fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Stats row ──
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("STEPS", "${agentState.stepCount}/30", Modifier.weight(1f))
            StatBox("BACK", "${agentState.backtracks}", Modifier.weight(1f))
            StatBox("VISITED", "${agentState.visitedStates.size}", Modifier.weight(1f))
            StatBox("FAILED", "${agentState.history.count { it.result == TransitionResult.FAILED }}", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Action Log ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ACTION LOG", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            OutlinedButton(
                onClick = {
                    val logText = agentState.log.joinToString("\n") { "[${it.tag}] ${it.message}" }
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logText))
                },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
            ) {
                Text("COPY LOG", fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new entries arrive
        LaunchedEffect(agentState.log.size) {
            if (agentState.log.isNotEmpty()) {
                listState.animateScrollToItem(agentState.log.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0D0D1A))
                .border(1.dp, Color(0xFF2A2A45), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(agentState.log) { entry ->
                val tagColor = when (entry.tag) {
                    "INIT", "SERVICE" -> Color(0xFF90CAF9)
                    "RESOLVE" -> Color(0xFFCE93D8)
                    "OPEN_APP" -> Color(0xFF80CBC4)
                    "MODEL" -> Color(0xFFFFCC80)
                    "OBSERVE" -> Color(0xFFA5D6A7)
                    "ACTION", "EXEC" -> Color(0xFF81D4FA)
                    "PARSE", "VALIDATE" -> Color(0xFFFFAB91)
                    "DONE" -> Color(0xFF66BB6A)
                    "ASK" -> Color(0xFFFFCA28)
                    "LOOP", "RECOVERY" -> Color(0xFFF48FB1)
                    "ERROR" -> Color(0xFFEF5350)
                    "END", "LIMIT" -> Color.Gray
                    "VOICE" -> AccentCyan
                    else -> Color.White
                }
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = String.format("[%s]", entry.tag),
                        color = tagColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(90.dp)
                    )
                    Text(
                        text = entry.message,
                        color = if (entry.isError) Color(0xFFEF5350) else Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
            if (agentState.log.isEmpty()) {
                item {
                    Text(
                        "Enter a goal above and press RUN AGENT,\nor tap 🎤 to speak your command.\nThe full action trace will appear here.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Voice Mic Button
// ──────────────────────────────────────────────

@Composable
fun VoiceMicButton(
    voiceState: VoiceAgentState,
    hasPermission: Boolean,
    isAgentRunning: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    val isListening = voiceState == VoiceAgentState.LISTENING

    // Pulsing animation when listening
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isListening -> MicActive
            voiceState == VoiceAgentState.ERROR -> MicError
            voiceState == VoiceAgentState.PROCESSING -> AccentBlue
            else -> Color(0xFF2A2A45)
        },
        label = "mic_bg"
    )

    val scale = if (isListening) pulseScale else 1f

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 2.dp,
                brush = if (isListening) Brush.linearGradient(listOf(MicActive, AccentCyan))
                else Brush.linearGradient(listOf(Color(0xFF3A3A55), Color(0xFF2A2A45))),
                shape = CircleShape
            )
            .clickable(enabled = !isAgentRunning) {
                if (isListening) {
                    onStopListening()
                } else {
                    onStartListening()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when {
                isListening -> "🔴"
                voiceState == VoiceAgentState.PROCESSING -> "⏳"
                voiceState == VoiceAgentState.ERROR -> "⚠️"
                !hasPermission -> "🔒"
                else -> "🎤"
            },
            fontSize = 20.sp
        )
    }
}

// ──────────────────────────────────────────────
// Voice State Bar
// ──────────────────────────────────────────────

@Composable
fun VoiceStateBar(
    voiceState: VoiceAgentState,
    partialText: String,
    lastTranscription: String,
    errorMessage: String
) {
    // Only show when voice is active or has recent info
    if (voiceState == VoiceAgentState.IDLE && lastTranscription.isEmpty()) return

    val (barColor, statusText) = when (voiceState) {
        VoiceAgentState.IDLE -> Color.Gray to "Ready"
        VoiceAgentState.LISTENING -> MicActive to "🎤 Listening..."
        VoiceAgentState.PROCESSING -> AccentBlue to "⏳ Processing..."
        VoiceAgentState.RUNNING_AGENT -> AccentPurple to "🤖 Agent running..."
        VoiceAgentState.DONE -> Color(0xFF66BB6A) to "✅ Task complete"
        VoiceAgentState.ERROR -> MicError to "❌ Error"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(barColor.copy(alpha = 0.1f))
            .border(1.dp, barColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = statusText,
            color = barColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        // Show partial transcription while listening
        if (voiceState == VoiceAgentState.LISTENING && partialText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\"$partialText\"",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
        }

        // Show last transcription when processing
        if (voiceState == VoiceAgentState.PROCESSING && lastTranscription.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\"$lastTranscription\"",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
        }

        // Show error message
        if (voiceState == VoiceAgentState.ERROR && errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMessage,
                color = MicError,
                fontSize = 11.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF16162A))
            .border(1.dp, Color(0xFF2A2A45), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color(0xFFA0A0C0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

