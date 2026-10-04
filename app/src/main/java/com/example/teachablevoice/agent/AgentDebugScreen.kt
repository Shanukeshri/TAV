package com.example.teachablevoice.agent

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val AccentPurple = Color(0xFF9C27B0)
private val AccentCyan = Color(0xFF18FFFF)

@Composable
fun AgentDebugScreen() {
    val agentState by AgentStateRepository.globalState.stateFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var objective by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ── Goal input ──
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
                                val intent = Intent(context, AgentForegroundService::class.java).apply {
                                    putExtra(AgentForegroundService.EXTRA_VOICE_COMMAND, objective)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
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

                    // CLEAR
                    OutlinedButton(
                        onClick = {
                            AgentStateRepository.globalState.reset()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                    ) {
                        Text("CLEAR")
                    }
                }
            }
        }

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
                        "Enter a goal above and press RUN AGENT.\nThe full action trace will appear here.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
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
