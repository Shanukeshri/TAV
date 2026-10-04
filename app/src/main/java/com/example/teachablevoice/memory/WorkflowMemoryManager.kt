package com.example.teachablevoice.memory

import android.content.Context
import android.util.Log
import com.example.teachablevoice.model.GeminiApiClient
import com.example.teachablevoice.teach.GeneralizedWorkflow
import com.example.teachablevoice.teach.SemanticStep
import org.json.JSONArray
import java.io.File
import kotlin.math.sqrt

/**
 * Manages persistent storage and semantic retrieval of learned workflows.
 *
 * Responsibilities:
 *  - Save new workflows (from the TeachSession generalisation pipeline)
 *  - Retrieve the best-matching workflow for a new request via cosine similarity
 *  - Update confidence / success / failure counts after execution
 *  - Versioning: create a new version rather than overwriting a working workflow
 *
 * Storage format: a JSON array in [memoryFile].
 * For a production app this should migrate to Room/SQLite, but the JSON approach
 * keeps the change footprint minimal while delivering full functionality.
 */
class WorkflowMemoryManager(private val context: Context, private val apiClient: GeminiApiClient) {
    private val memoryFile = File(context.filesDir, "workflows.json")

    companion object {
        private const val TAG = "MemoryManager"
    }

    // ──────────────────────────────────────────────
    // Save (new teach session → generalised workflow)
    // ──────────────────────────────────────────────

    /**
     * Save a generalised workflow produced by [WorkflowGeneralizer].
     * Generates an embedding for the goal text and persists everything.
     */
    suspend fun saveGeneralizedWorkflow(workflow: GeneralizedWorkflow) {
        if (workflow.steps.isEmpty()) {
            Log.w(TAG, "Workflow has 0 steps, skipping save.")
            return
        }
        try {
            val embeddingText = "${workflow.app} ${workflow.goal}"
            val embedding = apiClient.getEmbedding(embeddingText)
            val memory = WorkflowMemory(
                id = "wf_${System.currentTimeMillis()}",
                app = workflow.app,
                task = workflow.goal,
                goal = workflow.goal,
                embedding = embedding,
                steps = workflow.steps,
                parameters = workflow.parameters,
                version = 1,
                confidence = 1.0f,
                successCount = 1,  // the teaching itself was a success
                failureCount = 0,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis()
            )
            // As per requirements: only keep one workflow at a time
            val workflows = mutableListOf<WorkflowMemory>()
            workflows.add(memory)
            saveAllWorkflows(workflows)
            Log.i(TAG, "Saved generalised workflow: '${workflow.goal}' with ${workflow.steps.size} steps, ${workflow.parameters.size} params")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save generalised workflow", e)
        }
    }

    /**
     * Legacy save — preserves backward compatibility with existing TEACH-mode
     * code that saves raw [WorkflowStep] lists.
     */
    suspend fun saveWorkflow(app: String, task: String, steps: List<WorkflowStep>) {
        if (steps.isEmpty()) {
            Log.w(TAG, "Legacy workflow has 0 steps, skipping save.")
            return
        }
        try {
            val embedding = apiClient.getEmbedding(task)
            val semanticSteps = steps.mapIndexed { index, ws ->
                SemanticStep(
                    stepIndex = index,
                    action = ws.action,
                    elementId = ws.elementId,
                    inputTemplate = ws.value,
                    direction = ws.direction
                )
            }
            val workflow = WorkflowMemory(
                id = "wf_${System.currentTimeMillis()}",
                app = app,
                task = task,
                goal = task,
                embedding = embedding,
                steps = semanticSteps,
                version = 1,
                confidence = 0.8f, // lower than a properly generalised workflow
                successCount = 1,
                failureCount = 0,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis()
            )
            // As per requirements: only keep one workflow at a time
            val workflows = mutableListOf<WorkflowMemory>()
            workflows.add(workflow)
            saveAllWorkflows(workflows)
            Log.i(TAG, "Saved (legacy) workflow for task: $task")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save workflow", e)
        }
    }

    // ──────────────────────────────────────────────
    // Retrieval
    // ──────────────────────────────────────────────

    /**
     * Find the best matching workflow for a task string.
     *
     * @param task    Natural-language description of the requested task.
     * @param appHint Optional package name to further filter results.
     * @param threshold Minimum cosine similarity (0–1).
     * @return The best-matching [WorkflowMemory], or null.
     */
    suspend fun retrieveWorkflow(
        task: String,
        appHint: String? = null,
        threshold: Float = 0.65f
    ): WorkflowMemory? {
        try {
            val queryEmbedding = apiClient.getEmbedding(task)
            val workflows = loadAllWorkflows()

            data class Scored(val workflow: WorkflowMemory, val similarity: Float)

            val scored = workflows.mapNotNull { wf ->
                val sim = cosineSimilarity(queryEmbedding, wf.embedding)
                if (sim >= threshold) Scored(wf, sim) else null
            }

            if (scored.isEmpty()) {
                Log.i(TAG, "No matching workflow found above threshold $threshold")
                return null
            }

            // Rank by composite score: similarity × reliability
            val best = scored.maxByOrNull { it.similarity * it.workflow.reliabilityScore() }!!
            
            // If an app hint is provided, prefer same-app workflows
            val appFiltered = scored.filter { it.workflow.app == appHint }
            val finalBest = if (appHint != null && appFiltered.isNotEmpty()) {
                appFiltered.maxByOrNull { it.similarity * it.workflow.reliabilityScore() }!!
            } else {
                best
            }

            Log.i(TAG, "Retrieved workflow: '${finalBest.workflow.task}' " +
                    "(sim=${finalBest.similarity}, confidence=${finalBest.workflow.confidence}, " +
                    "success=${finalBest.workflow.successCount})")
            return finalBest.workflow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve workflow", e)
            return null
        }
    }

    // ──────────────────────────────────────────────
    // Post-execution updates
    // ──────────────────────────────────────────────

    /**
     * Record that a replayed workflow succeeded.
     */
    fun recordSuccess(workflowId: String) {
        updateWorkflow(workflowId) { wf ->
            wf.copy(
                successCount = wf.successCount + 1,
                lastUsedAt = System.currentTimeMillis(),
                confidence = minOf(1.0f, wf.confidence + 0.05f)
            )
        }
    }

    /**
     * Record that a replayed workflow failed.
     */
    fun recordFailure(workflowId: String) {
        updateWorkflow(workflowId) { wf ->
            wf.copy(
                failureCount = wf.failureCount + 1,
                lastUsedAt = System.currentTimeMillis(),
                confidence = maxOf(0.1f, wf.confidence - 0.1f)
            )
        }
    }

    /**
     * Create a new version of a workflow (e.g. after successful adaptation to a changed UI).
     */
    suspend fun createNewVersion(
        oldWorkflowId: String,
        updatedSteps: List<SemanticStep>,
        updatedGoal: String? = null
    ) {
        val workflows = loadAllWorkflows().toMutableList()
        val old = workflows.find { it.id == oldWorkflowId } ?: return
        val newVersion = old.copy(
            id = "wf_${System.currentTimeMillis()}",
            version = old.version + 1,
            steps = updatedSteps,
            goal = updatedGoal ?: old.goal,
            confidence = 1.0f,
            successCount = 1,
            failureCount = 0,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis()
        )
        workflows.add(newVersion)
        saveAllWorkflows(workflows)
        Log.i(TAG, "Created v${newVersion.version} for workflow '${old.task}'")
    }

    // ──────────────────────────────────────────────
    // Listing / management
    // ──────────────────────────────────────────────

    /**
     * Return all stored workflows (for the Memory management UI).
     */
    fun getAllWorkflows(): List<WorkflowMemory> = loadAllWorkflows()

    /**
     * Delete a workflow by ID.
     */
    fun deleteWorkflow(workflowId: String) {
        val workflows = loadAllWorkflows().toMutableList()
        workflows.removeAll { it.id == workflowId }
        saveAllWorkflows(workflows)
        Log.i(TAG, "Deleted workflow $workflowId")
    }

    /**
     * Delete all stored workflows.
     */
    fun clearAll() {
        saveAllWorkflows(emptyList())
        Log.i(TAG, "All workflows cleared")
    }

    // ──────────────────────────────────────────────
    // Internal persistence
    // ──────────────────────────────────────────────

    private fun loadAllWorkflows(): List<WorkflowMemory> {
        if (!memoryFile.exists()) return emptyList()
        return try {
            val jsonText = memoryFile.readText()
            val array = JSONArray(jsonText)
            val list = mutableListOf<WorkflowMemory>()
            for (i in 0 until array.length()) {
                list.add(WorkflowMemory.fromJson(array.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load workflows", e)
            emptyList()
        }
    }

    private fun saveAllWorkflows(workflows: List<WorkflowMemory>) {
        val array = JSONArray()
        for (wf in workflows) {
            array.put(wf.toJson())
        }
        memoryFile.writeText(array.toString(2))
    }

    private fun updateWorkflow(id: String, transform: (WorkflowMemory) -> WorkflowMemory) {
        val workflows = loadAllWorkflows().toMutableList()
        val index = workflows.indexOfFirst { it.id == id }
        if (index >= 0) {
            workflows[index] = transform(workflows[index])
            saveAllWorkflows(workflows)
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
}
