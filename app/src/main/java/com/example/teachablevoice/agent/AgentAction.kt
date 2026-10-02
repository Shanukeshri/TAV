package com.example.teachablevoice.agent

enum class ActionType {
    OPEN_APP, CLICK, LONG_CLICK, INPUT, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, DONE, ASK
}

data class AgentAction(
    val action: ActionType,
    val elementId: String? = null,
    val value: String? = null,
    val direction: String? = null,
    val message: String? = null
)
