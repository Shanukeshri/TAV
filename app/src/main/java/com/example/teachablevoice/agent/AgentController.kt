package com.example.teachablevoice.agent

import android.util.Log
import com.example.teachablevoice.model.ModelBackend
import com.example.teachablevoice.bridge.AutomationBridge
import com.example.teachablevoice.bridge.Direction
import com.example.teachablevoice.memory.WorkflowMemoryManager
import com.example.teachablevoice.memory.WorkflowStep
import com.example.teachablevoice.memory.WorkflowMemory
import com.example.teachablevoice.teach.TeachSession
import com.example.teachablevoice.teach.WorkflowGeneralizer
import com.example.teachablevoice.replay.WorkflowReplayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AgentController(
    private val model: ModelBackend,
    private val bridge: AutomationBridge,
    private val memoryManager: WorkflowMemoryManager
) {
    private val state = AgentStateRepository.globalState
    private val loopDetector = LoopDetector(state)
    private val recoveryManager = RecoveryManager(bridge, state)
    private val validator = ActionValidator()
    private val replayer = WorkflowReplayer(bridge)

    suspend fun execute(goal: Goal) {
        state.reset()
        state.currentGoal = goal
        state.isRunning = true
        state.status = AgentStatus.RUNNING
        state.resolvedApp = goal.app
        state.appendLog("INIT", "Goal: ${goal.objective}")
        state.appendLog("RESOLVE", "Target app: ${goal.app}")

        // Create a TeachSession if this is a TEACH request
        val teachSession: TeachSession? = if (goal.intent == "TEACH") {
            TeachSession(app = goal.app, originalCommand = goal.objective).also { it.start() }
        } else null

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

            // Wait for initial UI to load
            var currentState = bridge.waitForUiChange(5000)
            currentState = dismissPopups(bridge, state, currentState)
            
            // ──────────────────────────────────────────────
            // Memory retrieval (only for non-TEACH requests)
            // ──────────────────────────────────────────────
            var retrievedWorkflow: WorkflowMemory? = null
            if (goal.intent != "TEACH") {
                state.appendLog("MEMORY", "Searching for related workflows…")
                retrievedWorkflow = memoryManager.retrieveWorkflow(goal.objective, appHint = goal.app)
                if (retrievedWorkflow != null) {
                    state.appendLog("MEMORY", "Found workflow: '${retrievedWorkflow.task}' " +
                            "(v${retrievedWorkflow.version}, confidence=${retrievedWorkflow.confidence}, " +
                            "success=${retrievedWorkflow.successCount})")
                    
                    // ──────────────────────────────────────────────
                    // REPLAY: if workflow has semantic steps, try deterministic replay first
                    // ──────────────────────────────────────────────
                    if (retrievedWorkflow.steps.isNotEmpty() && retrievedWorkflow.confidence >= 0.5f) {
                        val replayResult = attemptReplay(retrievedWorkflow, goal)
                        if (replayResult.fullyReplayed) {
                            // Full replay succeeded — update memory and exit
                            memoryManager.recordSuccess(retrievedWorkflow.id)
                            state.appendLog("DONE", "Goal achieved via replay! ✓")
                            state.status = AgentStatus.COMPLETED
                            state.statusMessage = "Goal achieved (replayed)"
                            return
                        }
                        // Partial replay or fallback — continue with Gemini-assisted loop below
                        state.appendLog("FALLBACK", "⚡ Replay stopped at step ${replayResult.stoppedAtStep + 1}. Handing full control to Gemini AI.")
                    }
                } else {
                    state.appendLog("MEMORY", "No prior knowledge found.")
                }
            } else {
                state.appendLog("TEACH", "🎓 TEACHING MODE — recording every action for learning.")
            }

            // Step 2: Observe initial UI (already done above)
            state.appendLog("OBSERVE", "Got UI: app=${currentState.app}, elements=${currentState.elements.size}, fingerprint=${currentState.fingerprint}")

            // Check for sensitive page
            val keyword = getSensitiveKeyword(currentState)
            if (keyword != null) {
                state.appendLog("SECURITY", "Sensitive page detected (keyword: '$keyword'). Aborting for safety.", isError = true)
                state.status = AgentStatus.ERROR
                state.statusMessage = "Stopped at sensitive page."
                return
            }

            // Step 3: Agent loop
            if (goal.intent == "TEACH") {
                state.appendLog("TEACH", "🎓 TEACHING MODE — Passive observation started. Awaiting your actions.")
                val actionChannel = kotlinx.coroutines.channels.Channel<Pair<AgentAction, UiState>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    com.example.teachablevoice.teach.UserActionObserver.actions.collect {
                        actionChannel.send(it)
                    }
                }
                
                try {
                    while (state.isRunning) {
                        val event = kotlinx.coroutines.withTimeoutOrNull(500) { actionChannel.receive() }
                        if (event != null) {
                            val (action, uiBefore) = event
                            state.appendLog("OBSERVE", "User action: ${action.action} ${action.elementId}")
                            delay(1000) // Wait for UI to settle
                            val nextState = bridge.getUiState()
                            teachSession?.recordStep(uiBefore, action, nextState)
                        }
                    }
                } finally {
                    job.cancel()
                }
                
                if (state.status == AgentStatus.COMPLETED && teachSession != null) {
                    teachSession.stop()
                    state.appendLog("LEARNER", "🧠 Generalising ${teachSession.trajectory.size} recorded steps…")
                    try {
                        val generalizer = WorkflowGeneralizer(model)
                        val generalizedWorkflow = generalizer.generalize(teachSession)
                        if (generalizedWorkflow != null) {
                            memoryManager.saveGeneralizedWorkflow(generalizedWorkflow)
                            state.appendLog("LEARNER", "✓ Workflow saved: '${generalizedWorkflow.goal}' (${generalizedWorkflow.steps.size} steps)")
                        } else {
                            state.appendLog("LEARNER", "Generalisation returned null, saving raw trajectory", isError = true)
                            saveFallbackWorkflow(teachSession, goal)
                        }
                    } catch (e: Exception) {
                        state.appendLog("LEARNER", "Generalisation failed: ${e.message}", isError = true)
                        saveFallbackWorkflow(teachSession, goal)
                    }
                }
            } else {
                // Agent loop (Gemini-assisted)
                val zeroShotActions = mutableListOf<Triple<com.example.teachablevoice.agent.AgentAction, com.example.teachablevoice.agent.UiState, com.example.teachablevoice.agent.UiState>>()
                while (state.isRunning) {
                state.stepCount++
                val stepNum = state.stepCount
                
                // Build Context
                val prompt = buildPrompt(goal, currentState, state, retrievedWorkflow)
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
                    
                    // For non-TEACH successful executions: also update/learn
                    if (retrievedWorkflow != null) {
                        memoryManager.recordSuccess(retrievedWorkflow.id)
                        state.appendLog("LEARNER", "Workflow confidence updated (success)")
                    } else if (goal.intent != "TEACH" && zeroShotActions.isNotEmpty()) {
                        state.appendLog("LEARNER", "🧠 Auto-learning successful zero-shot workflow…")
                        val dummySession = com.example.teachablevoice.teach.TeachSession(goal.app, goal.objective)
                        dummySession.start()
                        for (triple in zeroShotActions) {
                            dummySession.recordStep(triple.second, triple.first, triple.third)
                        }
                        dummySession.stop()
                        
                        kotlinx.coroutines.GlobalScope.launch {
                            val generalizer = com.example.teachablevoice.teach.WorkflowGeneralizer(model)
                            val genWorkflow = generalizer.generalize(dummySession)
                            if (genWorkflow != null) {
                                memoryManager.saveGeneralizedWorkflow(genWorkflow)
                                state.appendLog("LEARNER", "✓ Learned and saved new zero-shot workflow: '${genWorkflow.goal}'")
                            }
                        }
                    }
                    
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
                
                // Capture UI state before action (for teach recording)
                val uiBeforeAction = currentState

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
                
                if (result == TransitionResult.SUCCESS) {
                    zeroShotActions.add(Triple(action, uiBeforeAction, nextState))
                }
                
                currentState = nextState

                // Security check after every new UI state
                val sensitiveKeyword = getSensitiveKeyword(currentState)
                if (sensitiveKeyword != null) {
                    state.appendLog("SECURITY", "Sensitive page detected (keyword: '$sensitiveKeyword'). Aborting for safety.", isError = true)
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
                        // Record failure for the workflow
                        if (retrievedWorkflow != null) {
                            memoryManager.recordFailure(retrievedWorkflow.id)
                        }
                        break
                    }
                    state.appendLog("RECOVERY", "Back pressed, retrying…")
                    delay(1000)
                    currentState = bridge.getUiState()
                }
            }
            } // end of else block

        } catch (e: Exception) {
            state.appendLog("ERROR", "Unhandled: ${e.message}", isError = true)
            state.status = AgentStatus.ERROR
            state.statusMessage = "Error: ${e.message}"
            Log.e("AgentController", "Agent error", e)
            // Record failure
            if (goal.intent != "TEACH") {
                val wf = try { memoryManager.retrieveWorkflow(goal.objective) } catch (_: Exception) { null }
                if (wf != null) memoryManager.recordFailure(wf.id)
            }
        } finally {
            teachSession?.let { if (it.isRecording) it.stop() }
            state.isRunning = false
            state.appendLog("END", "Agent stopped. Status: ${state.status}")
        }
    }

    // ──────────────────────────────────────────────
    // Replay attempt
    // ──────────────────────────────────────────────

    /**
     * Try to replay a known workflow deterministically.
     * Returns true if the full workflow was replayed successfully.
     */
    private suspend fun attemptReplay(
        workflow: WorkflowMemory,
        goal: Goal
    ): com.example.teachablevoice.replay.WorkflowReplayer.ReplayResult {
        state.appendLog("REPLAY", "⚡ Attempting fast replay of '${workflow.task}'")

        // Build parameter map from the goal's parameters
        val currentParams = mutableMapOf<String, String>()
        for ((key, value) in goal.parameters) {
            currentParams[key] = value.toString()
        }
        // Also use the workflow's stored default params as fallback
        for ((key, value) in workflow.parameters) {
            if (!currentParams.containsKey(key)) {
                currentParams[key] = value
            }
        }

        val result = replayer.replay(workflow, currentParams, state)

        if (result.fullyReplayed) {
            state.appendLog("REPLAY", "⚡ Full replay completed! ${result.stepsExecuted} steps, zero LLM calls")
        } else {
            state.appendLog("REPLAY", "Partial replay: ${result.stepsExecuted}/${result.totalSteps} steps. " +
                "Stopped at step ${result.stoppedAtStep + 1}.")
        }
        
        return result
    }

    // ──────────────────────────────────────────────
    // Fallback save (when Gemini generalisation fails)
    // ──────────────────────────────────────────────

    private suspend fun saveFallbackWorkflow(session: TeachSession, goal: Goal) {
        try {
            val steps = session.trajectory.filter {
                it.uiChangedSuccessfully || it.action == "INPUT" || it.action == "DONE"
            }.map {
                WorkflowStep(it.action, it.elementId, it.inputValue, it.direction)
            }
            memoryManager.saveWorkflow(goal.app, goal.objective, steps)
            state.appendLog("LEARNER", "Fallback: saved ${steps.size} raw steps.")
        } catch (e: Exception) {
            state.appendLog("LEARNER", "Failed even fallback save: ${e.message}", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    // Action execution (unchanged)
    // ──────────────────────────────────────────────
    
    private suspend fun executeAction(action: AgentAction) {
        when (action.action) {
            ActionType.CLICK -> bridge.click(action.elementId!!)
            ActionType.LONG_CLICK -> bridge.longClick(action.elementId!!)
            ActionType.INPUT -> bridge.input(action.elementId!!, action.value!!)
            ActionType.ENTER -> bridge.enter(action.elementId!!)
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
    
    private fun buildPrompt(goal: Goal, uiState: UiState, agentState: AgentState, priorKnowledge: WorkflowMemory?): String {
        val workflowHint = if (priorKnowledge != null) {
            // Build runtime parameters: merge workflow defaults with goal overrides
            val runtimeParams = priorKnowledge.parameters.toMutableMap()
            for ((key, value) in goal.parameters) {
                runtimeParams[key] = value.toString()
            }

            val stepsDesc = priorKnowledge.steps.mapIndexed { index, step ->
                var inputDesc = step.inputTemplate ?: "none"
                // Substitute runtime parameters into the description
                for ((key, value) in runtimeParams) {
                    inputDesc = inputDesc.replace("{{$key}}", value)
                }
                "  Step ${index + 1}: ${step.action} " +
                "target=[role=${step.targetRole ?: "?"}, label='${step.targetLabel ?: "?"}', " +
                "hint='${step.targetHint ?: ""}'] " +
                "input=$inputDesc"
            }.joinToString("\n")

            val paramsDesc = if (runtimeParams.isNotEmpty()) {
                "\nRuntime Parameters: ${runtimeParams.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
            } else ""

            """
            
            LEARNED WORKFLOW (MUST FOLLOW):
            You have a previously learned workflow for this exact task. You MUST follow these steps in order.
            Find the matching UI elements on screen and execute each step sequentially.
            If a step's label doesn't exactly match, find the closest matching element.
            $stepsDesc$paramsDesc
            
            IMPORTANT: Follow this workflow step-by-step. Do NOT deviate from it.
            Replace any {{parameter}} placeholders with the runtime values above.
            """
        } else ""

        return """
            GOAL: ${goal.objective}
            
            CURRENT APP: ${uiState.app}
            CURRENT STATE: ${uiState.fingerprint}$workflowHint
            
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
            - Allowed actions: OPEN_APP, CLICK, LONG_CLICK, INPUT, SCROLL, SWIPE, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT, DONE, ASK, ENTER
            - To simulate pressing the "Enter" or "Search" key on the soft keyboard (e.g. after typing a search query), use {"action": "ENTER", "element_id": "the_text_field_id"}.
            - For SWIPE and SCROLL, optionally provide "direction" (UP, DOWN, LEFT, RIGHT).
            - Do not repeat a failed action.
            - If dead end, choose BACK.
            - If goal satisfied, choose DONE.
        """.trimIndent()
    }
    
    private fun getSensitiveKeyword(uiState: UiState): String? {
        val sensitiveKeywords = listOf("credit card", "cvv", "upi pin", "enter your pin", "password", "card number", "payment details", "bank account")
        for (element in uiState.elements) {
            val text = element.label.lowercase()
            val hint = element.hint.lowercase()
            for (keyword in sensitiveKeywords) {
                if (text.contains(keyword) || hint.contains(keyword)) {
                    return keyword
                }
            }
        }
        return null
    }

    private suspend fun dismissPopups(bridge: com.example.teachablevoice.bridge.AutomationBridge, state: AgentState, initialUi: UiState): UiState {
        var currentUi = initialUi
        val popupKeywords = listOf("close", "skip", "not now", "no thanks", "dismiss", "x", "maybe later")
        
        for (i in 0..1) { // Try up to 2 times
            var dismissedSomething = false
            for (element in currentUi.elements) {
                if (!element.actions.contains("click") && !element.actions.contains("CLICK")) continue
                
                val text = element.label.lowercase().trim()
                val hint = element.hint.lowercase().trim()
                
                if (text in popupKeywords || hint in popupKeywords) {
                    state.appendLog("DISMISS", "Detected potential popup button: '${element.label ?: element.hint}'. Clicking to dismiss.")
                    bridge.click(element.id)
                    currentUi = bridge.waitForUiChange(3000)
                    dismissedSomething = true
                    break
                }
            }
            if (!dismissedSomething) {
                break
            }
        }
        return currentUi
    }
}
