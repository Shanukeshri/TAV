package com.example.teachablevoice.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LlamaCppBackend(private val context: Context) : ModelBackend {
    private var loaded = false
    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("tav_llama")
            nativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("LlamaCppBackend", "Native library not found", e)
            nativeAvailable = false
        }
    }

    private external fun loadNative(modelPath: String): Boolean
    private external fun generateNative(prompt: String): String
    private external fun unloadNative()

    override suspend fun load() {
        if (!nativeAvailable) throw IllegalStateException("Native library libtav_llama.so not loaded")
        withContext(Dispatchers.IO) {
            val assetName = "SmolLM2-135M-Instruct-Q4_K_M.gguf"
            val file = File(context.filesDir, assetName)
            if (!file.exists()) {
                android.util.Log.i("LlamaCppBackend", "Copying model from assets to ${file.absolutePath}…")
                context.assets.open(assetName).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                android.util.Log.i("LlamaCppBackend", "Model copied: ${file.length()} bytes")
            }
            loaded = loadNative(file.absolutePath)
            if (!loaded) throw IllegalStateException("Native loadNative() returned false")
        }
    }

    override suspend fun generate(prompt: String): String {
        if (!loaded) throw IllegalStateException("Model not loaded")
        return withContext(Dispatchers.Default) {
            generateNative(prompt)
        }
    }

    override suspend fun unload() {
        if (nativeAvailable && loaded) {
            withContext(Dispatchers.Default) {
                unloadNative()
            }
        }
        loaded = false
    }

    override fun isLoaded(): Boolean = loaded
}
