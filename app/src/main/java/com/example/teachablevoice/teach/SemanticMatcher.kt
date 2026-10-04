package com.example.teachablevoice.teach

import android.util.Log
import com.example.teachablevoice.agent.UiElement
import com.example.teachablevoice.agent.UiState

/**
 * Deterministic UI element matcher that finds the best on-screen element
 * matching a semantic workflow step — **without calling Gemini**.
 *
 * Matching strategy (executed in priority order):
 *  1. Exact role + exact label match
 *  2. Exact role + label-contains match
 *  3. Role match + hint match
 *  4. Label similarity (normalized) across all elements
 *  5. Fallback to original elementId (as a hint only)
 *
 * Only when the matcher returns null should the caller escalate to Gemini.
 * This provides a major speed boost for replayed workflows.
 */
object SemanticMatcher {

    private const val TAG = "SemanticMatcher"

    data class MatchResult(
        val element: UiElement,
        val confidence: Float,     // 0.0 – 1.0
        val matchType: String      // "exact", "contains", "hint", "fuzzy", "id_fallback"
    )

    /**
     * Find the best matching element on the current screen for a semantic step.
     *
     * @param step The workflow step describing what to find.
     * @param uiState The current screen's UI state.
     * @return A [MatchResult] or null if no reasonable match is found.
     */
    fun findMatch(step: SemanticStep, uiState: UiState): MatchResult? {
        val candidates = uiState.elements

        // Strategy 1: exact role + exact label
        if (step.targetRole != null && step.targetLabel != null) {
            val exact = candidates.find {
                it.role.equals(step.targetRole, ignoreCase = true) &&
                it.label.equals(step.targetLabel, ignoreCase = true)
            }
            if (exact != null) {
                Log.d(TAG, "Exact match: role=${step.targetRole} label=${step.targetLabel}")
                return MatchResult(exact, 1.0f, "exact")
            }
        }

        // Strategy 2: exact role + label-contains
        if (step.targetRole != null && step.targetLabel != null) {
            val contains = candidates.find {
                it.role.equals(step.targetRole, ignoreCase = true) &&
                (it.label.contains(step.targetLabel!!, ignoreCase = true) ||
                 step.targetLabel!!.contains(it.label, ignoreCase = true))
            }
            if (contains != null) {
                Log.d(TAG, "Contains match: role=${step.targetRole} label~=${step.targetLabel}")
                return MatchResult(contains, 0.85f, "contains")
            }
        }

        // Strategy 3: role + hint match
        if (step.targetRole != null && step.targetHint != null) {
            val hintMatch = candidates.find {
                it.role.equals(step.targetRole, ignoreCase = true) &&
                it.hint.contains(step.targetHint!!, ignoreCase = true)
            }
            if (hintMatch != null) {
                Log.d(TAG, "Hint match: role=${step.targetRole} hint~=${step.targetHint}")
                return MatchResult(hintMatch, 0.80f, "hint")
            }
        }

        // Strategy 4: fuzzy label match across all elements (normalize + compare)
        if (step.targetLabel != null) {
            val normalizedTarget = normalize(step.targetLabel!!)
            var bestScore = 0f
            var bestElement: UiElement? = null

            for (el in candidates) {
                val normalizedLabel = normalize(el.label)
                val score = similarity(normalizedTarget, normalizedLabel)
                if (score > bestScore && score >= 0.6f) {
                    bestScore = score
                    bestElement = el
                }
            }

            if (bestElement != null) {
                Log.d(TAG, "Fuzzy match: '${step.targetLabel}' ~ '${bestElement.label}' (score=$bestScore)")
                return MatchResult(bestElement, bestScore * 0.9f, "fuzzy")
            }
        }

        // Strategy 5: fallback to original element ID (least reliable)
        if (step.elementId != null) {
            val idMatch = candidates.find { it.id == step.elementId }
            if (idMatch != null) {
                Log.d(TAG, "ID fallback match: ${step.elementId}")
                return MatchResult(idMatch, 0.5f, "id_fallback")
            }
        }

        Log.d(TAG, "No match found for step: ${step.action} ${step.targetLabel ?: step.targetRole ?: "?"}")
        return null
    }

    // ──────────────────────────────────────────────
    // String normalization and similarity
    // ──────────────────────────────────────────────

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Simple word-overlap similarity (Jaccard-like).
     * Returns 0.0 – 1.0.
     */
    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val wordsA = a.split(" ").toSet()
        val wordsB = b.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB).size.toFloat()
        val union = wordsA.union(wordsB).size.toFloat()
        if (union == 0f) return 0f
        return intersection / union
    }
}
