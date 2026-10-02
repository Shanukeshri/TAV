package com.example.teachablevoice.agent

import android.util.Log
import com.example.teachablevoice.model.ModelBackend
import com.example.teachablevoice.bridge.AutomationBridge
import com.example.teachablevoice.bridge.Direction
import kotlinx.coroutines.delay

class AgentController(
    private val model: ModelBackend,
    private val bridge: AutomationBridge
) {
    private val state = AgentStateRepository.globalState
    private val loopDetector = LoopDetector(state)
    private val recoveryManager = RecoveryManager(bridge, state)
    private val validator = ActionValidator()
    
    suspend fun execute(goal: Goal) {
        state.reset()
        state.currentGoal = goal
        state.isRunning = true
        state.status = AgentStatus.RUNNING
        state.resolvedApp = goal.app
        state.appendLog("INIT", "Goal: ${goal.objective}")
        state.appendLog("RESOLVE", "Target app: ${goal.app}")

        try {
            // Step 1: Open target app
            state.appendLog("OPEN_APP", "Launching ${goal.app}…")
            val openResult = bridge.openApp(goal.app)
            if (openResult == com.example.teachablevoice.bridge.Result.FAILURE) {
                state.appendLog("ERROR", "Failed to open ${goal.app}", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Failed to open ${goal.app}"
                return
            }
            state.appendLog("OPEN_APP", "App launched successfully ✓")

            // Give the target app time to fully render
            delay(2000)

            // Step 2: Observe initial UI
            state.appendLog("OBSERVE", "Waiting for UI…")
            var currentState = bridge.waitForUiChange(5000)
            state.appendLog("OBSERVE", "Got UI: app=${currentState.app}, elements=${currentState.elements.size}, fingerprint=${currentState.fingerprint}")

            // Check for sensitive page
            if (isSensitivePage(currentState)) {
                state.appendLog("SECURITY", "Sensitive page detected (e.g. payment, credit card). Aborting for safety.", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Stopped at sensitive page."
                return
            }

            // Step 3: Agent loop
            while (state.isRunning) {
                state.stepCount++
                val stepNum = state.stepCount
                
                // Build Context
                val prompt = buildPrompt(goal, currentState, state)
                state.appendLog("MODEL", "Step $stepNum: Sending prompt (${currentState.elements.size} elements)…")
                
                // Model generates action
                val modelOutput: String
                try {
                    modelOutput = model.generate(prompt)
                } catch (e: Exception) {
                    state.appendLog("ERROR", "Model error: ${e.message}", isError = true)
                    state.status = AgentStatus.ERROR
                    state.statusMessage = "Model error: ${e.message}"
                    return
                }
                state.appendLog("MODEL", "Response: ${modelOutput.take(120)}")
                
                val action = ActionParser.parse(modelOutput)
                
                if (action == null) {
                    state.appendLog("PARSE", "Failed to parse action, retrying…", isError = true)
                    Log.e("AgentController", "Failed to parse action: $modelOutput")
                    continue
                }
                
                state.appendLog("ACTION", "Parsed: ${action.action} ${action.elementId ?: ""} ${action.value ?: ""} ${action.message ?: ""}")

                if (action.action == ActionType.DONE) {
                    state.appendLog("DONE", "Goal achieved! ✓")
                    state.status = AgentStatus.COMPLETED
                    state.statusMessage = "Goal achieved"
                    break
                }
                if (action.action == ActionType.ASK) {
                    state.appendLog("ASK", "Model asks: ${action.message}")
                    state.status = AgentStatus.ASKING
                    state.statusMessage = action.message ?: "Model needs clarification"
                    break
                }
                
                if (!validator.isValid(action, currentState)) {
                    state.appendLog("VALIDATE", "Invalid action: ${action.action} ${action.elementId ?: ""}", isError = true)
                    state.recordTransition(Transition(currentState.fingerprint, action, currentState.fingerprint, TransitionResult.FAILED))
                    continue
                }
                
                // Execute Action
                state.appendLog("EXEC", "Executing: ${action.action} ${action.elementId ?: ""}")
                executeAction(action)
                
                // Wait for action to take effect
                delay(1000)
                
                // Observe New State
                state.appendLog("OBSERVE", "Waiting for new UI…")
                val nextState = bridge.waitForUiChange(3000)
                val result = if (nextState.fingerprint == currentState.fingerprint) {
                    TransitionResult.NO_STATE_CHANGE
                } else {
                    TransitionResult.SUCCESS
                }
                
                state.appendLog("OBSERVE", "Result: $result (${nextState.elements.size} elements, fp=${nextState.fingerprint})")
                state.recordTransition(Transition(currentState.fingerprint, action, nextState.fingerprint, result))
                currentState = nextState

                // Security check after every new UI state
                if (isSensitivePage(currentState)) {
                    state.appendLog("SECURITY", "Sensitive page detected (e.g. payment, credit card). Aborting for safety.", isError = true)
                    state.status = AgentStatus.ERROR
                    state.statusMessage = "Stopped at sensitive page."
                    break
                }
                
                if (loopDetector.isLooping()) {
                    state.appendLog("LOOP", "Loop detected! Attempting recovery…", isError = true)
                    if (!recoveryManager.attemptRecovery()) {
                        state.appendLog("ERROR", "Recovery failed. Aborting.", isError = true)
                        state.status = AgentStatus.ERROR
                        state.statusMessage = "Stuck in loop, recovery failed"
                        break
                    }
                    state.appendLog("RECOVERY", "Back pressed, retrying…")
                    delay(1000)
                    currentState = bridge.getUiState()
                }
            }

        } catch (e: Exception) {
            state.appendLog("ERROR", "Unhandled: ${e.message}", isError = true)
            state.status = AgentStatus.ERROR
            state.statusMessage = "Error: ${e.message}"
            Log.e("AgentController", "Agent error", e)
        } finally {
            state.isRunning = false
            state.appendLog("END", "Agent stopped. Status: ${state.status}")
        }
    }
    
    private suspend fun executeAction(action: AgentAction) {
        when (action.action) {
            ActionType.CLICK -> bridge.click(action.elementId!!)
            ActionType.LONG_CLICK -> bridge.longClick(action.elementId!!)
            ActionType.INPUT -> bridge.input(action.elementId!!, action.value!!)
            ActionType.SCROLL -> bridge.scroll(action.elementId, Direction.valueOf(action.direction ?: "DOWN"))
            ActionType.SWIPE -> {
                val dir = action.direction?.uppercase() ?: "UP"
                when (dir) {
                    "UP" -> bridge.swipe(500f, 1500f, 500f, 500f)
                    "DOWN" -> bridge.swipe(500f, 500f, 500f, 1500f)
                    "LEFT" -> bridge.swipe(900f, 1000f, 100f, 1000f)
                    "RIGHT" -> bridge.swipe(100f, 1000f, 900f, 1000f)
                }
            }
            ActionType.BACK -> bridge.back()
            ActionType.HOME -> bridge.home()
            ActionType.RECENTS -> bridge.recents()
            ActionType.NOTIFICATIONS -> bridge.notifications()
            else -> {}
        }
    }
    
    private fun buildPrompt(goal: Goal, uiState: UiState, agentState: AgentState): String {
        return """
            GOAL: ${goal.objective}
            
            CURRENT APP: ${uiState.app}
            CURRENT STATE: ${uiState.fingerprint}
            
            ELEMENTS:
            ${uiState.elements.joinToString("\n") { "- id: ${it.id}, role: ${it.role}, label: '${it.label}', hint: '${it.hint}', editable: ${it.isEditable}, actions: ${it.actions}" }}
            
            RECENT ACTIONS:
            ${agentState.history.takeLast(3).joinToString("\n") { "${it.action.action} ${it.action.elementId ?: ""} -> ${it.result}" }}
            
            FAILED ACTIONS FROM CURRENT STATE:
            ${agentState.history.filter { it.fromState == uiState.fingerprint && it.result != TransitionResult.SUCCESS }.joinToString(", ") { it.action.action.name + " " + it.action.elementId }}
            
            VISITED STATES:
            ${agentState.visitedStates.joinToString(", ")}
            
            INSTRUCTIONS:
            - Output ONLY a valid JSON object.
            - Do not include any reasoning or markdown wrapping.
            - Format: {"action": "CLICK", "element_id": "id123"}
            - Allowed actions: OPEN_APP, CLICK, LONG_CLICK, INPUT, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, DONE, ASK
            - For SWIPE and SCROLL, optionally provide "direction" (UP, DOWN, LEFT, RIGHT).
            - Do not repeat a failed action.
            - If dead end, choose BACK.
            - If goal satisfied, choose DONE.
        """.trimIndent()
    }
    
    private fun isSensitivePage(uiState: UiState): Boolean {
        val sensitiveKeywords = listOf("credit card", "cvv", "upi pin", "enter your pin", "password", "card number", "payment details", "bank account")
        for (element in uiState.elements) {
            val text = element.label.lowercase()
            val hint = element.hint.lowercase()
            for (keyword in sensitiveKeywords) {
                if (text.contains(keyword) || hint.contains(keyword)) {
                    return true
                }
            }
        }
        return false
    }
}
