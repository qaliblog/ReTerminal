package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.launch
import com.rk.terminal.llm.LlmProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import com.rk.libcommons.application
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontWeight

private data class ChatMessage(val role: String, val content: String)

@Composable
fun ChatView(mainActivityActivity: MainActivity) {
    val sessionId = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first ?: return

    // Chat Session Manager state
    val chatRoot = remember { File(application!!.filesDir, "chat").apply { mkdirs() } }
    val chatSessions = remember { mutableStateOf(chatRoot.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()) }
    val currentChatId = remember(sessionId) { mutableStateOf(sessionId) }
    var showChatManager by remember { mutableStateOf(false) }
    val newChatName = remember { mutableStateOf("") }

    fun refreshChatSessions() {
        chatSessions.value = chatRoot.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    // Messages storage keyed by currentChatId
    val messagesByChat = remember { mutableMapOf<String, MutableList<ChatMessage>>() }
    val messages = messagesByChat.getOrPut(currentChatId.value) { mutableStateListOf() }

    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Chat history persistence under chat/<chatId>/history.json
    val chatDir = remember(currentChatId.value) { File(application!!.filesDir, "chat/${currentChatId.value}").apply { mkdirs() } }
    val historyFile = remember(currentChatId.value) { File(chatDir, "history.json") }

    fun saveHistory() {
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            historyFile.writeText(arr.toString(2))
        }
    }

    LaunchedEffect(currentChatId.value) {
        // Load history for selected chat
        messages.clear()
        runCatching {
            if (historyFile.exists()) {
                val txt = historyFile.readText()
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    messages.add(ChatMessage(o.optString("role"), o.optString("content")))
                }
            }
        }
    }

    DisposableEffect(messages.size, currentChatId.value) {
        onDispose { saveHistory() }
    }

    // Agent state
    val activePlan = remember(currentChatId.value) { mutableStateOf<AgentOrchestrator.Plan?>(null) }
    val isPlanning = remember { mutableStateOf(false) }
    var autoRun by remember { mutableStateOf(false) }

    // Single agent instance per chat session id
    val agent = remember(currentChatId.value) {
        AgentOrchestrator(
            context = mainActivityActivity,
            sessionId = currentChatId.value,
            workingDirProvider = {
                val svc2 = mainActivityActivity.sessionBinder?.getService()
                svc2?.fileManagerWorkingDirBySession?.get(sessionId) ?: "/sdcard"
            }
        )
    }

    // Workspace selector state
    val svc = mainActivityActivity.sessionBinder?.getService()
    val currentWd = remember { mutableStateOf(svc?.fileManagerWorkingDirBySession?.get(sessionId) ?: "/sdcard") }
    var showWdMenu by remember { mutableStateOf(false) }

    // Folder picker dialog state
    var showFolderPicker by remember { mutableStateOf(false) }
    val pickerPath = remember { mutableStateOf(currentWd.value) }

    fun listDirs(path: String): List<File> {
        val d = File(path)
        return d.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    // Predefined quick paths
    val quickPaths = listOf("/sdcard", application!!.filesDir.absolutePath)

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
                        SelectionContainer { Text(text = msg.content) }
                    }
                }
            }
        }

        // Plan panel
        if (hasPlan) {
            val plan = activePlan.value!!
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Plan", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auto-run")
                            Switch(checked = autoRun, onCheckedChange = { autoRun = it })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val statuses = agent.getPlanStatuses()
                    plan.tasks.take(12).forEach { t ->
                        val st = statuses[t.id] ?: "pending"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.id}: ${t.description}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(onClick = {}, label = { Text(st) }, colors = AssistChipDefaults.assistChipColors())
                                    if (!t.category.isNullOrBlank()) AssistChip(onClick = {}, label = { Text(t.category!!) })
                                }
                            }
                            Button(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val success = agent.executeNextTask(plan) { s ->
                                        scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                                    }
                                    if (!success) {
                                        val updated = agent.requestUpdatedPlan(plan)
                                        if (updated != null) {
                                            scope.launch(Dispatchers.Main) { activePlan.value = updated; postStatus("Plan updated."); saveHistory() }
                                        }
                                    } else if (autoRun) {
                                        // trigger next automatically
                                        this.launch { /* no-op, user can press Proceed or keep auto-run */ }
                                    }
                                }
                            }) { Text("Run") }
                        }
                    }
                    if (plan.tasks.size > 12) {
                        Text("… and ${plan.tasks.size - 12} more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Row 1: Chat session selector + Input + Send
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Chat session manager
            IconButton(onClick = { showChatManager = true }) {
                Icon(Icons.Default.Chat, contentDescription = "Chat sessions")
            }

            // Workspace selector button
            IconButton(onClick = { showWdMenu = true }) {
                Icon(Icons.Default.Folder, contentDescription = "Select workspace")
            }
            DropdownMenu(expanded = showWdMenu, onDismissRequest = { showWdMenu = false }) {
                quickPaths.forEach { path ->
                    DropdownMenuItem(
                        text = { Text(path) },
                        onClick = {
                            currentWd.value = path
                            svc?.fileManagerWorkingDirBySession?.set(sessionId, path)
                            showWdMenu = false
                            postStatus("Workspace set to: $path (agent will use absolute paths)")
                        }
                    )
                }
                // Parent of current
                val parent = File(currentWd.value).parentFile
                if (parent != null && parent.exists()) {
                    DropdownMenuItem(
                        text = { Text(".. (${parent.absolutePath})") },
                        onClick = {
                            val path = parent.absolutePath
                            currentWd.value = path
                            svc?.fileManagerWorkingDirBySession?.set(sessionId, path)
                            showWdMenu = false
                            postStatus("Workspace set to: $path (agent will use absolute paths)")
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Browse…") },
                    onClick = {
                        pickerPath.value = currentWd.value
                        showWdMenu = false
                        showFolderPicker = true
                    }
                )
            }
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
                    saveHistory()

                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            LlmProvider.current().generate(messages.map { com.rk.terminal.llm.LlmMessage(it.role, it.content) }).collect { token ->
                                scope.launch(Dispatchers.Main) {
                                    val lastIndex = messages.indexOfLast { it.role == "assistant" }
                                    if (lastIndex != -1) {
                                        val current = messages[lastIndex]
                                        val nextContent = if (current.content == "…") token else current.content + token
                                        messages[lastIndex] = current.copy(content = nextContent)
                                        saveHistory()
                                    }
                                }
                            }
                        }.onFailure { e ->
                            scope.launch(Dispatchers.Main) {
                                val lastIndex = messages.indexOfLast { it.role == "assistant" }
                                val err = e.message ?: e.toString()
                                if (lastIndex != -1) {
                                    messages[lastIndex] = ChatMessage("assistant", "Error: $err")
                                } else {
                                    messages.add(ChatMessage("assistant", "Error: $err"))
                                }
                                saveHistory()
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
                            messages.add(ChatMessage("assistant", "Planning… (stateless, JSON-only)"))
                            val plan = agent.generatePlan(goal)
                            if (plan == null) {
                                postStatus("Could not parse plan from AI.")
                                return@launch
                            }
                            scope.launch(Dispatchers.Main) { activePlan.value = plan }
                            postStatus("Plan ready: ${plan.tasks.size} task(s). Press Proceed to run the first task.")
                            saveHistory()
                        } catch (e: Exception) {
                            postStatus("Agent error: ${e.message}")
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
                                scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                            }
                            if (!success) {
                                scope.launch(Dispatchers.Main) { postStatus("Attempting to update plan based on the error or loop prevention…"); saveHistory() }
                                val updated = agent.requestUpdatedPlan(plan)
                                if (updated == null) {
                                    scope.launch(Dispatchers.Main) { postStatus("Could not update plan."); saveHistory() }
                                } else {
                                    scope.launch(Dispatchers.Main) {
                                        activePlan.value = updated
                                        postStatus("Plan updated: ${updated.tasks.size} task(s). Press Proceed for next step.")
                                        saveHistory()
                                    }
                                }
                            }
                            saveHistory()
                        } catch (e: Exception) {
                            postStatus("Agent error: ${e.message}")
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
                                scope.launch(Dispatchers.Main) { activePlan.value = updated }
                                postStatus("Plan updated: ${updated.tasks.size} task(s). Press Proceed for next step.")
                            }
                            saveHistory()
                        } catch (e: Exception) {
                            postStatus("Agent error: ${e.message}")
                        }
                    }
                },
                enabled = hasPlan
            ) { Text("Update Plan") }
        }

        // Folder picker dialog
        if (showFolderPicker) {
            AlertDialog(
                onDismissRequest = { showFolderPicker = false },
                title = { Text("Select Workspace") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pickerPath.value)
                        val dirs = listDirs(pickerPath.value)
                        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            items(dirs) { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pickerPath.value = dir.absolutePath }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Text(text = dir.name, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val path = pickerPath.value
                        currentWd.value = path
                        svc?.fileManagerWorkingDirBySession?.set(sessionId, path)
                        showFolderPicker = false
                        postStatus("Workspace set to: $path (agent will use absolute paths)")
                    }) { Text("Use this folder") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val parent = File(pickerPath.value).parentFile
                            if (parent != null && parent.exists()) pickerPath.value = parent.absolutePath
                        }) { Text("Up") }
                        TextButton(onClick = { showFolderPicker = false }) { Text("Cancel") }
                    }
                }
            )
        }

        // Chat session manager dialog
        if (showChatManager) {
            AlertDialog(
                onDismissRequest = { showChatManager = false },
                title = { Text("Chat Sessions") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Existing sessions
                        val sessions = chatSessions.value
                        if (sessions.isEmpty()) {
                            Text("No chat sessions yet.")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                items(sessions) { cid ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                currentChatId.value = cid
                                                showChatManager = false
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isCurrent = cid == currentChatId.value
                                        Text(
                                            text = if (isCurrent) "$cid (current)" else cid,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        // New session name input
                        OutlinedTextField(
                            value = newChatName.value,
                            onValueChange = { newChatName.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("New session name…") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newChatName.value.trim().ifBlank { "chat-${System.currentTimeMillis()}" }
                        File(chatRoot, name).mkdirs()
                        refreshChatSessions()
                        currentChatId.value = name
                        newChatName.value = ""
                        showChatManager = false
                    }) { Text("Create / Switch") }
                },
                dismissButton = {
                    TextButton(onClick = { showChatManager = false }) { Text("Close") }
                }
            )
        }
    }
}