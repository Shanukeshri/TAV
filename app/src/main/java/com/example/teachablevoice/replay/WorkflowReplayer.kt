package com.example.teachablevoice.replay

import android.util.Log
import com.example.teachablevoice.agent.*
import com.example.teachablevoice.bridge.AutomationBridge
import com.example.teachablevoice.bridge.Direction
import com.example.teachablevoice.memory.WorkflowMemory
import com.example.teachablevoice.teach.SemanticMatcher
import com.example.teachablevoice.teach.SemanticStep
import kotlinx.coroutines.delay

/**
 * Replays a learned workflow by deterministically matching each semantic step
 * to the current UI — **without calling Gemini** when matches are found.
 *
 * Flow per step:
 *  1. Read current UI state
 *  2. Substitute parameters into the step's input template
 *  3. Use [SemanticMatcher] to find the target element
 *  4. If match confidence ≥ [minMatchConfidence] → execute immediately
 *  5. If match confidence is low or no match → return FALLBACK so the caller
 *     can delegate that step to Gemini reasoning
 *
 * This is the primary speed-boost mechanism: for known tasks, most steps
 * execute with zero LLM latency.
 */
class WorkflowReplayer(
    private val bridge: AutomationBridge,
    private val minMatchConfidence: Float = 0.45f
) {
    companion object {
        private const val TAG = "WorkflowReplayer"
    }

    enum class StepResult {
        /** Step executed successfully and UI changed. */
        SUCCESS,
        /** Step executed but UI did not change (may still be valid, e.g. for BACK). */
        NO_CHANGE,
        /** Could not deterministically match the step to the UI — caller should use Gemini. */
        FALLBACK,
        /** The step's action requires no UI element (BACK, HOME, etc.) and was executed. */
        NAVIGATION
    }

    data class ReplayResult(
        /** How many steps were executed deterministically (fast). */
        val stepsExecuted: Int,
        /** Total steps in the workflow. */
        val totalSteps: Int,
        /** Whether the full workflow completed without needing Gemini fallback. */
        val fullyReplayed: Boolean,
        /** Index of the step where replay stopped (if not fully replayed). */
        val stoppedAtStep: Int,
        /** Remaining steps that still need execution (for Gemini to continue from). */
        val remainingSteps: List<SemanticStep>
    )

    /**
     * Attempt to replay the workflow. Returns after either completing all steps
     * or hitting a step that cannot be matched deterministically.
     *
     * @param workflow The learned workflow to replay.
     * @param currentParams Parameter values for this execution (e.g. {"item": "eggs", "quantity": "3"}).
     * @param agentState The shared agent state for logging.
     */
    suspend fun replay(
        workflow: WorkflowMemory,
        currentParams: Map<String, String>,
        agentState: AgentState
    ): ReplayResult {
        val steps = workflow.steps
        var executedCount = 0

        agentState.appendLog("REPLAY", "Replaying '${workflow.goal}' (${steps.size} steps, params=${currentParams})")

        for (i in steps.indices) {
            val step = steps[i]
            agentState.appendLog("REPLAY", "Step ${i + 1}/${steps.size}: ${step.action} ${step.targetLabel ?: step.targetRole ?: ""}")

            val result = executeStep(step, currentParams, agentState)

            when (result) {
                StepResult.SUCCESS, StepResult.NAVIGATION -> {
                    executedCount++
                    agentState.appendLog("REPLAY", "  ✓ Step ${i + 1} executed (${result.name})")
                    delay(800) // Allow UI to settle
                }
                StepResult.NO_CHANGE -> {
                    executedCount++
                    agentState.appendLog("REPLAY", "  ⚠ Step ${i + 1} executed but no UI change")
                    delay(500)
                }
                StepResult.FALLBACK -> {
                    agentState.appendLog("REPLAY", "  ✗ Step ${i + 1} cannot be matched — falling back to Gemini")
                    return ReplayResult(
                        stepsExecuted = executedCount,
                        totalSteps = steps.size,
                        fullyReplayed = false,
                        stoppedAtStep = i,
                        remainingSteps = steps.subList(i, steps.size)
                    )
                }
            }
        }

        agentState.appendLog("REPLAY", "Workflow fully replayed! ($executedCount steps)")
        return ReplayResult(
            stepsExecuted = executedCount,
            totalSteps = steps.size,
            fullyReplayed = true,
            stoppedAtStep = steps.size,
            remainingSteps = emptyList()
        )
    }

    // ──────────────────────────────────────────────
    // Individual step execution
    // ──────────────────────────────────────────────

    /** Max time to wait for a target element to appear on screen. */
    private val elementWaitTimeoutMs = 10_000L
    /** How often to poll the UI tree while waiting for an element. */
    private val pollIntervalMs = 500L

    private suspend fun executeStep(
        step: SemanticStep,
        params: Map<String, String>,
        agentState: AgentState
    ): StepResult {
        // Navigation actions don't require element matching
        when (step.action) {
            "BACK" -> { bridge.back(); return StepResult.NAVIGATION }
            "HOME" -> { bridge.home(); return StepResult.NAVIGATION }
            "RECENTS" -> { bridge.recents(); return StepResult.NAVIGATION }
            "NOTIFICATIONS" -> { bridge.notifications(); return StepResult.NAVIGATION }
            "DONE" -> return StepResult.SUCCESS
        }

        // ── Poll for target element to appear ──
        val startTime = System.currentTimeMillis()
        var match: SemanticMatcher.MatchResult? = null
        var latestUiState: UiState? = null
        var pollCount = 0

        while (System.currentTimeMillis() - startTime < elementWaitTimeoutMs) {
            pollCount++
            latestUiState = bridge.getUiState()
            val candidate = SemanticMatcher.findMatch(step, latestUiState)

            if (candidate != null && candidate.confidence >= minMatchConfidence) {
                match = candidate
                break
            }

            // Log periodic status so the user knows it's waiting, not stuck
            if (pollCount % 4 == 0) { // every ~2 seconds
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                agentState.appendLog("REPLAY", "  ⏳ Waiting for '${step.targetLabel ?: step.targetRole}' to appear (${elapsed}s)…")
            }

            delay(pollIntervalMs)
        }

        if (match == null || latestUiState == null) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            agentState.appendLog("REPLAY", "  ✗ Element '${step.targetLabel ?: step.targetRole}' not found after ${elapsed}s ($pollCount polls)")
            return StepResult.FALLBACK
        }

        val elementId = match.element.id
        agentState.appendLog("REPLAY", "  Matched '${step.targetLabel}' → '${match.element.label}' (${match.matchType}, conf=${match.confidence})")

        val beforeFingerprint = latestUiState.fingerprint

        when (step.action) {
            "CLICK" -> bridge.click(elementId)
            "LONG_CLICK" -> bridge.longClick(elementId)
            "INPUT" -> {
                val rawValue = step.inputTemplate ?: return StepResult.FALLBACK
                val resolvedValue = substituteParams(rawValue, params)
                bridge.input(elementId, resolvedValue)
            }
            "ENTER" -> bridge.enter(elementId)
            "SCROLL" -> {
                val dir = step.direction?.let { Direction.valueOf(it.uppercase()) } ?: Direction.DOWN
                bridge.scroll(elementId, dir)
            }
            "SWIPE" -> {
                val dir = step.direction?.uppercase() ?: "UP"
                when (dir) {
                    "UP" -> bridge.swipe(500f, 1500f, 500f, 500f)
                    "DOWN" -> bridge.swipe(500f, 500f, 500f, 1500f)
                    "LEFT" -> bridge.swipe(900f, 1000f, 100f, 1000f)
                    "RIGHT" -> bridge.swipe(100f, 1000f, 900f, 1000f)
                }
            }
            else -> return StepResult.FALLBACK
        }

        // Check if UI actually changed
        delay(600)
        val afterState = bridge.getUiState()
        return if (afterState.fingerprint != beforeFingerprint) {
            StepResult.SUCCESS
        } else {
            StepResult.NO_CHANGE
        }
    }

    // ──────────────────────────────────────────────
    // Parameter substitution
    // ──────────────────────────────────────────────

    /**
     * Replace {{param_name}} placeholders with actual values.
     * If a placeholder is not in [params], it's left as-is (literal value from teaching).
     */
    private fun substituteParams(template: String, params: Map<String, String>): String {
        var result = template
        for ((key, value) in params) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
