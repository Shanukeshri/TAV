package com.example.teachablevoice.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides the active ModelBackend. Gemini is the default; on-device llama is opt-in.
 */
object ModelManager {
    lateinit var backend: ModelBackend
        private set

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun init(context: Context) {
        if (!::backend.isInitialized) {
            Log.i("ModelManager", "Initializing GeminiApiClient")
            backend = GeminiApiClient()
        }
    }

    fun reportError(message: String) {
        _lastError.value = message
    }

    fun clearError() {
        _lastError.value = null
    }
}
