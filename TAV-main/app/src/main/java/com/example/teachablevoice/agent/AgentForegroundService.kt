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
import com.example.teachablevoice.model.GeminiException
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var agentJob: Job? = null

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_OBJECTIVE = "OBJECTIVE"
        const val EXTRA_TARGET_APP = "TARGET_APP"
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

        val state = AgentStateRepository.globalState
        state.reset()
        state.appendLog("SERVICE", "Agent service started")
        state.appendLog("SERVICE", "Objective: $objective")

        val preResolvedApp = intent?.getStringExtra(EXTRA_TARGET_APP)
        val targetApp: String = if (!preResolvedApp.isNullOrBlank()) {
            state.appendLog("RESOLVE", "Using pre-resolved app: $preResolvedApp")
            preResolvedApp
        } else {
            val resolved = AppResolver.findTargetApp(this, objective)
            if (resolved != null) {
                state.appendLog("RESOLVE", "Found app: $resolved")
                resolved
            } else {
                state.appendLog("RESOLVE", "No app found for: '$objective'. Ask user to specify.", isError = true)
                state.status = AgentStatus.ASKING
                state.statusMessage = "Which app do you mean?"
                stopSelf()
                return START_NOT_STICKY
            }
        }

        agentJob = serviceScope.launch {
            try {
                state.appendLog("MODEL", "Connecting to Gemini API…")
                ModelManager.backend.load()
                state.appendLog("MODEL", "Gemini API ready ✓")

                val bridge = AutomationBridgeImpl(this@AgentForegroundService)
                val metrics = resources.displayMetrics
                val controller = AgentController(ModelManager.backend, bridge, metrics)

                val goal = Goal(targetApp, objective)
                controller.execute(goal)
            } catch (e: GeminiException) {
                Log.e(TAG, "Gemini error", e)
                ModelManager.reportError(e.message ?: "Gemini error")
                state.appendLog("ERROR", e.message ?: "Gemini error", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = e.message ?: "Gemini error"
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
