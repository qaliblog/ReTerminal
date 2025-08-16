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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalFocusManager
import com.rk.settings.Settings
import androidx.compose.foundation.layout.PaddingValues
import com.rk.terminal.ui.screens.terminal.MainShell

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

    // Tab state: 0 = Chat, 1 = Git
    var selectedTab by remember { mutableStateOf(0) }

    // Chat history persistence under chat/<chatId>/history.json
    val chatDir = remember(currentChatId.value) { File(application!!.filesDir, "chat/${currentChatId.value}").apply { mkdirs() } }
    val historyFile = remember(currentChatId.value) { File(chatDir, "history.json") }
    val prefsFile = remember { File(application!!.filesDir, "chat_prefs.json") }

    fun saveHistory() {
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            historyFile.writeText(arr.toString(2))
        }
    }

    // Persist selected chat and scroll positions and settings
    fun savePrefs(selectedChatId: String, firstVisibleIndex: Int, firstVisibleOffset: Int, sendMode: String, gitBin: String? = null, gitPath: String? = null) {
        runCatching {
            val obj = runCatching { JSONObject(prefsFile.takeIf { it.exists() }?.readText() ?: "{}") }.getOrElse { JSONObject() }
            obj.put("selected_chat", selectedChatId)
            obj.put("scroll_index", firstVisibleIndex)
            obj.put("scroll_offset", firstVisibleOffset)
            obj.put("send_mode", sendMode)
            if (gitBin != null) obj.put("git_bin", gitBin)
            if (gitPath != null) obj.put("git_path", gitPath)
            prefsFile.writeText(obj.toString(2))
        }
    }
    fun loadPrefs(): JSONObject = runCatching { JSONObject(prefsFile.takeIf { it.exists() }?.readText() ?: "{}") }.getOrElse { JSONObject() }

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
    var searchAssist by remember { mutableStateOf(Settings.helper_agent_enabled || Settings.researcher_agent_enabled) }

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

    // Workspace selector state (Chat tab)
    val svc = mainActivityActivity.sessionBinder?.getService()
    val currentWd = remember { mutableStateOf(svc?.fileManagerWorkingDirBySession?.get(sessionId) ?: "/sdcard") }
    var showWdMenu by remember { mutableStateOf(false) }

    // Folder picker dialog state (Chat tab)
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

    // Git tab state
    val gitProjectPath = remember { mutableStateOf(currentWd.value) }
    var showGitFolderPicker by remember { mutableStateOf(false) }
    val gitPickerPath = remember { mutableStateOf(gitProjectPath.value) }
    val gitCommitMsg = remember { mutableStateOf("chore: save via app") }
    val gitLog = remember { mutableStateListOf<String>() }
    val initialPrefs = loadPrefs()
    val envPath = System.getenv("PATH") ?: ""
    var gitBin by remember { mutableStateOf(initialPrefs.optString("git_bin").ifBlank { "git" }) }
    var gitPath by remember { mutableStateOf(initialPrefs.optString("git_path").ifBlank { envPath }) }

    fun appendGitLog(s: String) { gitLog.add(s) }

    // Normalize Git settings: if user pasted an absolute git binary path into PATH,
    // move it to gitBin and keep PATH as the directory
    fun sanitizeGitSettings() {
        val pathField = (gitPath ?: "").trim()
        if (pathField.isNotBlank()) {
            val looksLikeBinary = pathField.endsWith("/git") && !java.io.File(pathField).isDirectory
            if (looksLikeBinary) {
                gitBin = pathField
                gitPath = java.io.File(pathField).parent
                appendGitLog("Detected git binary path; using $gitBin and PATH=${gitPath}")
            }
        }
        if (gitBin.isBlank()) gitBin = "git"
        // If gitBin looks like an absolute file but does not exist, fall back to 'git'
        runCatching {
            if (gitBin.contains('/') && !java.io.File(gitBin).exists()) {
                appendGitLog("git binary not found at $gitBin; falling back to 'git' on PATH")
                gitBin = "git"
            }
            // If PATH is empty and gitBin is an absolute path, default PATH to its parent
            if ((gitPath.isNullOrBlank()) && gitBin.contains('/')) {
                gitPath = java.io.File(gitBin).parent
            }
        }
    }

    fun isGitRepo(path: String): Boolean = File(path, ".git").exists()

    fun runGitCommand(path: String, command: String, onDone: (Int, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val pathExport = if (!gitPath.isNullOrBlank()) "export PATH=\"$gitPath:\$PATH\"; " else ""
                val cmd = pathExport + command
                val result = MainShell.execInMainSession(mainActivityActivity, path, cmd, 60_000L)
                val out = result.first
                val exit = result.second
                val body = out.lineSequence().filter { !it.startsWith("EXIT_CODE=") }.joinToString("\n").trim()
                val finalOut = if (body.isBlank()) "(no output)" else body
                launch(Dispatchers.Main) { onDone(exit, finalOut.ifBlank { "exit=$exit" }) }
            } catch (e: Exception) {
                val msg = (e.message ?: e.toString())
                launch(Dispatchers.Main) { onDone(-1, "error: $msg") }
            }
        }
    }

    // Send mode dropdown: think (default) | plan | chat
    val sendModes = listOf("think", "plan", "chat")
    var sendMode by remember {
        mutableStateOf(loadPrefs().optString("send_mode").ifBlank { "think" })
    }
    var showSendMenu by remember { mutableStateOf(false) }

    // Scroll state with persistence
    val listState = rememberLazyListState()
    LaunchedEffect(currentChatId.value) {
        val p = loadPrefs()
        if (p.optString("selected_chat") == currentChatId.value) {
            val idx = p.optInt("scroll_index", 0)
            val off = p.optInt("scroll_offset", 0)
            runCatching { listState.scrollToItem(idx, off) }
        }
    }

    // Back key hides keyboard like Termux when input focused
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = isInputFocused) {
        focusManager.clearFocus(force = true)
    }

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Chat") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Git") })
        }

        if (selectedTab == 1) {
            // =============== Git Panel ===============
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Version Control", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Project: ${gitProjectPath.value}")
                                val repoStatus = if (isGitRepo(gitProjectPath.value)) "Initialized" else "Not initialized"
                                AssistChip(onClick = {}, label = { Text(repoStatus) })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showGitFolderPicker = true }) { Icon(Icons.Default.Folder, contentDescription = "Select project") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = gitBin,
                                onValueChange = {
                                    gitBin = it
                                    savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode, gitBin = gitBin)
                                },
                                singleLine = true,
                                label = { Text("Git binary (git or /path/to/git)") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = gitPath ?: "",
                                onValueChange = {
                                    gitPath = it
                                    savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode, gitPath = gitPath)
                                },
                                singleLine = true,
                                label = { Text("PATH (e.g., /usr/bin:/bin)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                sanitizeGitSettings()
                                val cmd = "$gitBin --version"
                                appendGitLog("$ $cmd")
                                runGitCommand(gitProjectPath.value, cmd) { code, out -> appendGitLog(out.ifBlank { "exit=$code" }) }
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Test Git") }
                        }
                        OutlinedTextField(
                            value = gitCommitMsg.value,
                            onValueChange = { gitCommitMsg.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Commit message…") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                sanitizeGitSettings()
                                val path = gitProjectPath.value
                                appendGitLog("$ $gitBin init")
                                runGitCommand(path, "$gitBin init") { code, out -> appendGitLog(out.ifBlank { "exit=$code" }) }
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Init Repo") }
                            Button(onClick = {
                                sanitizeGitSettings()
                                val path = gitProjectPath.value
                                val msg = gitCommitMsg.value.ifBlank { "save" }
                                appendGitLog("$ $gitBin add . && $gitBin commit -m \"$msg\"")
                                runGitCommand(path, "$gitBin add . && $gitBin commit -m \"$msg\"") { code, out -> appendGitLog(out.ifBlank { "exit=$code" }) }
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Save Version") }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Git Log", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(gitLog) { line -> Text(line) }
                        }
                    }
                }
            }

            // Git Folder picker dialog
            if (showGitFolderPicker) {
                AlertDialog(
                    onDismissRequest = { showGitFolderPicker = false },
                    title = { Text("Select Project Folder") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(gitPickerPath.value)
                            val dirs = listDirs(gitPickerPath.value)
                            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                items(dirs) { dir ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { gitPickerPath.value = dir.absolutePath }
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
                            val path = gitPickerPath.value
                            gitProjectPath.value = path
                            showGitFolderPicker = false
                            appendGitLog("Project set to: $path")
                        }) { Text("Use this folder") }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val parent = File(gitPickerPath.value).parentFile
                                if (parent != null && parent.exists()) gitPickerPath.value = parent.absolutePath
                            }) { Text("Up") }
                            TextButton(onClick = { showGitFolderPicker = false }) { Text("Cancel") }
                        }
                    }
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = false,
            state = listState
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 360.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Plan", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auto-run")
                            Switch(checked = autoRun, onCheckedChange = { autoRun = it })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val statuses = agent.getPlanStatuses()
                    plan.tasks.forEach { t ->
                        val st = statuses[t.id] ?: "pending"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val obsFile = File(application!!.filesDir, "chat/${currentChatId.value}/observations.json")
                                val obsText = runCatching { if (obsFile.exists()) JSONObject(obsFile.readText()).optString(t.id) else null }.getOrNull()
                                val displayDesc = if (!obsText.isNullOrBlank() && st == "done") obsText.take(200) else t.description
                                Text("${t.id}: ${displayDesc}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(onClick = {}, label = { Text(st) }, colors = AssistChipDefaults.assistChipColors())
                                    if (!t.category.isNullOrBlank()) AssistChip(onClick = {}, label = { Text(t.category!!) })
                                }
                            }
                            Button(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        var loops = 0
                                        while (true) {
                                            val current = activePlan.value ?: break
                                            if (agent.getNextPendingTask(current) == null) break
                                            val success = agent.executeNextTask(current) { s ->
                                                scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                                            }
                                            if (!success) {
                                                val updated = agent.requestUpdatedPlan(current)
                                                if (updated != null) {
                                                    scope.launch(Dispatchers.Main) {
                                                        activePlan.value = updated
                                                        postStatus("Plan updated.")
                                                        saveHistory()
                                                    }
                                                }
                                            }
                                            loops++
                                            if (!autoRun || loops >= 50) break
                                        }
                                    } catch (e: Exception) {
                                        scope.launch(Dispatchers.Main) { postStatus("Agent error: ${e.message}"); saveHistory() }
                                    }
                                }
                            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text(if (st == "pending") "Run" else "Re-run") }
                        }
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
                            // Persist selected chat and workspace immediately
                            savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
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
                            savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
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
                modifier = Modifier.weight(1f).onFocusChanged { isInputFocused = it.isFocused },
                singleLine = true,
                placeholder = { Text("Type a message…") }
            )
            // Send mode dropdown
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sendMode.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { showSendMenu = true }.padding(bottom = 2.dp))
                DropdownMenu(expanded = showSendMenu, onDismissRequest = { showSendMenu = false }) {
                    sendModes.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode) }, onClick = {
                            sendMode = mode
                            showSendMenu = false
                            savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
                        })
                    }
                }
            }
            IconButton(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isEmpty()) return@IconButton
                    input = ""
                    messages.add(ChatMessage("user", prompt))
                    messages.add(ChatMessage("assistant", if (sendMode == "chat") "…" else "Thinking…"))
                    saveHistory()

                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            when (sendMode) {
                                "think" -> {
                                    val result = agent.thinkAndAct(prompt) { s ->
                                        scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                                    }
                                    if (result.producedPlan != null) {
                                        scope.launch(Dispatchers.Main) { activePlan.value = result.producedPlan }
                                        postStatus("Plan ready: ${result.producedPlan.tasks.size} task(s). Press Proceed to run.")
                                    } else if (!result.answer.isNullOrBlank()) {
                                        postStatus(result.answer)
                                    } else {
                                        postStatus("No actionable result from think-and-act.")
                                    }
                                }
                                "plan" -> {
                                    val plan = agent.generatePlan(prompt)
                                    if (plan == null) {
                                        postStatus("Could not parse plan from AI.")
                                    } else {
                                        scope.launch(Dispatchers.Main) { activePlan.value = plan }
                                        postStatus("Plan ready: ${plan.tasks.size} task(s). Press Proceed to run the first task.")
                                    }
                                }
                                else -> {
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
                        // Persist prefs after sending
                        savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
                    }
                }
            ) { Icon(Icons.Default.Send, contentDescription = "Send") }
        }

        // Row 2: Agent controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {
                    searchAssist = !searchAssist
                    Settings.helper_agent_enabled = searchAssist
                    Settings.researcher_agent_enabled = searchAssist
                    postStatus("Search assist ${if (searchAssist) "enabled" else "disabled"}.")
                },
                label = { Text(if (searchAssist) "Search: ON" else "Search: OFF") }
            )
            Button(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isEmpty()) return@Button
                    input = ""
                    messages.add(ChatMessage("user", prompt))
                    messages.add(ChatMessage("assistant", "Thinking…"))
                    saveHistory()
                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = agent.thinkAndAct(prompt) { s ->
                                scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                            }
                            if (result.producedPlan != null) {
                                scope.launch(Dispatchers.Main) { activePlan.value = result.producedPlan }
                                postStatus("Plan ready: ${result.producedPlan.tasks.size} task(s). Press Proceed to run.")
                            } else if (!result.answer.isNullOrBlank()) {
                                postStatus(result.answer)
                            } else {
                                postStatus("No actionable result from think-and-act.")
                            }
                            saveHistory()
                        } catch (e: Exception) {
                            postStatus("Agent error: ${e.message}")
                        }
                    }
                },
                enabled = input.isNotBlank() && !isPlanning.value,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("Think") }

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
                enabled = input.isNotBlank() && !isPlanning.value,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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
                enabled = canProceed,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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
                enabled = hasPlan,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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
                        savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
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
                                                savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
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
                        savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode)
                    }) { Text("Create / Switch") }
                },
                dismissButton = {
                    TextButton(onClick = { showChatManager = false }) { Text("Close") }
                }
            )
        }
        // Persist scroll on leave
        DisposableEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, currentChatId.value, sendMode, gitBin, gitPath) {
            onDispose { savePrefs(currentChatId.value, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sendMode, gitBin, gitPath) }
        }
    }

    // Auto-run loop: when enabled, automatically execute pending plan tasks until completion or error
    LaunchedEffect(autoRun, activePlan.value) {
        if (!autoRun) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            var loops = 0
            while (autoRun && loops < 50) {
                val current = activePlan.value ?: break
                if (agent.getNextPendingTask(current) == null) break
                try {
                    val success = agent.executeNextTask(current) { s ->
                        scope.launch(Dispatchers.Main) { postStatus(s); saveHistory() }
                    }
                    if (!success) {
                        val updated = agent.requestUpdatedPlan(current)
                        if (updated != null) {
                            scope.launch(Dispatchers.Main) {
                                activePlan.value = updated
                                postStatus("Plan updated.")
                                saveHistory()
                            }
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) { postStatus("Agent error: ${e.message}"); saveHistory() }
                    break
                }
                loops++
            }
        }
    }
}