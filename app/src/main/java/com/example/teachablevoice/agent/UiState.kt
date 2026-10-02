package com.example.teachablevoice.agent

data class UiElement(
    val id: String,
    val role: String,
    val label: String,
    val hint: String,
    val isEditable: Boolean,
    val actions: List<String>
)

data class UiState(
    val app: String,
    val screen: String = "Unknown",
    val elements: List<UiElement>,
    val scrollable: Boolean
) {
    // Basic fingerprint based on available interactive elements and their roles
    val fingerprint: String
        get() = elements.joinToString("|") { "${it.id}:${it.role}" }.hashCode().toString(16)
}
