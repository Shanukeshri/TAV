package com.example.teachablevoice.agent

data class Goal(
    val intent: String = "AUTOMATE",
    val app: String,
    val objective: String,
    val parameters: Map<String, Any> = emptyMap()
)
