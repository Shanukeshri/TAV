package com.example.teachablevoice.model

import android.content.Context
import android.util.Log

/**
 * Singleton that provides the active ModelBackend.
 *
 * Now uses GeminiApiClient (network LLM via Gemini API / gemini-3.5-flash-lite)
 * instead of the previous LlamaCppBackend (local GGUF model).
 *
 * The LlamaCppBackend class is preserved in the codebase but not used.
 */
object ModelManager {
    lateinit var backend: ModelBackend

    fun init(context: Context) {
        if (!::backend.isInitialized) {
            Log.i("ModelManager", "Initializing with GeminiApiClient (gemini-3.5-flash-lite)")
            backend = GeminiApiClient()
        }
    }
}
