package com.example.teachablevoice.agent

data class Goal(
    val app: String,
    val objective: String,
    val parameters: Map<String, Any> = emptyMap()
)
