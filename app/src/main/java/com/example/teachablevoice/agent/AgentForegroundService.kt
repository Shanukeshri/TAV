package com.example.teachablevoice.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.teachablevoice.bridge.MirrorAccessibilityService
import com.example.teachablevoice.bridge.UiMirrorRepository
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import com.example.teachablevoice.bridge.AutomationBridgeImpl
import com.example.teachablevoice.model.ModelManager
import com.example.teachablevoice.model.GeminiApiClient
import com.example.teachablevoice.memory.WorkflowMemoryManager
import com.example.teachablevoice.router.AgentRequestRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the agent automation task.
 *
 * Architecture:
 *   MainActivity → AgentTaskService/Foreground Service → AgentController → Gemini API LLM
 *   AgentController → Existing Accessibility Bridge → Target App (Amazon, etc.)
 *   AccessibilityService → translucent Agent Overlay (via AgentOverlayManager)
 *
 * The foreground service keeps an ongoing notification so Android doesn't kill it
 * while the user is interacting with the target app.
 */
class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var agentJob: Job? = null

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_OBJECTIVE = "OBJECTIVE"
        const val EXTRA_TARGET_APP = "TARGET_APP"
        const val EXTRA_INTENT = "INTENT"
        const val EXTRA_VOICE_COMMAND = "VOICE_COMMAND"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ModelManager.init(this)
        registerReceiver(simulateReceiver, IntentFilter("com.example.teachablevoice.SIMULATE_VOICE"), RECEIVER_EXPORTED)
    }

    private val simulateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra("COMMAND") ?: return
            serviceScope.launch {
                ModelManager.backend.load()
                val router = AgentRequestRouter(this@AgentForegroundService, ModelManager.backend)
                val request = router.route(command) ?: return@launch
                val goal = Goal(request.intent, request.targetApp, request.task)
                val bridge = AutomationBridgeImpl(this@AgentForegroundService)
                val memoryManager = WorkflowMemoryManager(this@AgentForegroundService, ModelManager.backend as GeminiApiClient)
                val controller = AgentController(ModelManager.backend, bridge, memoryManager)
                controller.execute(goal)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Agent Running...")
        startForeground(1, notification)

        val state = AgentStateRepository.globalState
        
        val rawVoice = intent?.getStringExtra(EXTRA_VOICE_COMMAND)
        if (!rawVoice.isNullOrBlank()) {
            state.reset()
            state.appendLog("SERVICE", "Routing raw voice command: \$rawVoice")
            agentJob = serviceScope.launch {
                try {
                    ModelManager.backend.load()
                    val router = AgentRequestRouter(this@AgentForegroundService, ModelManager.backend)
                    val request = router.route(rawVoice)
                    if (request != null) {
                        val bridge = AutomationBridgeImpl(this@AgentForegroundService)
                        val memoryManager = WorkflowMemoryManager(this@AgentForegroundService, ModelManager.backend as GeminiApiClient)
                        val controller = AgentController(ModelManager.backend, bridge, memoryManager)
                        val goal = Goal(request.intent, request.targetApp, request.task)
                        controller.execute(goal)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Router error", e)
                } finally {
                    try { ModelManager.backend.unload() } catch (_: Exception) {}
                    // stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        val objective = intent?.getStringExtra(EXTRA_OBJECTIVE)
            ?: intent?.getStringExtra("OBJECTIVE")
            ?: "Find Wi-Fi"

        val actionIntent = intent?.getStringExtra(EXTRA_INTENT) ?: "AUTOMATE"

        // Reset state for a fresh run
        state.reset()
        state.appendLog("SERVICE", "Agent service started")
        state.appendLog("SERVICE", "Using Gemini API (gemini-3.5-flash-lite)")
        state.appendLog("SERVICE", "Intent: $actionIntent, Objective: $objective")

        // Resolve target app from goal text (voice router may provide pre-resolved app)
        val preResolvedApp = intent?.getStringExtra(EXTRA_TARGET_APP)
        val targetApp = if (!preResolvedApp.isNullOrBlank()) {
            state.appendLog("RESOLVE", "Using pre-resolved app: $preResolvedApp")
            preResolvedApp
        } else {
            val resolved = AppResolver.findTargetApp(this, objective)
            if (resolved != null) {
                state.appendLog("RESOLVE", "Found app: $resolved")
                resolved
            } else {
                state.appendLog("RESOLVE", "No app match found, falling back to Settings")
                "com.android.settings"
            }
        }
        val resolvedApp = targetApp

        agentJob = serviceScope.launch {
            try {
                state.appendLog("MODEL", "Connecting to Gemini API…")
                ModelManager.backend.load()
                state.appendLog("MODEL", "Gemini API ready ✓")

                val bridge = AutomationBridgeImpl(this@AgentForegroundService)
                val memoryManager = WorkflowMemoryManager(this@AgentForegroundService, ModelManager.backend as GeminiApiClient)
                val controller = AgentController(ModelManager.backend, bridge, memoryManager)

                val goal = Goal(actionIntent, resolvedApp, objective)
                controller.execute(goal)
            } catch (e: Exception) {
                Log.e(TAG, "Service error", e)
                state.appendLog("ERROR", "Service error: ${e.message}", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Service error: ${e.message}"
            } finally {
                try { ModelManager.backend.unload() } catch (_: Exception) {}
                state.appendLog("SERVICE", "Agent service stopping")
                // stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        agentJob?.cancel()
        try { unregisterReceiver(simulateReceiver) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "agent_channel",
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Teachable Voice Agent")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
