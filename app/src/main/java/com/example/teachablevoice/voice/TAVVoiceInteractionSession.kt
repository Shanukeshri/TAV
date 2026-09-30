package com.example.teachablevoice.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.teachablevoice.agent.AgentForegroundService
import com.example.teachablevoice.agent.AgentStateRepository
import com.example.teachablevoice.agent.AgentStatus
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The actual voice session that runs when the wake word is detected.
 *
 * Responsibilities:
 * - Start STT to listen for the user's command.
 * - Route the command through Gemini via AgentRequestRouter.
 * - Launch AgentForegroundService.
 * - Hide itself / finish when done, so TAVVoiceInteractionService can
 *   resume wake-word listening.
 *
 * It updates VoiceStateRepository so MirrorAccessibilityService can draw the UI.
 * (We do not use VoiceInteractionSession's built-in window UI here because
 * we want the UI to remain in the accessibility overlay).
 */
class TAVVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "TAVVoiceSession"
        private const val RESTART_DELAY_MS = 1500L
    }

    private val voiceInputManager = VoiceInputManager(context)
    private val voiceState = VoiceStateRepository.globalState
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // We reuse wake word manager just to check if the user repeated the wake phrase
    private val wakeWordManager = WakeWordManager()

    // Keep track of how many times we've restarted STT during this session
    private var sttRestartCount = 0
    private val MAX_STT_RESTARTS = 5

    override fun onCreate() {
        super.onCreate()
        
        // Hide the system's default VoiceInteractionSession UI window
        // because we draw our UI using AccessibilityOverlay.
        window.window?.attributes?.alpha = 0f
        
        setupCommandListener()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Session shown, starting command capture")
        
        sttRestartCount = 0
        voiceState.transitionTo(VoiceListenerState.COMMAND_LISTENING)
        
        // Give the UI a moment to show up before starting the mic
        handler.postDelayed({
            voiceInputManager.startListening()
        }, 300)
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "Session hidden")
        voiceInputManager.stopListening()
        
        // When session hides, if we aren't running the agent, return to wake listening
        if (voiceState.current.state != VoiceListenerState.AGENT_LAUNCHED) {
            voiceState.transitionTo(VoiceListenerState.WAKE_LISTENING)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputManager.destroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun setupCommandListener() {
        voiceInputManager.setListener(object : VoiceInputListener {
            override fun onSpeechResult(text: String) {
                Log.d(TAG, "Command heard: \"$text\"")

                if (wakeWordManager.isWakePhrase(text)) {
                    Log.d(TAG, "Wake phrase repeated, staying in COMMAND_LISTENING")
                    // Restart listening for actual command
                    handler.postDelayed({ voiceInputManager.startListening() }, 300)
                    return
                }

                processCommand(text)
            }

            override fun onPartialResult(partialText: String) {
                if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                    voiceState.updatePartialText(partialText)
                }
            }

            override fun onListeningStarted() {
                Log.d(TAG, "Command STT started")
            }

            override fun onListeningStopped() {
                Log.d(TAG, "Command STT stopped")
                if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                    if (sttRestartCount < MAX_STT_RESTARTS) {
                        sttRestartCount++
                        handler.postDelayed({
                            if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                                voiceInputManager.startListening()
                            }
                        }, 500)
                    } else {
                        voiceState.setError("Listening timed out.")
                        handler.postDelayed({ hide() }, RESTART_DELAY_MS)
                    }
                }
            }

            override fun onSpeechError(error: String) {
                Log.w(TAG, "Command STT error: $error")
                if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                    if (sttRestartCount < MAX_STT_RESTARTS) {
                        sttRestartCount++
                        // Just silently restart without showing an error to keep the window open
                        handler.postDelayed({
                            if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                                voiceInputManager.startListening()
                            }
                        }, 500)
                    } else {
                        voiceState.setError("Couldn't hear you: $error")
                        handler.postDelayed({ hide() }, RESTART_DELAY_MS)
                    }
                }
            }
        })
    }

    private fun processCommand(command: String) {
        voiceState.setTranscription(command)
        voiceState.transitionTo(VoiceListenerState.PROCESSING)
        
        // Stop STT
        handler.post { voiceInputManager.stopListening() }

        AgentStateRepository.globalState.appendLog("VOICE", "Heard: \"$command\"")

        scope.launch {
            try {
                ModelManager.init(context)
                if (!ModelManager.backend.isLoaded()) {
                    ModelManager.backend.load()
                }

                val router = AgentRequestRouter(context, ModelManager.backend)
                val request = router.route(command)

                if (request == null) {
                    voiceState.setError("Couldn't understand that command")
                    AgentStateRepository.globalState.appendLog("VOICE", "Failed to parse command", isError = true)
                    handler.postDelayed({ hide() }, RESTART_DELAY_MS)
                    return@launch
                }

                Log.i(TAG, "Routed: app=${request.targetApp}, task=${request.task}")
                AgentStateRepository.globalState.appendLog("VOICE", "→ ${request.targetApp}: ${request.task}")

                val appLabel = request.targetApp.substringAfterLast(".")
                voiceState.setRouting(appLabel, request.task)

                voiceState.transitionTo(VoiceListenerState.AGENT_LAUNCHED)
                launchAgent(request)
                
                // Monitor agent completion before hiding the session completely (or hide immediately)
                monitorAgentCompletion()

            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}", e)
                voiceState.setError("Error: ${e.message}")
                AgentStateRepository.globalState.appendLog("VOICE", "Error: ${e.message}", isError = true)
                handler.postDelayed({ hide() }, RESTART_DELAY_MS)
            }
        }
    }

    private fun launchAgent(request: AgentRequestRouter.AgentRequest) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            putExtra(AgentForegroundService.EXTRA_OBJECTIVE, request.task)
            putExtra(AgentForegroundService.EXTRA_TARGET_APP, request.targetApp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun monitorAgentCompletion() {
        scope.launch {
            AgentStateRepository.globalState.stateFlow.collect { agentState ->
                if (voiceState.current.state == VoiceListenerState.AGENT_LAUNCHED && !agentState.isRunning) {
                    
                    if (agentState.status == AgentStatus.COMPLETED) {
                        AgentStateRepository.globalState.appendLog("VOICE", "✓ Task complete.")
                    } else if (agentState.status == AgentStatus.ERROR) {
                        AgentStateRepository.globalState.appendLog("VOICE", "✗ Task failed.")
                    }
                    
                    // Finish the session, yielding control back to TAVVoiceInteractionService
                    // (which will automatically resume WAKE_LISTENING because we set it in onHide)
                    hide()
                    return@collect
                }
            }
        }
    }
}
