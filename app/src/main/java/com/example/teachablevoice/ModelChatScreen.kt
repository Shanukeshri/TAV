package com.example.teachablevoice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.launch

data class ChatMessage(val isUser: Boolean, val text: String)

@Composable
fun ModelChatScreen() {
    var prompt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Model Chat", fontSize = 24.sp, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { message ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(
                        color = if (message.isUser) Color(0xFF7C4DFF) else Color(0xFF1E1E30),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp)
                    ) {
                        Text(message.text, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            if (isLoading) {
                item {
                    Text("Model is typing...", color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter prompt...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF7C4DFF),
                    unfocusedBorderColor = Color(0xFF2A2A45)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (prompt.isNotBlank() && !isLoading) {
                        val userText = prompt
                        messages = messages + ChatMessage(isUser = true, text = userText)
                        prompt = ""
                        isLoading = true
                        
                        scope.launch {
                            try {
                                if (!ModelManager.backend.isLoaded()) {
                                    messages = messages + ChatMessage(isUser = false, text = "Loading model, this might take a moment...")
                                    ModelManager.backend.load()
                                }
                                val response = ModelManager.backend.generate(userText)
                                // Remove the loading message if present
                                messages = messages.filter { it.text != "Loading model, this might take a moment..." } + ChatMessage(isUser = false, text = response)
                            } catch (e: Exception) {
                                messages = messages.filter { it.text != "Loading model, this might take a moment..." } + ChatMessage(isUser = false, text = "Error: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = !isLoading && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
            ) {
                Text("Send", color = Color.White)
            }
        }
    }
}
