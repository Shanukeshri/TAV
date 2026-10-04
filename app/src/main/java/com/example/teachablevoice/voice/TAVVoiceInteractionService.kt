package com.example.teachablevoice.voice

import android.content.Intent
import android.os.Bundle
import com.example.teachablevoice.core.VoiceStateRepository
import com.example.teachablevoice.core.VoiceListenerState
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * System-managed VoiceInteractionService.
 *
 * Once the user selects TAV as the phone's default Digital Assistant,
 * Android keeps this service alive in the background.
 *
 * Responsibilities:
 * - Listens for the wake word ("Hey start listening").
 * - When detected, launches the TAVVoiceInteractionSession.
 *
 * This replaces the ordinary foreground service, providing true system-level
 * integration for always-on listening without foreground notification spam
 * and background-start restrictions.
 */
class TAVVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "TAVVoiceService"
        private const val RESTART_DELAY_MS = 1500L
    }

    private lateinit var voiceInputManager: VoiceInputManager
    private val wakeWordManager = WakeWordManager()
    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "TAVVoiceInteractionService onReady")
        VoiceStateRepository.globalState.setServiceRunning(true)
        voiceInputManager = VoiceInputManager(this)
        startWakeWordListening()
        
        scope.launch {
            VoiceStateRepository.globalState.stateFlow.collectLatest { state ->
                if (state.state == VoiceListenerState.IDLE && isActive) {
                    restartListening()
                } else if (state.state != VoiceListenerState.IDLE) {
                    voiceInputManager.stopListening()
                }
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "TAVVoiceInteractionService onShutdown")
        VoiceStateRepository.globalState.setServiceRunning(false)
        stopWakeWordListening()
        voiceInputManager.destroy()
        scope.cancel()
    }

    private fun startWakeWordListening() {
        isActive = true
        VoiceStateRepository.globalState.transitionTo(VoiceListenerState.IDLE)
        
        voiceInputManager.setListener(object : VoiceInputListener {
            override fun onSpeechResult(text: String) {
                Log.d(TAG, "Wake listener heard: \"$text\"")
                if (wakeWordManager.isWakePhrase(text)) {
                    Log.i(TAG, "Wake word detected! Launching session.")
                    launchSession()
                }
            }

            override fun onPartialResult(partialText: String) {}

            override fun onListeningStarted() {
                Log.d(TAG, "Wake STT started")
            }

            override fun onListeningStopped() {
                Log.d(TAG, "Wake STT stopped")
                restartListening()
            }

            override fun onSpeechError(error: String) {
                Log.w(TAG, "Wake STT error: $error")
                // Ignored here because onListeningStopped will handle the restart
            }
        })

        handler.post {
            voiceInputManager.startListening()
        }
    }

    private fun stopWakeWordListening() {
        isActive = false
        VoiceStateRepository.globalState.transitionTo(VoiceListenerState.IDLE)
        voiceInputManager.stopListening()
        handler.removeCallbacksAndMessages(null)
    }

    private fun restartListening() {
        if (!isActive || VoiceStateRepository.globalState.current.state != VoiceListenerState.IDLE) return
        handler.postDelayed({
            if (isActive && VoiceStateRepository.globalState.current.state == VoiceListenerState.IDLE) {
                voiceInputManager.startListening()
            }
        }, RESTART_DELAY_MS)
    }

    private fun launchSession() {
        // Transition state first so restartListening aborts
        VoiceStateRepository.globalState.transitionTo(VoiceListenerState.LISTENING)
        voiceInputManager.stopListening()
        
        // Android API to show the interaction session
        showSession(Bundle(), 0)
    }
}
