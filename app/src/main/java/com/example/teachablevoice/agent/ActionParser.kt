package com.example.teachablevoice.agent

import org.json.JSONObject

object ActionParser {
    fun parse(jsonString: String): AgentAction? {
        return try {
            // Very simple JSON extraction, in reality needs better robustness
            // Assuming output like {"action": "CLICK", "element_id": "n17"}
            
            // Clean up the string to find the JSON part if the model added thinking text
            val jsonStart = jsonString.indexOf("{")
            val jsonEnd = jsonString.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) return null
            
            val cleanJson = jsonString.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(cleanJson)
            
            val actionStr = json.getString("action")
            val actionType = ActionType.valueOf(actionStr)
            
            AgentAction(
                action = actionType,
                elementId = json.optString("element_id").takeIf { it.isNotEmpty() },
                value = json.optString("value").takeIf { it.isNotEmpty() },
                direction = json.optString("direction").takeIf { it.isNotEmpty() },
                message = json.optString("message").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }
}
