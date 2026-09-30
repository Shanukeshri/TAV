package com.example.teachablevoice.model

import android.content.Context

object ModelManager {
    lateinit var backend: ModelBackend
    
    fun init(context: Context) {
        if (!::backend.isInitialized) {
            backend = LlamaCppBackend(context.applicationContext)
        }
    }
}
