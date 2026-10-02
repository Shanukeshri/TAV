package com.example.teachablevoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.teachablevoice.memory.WorkflowMemoryManager
import com.example.teachablevoice.memory.WorkflowStep
import com.example.teachablevoice.model.GeminiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentWorkflowTest {
    @Test
    fun testTeachableWorkflowE2E() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val apiKey = com.example.teachablevoice.BuildConfig.GEMINI_API_KEY
        val backend = GeminiApiClient(apiKey)
        backend.load()
        
        // Clear old database for test purity
        val dbFile = File(appContext.filesDir, "workflows.json")
        if (dbFile.exists()) dbFile.delete()

        val memoryManager = WorkflowMemoryManager(appContext, backend)

        // 1. Learning Phase
        val teachApp = "com.amazon.mShop.android.shopping"
        val teachGoal = "how to add to cart shoes on amazon"
        val steps = listOf(
            WorkflowStep("CLICK", "search_bar", null, null),
            WorkflowStep("SET_TEXT", "search_bar", "shoes", null),
            WorkflowStep("CLICK", "search_button", null, null),
            WorkflowStep("CLICK", "product_1", null, null),
            WorkflowStep("CLICK", "add_to_cart", null, null)
        )
        memoryManager.saveWorkflow(teachApp, teachGoal, steps)

        // Wait a little bit just in case
        kotlinx.coroutines.delay(1000)

        // 2. Automate Phase
        val testGoal = "add earphones to cart on amazon"
        val retrieved = memoryManager.retrieveWorkflow(testGoal)

        assertNotNull("Workflow should be retrieved due to semantic similarity", retrieved)
        assertEquals("how to add to cart shoes on amazon", retrieved?.task)
        assertEquals(5, retrieved?.steps?.size)
        assertEquals("shoes", retrieved?.steps?.get(1)?.value)
        
        println("E2E Test Passed: Semantic memory retrieved successfully!")
    }
}
