package com.example.teachablevoice.memory

import android.content.Context
import android.util.Log
import com.example.teachablevoice.model.GeminiApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

class WorkflowMemoryManager(private val context: Context, private val apiClient: GeminiApiClient) {
    private val memoryFile = File(context.filesDir, "workflows.json")

    companion object {
        private const val TAG = "MemoryManager"
    }

    suspend fun saveWorkflow(app: String, task: String, steps: List<WorkflowStep>) {
        try {
            val embedding = apiClient.getEmbedding(task)
            val workflow = WorkflowMemory(
                id = "wf_" + System.currentTimeMillis(),
                app = app,
                task = task,
                embedding = embedding,
                steps = steps
            )
            
            val workflows = loadAllWorkflows().toMutableList()
            workflows.add(workflow)
            
            saveAllWorkflows(workflows)
            Log.i(TAG, "Saved workflow for task: \$task")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save workflow", e)
        }
    }

    suspend fun retrieveWorkflow(task: String, threshold: Float = 0.7f): WorkflowMemory? {
        try {
            val queryEmbedding = apiClient.getEmbedding(task)
            val workflows = loadAllWorkflows()
            
            var bestMatch: WorkflowMemory? = null
            var highestSimilarity = -1f
            
            for (wf in workflows) {
                val similarity = cosineSimilarity(queryEmbedding, wf.embedding)
                if (similarity > highestSimilarity) {
                    highestSimilarity = similarity
                    bestMatch = wf
                }
            }
            
            if (highestSimilarity >= threshold) {
                Log.i(TAG, "Found matching workflow: \${bestMatch?.task} with similarity \$highestSimilarity")
                return bestMatch
            } else {
                Log.i(TAG, "No matching workflow found. Highest similarity: \$highestSimilarity")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve workflow", e)
            return null
        }
    }

    private fun loadAllWorkflows(): List<WorkflowMemory> {
        if (!memoryFile.exists()) return emptyList()
        try {
            val jsonText = memoryFile.readText()
            val array = JSONArray(jsonText)
            val list = mutableListOf<WorkflowMemory>()
            for (i in 0 until array.length()) {
                list.add(WorkflowMemory.fromJson(array.getJSONObject(i)))
            }
            return list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load workflows", e)
            return emptyList()
        }
    }

    private fun saveAllWorkflows(workflows: List<WorkflowMemory>) {
        val array = JSONArray()
        for (wf in workflows) {
            array.put(wf.toJson())
        }
        memoryFile.writeText(array.toString(2))
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
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
