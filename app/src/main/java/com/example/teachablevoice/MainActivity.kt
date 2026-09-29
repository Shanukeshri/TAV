package com.example.teachablevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.teachablevoice.ui.theme.TeachableVoiceAutomationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeachableVoiceAutomationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MirrorScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// Utility to recursively count the exact number of nodes captured by the UIExtractor
fun countTotalNodes(node: NormalizedNode): Int {
    var count = 1
    for (child in node.children) {
        count += countTotalNodes(child)
    }
    return count
}

@Composable
fun MirrorScreen(modifier: Modifier = Modifier) {
    val snapshot by UiMirrorRepository.snapshotFlow.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (snapshot == null) {
            Text(
                text = "Waiting for a third-party app...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Open Chrome or another supported app to capture its UI."
            )
        } else {
            val s = snapshot!!
            val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.timestamp))
            val totalCaptured = remember(s.rootNode) { countTotalNodes(s.rootNode) }
            var renderedNodesCount by remember { mutableStateOf(0) }

            Text(
                text = "Mirroring: ${s.packageName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Last captured: $date",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            // Helpful telemetry to monitor our performance limits
            Text(
                text = "Captured nodes: $totalCaptured",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Rendered nodes: $renderedNodesCount",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            MirrorRenderer(
                rootNode = s.rootNode,
                onRenderCount = { count ->
                    renderedNodesCount = count
                },
                onNodeClicked = { nodeId ->
                    MirrorInteractionController.requestClick(nodeId)
                }
            )
        }
    }
}