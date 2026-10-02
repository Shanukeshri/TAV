package com.example.teachablevoice.voice

import android.content.Context
import android.util.Log
import com.example.teachablevoice.agent.AppResolver
import com.example.teachablevoice.model.ModelBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Routes a natural-language voice command into a structured agent request.
 *
 * The first Gemini call does NOT immediately click UI elements.
 * Instead, it produces a structured request:
 *
 *   {
 *     "intent": "AUTOMATE",
 *     "target_app": "Amazon",
 *     "task": "Find 5 packets of milk",
 *     "parameters": { "quantity": 5, "item": "milk" }
 *   }
 *
 * The router then:
 *   1. Extracts the target app
 *   2. Falls back to AppResolver if Gemini's app name doesn't match an installed package
 *   3. Returns an AgentRequest that can be passed to AgentForegroundService
 *
 * Architecture:
 *   Voice text → AgentRequestRouter → AgentRequest → AgentForegroundService
 *
 * Gemini determines the semantic destination.
 * Android bridge determines how to physically interact with that destination.
 * This separation is critical: don't make STT responsible for opening apps,
 * and don't make AccessibilityService interpret natural language.
 */
class AgentRequestRouter(
    private val context: Context,
    private val model: ModelBackend
) {

    companion object {
        private const val TAG = "AgentRequestRouter"
    }

    /**
     * Structured request produced by the router.
     */
    data class AgentRequest(
        val intent: String,       // "AUTOMATE", "NAVIGATE", "QUERY"
        val targetApp: String,    // Resolved package name
        val task: String,         // Natural language goal for the agent loop
        val parameters: Map<String, Any> = emptyMap()
    )

    /**
     * Parse a raw voice/text command into a structured AgentRequest.
     *
     * @param rawCommand The natural language command, e.g. "Open Amazon and find 5 packets of milk"
     * @return AgentRequest with resolved target app and task, or null if parsing fails
     */
    suspend fun route(rawCommand: String): AgentRequest? {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Ask Gemini to produce a structured request
                val geminiResponse = askGeminiToParseRequest(rawCommand)
                Log.d(TAG, "Gemini router response: $geminiResponse")

                // Step 2: Parse the JSON response
                val parsed = parseRouterResponse(geminiResponse)
                if (parsed == null) {
                    Log.w(TAG, "Failed to parse Gemini router response, using fallback")
                    return@withContext fallbackRoute(rawCommand)
                }

                // Step 3: Resolve the target app to a package name
                val resolvedApp = resolveAppPackage(parsed.targetApp, rawCommand)
                    ?: return@withContext null

                parsed.copy(targetApp = resolvedApp)
            } catch (e: Exception) {
                Log.e(TAG, "Router error: ${e.message}", e)
                fallbackRoute(rawCommand)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Gemini request parsing
    // ──────────────────────────────────────────────

    private suspend fun askGeminiToParseRequest(command: String): String {
        val prompt = """
            You are a request parser for an Android automation agent.
            
            The user said: "$command"
            
            Parse this into a structured JSON request with:
            - "intent": one of "AUTOMATE" (interact with an app), "NAVIGATE" (open an app/page), "QUERY" (ask a question)
            - "target_app": the app name the user wants to use (e.g. "Amazon", "Zomato", "Chrome", "Settings")
            - "task": a clear description of what the user wants to accomplish
            - "parameters": an object with extracted parameters (optional)
            
            Output ONLY a valid JSON object. No explanation, no markdown.
            
            Examples:
            Input: "Open Amazon and find 5 packets of milk"
            Output: {"intent": "AUTOMATE", "target_app": "Amazon", "task": "Find 5 packets of milk", "parameters": {"quantity": 5, "item": "milk"}}
            
            Input: "Find biryani near me on Zomato"
            Output: {"intent": "AUTOMATE", "target_app": "Zomato", "task": "Find biryani near me", "parameters": {"food": "biryani", "location": "current"}}
            
            Input: "Turn on Wi-Fi"
            Output: {"intent": "AUTOMATE", "target_app": "Settings", "task": "Turn on Wi-Fi", "parameters": {"setting": "wifi", "action": "enable"}}
            
            Input: "Open Chrome and search for weather"
            Output: {"intent": "AUTOMATE", "target_app": "Chrome", "task": "Search for weather", "parameters": {"query": "weather"}}
        """.trimIndent()

        return model.generate(prompt)
    }

    private fun parseRouterResponse(response: String): AgentRequest? {
        return try {
            val json = JSONObject(response)
            AgentRequest(
                intent = json.optString("intent", "AUTOMATE"),
                targetApp = json.optString("target_app", ""),
                task = json.optString("task", ""),
                parameters = parseParameters(json.optJSONObject("parameters"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    private fun parseParameters(paramsJson: JSONObject?): Map<String, Any> {
        if (paramsJson == null) return emptyMap()
        val map = mutableMapOf<String, Any>()
        for (key in paramsJson.keys()) {
            map[key] = paramsJson.get(key)
        }
        return map
    }

    // ──────────────────────────────────────────────
    // App resolution
    // ──────────────────────────────────────────────

    /**
     * Resolve a human-readable app name (e.g. "Amazon") to a package name.
     * Uses AppResolver's existing installed-app matching as a fallback.
     */
    private fun resolveAppPackage(appName: String, rawCommand: String): String? {
        if (appName.isBlank()) {
            return AppResolver.findTargetApp(context, rawCommand)
        }
        AppResolver.findTargetApp(context, appName)?.let { return it }
        return AppResolver.findTargetApp(context, rawCommand)
    }

    private fun fallbackRoute(rawCommand: String): AgentRequest? {
        val targetApp = AppResolver.findTargetApp(context, rawCommand) ?: return null
        return AgentRequest(
            intent = "AUTOMATE",
            targetApp = targetApp,
            task = rawCommand,
            parameters = emptyMap()
        )
    }
}
