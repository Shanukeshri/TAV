package com.example.teachablevoice.memory

import org.json.JSONArray
import org.json.JSONObject

data class WorkflowMemory(
    val id: String,
    val app: String,
    val task: String,
    val embedding: FloatArray,
    val steps: List<WorkflowStep>
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("app", app)
        json.put("task", task)
        
        val embArray = JSONArray()
        embedding.forEach { embArray.put(it.toDouble()) }
        json.put("embedding", embArray)
        
        val stepsArray = JSONArray()
        steps.forEach { step ->
            val stepJson = JSONObject()
            stepJson.put("action", step.action)
            step.elementId?.let { stepJson.put("elementId", it) }
            step.value?.let { stepJson.put("value", it) }
            step.direction?.let { stepJson.put("direction", it) }
            stepsArray.put(stepJson)
        }
        json.put("steps", stepsArray)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): WorkflowMemory {
            val embArray = json.getJSONArray("embedding")
            val embedding = FloatArray(embArray.length())
            for (i in 0 until embArray.length()) {
                embedding[i] = embArray.getDouble(i).toFloat()
            }
            
            val stepsArray = json.getJSONArray("steps")
            val steps = mutableListOf<WorkflowStep>()
            for (i in 0 until stepsArray.length()) {
                val stepJson = stepsArray.getJSONObject(i)
                steps.add(
                    WorkflowStep(
                        action = stepJson.getString("action"),
                        elementId = stepJson.optString("elementId", "").takeIf { it != "null" && it.isNotEmpty() },
                        value = stepJson.optString("value", "").takeIf { it != "null" && it.isNotEmpty() },
                        direction = stepJson.optString("direction", "").takeIf { it != "null" && it.isNotEmpty() }
                    )
                )
            }
            
            return WorkflowMemory(
                id = json.getString("id"),
                app = json.getString("app"),
                task = json.getString("task"),
                embedding = embedding,
                steps = steps
            )
        }
    }
}

data class WorkflowStep(
    val action: String,
    val elementId: String?,
    val value: String?,
    val direction: String?
)
