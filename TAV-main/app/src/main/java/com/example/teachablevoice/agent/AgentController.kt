package com.example.teachablevoice.agent

import android.util.DisplayMetrics
import android.util.Log
import com.example.teachablevoice.bridge.AutomationBridge
import com.example.teachablevoice.bridge.Direction
import com.example.teachablevoice.model.ModelBackend

class AgentController(
    private val model: ModelBackend,
    private val bridge: AutomationBridge,
    private val displayMetrics: DisplayMetrics = DisplayMetrics()
) {
    private val state = AgentStateRepository.globalState
    private val loopDetector = LoopDetector(state)
    private val recoveryManager = RecoveryManager(bridge, state)
    private val validator = ActionValidator()

    companion object {
        private const val TAG = "AgentController"
        const val MAX_STEPS = 25
        const val WALL_CLOCK_TIMEOUT_MS = 5 * 60 * 1_000L
        private const val MAX_PARSE_RETRIES = 3
        private const val STABLE_UI_WAIT_MS = 3_000L
        private const val STABLE_FOR_MS = 400L
    }

    suspend fun execute(goal: Goal) {
        state.reset()
        state.currentGoal = goal
        state.isRunning = true
        state.status = AgentStatus.RUNNING
        state.resolvedApp = goal.app
        state.appendLog("INIT", "Goal: ${goal.objective}")
        state.appendLog("RESOLVE", "Target app: ${goal.app}")

        val deadline = System.currentTimeMillis() + WALL_CLOCK_TIMEOUT_MS

        try {
            state.appendLog("OPEN_APP", "Launching ${goal.app}…")
            val openResult = bridge.openApp(goal.app)
            if (openResult == com.example.teachablevoice.bridge.Result.FAILURE) {
                state.appendLog("ERROR", "Failed to open ${goal.app}", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Failed to open ${goal.app}"
                return
            }
            state.appendLog("OPEN_APP", "App launched successfully ✓")

            state.appendLog("OBSERVE", "Waiting for stable UI after launch…")
            var currentState = bridge.waitForStableUi(STABLE_UI_WAIT_MS, STABLE_FOR_MS)
            state.appendLog(
                "OBSERVE",
                "Got UI: app=${currentState.app}, elements=${currentState.elements.size}, fingerprint=${currentState.fingerprint}"
            )

            var parseFailStreak = 0

            while (state.isRunning) {
                if (state.stepCount >= MAX_STEPS) {
                    state.appendLog("LIMIT", "Reached MAX_STEPS ($MAX_STEPS). Aborting.", isError = true)
                    state.status = AgentStatus.ERROR
                    state.statusMessage = "Max step limit ($MAX_STEPS) reached"
                    break
                }
                if (System.currentTimeMillis() >= deadline) {
                    state.appendLog("LIMIT", "Wall-clock timeout. Aborting.", isError = true)
                    state.status = AgentStatus.ERROR
                    state.statusMessage = "Timeout — task took too long"
                    break
                }

                state.stepCount++
                val stepNum = state.stepCount

                val prompt = buildPrompt(goal, currentState, state)
                state.appendLog("MODEL", "Step $stepNum: Sending prompt (${currentState.elements.size} elements)…")

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
                    parseFailStreak++
                    state.appendLog(
                        "PARSE",
                        "Failed to parse action (attempt $parseFailStreak/$MAX_PARSE_RETRIES)…",
                        isError = true
                    )
                    Log.e(TAG, "Failed to parse action")
                    if (parseFailStreak >= MAX_PARSE_RETRIES) {
                        state.appendLog("PARSE", "Max parse retries reached. Aborting.", isError = true)
                        state.status = AgentStatus.ERROR
                        state.statusMessage = "Could not parse model output after $MAX_PARSE_RETRIES attempts"
                        break
                    }
                    continue
                }
                parseFailStreak = 0

                state.appendLog(
                    "ACTION",
                    "Parsed: ${action.action} ${action.elementId ?: ""} ${action.value ?: ""} ${action.message ?: ""}"
                )

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
                    state.recordTransition(
                        Transition(currentState.fingerprint, action, currentState.fingerprint, TransitionResult.FAILED)
                    )
                    continue
                }

                state.appendLog("EXEC", "Executing: ${action.action} ${action.elementId ?: ""}")
                executeAction(action, currentState)

                state.appendLog("OBSERVE", "Waiting for UI to settle…")
                val nextState = bridge.waitForStableUi(STABLE_UI_WAIT_MS, STABLE_FOR_MS)
                val result = if (nextState.fingerprint == currentState.fingerprint) {
                    TransitionResult.NO_STATE_CHANGE
                } else {
                    TransitionResult.SUCCESS
                }

                state.appendLog("OBSERVE", "Result: $result (${nextState.elements.size} elements)")
                state.recordTransition(Transition(currentState.fingerprint, action, nextState.fingerprint, result))
                currentState = nextState

                if (loopDetector.isLooping()) {
                    state.appendLog("LOOP", "Loop detected! Attempting recovery…", isError = true)
                    if (!recoveryManager.attemptRecovery()) {
                        state.appendLog("ERROR", "Recovery failed. Aborting.", isError = true)
                        state.status = AgentStatus.ERROR
                        state.statusMessage = "Stuck in loop, recovery failed"
                        break
                    }
                    state.appendLog("RECOVERY", "Back pressed, retrying…")
                    currentState = bridge.waitForStableUi(STABLE_UI_WAIT_MS, STABLE_FOR_MS)
                }
            }
        } catch (e: Exception) {
            state.appendLog("ERROR", "Unhandled: ${e.message}", isError = true)
            state.status = AgentStatus.ERROR
            state.statusMessage = "Error: ${e.message}"
            Log.e(TAG, "Agent error", e)
        } finally {
            state.isRunning = false
            state.appendLog("END", "Agent stopped. Status: ${state.status}")
        }
    }

    private suspend fun executeAction(action: AgentAction, currentState: UiState) {
        when (action.action) {
            ActionType.CLICK -> bridge.click(action.elementId!!)
            ActionType.LONG_CLICK -> bridge.longClick(action.elementId!!)
            ActionType.INPUT -> bridge.input(action.elementId!!, action.value!!)
            ActionType.SCROLL -> bridge.scroll(action.elementId, Direction.valueOf(action.direction ?: "DOWN"))
            ActionType.SWIPE -> swipeWithoutHardcodedPixels(action, currentState)
            ActionType.BACK -> bridge.back()
            ActionType.HOME -> bridge.home()
            ActionType.RECENTS -> bridge.recents()
            ActionType.NOTIFICATIONS -> bridge.notifications()
            else -> {}
        }
    }

    private suspend fun swipeWithoutHardcodedPixels(action: AgentAction, currentState: UiState) {
        val dir = Direction.valueOf((action.direction ?: "DOWN").uppercase().let {
            if (it in listOf("UP", "DOWN", "LEFT", "RIGHT")) it else "DOWN"
        })
        val scrollable = currentState.elements.firstOrNull { el ->
            el.actions.any { it.contains("SCROLL", ignoreCase = true) }
        }
        if (scrollable != null && (dir == Direction.UP || dir == Direction.DOWN)) {
            bridge.scroll(scrollable.id, dir)
            return
        }
        val w = displayMetrics.widthPixels.takeIf { it > 0 }?.toFloat() ?: 1080f
        val h = displayMetrics.heightPixels.takeIf { it > 0 }?.toFloat() ?: 1920f
        val cx = w * 0.5f
        val top = h * 0.25f
        val bottom = h * 0.75f
        val left = w * 0.15f
        val right = w * 0.85f
        val mid = h * 0.5f
        when (dir) {
            Direction.UP -> bridge.swipe(cx, bottom, cx, top)
            Direction.DOWN -> bridge.swipe(cx, top, cx, bottom)
            Direction.LEFT -> bridge.swipe(right, mid, left, mid)
            Direction.RIGHT -> bridge.swipe(left, mid, right, mid)
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
            
            STEP: ${agentState.stepCount}/$MAX_STEPS
            
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
}
