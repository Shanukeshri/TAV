package com.example.teachablevoice.model

interface ModelBackend {
    suspend fun load()
    suspend fun generate(prompt: String): String
    suspend fun unload()
    fun isLoaded(): Boolean
}
