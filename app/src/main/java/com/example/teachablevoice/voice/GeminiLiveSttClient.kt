package com.example.teachablevoice.voice

import android.util.Log
import com.example.teachablevoice.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for connecting to the Gemini Live API via WebSockets for Speech-To-Text.
 *
 * It streams PCM audio chunks to the server and receives text transcripts.
 */
class GeminiLiveSttClient(private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onTranscript(text: String, isFinal: Boolean)
        fun onError(error: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "GeminiLiveSttClient"
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    fun connect() {
        Log.i(TAG, "Connecting to Gemini Live WebSocket")
        
        // Use an ephemeral token in production, for now we append the key
        val apiKey = BuildConfig.GEMINI_API_KEY
        val url = "$WS_URL?key=$apiKey"
        
        val request = Request.Builder()
            .url(url)
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                
                // Send initial setup message per Gemini Live API specs
                val setupMessage = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/gemini-3.5-transcribe-live")
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().put("TEXT"))
                        })
                    })
                }
                webSocket.send(setupMessage.toString())
                
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("serverContent")) {
                        val serverContent = json.getJSONObject("serverContent")
                        if (serverContent.has("modelTurn")) {
                            val parts = serverContent.getJSONObject("modelTurn").getJSONArray("parts")
                            if (parts.length() > 0) {
                                val part = parts.getJSONObject(0)
                                if (part.has("text")) {
                                    val transcript = part.getString("text")
                                    val isFinal = serverContent.optBoolean("turnComplete", false)
                                    listener.onTranscript(transcript, isFinal)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                listener.onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $reason")
                listener.onClosed()
            }
        })
    }

    /**
     * Sends PCM audio chunks (16kHz, mono, 16-bit PCM) to Gemini.
     */
    fun sendAudio(pcmData: ByteArray, size: Int) {
        val ws = webSocket ?: return
        
        // Wrap the audio bytes in the required JSON envelope
        val base64Audio = android.util.Base64.encodeToString(pcmData, 0, size, android.util.Base64.NO_WRAP)
        
        val realtimeInput = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    })
                })
            })
        }
        
        ws.send(realtimeInput.toString())
    }

    /**
     * Sends the audio stream end signal when the user stops speaking.
     */
    fun sendEndOfAudio() {
        val ws = webSocket ?: return
        Log.i(TAG, "Sending audioStreamEnd")
        val clientContent = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turnComplete", true)
            })
        }
        ws.send(clientContent.toString())
    }

    fun close() {
        Log.i(TAG, "Closing WebSocket")
        webSocket?.close(1000, "Client closed")
        webSocket = null
    }
}
