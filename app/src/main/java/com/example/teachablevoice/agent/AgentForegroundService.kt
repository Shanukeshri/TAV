package com.example.teachablevoice.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.teachablevoice.bridge.AutomationBridgeImpl
import com.example.teachablevoice.model.ModelManager
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ModelManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Agent Running...")
        startForeground(1, notification)

        val objective = intent?.getStringExtra(EXTRA_OBJECTIVE)
            ?: intent?.getStringExtra("OBJECTIVE")
            ?: "Find Wi-Fi"

        // Reset state for a fresh run
        val state = AgentStateRepository.globalState
        state.reset()
        state.appendLog("SERVICE", "Agent service started")
        state.appendLog("SERVICE", "Using Gemini API (gemini-3.5-flash-lite)")
        state.appendLog("SERVICE", "Objective: $objective")

        // Resolve target app from goal text
        val targetApp = AppResolver.findTargetApp(this, objective)
        if (targetApp != null) {
            state.appendLog("RESOLVE", "Found app: $targetApp")
        } else {
            state.appendLog("RESOLVE", "No app match found, falling back to Settings")
        }
        val resolvedApp = targetApp ?: "com.android.settings"

        agentJob = serviceScope.launch {
            try {
                state.appendLog("MODEL", "Connecting to Gemini API…")
                ModelManager.backend.load()
                state.appendLog("MODEL", "Gemini API ready ✓")

                val bridge = AutomationBridgeImpl(this@AgentForegroundService)
                val controller = AgentController(ModelManager.backend, bridge)

                val goal = Goal(resolvedApp, objective)
                controller.execute(goal)
            } catch (e: Exception) {
                Log.e(TAG, "Service error", e)
                state.appendLog("ERROR", "Service error: ${e.message}", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Service error: ${e.message}"
            } finally {
                try { ModelManager.backend.unload() } catch (_: Exception) {}
                state.appendLog("SERVICE", "Agent service stopping")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        agentJob?.cancel()
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
