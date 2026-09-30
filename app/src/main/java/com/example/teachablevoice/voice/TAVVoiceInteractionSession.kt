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

    // ── Continuous Listening State ──
    private var accumulatedCommand = ""
    private var currentPartial = ""
    private var silenceRunnable: Runnable? = null
    private val SILENCE_TIMEOUT_MS = 1500L
    private var emptySilenceCount = 0
    private val MAX_EMPTY_SILENCE = 5 // Hide if no speech after ~7.5 seconds

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
        
        accumulatedCommand = ""
        currentPartial = ""
        emptySilenceCount = 0
        voiceState.transitionTo(VoiceListenerState.COMMAND_LISTENING)
        
        // Give the UI a moment to show up before starting the mic
        handler.postDelayed({
            voiceInputManager.startListening()
            startSilenceTimer()
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
        silenceRunnable = null
    }

    private fun updateUI() {
        val display = (accumulatedCommand + " " + currentPartial).trim()
        if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
            voiceState.updatePartialText(display)
        }
    }

    private fun startSilenceTimer() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (voiceState.current.state != VoiceListenerState.COMMAND_LISTENING) return@Runnable

            val finalCommand = (accumulatedCommand + " " + currentPartial).trim()
            if (finalCommand.isNotEmpty()) {
                Log.i(TAG, "Silence timeout reached. Processing: $finalCommand")
                processCommand(finalCommand)
            } else {
                emptySilenceCount++
                if (emptySilenceCount >= MAX_EMPTY_SILENCE) {
                    Log.i(TAG, "Max silence reached. Hiding session.")
                    voiceState.setError("Listening timed out.")
                    handler.postDelayed({ hide() }, RESTART_DELAY_MS)
                } else {
                    // Keep waiting
                    startSilenceTimer()
                }
            }
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_TIMEOUT_MS)
    }

    private fun resetSilenceTimer() {
        emptySilenceCount = 0
        startSilenceTimer()
    }

    private fun setupCommandListener() {
        voiceInputManager.setListener(object : VoiceInputListener {
            override fun onSpeechResult(text: String) {
                if (!wakeWordManager.isWakePhrase(text)) {
                    accumulatedCommand += " $text"
                }
                currentPartial = ""
                updateUI()
                resetSilenceTimer()
            }

            override fun onPartialResult(partialText: String) {
                currentPartial = partialText
                updateUI()
                resetSilenceTimer()
            }

            override fun onListeningStarted() {
                Log.d(TAG, "Command STT started")
            }

            override fun onListeningStopped() {
                Log.d(TAG, "Command STT stopped")
                // STT stopped natively (e.g. paused). Restart it immediately so we don't miss anything.
                if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                    handler.postDelayed({
                        if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                            voiceInputManager.startListening()
                        }
                    }, 100)
                }
            }

            override fun onSpeechError(error: String) {
                Log.w(TAG, "Command STT error: $error")
                // Ignore STT errors (like timeout) during continuous listening; just restart.
                if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                    handler.postDelayed({
                        if (voiceState.current.state == VoiceListenerState.COMMAND_LISTENING) {
                            voiceInputManager.startListening()
                        }
                    }, 100)
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
