package com.example.teachablevoice.agent

class ActionValidator {
    fun isValid(action: AgentAction, uiState: UiState): Boolean {
        when (action.action) {
            ActionType.CLICK, ActionType.INPUT -> {
                val el = uiState.elements.find { it.id == action.elementId } ?: return false
                if (action.action == ActionType.CLICK) {
                    return el.actions.contains("CLICK") || el.actions.contains("ACTION_CLICK") || el.isEditable
                }
                if (action.action == ActionType.INPUT) {
                    return el.actions.contains("INPUT") || el.actions.contains("ACTION_SET_TEXT") || el.role == "textfield" || el.isEditable
                }
            }
            ActionType.SCROLL -> {
                return uiState.scrollable
            }
            else -> return true
        }
        return true
    }
}
