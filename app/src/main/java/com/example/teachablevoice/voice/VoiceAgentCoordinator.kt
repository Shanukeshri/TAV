package com.example.teachablevoice.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.teachablevoice.agent.AgentForegroundService
import com.example.teachablevoice.agent.AgentStateRepository
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinator that wires together VoiceInputManager, WakeWordManager,
 * AgentRequestRouter, and AgentForegroundService.
 *
 * This is the single orchestrator that manages the voice → agent flow.
 * It implements the state machine:
 *
 *   IDLE → LISTENING → PROCESSING → RUNNING_AGENT → DONE → IDLE
 *
 * Architecture:
 *   MainActivity
 *     ├── Typed command ────────────────────────────┐
 *     └── Voice command                             │
 *           │                                       │
 *           ▼                                       │
 *         VoiceInputManager                         │
 *           │                                       │
 *           ▼                                       │
 *         WakeWordManager                           │
 *           │ (if not wake phrase)                   │
 *           ▼                                       ▼
 *         AgentRequestRouter ──────────────→ AgentForegroundService
 *                                               │
 *                                               ▼
 *                                          AgentController
 *                                            ├── Gemini API
 *                                            └── Accessibility Bridge
 */
class VoiceAgentCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCoordinator"
    }

    // ── Components ──
    private val voiceInputManager = VoiceInputManager(context)
    private val wakeWordManager = WakeWordManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Observable state ──
    private val _state = MutableStateFlow(VoiceAgentState.IDLE)
    val state: StateFlow<VoiceAgentState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastTranscription = MutableStateFlow("")
    val lastTranscription: StateFlow<String> = _lastTranscription.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    /** Whether wake-word mode is enabled (Stage A: always false; Stage B: toggleable) */
    private var wakeWordEnabled = false

    init {
        setupVoiceListener()
        setupWakeWordListener()
    }

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * Start listening for voice input.
     * Called by the "START LISTENING" button or by the wake word detector.
     */
    fun startListening() {
        if (_state.value == VoiceAgentState.LISTENING) {
            Log.w(TAG, "Already listening")
            return
        }

        _state.value = VoiceAgentState.LISTENING
        _partialText.value = ""
        _lastTranscription.value = ""
        _errorMessage.value = ""
        voiceInputManager.startListening()
        Log.i(TAG, "Transitioned to LISTENING")
    }

    /**
     * Stop listening and return to IDLE.
     */
    fun stopListening() {
        voiceInputManager.stopListening()
        _state.value = VoiceAgentState.IDLE
        _partialText.value = ""
        Log.i(TAG, "Transitioned to IDLE")
    }

    /**
     * Process a typed command through the same pipeline as voice.
     * This is the unification point: typed and voice share the same entry.
     *
     *   Typed command ─────┐
     *                      ├──→ routeAndExecute()
     *   Voice STT ─────────┘
     */
    fun processCommand(command: String) {
        _lastTranscription.value = command
        _state.value = VoiceAgentState.PROCESSING
        routeAndExecute(command)
    }

    /**
     * Release all resources. Call from Activity/Service onDestroy.
     */
    fun destroy() {
        voiceInputManager.destroy()
    }

    // ──────────────────────────────────────────────
    // Internal wiring
    // ──────────────────────────────────────────────

    private fun setupVoiceListener() {
        voiceInputManager.setListener(object : VoiceInputListener {
            override fun onSpeechResult(text: String) {
                Log.i(TAG, "Speech result: '$text'")
                _lastTranscription.value = text
                _partialText.value = ""

                // Check if it's the wake phrase
                if (wakeWordEnabled && wakeWordManager.processUtterance(text)) {
                    // Wake word handled — stay in LISTENING for the actual command
                    return
                }

                // It's a real command — route it
                _state.value = VoiceAgentState.PROCESSING
                voiceInputManager.stopListening()
                routeAndExecute(text)
            }

            override fun onListeningStarted() {
                Log.d(TAG, "Microphone started")
            }

            override fun onListeningStopped() {
                Log.d(TAG, "Microphone stopped")
                if (_state.value == VoiceAgentState.LISTENING) {
                    // Unexpected stop — go back to IDLE
                    _state.value = VoiceAgentState.IDLE
                }
            }

            override fun onSpeechError(error: String) {
                Log.e(TAG, "Speech error: $error")
                _errorMessage.value = error
                if (_state.value == VoiceAgentState.LISTENING) {
                    _state.value = VoiceAgentState.ERROR
                }
            }

            override fun onPartialResult(partialText: String) {
                _partialText.value = partialText
            }
        })
    }

    private fun setupWakeWordListener() {
        wakeWordManager.setListener(object : WakeWordManager.WakeWordListener {
            override fun onWakeWordDetected() {
                Log.i(TAG, "Wake word detected! Entering listening mode.")
                // Already in LISTENING state from VoiceInputManager
                // The next speech result will be treated as a command
                AgentStateRepository.globalState.appendLog("VOICE", "Wake word detected: entering listening mode")
            }
        })
    }

    /**
     * Route the command through Gemini and launch the agent.
     */
    private fun routeAndExecute(command: String) {
        scope.launch {
            try {
                AgentStateRepository.globalState.appendLog("VOICE", "Processing: \"$command\"")

                // Ensure model is loaded
                ModelManager.init(context)
                if (!ModelManager.backend.isLoaded()) {
                    ModelManager.backend.load()
                }

                // Route through Gemini to get structured request
                val router = AgentRequestRouter(context, ModelManager.backend)
                val request = router.route(command)

                if (request == null) {
                    _state.value = VoiceAgentState.ERROR
                    _errorMessage.value = "Could not understand the command"
                    AgentStateRepository.globalState.appendLog("VOICE", "Failed to parse command", isError = true)
                    return@launch
                }

                AgentStateRepository.globalState.appendLog("VOICE", "Routed: app=${request.targetApp}, task=${request.task}")

                // Launch the agent foreground service with the resolved task
                _state.value = VoiceAgentState.RUNNING_AGENT
                launchAgent(request)

            } catch (e: Exception) {
                Log.e(TAG, "Route error: ${e.message}", e)
                _state.value = VoiceAgentState.ERROR
                _errorMessage.value = "Error: ${e.message}"
                AgentStateRepository.globalState.appendLog("VOICE", "Error: ${e.message}", isError = true)
            }
        }
    }

    /**
     * Launch AgentForegroundService with the structured request.
     * The agent service is the same one used by typed commands.
     */
    private fun launchAgent(request: AgentRequestRouter.AgentRequest) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            putExtra(AgentForegroundService.EXTRA_OBJECTIVE, request.task)
            // The service uses AppResolver internally, but we pass the resolved
            // target app to override its resolution if possible
            putExtra("TARGET_APP", request.targetApp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        Log.i(TAG, "Agent service launched: app=${request.targetApp}, task=${request.task}")
    }
}
