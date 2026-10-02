package com.example.teachablevoice.model

import android.util.Log
import com.example.teachablevoice.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * ModelBackend implementation that calls the Gemini API.
 * API key is read from BuildConfig.GEMINI_API_KEY. Never log the key.
 */
class GeminiApiClient(
    private val apiKey: String = API_KEY,
    private val model: String = MODEL_ID
) : ModelBackend {

    private var ready = false
    private var modelVerified = false

    companion object {
        private const val TAG = "GeminiApiClient"
        private val API_KEY = BuildConfig.GEMINI_API_KEY
        val MODEL_ID: String = BuildConfig.GEMINI_MODEL.ifBlank { "gemini-2.0-flash-lite" }
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RETRIES = 4
    }

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
        Log.i(TAG, "GeminiApiClient ready (model=${safeModelName()})")
        ready = true
    }

    override suspend fun generate(prompt: String): String {
        if (!ready) throw IllegalStateException("GeminiApiClient not initialized. Call load() first.")
        if (apiKey.isBlank()) {
            throw GeminiException("GEMINI_API_KEY is missing. Add it to local.properties.")
        }

        return withContext(Dispatchers.IO) {
            verifyModelIfNeeded()
            val requestBody = buildRequestBody(prompt)
            executeWithRetry(requestBody)
        }
    }

    override suspend fun unload() {
        ready = false
        Log.i(TAG, "GeminiApiClient unloaded")
    }

    override fun isLoaded(): Boolean = ready

    private fun safeModelName(): String = model.take(80)

    private fun verifyModelIfNeeded() {
        if (modelVerified) return
        val url = URL("${BASE_URL}${model}?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                modelVerified = true
                Log.i(TAG, "Verified Gemini model ${safeModelName()}")
                return
            }
            val err = readStreamSafely(connection.errorStream)
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw GeminiException(
                    "Gemini model '${safeModelName()}' is not valid. Set GEMINI_MODEL in local.properties."
                )
            }
            Log.w(TAG, "Model verify HTTP $code")
            if (err.contains("API_KEY_INVALID", ignoreCase = true)) {
                throw GeminiException("Gemini API key is invalid.")
            }
            // Non-404: still allow generate to try (some keys cannot GET the model resource)
            modelVerified = true
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(userPrompt: String): String {
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", systemPrompt) })
            })
        }
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userPrompt) })
                })
            })
        }
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

    private suspend fun executeWithRetry(body: String): String {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return executeHttpRequest(body)
            } catch (e: GeminiHttpException) {
                lastError = e
                if (e.code == 429 || e.code == 503 || e.code == 500) {
                    val backoff = (500L * (1L shl attempt)).coerceAtMost(8_000L)
                    Log.w(TAG, "Retryable HTTP ${e.code}, waiting ${backoff}ms (attempt ${attempt + 1})")
                    delay(backoff)
                } else {
                    throw e
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
                val backoff = (500L * (1L shl attempt)).coerceAtMost(8_000L)
                Log.w(TAG, "Timeout, retrying in ${backoff}ms")
                delay(backoff)
            }
        }
        throw lastError ?: GeminiException("Gemini request failed after retries")
    }

    private fun executeHttpRequest(body: String): String {
        val url = URL("${BASE_URL}${model}:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "API response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readStreamSafely(connection.errorStream)
                Log.e(TAG, "API error ($responseCode)")
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw GeminiException(
                        "Gemini model '${safeModelName()}' is not valid. Set GEMINI_MODEL in local.properties."
                    )
                }
                throw GeminiHttpException(responseCode, "Gemini API error $responseCode")
            }

            val raw = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
            return parseResponse(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun readStreamSafely(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseResponse(responseJson: String): String {
        try {
            val json = JSONObject(responseJson)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) {
                throw GeminiException("Gemini API returned empty candidates")
            }
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val text = parts.getJSONObject(0).getString("text").trim()
            Log.d(TAG, "Model output: ${text.take(200)}")
            return text
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}")
            throw GeminiException("Failed to parse Gemini API response: ${e.message}", e)
        }
    }
}

open class GeminiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class GeminiHttpException(val code: Int, message: String) : GeminiException(message)
