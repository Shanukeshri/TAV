package com.example.teachablevoice.memory

import com.example.teachablevoice.teach.SemanticStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a complete learned workflow stored in persistent memory.
 *
 * Key improvements over the original model:
 *  - Steps use **semantic selectors** (role, label, description) instead of node IDs
 *  - **Parameters** enable reuse with different values
 *  - **Confidence / versioning** track reliability
 *  - **Success/failure counts** let the system rank workflows
 */
data class WorkflowMemory(
    val id: String,
    val app: String,
    val task: String,
    val goal: String,
    val embedding: FloatArray,
    val steps: List<SemanticStep>,
    val parameters: Map<String, String> = emptyMap(),
    val version: Int = 1,
    val confidence: Float = 1.0f,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("app", app)
        put("task", task)
        put("goal", goal)
        put("version", version)
        put("confidence", confidence.toDouble())
        put("successCount", successCount)
        put("failureCount", failureCount)
        put("createdAt", createdAt)
        put("lastUsedAt", lastUsedAt)

        // Embedding
        put("embedding", JSONArray().also { arr ->
            embedding.forEach { arr.put(it.toDouble()) }
        })

        // Semantic steps
        put("steps", JSONArray().also { arr ->
            steps.forEach { arr.put(it.toJson()) }
        })

        // Parameters
        put("parameters", JSONObject().also { obj ->
            parameters.forEach { (k, v) -> obj.put(k, v) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): WorkflowMemory {
            // Embedding
            val embArray = json.getJSONArray("embedding")
            val embedding = FloatArray(embArray.length()) { embArray.getDouble(it).toFloat() }

            // Steps
            val stepsArray = json.getJSONArray("steps")
            val steps = mutableListOf<SemanticStep>()
            for (i in 0 until stepsArray.length()) {
                val stepJson = stepsArray.getJSONObject(i)
                // Support both old-format steps (action/elementId/value/direction) and new SemanticStep format
                if (stepJson.has("targetRole") || stepJson.has("targetLabel") || stepJson.has("inputTemplate")) {
                    steps.add(SemanticStep.fromJson(stepJson))
                } else {
                    // Legacy step migration
                    steps.add(SemanticStep(
                        stepIndex = i,
                        action = stepJson.getString("action"),
                        elementId = stepJson.optString("elementId").takeIf { it.isNotBlank() && it != "null" },
                        inputTemplate = stepJson.optString("value").takeIf { it.isNotBlank() && it != "null" },
                        direction = stepJson.optString("direction").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }

            // Parameters
            val paramsJson = json.optJSONObject("parameters")
            val parameters = mutableMapOf<String, String>()
            if (paramsJson != null) {
                for (key in paramsJson.keys()) {
                    parameters[key] = paramsJson.optString(key, "")
                }
            }

            return WorkflowMemory(
                id = json.getString("id"),
                app = json.getString("app"),
                task = json.getString("task"),
                goal = json.optString("goal", json.getString("task")),
                embedding = embedding,
                steps = steps,
                parameters = parameters,
                version = json.optInt("version", 1),
                confidence = json.optDouble("confidence", 1.0).toFloat(),
                successCount = json.optInt("successCount", 0),
                failureCount = json.optInt("failureCount", 0),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastUsedAt = json.optLong("lastUsedAt", System.currentTimeMillis())
            )
        }
    }

    /**
     * Compute an overall reliability score for ranking multiple candidate workflows.
     */
    fun reliabilityScore(): Float {
        val total = successCount + failureCount
        if (total == 0) return confidence
        val successRate = successCount.toFloat() / total
        return confidence * 0.4f + successRate * 0.6f
    }
}

/**
 * Legacy data class kept for backward-compatibility with any code that
 * references the old WorkflowStep directly. New code should use [SemanticStep].
 */
data class WorkflowStep(
    val action: String,
    val elementId: String?,
    val value: String?,
    val direction: String?
)
