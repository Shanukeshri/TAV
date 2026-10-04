package com.example.teachablevoice.model

import android.util.Log
import com.example.teachablevoice.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelBackend implementation that calls the Gemini API.
 * Uses the gemini-3.5-flash-lite model for fast inference.
 *
 * Architecture: AgentController → GeminiApiClient → Gemini API
 *
 * API key is read from BuildConfig.GEMINI_API_KEY, which is injected
 * from local.properties at build time. Never hardcode the key in source.
 */
class GeminiApiClient(
    private val apiKey: String = API_KEY,
    private val model: String = MODEL_ID
) : ModelBackend {

    private var ready = false

    companion object {
        private const val TAG = "GeminiApiClient"
        private val API_KEY = BuildConfig.GEMINI_API_KEY
        private const val MODEL_ID = "gemini-3.5-flash-lite"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val EMBEDDING_MODEL_ID = "gemini-embedding-001"
    }

    /**
     * System prompt that constrains the model to output only valid JSON actions.
     */
    private val systemPrompt = """
        You are an Android UI automation agent. You receive the current UI state of an Android app
        and must decide the single next action to take toward the user's goal.

        RULES:
        - ON EVERY STEP, evaluate if the user's goal has been fully achieved based on the current UI state. If it is achieved, you MUST output ONLY {"action": "DONE"} and stop.
        - FIRST, validate if you are on the correct app/page for the goal. If not, take navigation actions (like BACK, HOME, or CLICKing menus) to reach the correct page BEFORE proceeding.
        - Output ONLY a single valid JSON object. No explanation, no markdown, no reasoning.
        - Format: {"action": "ACTION_TYPE", "element_id": "id", "value": "text", "direction": "UP/DOWN/LEFT/RIGHT", "message": "text"}
        - Allowed actions: CLICK, LONG_CLICK, INPUT, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, DONE, ASK
        - For CLICK/LONG_CLICK/INPUT: provide "element_id"
        - For INPUT: also provide "value" with the text to type
        - For SCROLL/SWIPE: optionally provide "direction" (UP, DOWN, LEFT, RIGHT)
        - For DONE: use ONLY when the goal is fully achieved
        - For ASK: use when you need clarification, provide "message"
        - Do NOT repeat failed actions
        - If stuck, choose BACK
    """.trimIndent()

    override suspend fun load() {
        Log.i(TAG, "GeminiApiClient ready (model=$model)")
        ready = true
    }

    override suspend fun generate(prompt: String): String {
        if (!ready) throw IllegalStateException("GeminiApiClient not initialized. Call load() first.")

        return withContext(Dispatchers.IO) {
            val requestBody = buildRequestBody(prompt)
            val response = executeHttpRequest(requestBody)
            parseResponse(response)
        }
    }

    override suspend fun unload() {
        ready = false
        Log.i(TAG, "GeminiApiClient unloaded")
    }

    override fun isLoaded(): Boolean = ready

    // ──────────────────────────────────────────────
    // HTTP Request Construction
    // ──────────────────────────────────────────────

    private fun buildRequestBody(userPrompt: String): String {
        // System instruction format for Gemini
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", systemPrompt)
                })
            })
        }
        
        // User content
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", userPrompt)
                    })
                })
            })
        }

        // Configuration to force JSON output
        val generationConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("responseMimeType", "application/json")
        }

        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", contents)
            put("generationConfig", generationConfig)
        }.toString()
    }

    // ──────────────────────────────────────────────
    // HTTP Execution
    // ──────────────────────────────────────────────

    private fun executeHttpRequest(body: String): String {
        val url = URL("${BASE_URL}${model}:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            // Write request body
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "API response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                // Read error stream for debugging
                val errorStream = connection.errorStream
                val errorBody = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream, "UTF-8")).use { it.readText() }
                } else {
                    "No error body"
                }
                Log.e(TAG, "API error ($responseCode): $errorBody")
                throw RuntimeException("Gemini API error $responseCode: $errorBody")
            }

            // Read success response
            return BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    // ──────────────────────────────────────────────
    // Response Parsing
    // ──────────────────────────────────────────────

    private fun parseResponse(responseJson: String): String {
        try {
            val json = JSONObject(responseJson)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return ""
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) {
                return ""
            }
            val firstPart = parts.getJSONObject(0)
            
            val text = if (firstPart.has("audioTranscription")) {
                firstPart.getJSONObject("audioTranscription").optString("text", "").trim()
            } else {
                firstPart.optString("text", "").trim()
            }

            Log.d(TAG, "Model output: ${text.take(200)}")
            return text
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}")
            Log.e(TAG, "Raw response: ${responseJson.take(500)}")
            throw RuntimeException("Failed to parse Gemini API response: ${e.message}", e)
        }
    }
    
    // ──────────────────────────────────────────────
    // ──────────────────────────────────────────────

    suspend fun getEmbedding(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!ready) throw IllegalStateException("GeminiApiClient not initialized.")
        
        val requestBody = JSONObject().apply {
            put("model", "models/$EMBEDDING_MODEL_ID")
            put("content", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", text)
                    })
                })
            })
        }.toString()
        
        val url = URL("${BASE_URL}${EMBEDDING_MODEL_ID}:embedContent?key=$apiKey")
        
        // Very basic HTTP call for embeddings, without the full retry logic for brevity here,
        // although it should ideally reuse a generic HTTP executor.
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseJson = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(responseJson)
                val values = json.getJSONObject("embedding").getJSONArray("values")
                val floatArray = FloatArray(values.length())
                for (i in 0 until values.length()) {
                    floatArray[i] = values.getDouble(i).toFloat()
                }
                return@withContext floatArray
            } else {
                throw RuntimeException("Embedding API error: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    // ──────────────────────────────────────────────
    // Audio Transcription
    // ──────────────────────────────────────────────

    suspend fun transcribeAudio(wavData: ByteArray): String = withContext(Dispatchers.IO) {
        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
        
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Please transcribe this audio accurately. Output only the transcript.")
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", base64Audio)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
            })
        }.toString()

        val url = URL("${BASE_URL}${model}:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseJson = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                return@withContext parseResponse(responseJson)
            } else {
                val errorStream = connection.errorStream
                val errorBody = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream, "UTF-8")).use { it.readText() }
                } else {
                    "No error body"
                }
                Log.e(TAG, "Audio transcription API error ($responseCode): $errorBody")
                throw RuntimeException("Audio transcription failed: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }
}
