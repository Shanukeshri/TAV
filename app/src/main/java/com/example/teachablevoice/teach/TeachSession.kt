package com.example.teachablevoice.teach

import android.util.Log
import com.example.teachablevoice.agent.AgentAction
import com.example.teachablevoice.agent.UiElement
import com.example.teachablevoice.agent.UiState

/**
 * Records the raw trajectory of actions taken during a Teach-mode session.
 *
 * Each recorded entry captures:
 *  - the UI state **before** the action
 *  - the agent action
 *  - the UI element that was interacted with (semantic attributes)
 *
 * After the session completes successfully, the raw trajectory is fed to
 * [WorkflowGeneralizer] which asks Gemini to produce a parameterised,
 * semantically-described, reusable workflow.
 */
class TeachSession(
    val app: String,
    val originalCommand: String
) {
    companion object {
        private const val TAG = "TeachSession"
    }

    /** Whether this session is actively recording. */
    var isRecording: Boolean = false
        private set

    /** Raw trajectory entries collected during the session. */
    private val _trajectory = mutableListOf<TrajectoryEntry>()
    val trajectory: List<TrajectoryEntry> get() = _trajectory.toList()

    fun start() {
        isRecording = true
        _trajectory.clear()
        Log.i(TAG, "Teaching session started for app=$app command='$originalCommand'")
    }

    /**
     * Record a single agent step: the action that was taken and the UI context
     * in which it was taken.
     */
    fun recordStep(
        uiStateBefore: UiState,
        action: AgentAction,
        uiStateAfter: UiState
    ) {
        if (!isRecording) return

        // Resolve the element the action targeted (if any)
        val targetElement: UiElement? = action.elementId?.let { id ->
            uiStateBefore.elements.find { it.id == id }
        }

        val entry = TrajectoryEntry(
            stepIndex = _trajectory.size,
            action = action.action.name,
            elementId = action.elementId,
            elementRole = targetElement?.role,
            elementLabel = targetElement?.label,
            elementHint = targetElement?.hint,
            elementIsEditable = targetElement?.isEditable ?: false,
            inputValue = action.value,
            direction = action.direction,
            screenAppBefore = uiStateBefore.app,
            screenFingerprintBefore = uiStateBefore.fingerprint,
            screenAppAfter = uiStateAfter.app,
            screenFingerprintAfter = uiStateAfter.fingerprint,
            uiChangedSuccessfully = uiStateBefore.fingerprint != uiStateAfter.fingerprint
        )

        _trajectory.add(entry)
        Log.i(TAG, "Recorded step ${entry.stepIndex}: ${entry.action} " +
                "element=${entry.elementLabel ?: entry.elementId ?: "none"} " +
                "changed=${entry.uiChangedSuccessfully}")
    }

    fun stop() {
        isRecording = false
        Log.i(TAG, "Teaching session stopped. ${_trajectory.size} steps recorded.")
    }

    /**
     * Convert the entire recorded trajectory into a textual summary that can be
     * sent to Gemini for generalisation.
     */
    fun toSummaryText(): String {
        val sb = StringBuilder()
        sb.appendLine("App: $app")
        sb.appendLine("Original Command: $originalCommand")
        sb.appendLine("Steps recorded: ${_trajectory.size}")
        sb.appendLine()
        for (entry in _trajectory) {
            sb.appendLine("Step ${entry.stepIndex}:")
            sb.appendLine("  Action: ${entry.action}")
            if (entry.elementRole != null) sb.appendLine("  Element Role: ${entry.elementRole}")
            if (entry.elementLabel != null) sb.appendLine("  Element Label: ${entry.elementLabel}")
            if (entry.elementHint != null) sb.appendLine("  Element Hint: ${entry.elementHint}")
            if (entry.inputValue != null) sb.appendLine("  Input Value: ${entry.inputValue}")
            if (entry.direction != null) sb.appendLine("  Direction: ${entry.direction}")
            sb.appendLine("  UI Changed: ${entry.uiChangedSuccessfully}")
            sb.appendLine()
        }
        return sb.toString()
    }
}

/**
 * A single raw entry in the teaching trajectory.
 */
data class TrajectoryEntry(
    val stepIndex: Int,
    val action: String,
    val elementId: String?,
    val elementRole: String?,
    val elementLabel: String?,
    val elementHint: String?,
    val elementIsEditable: Boolean,
    val inputValue: String?,
    val direction: String?,
    val screenAppBefore: String,
    val screenFingerprintBefore: String,
    val screenAppAfter: String,
    val screenFingerprintAfter: String,
    val uiChangedSuccessfully: Boolean
)
