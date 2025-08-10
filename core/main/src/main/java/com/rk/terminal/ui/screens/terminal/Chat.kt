package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.terminal.ui.activities.terminal.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import com.rk.terminal.llm.LlmProvider

private data class ChatMessage(val role: String, val content: String)

@Composable
fun ChatView(mainActivityActivity: MainActivity) {
    val sessionId = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first ?: return

    val messagesBySession = remember { mutableMapOf<String, MutableList<ChatMessage>>() }
    val messages = messagesBySession.getOrPut(sessionId) { mutableStateListOf() }

    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Agent state
    val activePlan = remember { mutableStateOf<AgentOrchestrator.Plan?>(null) }
    val isPlanning = remember { mutableStateOf(false) }

    // Single agent instance per session
    val agent = remember(sessionId) {
        AgentOrchestrator(
            context = mainActivityActivity,
            sessionId = sessionId,
            workingDirProvider = {
                val svc = mainActivityActivity.sessionBinder?.getService()
                svc?.fileManagerWorkingDirBySession?.get(sessionId) ?: "/sdcard"
            }
        )
    }

    fun postStatus(s: String) {
        messages.add(ChatMessage("assistant", s))
    }

    val hasPlan = activePlan.value != null
    val canProceed = hasPlan && agent.getNextPendingTask(activePlan.value!!) != null

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = false
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(text = if (isUser) "You" else "Assistant", style = MaterialTheme.typography.labelSmall)
                        Text(text = msg.content)
                    }
                }
            }
        }

        // Row 1: Input + Send
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type a message…") }
            )
            IconButton(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isEmpty()) return@IconButton
                    input = ""
                    messages.add(ChatMessage("user", prompt))
                    messages.add(ChatMessage("assistant", "…"))

                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            LlmProvider.current().generate(messages.map { com.rk.terminal.llm.LlmMessage(it.role, it.content) }).collect { token ->
                                scope.launch(Dispatchers.Main) {
                                    val lastIndex = messages.indexOfLast { it.role == "assistant" }
                                    if (lastIndex != -1) {
                                        val current = messages[lastIndex]
                                        val nextContent = if (current.content == "…") token else current.content + token
                                        messages[lastIndex] = current.copy(content = nextContent)
                                    }
                                }
                            }
                        }.onFailure { e ->
                            scope.launch(Dispatchers.Main) {
                                val lastIndex = messages.indexOfLast { it.role == "assistant" }
                                val err = e.message ?: e.toString()
                                if (lastIndex != -1) {
                                    messages[lastIndex] = ChatMessage("assistant", "Error: ${'$'}err")
                                } else {
                                    messages.add(ChatMessage("assistant", "Error: ${'$'}err"))
                                }
                            }
                        }
                    }
                }
            ) { Icon(Icons.Default.Send, contentDescription = "Send") }
        }

        // Row 2: Agent controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val goal = input.trim()
                    if (goal.isEmpty() || isPlanning.value) return@Button
                    input = ""
                    messages.add(ChatMessage("user", goal))
                    scope.launch(Dispatchers.IO) {
                        try {
                            isPlanning.value = true
                            messages.add(ChatMessage("assistant", "Planning…"))
                            val plan = agent.generatePlan(goal)
                            if (plan == null) {
                                postStatus("Could not parse plan from AI.")
                                return@launch
                            }
                            activePlan.value = plan
                            postStatus("Plan ready: ${'$'}{plan.tasks.size} task(s). Press Proceed to run the first task.")
                        } catch (e: Exception) {
                            postStatus("Agent error: ${'$'}{e.message}")
                        } finally {
                            isPlanning.value = false
                        }
                    }
                },
                enabled = input.isNotBlank() && !isPlanning.value
            ) { Text("Plan") }

            Button(
                onClick = {
                    val plan = activePlan.value ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        try {
                            val success = agent.executeNextTask(plan) { s ->
                                scope.launch(Dispatchers.Main) { postStatus(s) }
                            }
                            if (!success) {
                                postStatus("No pending task or step failed.")
                            }
                        } catch (e: Exception) {
                            postStatus("Agent error: ${'$'}{e.message}")
                        }
                    }
                },
                enabled = canProceed
            ) { Text("Proceed") }

            Button(
                onClick = {
                    val plan = activePlan.value ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        try {
                            messages.add(ChatMessage("assistant", "Updating plan…"))
                            val updated = agent.requestUpdatedPlan(plan)
                            if (updated == null) {
                                postStatus("Could not update plan.")
                            } else {
                                activePlan.value = updated
                                postStatus("Plan updated: ${'$'}{updated.tasks.size} task(s). Press Proceed for next step.")
                            }
                        } catch (e: Exception) {
                            postStatus("Agent error: ${'$'}{e.message}")
                        }
                    }
                },
                enabled = hasPlan
            ) { Text("Update Plan") }
        }
    }
}