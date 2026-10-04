package com.example.teachablevoice.teach

import android.util.Log
import com.example.teachablevoice.model.ModelBackend
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses Gemini to convert a raw teaching trajectory into a generalized,
 * parameterized, reusable semantic workflow.
 *
 * Input:  [TeachSession.toSummaryText] — the literal recorded steps.
 * Output: A [GeneralizedWorkflow] with semantic steps and extracted parameters.
 *
 * The key idea: Gemini identifies which values are variable (e.g. "milk" → {{item}})
 * and re-describes each step using semantic selectors instead of node IDs.
 */
class WorkflowGeneralizer(private val model: ModelBackend) {

    companion object {
        private const val TAG = "WorkflowGeneralizer"
    }

    /**
     * Ask Gemini to take the raw trajectory and produce a generalized workflow.
     */
    suspend fun generalize(session: TeachSession): GeneralizedWorkflow? {
        return try {
            val prompt = buildGeneralizationPrompt(session)
            Log.d(TAG, "Sending generalisation prompt (${prompt.length} chars)")
            val response = model.generate(prompt)
            Log.d(TAG, "Gemini response: ${response.take(300)}")
            parseGeneralizedWorkflow(response, session)
        } catch (e: Exception) {
            Log.e(TAG, "Generalisation failed", e)
            // Fallback: convert trajectory directly without generalisation
            fallbackGeneralize(session)
        }
    }

    private fun buildGeneralizationPrompt(session: TeachSession): String {
        return """
            You are an Android automation workflow analyser.
            
            A user just demonstrated a task on their phone. Below is the recorded trajectory
            of every action taken, including the semantic properties of each UI element
            (role, label, hint) and whether the UI changed after each step.
            
            TRAJECTORY:
            ${session.toSummaryText()}
            
            TASK:
            Create a generalized, reusable workflow from this demonstration.
            
            RULES:
            1. Identify parameters — values that would change between executions.
               For example, if the user typed "milk", that should become a parameter "{{item}}".
               If a quantity "5" was used, it becomes "{{quantity}}".
            2. Describe each step using semantic UI selectors (role + label + hint),
               NOT element IDs. Element IDs change between sessions.
            3. Keep only successful steps (where UI changed or the action was meaningful).
            4. For INPUT actions, replace concrete values with parameter templates like {{item}}.
            5. Remove redundant or failed steps.
            6. EXCLUDE any steps that dismiss promotional popups, ads, or one-time dialogs (e.g., clicking 'Close', 'Skip', 'Not now', 'X').
            7. The `goal` field MUST be a generalized action statement. Strip away conversational wrappers like "Teach how to", "I want to", "Show me how to". For example, "Teach how to add things to cart" becomes "Add {{item}} to cart".
            
            ALLOWED ACTIONS: CLICK, LONG_CLICK, INPUT, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, ENTER
            
            OUTPUT FORMAT — respond with ONLY a single JSON object, no explanation:
            {
              "goal": "A short, generalized description of what this workflow does (e.g. 'Add {{item}} to cart')",
              "parameters": {
                "param_name": "example_value",
                ...
              },
              "steps": [
                {
                  "stepIndex": 0,
                  "action": "CLICK",
                  "targetRole": "textfield",
                  "targetLabel": "Search",
                  "targetDescription": "",
                  "targetHint": "Search products",
                  "inputTemplate": null,
                  "direction": null,
                  "screenContext": "home"
                },
                {
                  "stepIndex": 1,
                  "action": "ENTER",
                  "targetRole": "textfield",
                  "targetLabel": "Search",
                  "targetDescription": "",
                  "targetHint": "Search products",
                  "inputTemplate": null,
                  "direction": null,
                  "screenContext": "home"
                },
                ...
              ]
            }
        """.trimIndent()
    }

    private fun parseGeneralizedWorkflow(response: String, session: TeachSession): GeneralizedWorkflow? {
        return try {
            // Extract JSON from response
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) return fallbackGeneralize(session)

            val cleanJson = response.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(cleanJson)

            val goal = json.optString("goal", session.originalCommand)

            // Parse parameters
            val paramsJson = json.optJSONObject("parameters")
            val parameters = mutableMapOf<String, String>()
            if (paramsJson != null) {
                for (key in paramsJson.keys()) {
                    parameters[key] = paramsJson.optString(key, "")
                }
            }

            // Parse steps
            val stepsArray = json.optJSONArray("steps") ?: return fallbackGeneralize(session)
            val steps = mutableListOf<SemanticStep>()
            for (i in 0 until stepsArray.length()) {
                val stepJson = stepsArray.getJSONObject(i)
                steps.add(SemanticStep(
                    stepIndex = stepJson.optInt("stepIndex", i),
                    action = stepJson.getString("action"),
                    targetRole = stepJson.optString("targetRole").takeIf { it.isNotBlank() },
                    targetLabel = stepJson.optString("targetLabel").takeIf { it.isNotBlank() },
                    targetDescription = stepJson.optString("targetDescription").takeIf { it.isNotBlank() },
                    targetHint = stepJson.optString("targetHint").takeIf { it.isNotBlank() },
                    inputTemplate = stepJson.optString("inputTemplate").takeIf { it.isNotBlank() && it != "null" },
                    direction = stepJson.optString("direction").takeIf { it.isNotBlank() && it != "null" },
                    screenContext = stepJson.optString("screenContext").takeIf { it.isNotBlank() }
                ))
            }

            Log.i(TAG, "Generalised workflow: goal='$goal', ${parameters.size} params, ${steps.size} steps")
            GeneralizedWorkflow(
                goal = goal,
                app = session.app,
                parameters = parameters,
                steps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse generalised response", e)
            fallbackGeneralize(session)
        }
    }

    /**
     * If Gemini generalisation fails, build a workflow directly from the
     * raw trajectory. No parameter extraction, but the semantic attributes
     * (role, label) are preserved so the workflow is still more resilient
     * than raw node-ID recordings.
     */
    private fun fallbackGeneralize(session: TeachSession): GeneralizedWorkflow {
        Log.w(TAG, "Using fallback generalisation (no parameter extraction)")
        val steps = session.trajectory
            .filter { it.uiChangedSuccessfully || it.action == "INPUT" || it.action == "ENTER" || it.action == "DONE" }
            .mapIndexed { index, entry ->
                SemanticStep(
                    stepIndex = index,
                    action = entry.action,
                    targetRole = entry.elementRole,
                    targetLabel = entry.elementLabel,
                    targetHint = entry.elementHint,
                    inputTemplate = entry.inputValue, // literal, not parameterised
                    direction = entry.direction,
                    elementId = entry.elementId
                )
            }

        return GeneralizedWorkflow(
            goal = session.originalCommand,
            app = session.app,
            parameters = emptyMap(),
            steps = steps
        )
    }
}

/**
 * The output of the generalisation process: a reusable, parameterised workflow.
 */
data class GeneralizedWorkflow(
    val goal: String,
    val app: String,
    val parameters: Map<String, String>,
    val steps: List<SemanticStep>
)
