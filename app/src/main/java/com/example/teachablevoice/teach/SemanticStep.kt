package com.example.teachablevoice.teach

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single step in a recorded or generalized workflow, stored using
 * **semantic UI attributes** (role, label, contentDescription) rather than
 * fragile accessibility node IDs.
 *
 * This makes workflows resilient to app UI changes and session restarts.
 */
data class SemanticStep(
    val stepIndex: Int,
    val action: String,                     // CLICK, INPUT, SCROLL, SWIPE, BACK, etc.
    val targetRole: String? = null,         // e.g. "button", "textfield", "image"
    val targetLabel: String? = null,        // visible text on the element
    val targetDescription: String? = null,  // contentDescription
    val targetHint: String? = null,         // hint text (for editable fields)
    val inputTemplate: String? = null,      // e.g. "{{item}}" or literal "milk"
    val direction: String? = null,          // for SCROLL / SWIPE
    val screenContext: String? = null,      // e.g. "search_results", "home"
    val elementId: String? = null           // original ID, kept only as a hint
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepIndex", stepIndex)
        put("action", action)
        targetRole?.let { put("targetRole", it) }
        targetLabel?.let { put("targetLabel", it) }
        targetDescription?.let { put("targetDescription", it) }
        targetHint?.let { put("targetHint", it) }
        inputTemplate?.let { put("inputTemplate", it) }
        direction?.let { put("direction", it) }
        screenContext?.let { put("screenContext", it) }
        elementId?.let { put("elementId", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): SemanticStep = SemanticStep(
            stepIndex = json.optInt("stepIndex", 0),
            action = json.getString("action"),
            targetRole = json.optString("targetRole").takeIf { it.isNotBlank() },
            targetLabel = json.optString("targetLabel").takeIf { it.isNotBlank() },
            targetDescription = json.optString("targetDescription").takeIf { it.isNotBlank() },
            targetHint = json.optString("targetHint").takeIf { it.isNotBlank() },
            inputTemplate = json.optString("inputTemplate").takeIf { it.isNotBlank() },
            direction = json.optString("direction").takeIf { it.isNotBlank() },
            screenContext = json.optString("screenContext").takeIf { it.isNotBlank() },
            elementId = json.optString("elementId").takeIf { it.isNotBlank() }
        )
    }
}
