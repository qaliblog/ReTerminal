package com.rk.terminal.ui.screens.terminal

import android.content.Context
import com.rk.libcommons.application
import com.rk.terminal.llm.LlmMessage
import com.rk.terminal.llm.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import android.util.Base64
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.rk.settings.Settings
import java.util.ArrayDeque
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.service.SessionService
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import android.util.Log
import com.rk.terminal.agent.ControlApiClient
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Minimal agent orchestrator that:
 * - Requests a structured plan (JSON) from the LLM based on a user goal
 * - Caches the plan per-session
 * - Iterates tasks and, for each task, requests a single tool call JSON to perform the task
 * - Executes the tool call (create file, write file, mkdir, run shell, list directory, read file, apply minimal edits)
 * - Marks tasks done in a progress cache to ensure idempotency
 * - Captures observations (e.g., dir listings, file contents, command outputs) and feeds them into subsequent tool calls
 */
class AgentOrchestrator(
    private val context: Context,
    private val sessionId: String,
    private val workingDirProvider: () -> String
) {

    data class Task(
        val id: String,
        val description: String,
        val category: String? = null,
        val targets: List<String>? = null,   // optional absolute/relative paths or globs
        val search: List<String>? = null,    // optional regex patterns to grep
        val markers: List<String>? = null    // optional markers to locate sections
    )

    data class Plan(
        val goal: String,
        val tasks: List<Task>
    )

    data class MiniTask(
        val id: String,
        val description: String,
        val category: String? = null,
        val targets: List<String>? = null,
        val search: List<String>? = null,
        val markers: List<String>? = null
    )

    data class MiniPlan(
        val parentTaskId: String,
        val reason: String,
        val tasks: List<MiniTask>
    )

        data class ThinkResult(
        val producedPlan: Plan? = null,
        val answer: String? = null,
        val blueprintPath: String? = null
    )
 
    // Per-run stats for dynamic summary lines
    private data class RunStats(
        val startedMs: Long = System.currentTimeMillis(),
        var endedMs: Long = 0L,
        val toolCounts: MutableMap<String, Int> = LinkedHashMap(),
        val filesRead: MutableList<String> = mutableListOf(),
        val dirsListed: MutableList<String> = mutableListOf(),
        val grepPatterns: MutableList<String> = mutableListOf(),
        val commandsRun: MutableList<String> = mutableListOf(),
        val filesModified: MutableList<String> = mutableListOf()
    )
    private var currentRunStats: RunStats? = null
    private var currentTaskContext: Task? = null
    private var lastInstallSuccess: Boolean = false
    private var lastPlanGoal: String? = null
    private var projectRequirements: String? = null // Store the original project requirements
    private var folderStructureEnsured: Boolean = false
    
    // Context cache for maintaining code continuity across tasks
    private data class FileContext(
        val path: String,
        val content: String,
        val type: String, // "python", "html", "css", "js", "config"
        val functions: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
        val routes: List<String> = emptyList(),
        val dependencies: List<String> = emptyList()
    )
    
    private val contextCache = mutableMapOf<String, FileContext>()
    private val projectStructure = mutableMapOf<String, String>() // path -> description
    private fun beginRunStats() { currentRunStats = RunStats(); appendTaskLog("run_start") { } }
    
    private fun captureProjectRequirements(goal: String) {
        projectRequirements = goal
    }
    private fun endRunStatsAndReport(onStatus: (String) -> Unit, verb: String = "thought") {
        val stats = currentRunStats ?: return
        stats.endedMs = System.currentTimeMillis()
        val secs = ((stats.endedMs - stats.startedMs).coerceAtLeast(0L) / 100L).toDouble() / 10.0
        val parts = mutableListOf<String>()
        if (stats.toolCounts.isNotEmpty()) parts.add(stats.toolCounts.entries.joinToString(", ") { (k: String, v: Int) -> "$k×$v" })
        if (stats.filesRead.isNotEmpty()) parts.add("read ${stats.filesRead.size} file(s): ${stats.filesRead.take(3).joinToString(", ")}" + if (stats.filesRead.size > 3) " …" else "")
        if (stats.filesModified.isNotEmpty()) parts.add("modified ${stats.filesModified.size} file(s): ${stats.filesModified.take(3).joinToString(", ")}" + if (stats.filesModified.size > 3) " …" else "")
        if (stats.commandsRun.isNotEmpty()) parts.add("ran ${stats.commandsRun.size} command(s): ${stats.commandsRun.take(1).joinToString()}" + if (stats.commandsRun.size > 1) " …" else "")
        if (stats.grepPatterns.isNotEmpty()) parts.add("grep ${stats.grepPatterns.size} pattern(s)")
        val summary = "$verb for ${secs}s" + if (parts.isNotEmpty()) "; " + parts.joinToString("; ") else ""
        onStatus(summary)
        runCatching {
            val j = JSONObject()
            val tcObj = JSONObject()
            stats.toolCounts.forEach { (k, v) -> tcObj.put(k, v) }
            j.put("duration_s", secs)
                .put("tool_counts", tcObj)
                .put("files_read", JSONArray(stats.filesRead))
                .put("dirs_listed", JSONArray(stats.dirsListed))
                .put("grep_patterns", JSONArray(stats.grepPatterns))
                .put("commands_run", JSONArray(stats.commandsRun))
                .put("files_modified", JSONArray(stats.filesModified))
            appendTaskLog("run_end") { put("stats", j) }
        }
        currentRunStats = null
    }
 
     private val agentDir: File by lazy {
         // Store per chat session
         File(application!!.filesDir, "chat/${sessionId}").apply { mkdirs() }
     }
    private val planFile: File by lazy { File(agentDir, "plan.json") }
    private val progressFile: File by lazy { File(agentDir, "progress.json") }
    private val observationsFile: File by lazy { File(agentDir, "observations.json") }
    private val commandsCacheFile: File by lazy { File(agentDir, "commands.json") }
    private val miniPlanFile: File by lazy { File(agentDir, "miniPlan.json") }
    private val blueprintFile: File by lazy { File(agentDir, "blueprint.json") }
    private val cliReportFile: File by lazy { File(agentDir, "cli_report.json") }
    private val writerToolsFile: File by lazy { File(agentDir, "writer_tools.json") }

    // Add task log file (JSON Lines)
    private val taskLogFile: File by lazy { File(agentDir, "task_log.jsonl") }

    // Per-run observations (taskId -> observation text)
    private val observations: MutableMap<String, String> = linkedMapOf()
    // Command output cache: key -> {command, wd, output, exit, ts}
    private val commandCache: MutableMap<String, JSONObject> = LinkedHashMap()
    // Track workspace changes to refresh codebase cache
    private val pendingCodebaseChanges: MutableSet<String> = linkedSetOf()
    private var lastCodebaseRefreshMs: Long = 0L
    // Environment detection signals cached during run_shell preflight
    private var lastDetectedOsId: String? = null
    private val lastDetectedManagers: MutableSet<String> = linkedSetOf()

    init {
        // Load persisted observations if available to make the agent resilient to restarts
        runCatching {
            if (observationsFile.exists()) {
                val obj = JSONObject(observationsFile.readText())
                obj.keys().forEach { k ->
                    observations[k] = obj.optString(k)
                }
            }
        }
        // Load command cache
        runCatching {
            if (commandsCacheFile.exists()) {
                val obj = JSONObject(commandsCacheFile.readText())
                obj.keys().forEach { k ->
                    val v = obj.optJSONObject(k)
                    if (v != null) commandCache[k] = v
                }
            }
            // Build initial CLI report from any pre-existing cache
            persistCliReport()
        }
        // Ensure mini plan storage exists
        runCatching {
            if (!miniPlanFile.exists()) {
                miniPlanFile.writeText(JSONObject().put("plans", JSONObject()).toString(2))
            }
        }
        // Ensure blueprint file placeholder exists (optional)
        runCatching {
            if (!blueprintFile.exists()) {
                blueprintFile.writeText("{}")
            }
        }
        // Ensure writer tools suggestions store exists
        runCatching {
            if (!writerToolsFile.exists()) {
                writerToolsFile.writeText(JSONObject().put("suggestions", JSONArray()).toString(2))
            }
        }
    }

    private fun saveObservations() {
        runCatching {
            val obj = JSONObject()
            observations.forEach { (k, v) -> obj.put(k, v) }
            observationsFile.writeText(obj.toString(2))
        }
    }

    private fun saveCommandCache() {
        runCatching {
            val obj = JSONObject()
            commandCache.forEach { (k, v) -> obj.put(k, v) }
            commandsCacheFile.writeText(obj.toString(2))
        }
    }
    
    private fun updateContextCache(filePath: String, content: String, fileType: String = "unknown") {
        val context = FileContext(
            path = filePath,
            content = content,
            type = fileType,
            functions = extractFunctions(content, fileType),
            classes = extractClasses(content, fileType),
            routes = extractRoutes(content, fileType),
            dependencies = extractDependencies(content, fileType)
        )
        contextCache[filePath] = context
        projectStructure[filePath] = getFileDescription(filePath, content)
    }
    
    private fun extractFunctions(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("def\\s+(\\w+)\\s*\\(").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("function\\s+(\\w+)\\s*\\(").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractClasses(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("class\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("class\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractRoutes(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("@app\\.route\\('([^']+)'\\)").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun extractDependencies(content: String, fileType: String): List<String> {
        return when (fileType) {
            "python" -> Regex("import\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList() +
                       Regex("from\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            "javascript" -> Regex("import\\s+.*?from\\s+['\"]([^'\"]+)['\"]").findAll(content).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }
    
    private fun getFileDescription(filePath: String, content: String): String {
        return when {
            filePath.endsWith(".py") -> "Python file with ${extractFunctions(content, "python").size} functions"
            filePath.endsWith(".html") -> "HTML template file"
            filePath.endsWith(".js") -> "JavaScript file with ${extractFunctions(content, "javascript").size} functions"
            filePath.endsWith(".css") -> "CSS stylesheet"
            filePath.endsWith("requirements.txt") -> "Python dependencies"
            filePath.endsWith("README.md") -> "Project documentation"
            else -> "Configuration or data file"
        }
    }
    
    private fun getContextSummary(): String {
        if (contextCache.isEmpty()) return "No files created yet."
        
        return buildString {
            appendLine("## Project Context Summary")
            appendLine("Created files and their key components:")
            
            contextCache.values.forEach { context ->
                appendLine("- **${context.path}** (${context.type})")
                if (context.functions.isNotEmpty()) {
                    appendLine("  - Functions: ${context.functions.joinToString(", ")}")
                }
                if (context.classes.isNotEmpty()) {
                    appendLine("  - Classes: ${context.classes.joinToString(", ")}")
                }
                if (context.routes.isNotEmpty()) {
                    appendLine("  - Routes: ${context.routes.joinToString(", ")}")
                }
                if (context.dependencies.isNotEmpty()) {
                    appendLine("  - Dependencies: ${context.dependencies.joinToString(", ")}")
                }
            }
        }
    }

    private fun loadMiniPlansRoot(): JSONObject {
        return runCatching { JSONObject(miniPlanFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrElse { JSONObject().put("plans", JSONObject()) }
    }

    private fun saveMiniPlansRoot(root: JSONObject) {
        miniPlanFile.writeText(root.toString(2))
    }

    private fun getMiniPlanJsonForParent(parentTaskId: String): JSONObject? {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: return null
        return plans.optJSONObject(parentTaskId)
    }

    private fun setMiniPlanJsonForParent(parentTaskId: String, miniJson: JSONObject) {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        plans.put(parentTaskId, miniJson)
        saveMiniPlansRoot(root)
    }

    private fun persistMiniPlanWithStatuses(mini: MiniPlan) {
        val tasksArr = JSONArray()
        mini.tasks.forEach { t ->
            tasksArr.put(JSONObject().apply {
                put("id", t.id)
                put("description", t.description)
                if (!t.category.isNullOrBlank()) put("category", t.category)
                if (!t.targets.isNullOrEmpty()) put("targets", JSONArray(t.targets))
                if (!t.search.isNullOrEmpty()) put("search", JSONArray(t.search))
                if (!t.markers.isNullOrEmpty()) put("markers", JSONArray(t.markers))
                put("status", "pending")
                put("attempts", 0)
            })
        }
        val json = JSONObject().apply {
            put("parent_task_id", mini.parentTaskId)
            put("reason", mini.reason)
            put("tasks", tasksArr)
        }
        setMiniPlanJsonForParent(mini.parentTaskId, json)
    }

    private fun loadMiniPlan(parentTaskId: String): MiniPlan? {
        val json = getMiniPlanJsonForParent(parentTaskId) ?: return null
        val arr = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<MiniTask>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("id").ifBlank { "m${i + 1}" }
            val desc = t.optString("description")
            if (desc.isBlank()) continue
            val cat = t.optString("category").ifBlank { null }
            val targets = t.optJSONArray("targets")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val search = t.optJSONArray("search")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val markers = t.optJSONArray("markers")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            tasks.add(MiniTask(id, desc, cat, targets, search, markers))
        }
        val reason = json.optString("reason").ifBlank { "" }
        return MiniPlan(parentTaskId, reason, tasks)
    }

    private fun getMiniTaskStatus(parentTaskId: String, miniTaskId: String): String {
        val json = getMiniPlanJsonForParent(parentTaskId) ?: return "pending"
        val arr = json.optJSONArray("tasks") ?: return "pending"
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) return t.optString("status", "pending")
        }
        return "pending"
    }

    private fun markMiniTaskDone(parentTaskId: String, miniTaskId: String) {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        val json = plans.optJSONObject(parentTaskId) ?: return
        val arr = json.optJSONArray("tasks") ?: return
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) {
                t.put("status", "done")
                t.put("ts", System.currentTimeMillis())
            }
        }
        saveMiniPlansRoot(root)
    }

    private fun incrementMiniTaskAttempts(parentTaskId: String, miniTaskId: String): Int {
        val root = loadMiniPlansRoot()
        val plans = root.optJSONObject("plans") ?: JSONObject().also { root.put("plans", it) }
        val json = plans.optJSONObject(parentTaskId) ?: return 0
        val arr = json.optJSONArray("tasks") ?: return 0
        var next = 0
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("id") == miniTaskId) {
                next = t.optInt("attempts", 0) + 1
                t.put("attempts", next)
                if (!t.has("status")) t.put("status", "pending")
            }
        }
        saveMiniPlansRoot(root)
        return next
    }

    private fun getNextPendingMiniTask(mini: MiniPlan): MiniTask? {
        for (t in mini.tasks) {
            if (getMiniTaskStatus(mini.parentTaskId, t.id) != "done") return t
        }
        return null
    }

    private suspend fun decideRemediationAction(goal: String, task: Task, failureNote: String): String = withContext(Dispatchers.IO) {
        val sys = """
            You are a supervisor deciding the smallest effective remediation when a task struggles.
            Return ONLY JSON: {"action": "mini_plan"|"revise_plan"|"retry", "why": string} with no extra text.
            - mini_plan: when a few targeted discovery/edits can unblock the task without changing the whole plan
            - revise_plan: when the plan likely needs restructuring or different approach
            - retry: when the failure seems transient or due to missing small context that another attempt can fetch
        """.trimIndent()
        val user = """
            Goal: ${goal}
            Current task: ${task.id} - ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Failure note: ${failureNote}
            Prior observations: ${(observations[task.id] ?: "(none)").take(800)}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("remediation_decision", mapOf("task" to task.description.take(300)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext "mini_plan"
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext "mini_plan"
        val action = obj.optString("action").ifBlank { "mini_plan" }
        appendTaskLog("remediation_decision") {
            put("task_id", task.id)
            put("action", action)
            put("failure_note", failureNote.take(400))
        }
        return@withContext action
    }

    private suspend fun requestMiniPlanForTask(plan: Plan, task: Task, failureNote: String): MiniPlan? = withContext(Dispatchers.IO) {
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val obsJson = JSONObject().apply {
            observations.entries.forEach { (k, v) -> put(k, if (v.length > 4000) v.take(4000) + " …" else v) }
        }.toString()
        val sys = """
            You create a small remediation mini-plan to unblock a single parent task. Return ONLY JSON:
            {"parent_task_id": string, "reason": string, "tasks": [{"id": string, "category": string, "description": string, "targets": [string...], "search": [string...], "markers": [string...]}, ...]}
            Rules:
            - 2 to 6 concise steps max
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell | json_edit
            - Favor discovery-first then precise, idempotent edits
            - ids must be stable and short (m1, m2, ...)
            - No explanations beyond the 'reason' field
        """.trimIndent()
        val user = """
            Goal: ${plan.goal}
            Parent task: ${task.id} - ${task.description}
            Category: ${task.category ?: "unspecified"}
            Failure note: ${failureNote}
            Working directory: ${wdPath}
            Workspace snapshot: ${workspaceInfo}
            Prior observations: ${obsJson}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("mini_plan", mapOf("parent_task" to task.description.take(300)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val parent = obj.optString("parent_task_id").ifBlank { task.id }
        val reason = obj.optString("reason").ifBlank { "remediate failure" }
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<MiniTask>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("id").ifBlank { "m${i + 1}" }
            val desc = t.optString("description")
            val cat = t.optString("category").ifBlank { null }
            val targets = t.optJSONArray("targets")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val search = t.optJSONArray("search")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            val markers = t.optJSONArray("markers")?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx) } }
            if (desc.isNotBlank()) tasks.add(MiniTask(id, desc, cat, targets, search, markers))
        }
        val mini = MiniPlan(parent, reason, tasks)
        persistMiniPlanWithStatuses(mini)
        appendTaskLog("mini_plan_created") {
            put("parent_task_id", parent)
            put("reason", reason)
            put("steps", tasks.size)
        }
        return@withContext mini
    }

    private suspend fun executeMiniPlanForTask(plan: Plan, parentTask: Task, onStatus: (String) -> Unit): Boolean {
        var mini = loadMiniPlan(parentTask.id)
        if (mini == null) {
            val created = requestMiniPlanForTask(plan, parentTask, observations[parentTask.id] ?: "no_note")
            if (created == null) return false
            mini = created
            onStatus("Mini-plan created for ${parentTask.id}: ${mini.tasks.size} step(s)")
            appendTaskLog("mini_plan_start") { put("parent_task_id", parentTask.id); put("steps", mini.tasks.size) }
        }
        var steps = 0
        val maxSteps = 12
        while (steps < maxSteps) {
            val mt = getNextPendingMiniTask(mini) ?: return true
            onStatus("Mini ${mini.parentTaskId}.${mt.id}: ${mt.description}")
            appendTaskLog("mini_step_start") {
                put("parent_task_id", mini.parentTaskId)
                put("step_id", mt.id)
                put("description", mt.description)
            }
            val attemptNo = incrementMiniTaskAttempts(mini.parentTaskId, mt.id)
            if (attemptNo > 3) {
                onStatus("Mini ${mini.parentTaskId}.${mt.id}: attempts exceeded")
                appendTaskLog("mini_step_aborted") { put("parent_task_id", mini.parentTaskId); put("step_id", mt.id); put("reason", "attempts_exceeded") }
                return false
            }
            val pseudoTask = Task(
                id = "${mini.parentTaskId}.${mt.id}",
                description = mt.description,
                category = mt.category,
                targets = mt.targets,
                search = mt.search,
                markers = mt.markers
            )
            val proposed = requestSingleToolCall(plan.goal, pseudoTask)
            val toolCall = proposed ?: ToolCall("analyze", JSONObject())
                        appendTaskLog("tool_call_selected") { put("task_id", pseudoTask.id); put("type", toolCall.type); put("args", toolCall.args) }
            runCatching {
                val kind = detectTaskKind(pseudoTask)
                ControlApiClient.recordTaskDetection(sessionId, pseudoTask.id, kind, pseudoTask.description, pseudoTask.category)
            }
            val result = try {
                currentTaskContext = pseudoTask
                executeToolCall(toolCall)
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                observations[pseudoTask.id] = "mini error: ${err}"
                saveObservations()
                steps++
                appendTaskLog("tool_result") {
                    put("task_id", pseudoTask.id)
                    put("type", toolCall.type)
                    put("ok", false)
                    put("error", (e.message ?: e.toString()).take(1000))
                }
                continue
            } finally {
                currentTaskContext = null
            }
            appendTaskLog("tool_result") {
                put("task_id", pseudoTask.id)
                put("type", toolCall.type)
                put("ok", result.ok)
                result.observation?.let { put("observation_preview", it.take(800)); put("observation_bytes", it.toByteArray(StandardCharsets.UTF_8).size) }
            }
            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[pseudoTask.id] = result.observation
                    saveObservations()
                    onStatus("Observed (${pseudoTask.id}): ${result.observation.take(600)}${if ((result.observation?.length ?: 0) > 600) " …" else ""}")
                }
                // Consider the mini step done on any successful action
                markMiniTaskDone(mini.parentTaskId, mt.id)
                onStatus("Mini ${mini.parentTaskId}.${mt.id}: done")
                appendTaskLog("mini_step_done") { put("parent_task_id", mini.parentTaskId); put("step_id", mt.id) }
            } else {
                if (!observations.containsKey(pseudoTask.id)) {
                    observations[pseudoTask.id] = "mini failed without exception"
                    saveObservations()
                }
                steps++
            }
        }
        onStatus("Mini-plan: reached step limit")
        return false
    }

    // ===================== THINK & ACT (Adaptive) =====================
    private suspend fun classifyUserIntent(prompt: String, workspaceInfo: String): JSONObject = withContext(Dispatchers.IO) {
        val sys = """
            Classify the user's request into one intent. Return ONLY JSON:
            {"intent": "error_diagnosis"|"question_analysis"|"project_bootstrap"|"feature_addition"|"plan_and_execute", "why": string}
        """.trimIndent()
        val user = """
            Prompt: ${prompt}
            Workspace snapshot: ${workspaceInfo}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("classify_intent", mapOf("prompt" to prompt.take(500)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: "{\"intent\":\"plan_and_execute\"}"
        return@withContext runCatching { JSONObject(jsonText) }.getOrElse { JSONObject().put("intent", "plan_and_execute") }
    }

    private suspend fun requestDiscoveryToolCall(contextNote: String): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            Propose one discovery tool call to gather information. Return ONLY JSON with one of these types: list_dir, list_dir_recursive, grep, read_file, read_file_lines, head_file, tail_file, read_file_chunk, read_file_section_by_markers, read_files_glob, stat_file, json_get, get_cached_command_output, list_cached_commands, run_shell.
            Favor environment checks first when context suggests system interactions, e.g., uname -a; cat /etc/os-release 2>/dev/null || true; (command -v apt || command -v dnf || command -v yum || command -v pacman || command -v apk || true); (command -v python3 || command -v python || true); (command -v node || true); (command -v npm || true); (command -v gcc || true); (command -v g++ || true); echo ${'$'}SHELL; echo ${'$'}PATH.
            Schema examples same as earlier. Output must be one minified JSON object.
        """.trimIndent()
        val wd = workingDirProvider()
        val user = """
            Working directory: ${wd}
            Context: ${contextNote}
            Prior signals: ${(observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(200)}" }).ifBlank { "(none)" }}
        """.trimIndent()
        val msgs = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("discovery", mapOf("wd" to wd, "context" to contextNote))
        applyHelperToMessages(reco, msgs)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(msgs) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val type = obj.optString("type")
        val args = obj.optJSONObject("args") ?: JSONObject()
        return@withContext ToolCall(type, args)
    }

    private suspend fun requestBlueprint(prompt: String, workspaceInfo: String): String? = withContext(Dispatchers.IO) {
        val sys = """
            Produce a concise, well-structured project blueprint as minified JSON and nothing else.
            Shape: {"name": string, "summary": string, "stack": {"lang": string, "frameworks": [string...]}, "modules": [{"id": string, "name": string, "responsibilities": [string...] }], "apis": [{"name": string, "endpoints":[{"path": string, "method": string, "desc": string}]}]}
            Keep it small but thoughtful; it will guide subsequent planning.
        """.trimIndent()
        val user = """
            Goal: ${prompt}
            Workspace snapshot: ${workspaceInfo}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("blueprint", mapOf("goal" to prompt.take(500)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        blueprintFile.writeText(jsonText)
        return@withContext blueprintFile.absolutePath
    }

    private suspend fun answerQuestionFromObservations(prompt: String): String = withContext(Dispatchers.IO) {
        val sys = """
            You are given prior observations from a repository; answer the user's question concisely.
            Answer in plain text, cite filenames or paths inline when helpful.
        """.trimIndent()
        val obs = observations.entries.joinToString("\n\n") { (k, v) -> "[${k}]\n${v.take(4000)}" }
        val user = """
            Question: ${prompt}
            Observations:
            ${obs.ifBlank { "(none)" }}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("qa_answer", mapOf("obs_preview" to obs.take(800)))
        applyHelperToMessages(reco, messages)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(messages) })
        return@withContext content
    }

    private suspend fun generatePlanWithContext(userGoal: String, extraContext: String?): Plan? = withContext(Dispatchers.IO) {
        runCatching { if (progressFile.exists()) progressFile.delete() }
        observations.clear()
        saveObservations()
        val wdPath = workingDirProvider()
                val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        // Record initial setup (user goal + expectations) to external control API if enabled
        runCatching {
            val expectations = extraContext ?: ""
            ControlApiClient.recordInitialSetup(sessionId, userGoal, expectations, workspaceInfo)
        }
        // If a plan requires creation, ensure codebase discovery is run first
            if (Settings.codebase_agent_enabled) runCatching { buildCodebaseCache({ }, includeRecursive = true) }
        val sys = """
            You are an expert software architect that creates concise, step-by-step plans for software projects OR codebase updates.
            Return ONLY a minified JSON object with the shape:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "targets": [string...], "search": [string...], "markers": [string...]}, ...]}

            Rules:
            - For update/refactor/fix goals: first run a codebase discovery (list_dir_recursive or read_files_glob) and write a compact overview to codebase cache before any edits.
            - For creation goals: front-load discovery if workspace is non-empty, then create directories and files.
            - ids must be unique short strings (e.g., t1, t2).
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell | json_edit.
            - Do not include code in the plan itself.
        """.trimIndent()
        val user = """
            Goal: ${userGoal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
            Extra context: ${extraContext ?: "(none)"}
        """.trimIndent()
        var plan: Plan? = null
        var attempts = 0
        while (attempts < 3) {
            // Prefer external Control API plan if enabled
            if (attempts == 0) {
                val apiPlan = runCatching {
                    ControlApiClient.requestPlan(sessionId, userGoal, extraContext ?: "", workspaceInfo)
                }.getOrNull()
                if (apiPlan != null) {
                    val pGoal = apiPlan.optString("goal").ifBlank { userGoal }
                    val tasksArr = apiPlan.optJSONArray("tasks") ?: JSONArray()
                    val tasks = mutableListOf<Task>()
                    for (i in 0 until tasksArr.length()) {
                        val t = tasksArr.optJSONObject(i) ?: continue
                        val id = t.optString("id").ifBlank { "t${i + 1}" }
                        val desc = t.optString("description")
                        val cat = t.optString("category").ifBlank { null }
                        val targets = t.optJSONArray("targets")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        val search = t.optJSONArray("search")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        val markers = t.optJSONArray("markers")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                        if (desc.isNotBlank()) tasks.add(Task(id, desc, cat, targets, search, markers))
                    }
                    if (tasks.isNotEmpty()) {
                        plan = Plan(pGoal, tasks)
                        break
                    }
                }
            }
            val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
            val jsonText = extractFirstJsonObject(content) ?: continue
            val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val goal = obj.optString("goal").ifBlank { userGoal }
            val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
            val tasks = mutableListOf<Task>()
            for (i in 0 until tasksArr.length()) {
                val t = tasksArr.optJSONObject(i) ?: continue
                val id = t.optString("id").ifBlank { "t${i + 1}" }
                val desc = t.optString("description")
                val cat = t.optString("category").ifBlank { null }
                val targets = t.optJSONArray("targets")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                val search = t.optJSONArray("search")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                val markers = t.optJSONArray("markers")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optString(idx) } }
                if (desc.isNotBlank()) {
                    tasks.add(Task(id, desc, cat, targets, search, markers))
                }
            }
 
             if (tasks.isEmpty()) {
                 // Simple fallback plan to avoid zero-task output
                 val fallback = mutableListOf<Task>()
                 fallback.add(Task("t1", "List top-level workspace", "list_dir", listOf(wdPath), null, null))
                 fallback.add(Task("t2", "Search for common project files", "grep", listOf(wdPath), listOf("build\\.gradle|settings\\.gradle|package\\.json|README|Main|AndroidManifest"), null))
                 tasks.addAll(fallback)
             }
 
             val isWebAppProject = userGoal.contains("web", ignoreCase = true) ||
                                   userGoal.contains("website", ignoreCase = true) ||
                                   userGoal.contains("flask", ignoreCase = true) ||
                                   userGoal.contains("html", ignoreCase = true)
 
             val fileCreationTasks = tasks.count { it.category == "create_file" || it.category == "write_file" }
 
             if (isWebAppProject && fileCreationTasks < 3) {
                 attempts++
                 continue // Regenerate the plan if it's too simple for a web app
             }
 
             plan = Plan(goal, tasks)
             break
         }
 
         if (plan == null) return@withContext null
 
 
         captureProjectRequirements(plan.goal) // Capture the project requirements
         persistPlanWithStatuses(plan)
         runCatching {
             val tArr = JSONArray()
             plan.tasks.forEach { t -> tArr.put(JSONObject().put("id", t.id).put("description", t.description).put("category", t.category ?: "")) }
             appendTaskLog("plan_created") { put("goal", plan.goal); put("tasks", tArr) }
         }
         return@withContext plan
     }

    suspend fun thinkAndAct(prompt: String, onStatus: (String) -> Unit): ThinkResult {
        beginRunStats()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        if (Settings.codebase_agent_enabled) {
            runCatching { buildCodebaseCache(onStatus, includeRecursive = true) }
        }
        // After executing a modifying tool, we already schedule cache upgrade via notifyWorkspaceChanged

        // Apply any pending codebase cache upgrades due to file changes
        performCodebaseUpgradeIfPending(onStatus)
        onStatus("Thinking about intent…")
        val intentObj = classifyUserIntent(prompt, workspaceInfo)
        val intent = intentObj.optString("intent", "plan_and_execute")
        val isUpdateLike = prompt.contains("update", ignoreCase = true) || prompt.contains("modify", ignoreCase = true) || prompt.contains("refactor", ignoreCase = true) || prompt.contains("fix", ignoreCase = true)
        // Enable codebase agent for both update-like and non-empty creation goals
        if (isUpdateLike || workspaceInfo.contains("\"items\":[") ) Settings.codebase_agent_enabled = true
        onStatus("Intent: ${intent}")
        // Search suggestion
        if (Settings.helper_agent_enabled && promptSuggestsSearch(prompt)) {
            onStatus("Search hint: query seems to need external info. Running search…")
            val info = runSearchAgent(prompt, onStatus)
            onStatus(info.take(1200))
        }
        when (intent) {
            "error_diagnosis" -> {
                onStatus("Diagnosing error via discovery loop…")
                var steps = 0
                val startTime = System.currentTimeMillis()
                val maxTime = 15000L // 15 seconds max for discovery
                
                while (steps < 10) {
                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > maxTime) {
                        onStatus("Discovery timeout reached, proceeding with plan generation")
                        break
                    }
                    
                    val tc = requestDiscoveryToolCall("error-diagnosis for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:error:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type}): ${result.observation.take(600)}${if ((result.observation?.length ?: 0) > 600) " …" else ""}")
                    }
                    steps++
                    if (!isDiscoveryTool(tc.type)) break
                }
                onStatus("Asking AI for remediation plan…")
                val plan = generatePlanWithContext(prompt, observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(1000)}" })
                return ThinkResult(producedPlan = plan)
            }
            "question_analysis" -> {
                onStatus("Investigating repository to answer question…")
                var steps = 0
                while (steps < 8) {
                    val tc = requestDiscoveryToolCall("question-analysis for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:qa:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type})")
                    }
                    steps++
                }
                val answer = answerQuestionFromObservations(prompt)
                onStatus(answer.take(1200))
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(answer = answer)
            }
            "project_bootstrap" -> {
                onStatus("Drafting blueprint…")
                val bpPath = requestBlueprint(prompt, workspaceInfo)
                if (bpPath != null) onStatus("Blueprint saved: ${bpPath}") else onStatus("Blueprint not produced")
                val bpText = runCatching { blueprintFile.readText() }.getOrElse { "{}" }
                onStatus("Planning using blueprint…")
                val plan = generatePlanWithContext(prompt, bpText)
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan, blueprintPath = bpPath)
            }
            "feature_addition" -> {
                onStatus("Analyzing project for feature addition…")
                var steps = 0
                while (steps < 10) {
                    val tc = requestDiscoveryToolCall("feature-addition context for: ${prompt}") ?: break
                    val result = executeToolCall(tc)
                    val key = "think:feature:${steps+1}:${tc.type}"
                    if (result.observation != null) {
                        observations[key] = result.observation
                        saveObservations()
                        onStatus("Observed (${tc.type})")
                    }
                    steps++
                }
                onStatus("Planning feature implementation…")
                val plan = generatePlanWithContext(prompt, observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(1000)}" })
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan)
            }
            else -> {
                onStatus("Generating plan…")
                val plan = generatePlanWithContext(prompt, runCatching { blueprintFile.readText() }.getOrNull())
                endRunStatsAndReport(onStatus, verb = "thought")
                return ThinkResult(producedPlan = plan)
            }
        }
    }

    private suspend fun runSearchAgent(query: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val suggestSys = """
            You suggest 1-3 websites to consult for the given query. Return ONLY minified JSON:
            {"sites": [{"url": string, "why": string}...]}
        """.trimIndent()
        val suggestUser = """
            Query: ${query}
            Prefer official docs, MDN, language/framework docs, reputable blogs.
        """.trimIndent()
        val suggestContent = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", suggestSys), LlmMessage("user", suggestUser))) })
        val suggestJson = extractFirstJsonObject(suggestContent)
        val sites = if (suggestJson != null) runCatching { JSONObject(suggestJson).optJSONArray("sites") }.getOrNull() ?: JSONArray() else JSONArray()
        val fetched = JSONArray()
        fun curl(url: String): String {
            val cmd = "curl -L --max-time 15 --silent --show-error --compressed --user-agent 'Mozilla/5.0' '" + url.replace("'", "%27") + "'"
            val res = executeToolCall(ToolCall("run_shell", JSONObject().put("command", cmd)))
            return res.observation ?: ""
        }
        val linkRegex = Regex("href=\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE)
        val toVisit = ArrayDeque<String>()
        for (i in 0 until sites.length()) {
            val u = sites.optJSONObject(i)?.optString("url").orEmpty()
            if (u.isNotBlank()) toVisit.add(u)
        }
        val visited = mutableSetOf<String>()
        var pages = 0
        while (toVisit.isNotEmpty() && pages < 3) {
            val u = toVisit.removeFirst()
            if (visited.contains(u)) continue
            visited.add(u)
            onStatus("Search: fetching ${u}")
            val html = curl(u)
            if (html.isNotBlank()) {
                fetched.put(JSONObject().put("url", u).put("html", html.take(20000)))
                // enqueue a couple more links from this page
                linkRegex.findAll(html).take(2).forEach { m ->
                    val link = m.groupValues[1]
                    if (!visited.contains(link)) toVisit.add(link)
                }
                pages++
            }
        }
        val synthSys = """
            You synthesize concise, accurate information from fetched pages. Return ONLY text. Cite URLs inline.
        """.trimIndent()
        val bundle = (0 until fetched.length()).joinToString("\n\n") { idx ->
            val o = fetched.getJSONObject(idx)
            "URL: ${o.optString("url")}\nHTML:\n" + o.optString("html")
        }
        val synthUser = """
            Query: ${query}
            Fetched pages:
            ${bundle}
        """.trimIndent()
        val final = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", synthSys), LlmMessage("user", synthUser))) })
        return@withContext final
    }

    private fun promptSuggestsSearch(prompt: String): Boolean {
        val p = prompt.lowercase()
        return listOf("what is", "how to", "error ", "exception ", "docs", "documentation", "api", "install", "tutorial").any { p.contains(it) }
    }

    private suspend fun researcherAssistIfNeeded(errorNote: String, latestObs: String?): String? = withContext(Dispatchers.IO) {
        if (!Settings.researcher_agent_enabled) return@withContext null
        val sys = """
            You decide what to research to resolve an error.
            Return ONLY minified JSON: {"query": string}
        """.trimIndent()
        val user = """
            Error: ${errorNote}
            Context: ${latestObs?.take(800) ?: "(none)"}
        """.trimIndent()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val q = obj.optString("query").ifBlank { null } ?: return@withContext null
        return@withContext runSearchAgent(q) { }
    }

    private fun loadProgress(): JSONObject {
        return runCatching { JSONObject(progressFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrElse { JSONObject() }
    }

    private fun saveProgress(progress: JSONObject) {
        progressFile.writeText(progress.toString(2))
    }

    private fun markTaskDone(taskId: String) {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        tObj.put("status", "done")
        tObj.put("ts", System.currentTimeMillis())
        tObj.put("attempts", tObj.optInt("attempts", 0))
        tasks.put(taskId, tObj)
        saveProgress(progress)
        appendTaskLog("task_done") {
            put("task_id", taskId)
            put("attempts", tObj.optInt("attempts", 0))
            put("ts", tObj.optLong("ts"))
        }
    }

    private fun markTaskFailed(taskId: String, note: String) {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        tObj.put("status", "failed")
        tObj.put("ts", System.currentTimeMillis())
        tObj.put("attempts", tObj.optInt("attempts", 0))
        tasks.put(taskId, tObj)
        saveProgress(progress)
        observations[taskId] = note
        saveObservations()
        appendTaskLog("task_failed") {
            put("task_id", taskId)
            put("attempts", tObj.optInt("attempts", 0))
            put("note", note.take(400))
            put("ts", tObj.optLong("ts"))
        }
    }

    private fun incrementAttempts(taskId: String): Int {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: JSONObject().also { progress.put("tasks", it) }
        val tObj = tasks.optJSONObject(taskId) ?: JSONObject()
        val next = tObj.optInt("attempts", 0) + 1
        tObj.put("attempts", next)
        if (!tObj.has("status")) tObj.put("status", "pending")
        tasks.put(taskId, tObj)
        saveProgress(progress)
        appendTaskLog("task_attempt") { put("task_id", taskId); put("attempt", next) }
        return next
    }

    private fun resetAllAttempts() {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return
        val keys = tasks.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val t = tasks.optJSONObject(k) ?: continue
            t.put("attempts", 0)
        }
        saveProgress(progress)
    }

    private fun getPlanSignature(progress: JSONObject): String? = progress.optString("plan_signature", "").ifBlank { null }
    private fun setPlanSignature(progress: JSONObject, sig: String) {
        progress.put("plan_signature", sig)
        progress.put("plan_rev", progress.optInt("plan_rev", 0) + 1)
    }

    private fun computePlanSignature(plan: Plan): String {
        val sb = StringBuilder()
        plan.tasks.forEach { sb.append(it.id).append('|').append(it.category ?: "").append('|').append(it.description).append('|')
            .append(it.targets?.joinToString(",") ?: "").append('|')
            .append(it.search?.joinToString(",") ?: "").append('|')
            .append(it.markers?.joinToString(",") ?: "").append('\n') }
        return sb.toString().hashCode().toString()
    }

    fun getPlanStatuses(): Map<String, String> {
        return runCatching {
            if (!planFile.exists()) return emptyMap()
            val obj = JSONObject(planFile.readText())
            val arr = obj.optJSONArray("tasks") ?: return emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    put(t.optString("id"), t.optString("status", "pending"))
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun isTaskDone(taskId: String): Boolean {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return false
        return tasks.optJSONObject(taskId)?.optString("status") == "done"
    }

    private fun isTaskFailed(taskId: String): Boolean {
        val progress = loadProgress()
        val tasks = progress.optJSONObject("tasks") ?: return false
        return tasks.optJSONObject(taskId)?.optString("status") == "failed"
    }

    private fun taskStatus(taskId: String): String = if (isTaskDone(taskId)) "done" else "pending"

    private fun listTopLevel(dir: File, limit: Int = 50): String {
        val items = dir.listFiles()?.take(limit).orEmpty()
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("type", if (f.isDirectory) "dir" else "file")
            )
        }
        return JSONObject().put("path", dir.absolutePath).put("items", arr).toString()
    }

    private fun persistPlanWithStatuses(plan: Plan) {
        val arr = JSONArray()
        plan.tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("description", t.description)
                if (t.category != null) put("category", t.category)
                if (!t.targets.isNullOrEmpty()) put("targets", JSONArray(t.targets))
                if (!t.search.isNullOrEmpty()) put("search", JSONArray(t.search))
                if (!t.markers.isNullOrEmpty()) put("markers", JSONArray(t.markers))
                put("status", taskStatus(t.id))
            })
        }
        val root = JSONObject().apply {
            put("goal", plan.goal)
            put("tasks", arr)
        }
        planFile.writeText(root.toString(2))
    }

    fun getNextPendingTask(plan: Plan): Task? {
        // Skip tasks already done or explicitly marked as failed
        return plan.tasks.firstOrNull { !isTaskDone(it.id) && !isTaskFailed(it.id) }
    }

    suspend fun executeNextTask(
        plan: Plan,
        onStatus: (String) -> Unit
    ): Boolean {
        beginRunStats()
        lastPlanGoal = plan.goal
        // Detect plan change and reset attempts if needed
        val progress = loadProgress()
        val currentSig = computePlanSignature(plan)
        val prevSig = getPlanSignature(progress)
        if (prevSig != currentSig) {
            resetAllAttempts()
            setPlanSignature(progress, currentSig)
            saveProgress(progress)
        }

        val task = getNextPendingTask(plan) ?: return false
        onStatus("Task ${task.id}: ${task.description}")
        appendTaskLog("task_start") {
            put("task_id", task.id)
            put("description", task.description)
            put("category", task.category ?: "")
            put("plan_goal", plan.goal)
        }

        val attemptNo = incrementAttempts(task.id)
        if (attemptNo > 3) {
            val note = "attempts_exceeded_${attemptNo}"
            markTaskFailed(task.id, note)
            onStatus("Task ${task.id}: attempts exceeded; requesting plan update")
            endRunStatsAndReport(onStatus, verb = "thought")
            return false
        }

        var stepsTaken = 0
        val maxSteps = 5
        var lastObservation: String? = null
        var lastToolType: String? = null
        var repeatedObservationCount = 0
        val maxRepeatedObservations = 3
                val startTime = System.currentTimeMillis()
        val maxExecutionTime = 120000L // 120 seconds timeout

        while (stepsTaken < maxSteps) {
            // Check for timeout to prevent infinite loops with detailed debugging
            if (System.currentTimeMillis() - startTime > maxExecutionTime) {
                val timeoutDebugInfo = """
                    Task ${task.id} FAILED - Execution Timeout:
                    - Task: ${task.description}
                    - Category: ${task.category}
                    - Steps Taken: $stepsTaken
                    - Max Steps: $maxSteps
                    - Execution Time: ${System.currentTimeMillis() - startTime}ms
                    - Max Execution Time: ${maxExecutionTime}ms
                    - Last Tool: ${lastToolType ?: "none"}
                    - Reason: execution_timeout
                """.trimIndent()
                
                onStatus(timeoutDebugInfo)
                markTaskFailed(task.id, "execution_timeout")
                endRunStatsAndReport(onStatus, verb = "thought")
                return false
            }
            val toolCall = requestSingleToolCall(plan.goal, task)
            if (toolCall == null) {
                observations[task.id] = "could not determine action for this task"
                saveObservations()
                onStatus("Task ${task.id}: no action suggested; revising plan…")
                appendTaskLog("tool_call_none") { put("task_id", task.id) }
                val revised = revisePlanBasedOnHistoryAndError(plan.goal, "no_tool_call")
                if (revised != null) {
                    persistPlanWithStatuses(revised)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                onStatus("Task ${task.id}: plan revision unavailable; deciding remediation…")
                val decision = decideRemediationAction(plan.goal, task, "no_tool_call")
                when (decision) {
                    "mini_plan" -> {
                        val ok = executeMiniPlanForTask(plan, task, onStatus)
                        if (ok) { onStatus("Mini-plan completed; retrying task ${task.id}"); stepsTaken++; continue } else return false
                    }
                    "revise_plan" -> {
                        val revised2 = revisePlanBasedOnHistoryAndError(plan.goal, "no_tool_call")
                        if (revised2 != null) { persistPlanWithStatuses(revised2); endRunStatsAndReport(onStatus, verb = "thought"); return true } else return false
                    }
                    "retry" -> { stepsTaken++; continue }
                    else -> { return false }
                }
            }
            // Writer agent may refine write tool selections for modifying actions
            val coerced = coerceToolCallForTaskCategory(task, toolCall)
            val effectiveToolCall = if (isModifyingTool(coerced.type) && Settings.writer_agent_enabled) {
                runCatching { writerSuggestTool(plan.goal, task, coerced) }.getOrNull() ?: coerced
            } else coerced
                        appendTaskLog("tool_call_selected") { put("task_id", task.id); put("type", effectiveToolCall.type); put("args", effectiveToolCall.args) }
            runCatching {
                val kind = detectTaskKind(task)
                ControlApiClient.recordTaskDetection(sessionId, task.id, kind, task.description, task.category)
            }
            val result = runCatching {
                currentTaskContext = task
                executeToolCall(effectiveToolCall)
            }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                saveObservations()
                onStatus("Task ${task.id} failed: ${err}; revising plan…")
                appendTaskLog("task_error") { put("task_id", task.id); put("error", err.take(1000)) }
                val revised = revisePlanBasedOnHistoryAndError(plan.goal, err)
                if (revised != null) {
                    persistPlanWithStatuses(revised)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                onStatus("Task ${task.id}: plan revision unavailable; proceeding with remediation…")
                ToolResult(false, null)
            }.also { currentTaskContext = null }
            appendTaskLog("tool_result") {
                put("task_id", task.id)
                put("type", effectiveToolCall.type)
                put("ok", result.ok)
                result.observation?.let { put("observation_preview", it.take(800)); put("observation_bytes", it.toByteArray(StandardCharsets.UTF_8).size) }
            }
            // Attempt back-plan fix if a modifying tool failed
            if (!result.ok && Settings.backplan_enabled && isModifyingTool(effectiveToolCall.type)) {
                val targets = mutableSetOf<String>()
                when (effectiveToolCall.type) {
                    "apply_changes" -> {
                        val edits = effectiveToolCall.args.optJSONArray("edits") ?: JSONArray()
                        for (i in 0 until edits.length()) {
                            edits.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { targets.add(it) }
                        }
                    }
                    "write_file", "search_replace", "delete_file", "copy_file", "move_file" -> {
                        effectiveToolCall.args.optString("path").takeIf { it.isNotBlank() }?.let { targets.add(it) }
                    }
                }
                var fixed = false
                for (p in targets) {
                    val intended = when (effectiveToolCall.type) {
                        "apply_changes" -> effectiveToolCall.args.toString().take(4000)
                        "write_file" -> effectiveToolCall.args.optString("content").take(4000)
                        "search_replace" -> JSONObject().put("old", effectiveToolCall.args.optString("old")).put("new", effectiveToolCall.args.optString("new")).toString()
                        else -> ""
                    }
                    val failureNote = result.observation?.take(400) ?: "tool_failed"
                    val ok = runBackPlanFixIfNeeded(p, intended = intended, userInstruction = task.description, failureNote = failureNote, onStatus = onStatus)
                    if (ok) { fixed = true; break }
                }
                if (fixed) {
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
            }

            // Refresh codebase cache immediately after modifying tools so next steps see updated state
            if (Settings.codebase_agent_enabled && isModifyingTool(effectiveToolCall.type)) {
                performCodebaseUpgradeIfPending(onStatus)
                // Add/update codebase observation for LLM context
                runCatching {
                    val cb = File(workingDirProvider(), Settings.codebase_cache_path)
                    if (cb.exists()) {
                        val text = cb.readText()
                        observations["codebase"] = text.take(120000)
                        saveObservations()
                    }
                }
            }

            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[task.id] = result.observation
                    saveObservations()
                    val preview = result.observation.take(800)
                    val info = informativeForTask(plan.goal, task, result.observation)
                    if (info != null) {
                        val what = info.optString("what").ifBlank { null }
                        if (what != null) onStatus(what) else onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                    } else {
                        onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                    }
                }

                // If this task is a Python/pip presence check and output shows versions, consider it complete
                runCatching {
                    val cmdStr = if (effectiveToolCall.type == "run_shell") effectiveToolCall.args.optString("command").lowercase() else ""
                    val outLower = result.observation?.lowercase().orEmpty()
                    val isPyCheckTask = task.description.lowercase().let { it.contains("python") || it.contains("pip") } &&
                            (task.description.lowercase().contains("check") || task.description.lowercase().contains("installed") || task.description.lowercase().contains("accessible") || task.description.lowercase().contains("version") || task.description.lowercase().contains("discover"))
                    
                    // Check for Python version output (e.g., "Python 3.12.11")
                    val pythonVersionSignal = outLower.contains("python ") && outLower.matches(Regex(".*python\\s+\\d+\\.\\d+\\.\\d+.*"))
                    
                    // Check for pip version output (e.g., "pip 23.x.x")
                    val pipVersionSignal = outLower.contains("pip ") && outLower.matches(Regex(".*pip\\s+\\d+.*"))
                    
                    // Check for successful version discovery
                    val versionSignals = pythonVersionSignal || pipVersionSignal
                    
                    if (effectiveToolCall.type == "run_shell" && isPyCheckTask && versionSignals) {
                        markTaskDone(task.id)
                        onStatus("Task ${task.id}: Python version discovered successfully")
                        persistPlanWithStatuses(plan)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    }
                }

                // If the tool modified the workspace, consider the task complete.
                if (isModifyingTool(effectiveToolCall.type)) {
                    markTaskDone(task.id)
                    val info = informativeForTask(plan.goal, task, observations[task.id])
                    if (info != null) {
                        val success = info.optString("success").ifBlank { null }
                        if (success != null) onStatus(success) else onStatus("Task ${task.id}: done")
                    } else {
                        onStatus("Task ${task.id}: done")
                    }
                    // Ensure UI sees latest statuses
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                // Installation via run_shell succeeded; treat as completion
                if (lastInstallSuccess) {
                    lastInstallSuccess = false
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: installation complete")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // Special handling for PEP 668 externally managed environment - mark as done for any pip install
                if (effectiveToolCall.type == "run_shell" && result.observation?.lowercase()?.contains("externally-managed-environment") == true) {
                    val cmd = effectiveToolCall.args.optString("command").lowercase()
                    if (cmd.contains("pip") && cmd.contains("install")) {
                        onStatus("Task ${task.id}: Python package installation attempted (PEP 668 environment detected)")
                        markTaskDone(task.id)
                        persistPlanWithStatuses(plan)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    }
                }

                // If this is a discovery tool and the task category is discovery, or env preflight shell, complete the task now.
                val envPreflight = effectiveToolCall.type == "run_shell" && isEnvPreflightCommand(effectiveToolCall.args.optString("command"))
                if ((isDiscoveryTool(effectiveToolCall.type) && isDiscoveryCategory(task.category)) || envPreflight) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: discovery completed successfully")
                    // Ensure UI sees latest statuses
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // If this is a successful discovery command that returned useful information, complete the task
                val isDiscoveryCommand = effectiveToolCall.type == "run_shell" && 
                                        (task.description.lowercase().contains("discover") || 
                                         task.description.lowercase().contains("check") ||
                                         task.description.lowercase().contains("version"))
                val hasUsefulOutput = !result.observation.isNullOrBlank() && 
                                     result.observation.length > 10 && 
                                     !result.observation.lowercase().contains("error") &&
                                     !result.observation.lowercase().contains("not found")
                
                if (isDiscoveryCommand && hasUsefulOutput) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: discovery completed successfully")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                			// If this is a development server task that started successfully, complete the task
			val isDevServerTask = effectiveToolCall.type == "run_shell" && 
								 (task.description.lowercase().contains("server") || 
								  task.description.lowercase().contains("run") ||
								  task.description.lowercase().contains("start") ||
								  task.description.lowercase().contains("development") ||
								  task.description.lowercase().contains("flask"))
			val serverStartedSuccessfully = !result.observation.isNullOrBlank() && 
										  (result.observation.lowercase().contains("running") ||
										   result.observation.lowercase().contains("serving") ||
										   result.observation.lowercase().contains("debug") ||
										   result.observation.lowercase().contains("localhost") ||
										   result.observation.lowercase().contains("127.0.0.1") ||
										   result.observation.lowercase().contains("0.0.0.0") ||
										   result.observation.lowercase().contains("flask") ||
										   !result.observation.lowercase().contains("error"))
                
                if (isDevServerTask && serverStartedSuccessfully) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: development server started successfully")
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                // Prevent loops on repeated identical non-modifying observations
                val obs = result.observation
                if (lastToolType == effectiveToolCall.type && obs != null && lastObservation == obs) {
                    repeatedObservationCount++
                    
                    // Special handling for empty directory listings - don't fail immediately
                    val isListDir = effectiveToolCall.type == "list_dir" || effectiveToolCall.type == "list_dir_recursive"
                    val isEmptyDir = isListDir && obs.contains("\"empty\":true")
                    
                                    // Special handling for reading files when should be writing instead
                val isReadFile = effectiveToolCall.type == "read_file"
                val isEmptyFile = isReadFile && obs.contains("\"bytes\":0") && obs.contains("\"content\":\"\"")
                val hasContent = isReadFile && obs.contains("\"bytes\":") && !obs.contains("\"bytes\":0")
                val shouldBeWriting = shouldUseWriteFile(task)
                
                // Special handling for listing directories when should be creating directories
                val shouldBeCreatingDir = shouldUseMakeDir(task)
                
                // For empty directories, allow only 1 retry then fail gracefully
                if (isEmptyDir && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: empty directory detected, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading empty files when should be writing, mark as done and suggest correction
                if (isEmptyFile && shouldBeWriting && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading empty file when should be writing, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading files with content when should be writing, mark as done
                if (hasContent && shouldBeWriting && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading file with content when should be writing, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For reading empty files when should be writing content, don't mark as done - let the agent write content
                val isWebTemplate = isEmptyFile && shouldBeWriting && 
                                  (task.description.lowercase().contains("html") || 
                                   task.description.lowercase().contains("template") ||
                                   task.description.lowercase().contains("interface"))
                if (isWebTemplate && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected reading empty file when should be writing content - allowing agent to write content")
                    // Don't mark as done - let the agent actually write content
                    lastObservation = obs
                    lastToolType = effectiveToolCall.type
                    stepsTaken++
                    continue
                }
                
                // For listing directories when should be creating directories, mark as done
                if (isListDir && shouldBeCreatingDir && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected listing directory when should be creating directory, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For listing directories when should be editing files, mark as done
                val shouldBeEditing = shouldUseEditTool(task)
                if (isListDir && shouldBeEditing && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: detected listing directory when should be editing files, marking task complete")
                    markTaskDone(task.id)
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                
                // For creating empty files when should be writing content, fail and request proper content
                val isCreateFile = effectiveToolCall.type == "create_file"
                val shouldBeWritingContent = shouldUseEditTool(task) || task.category == "write_file"
                if (isCreateFile && shouldBeWritingContent && repeatedObservationCount <= 1) {
                    onStatus("Task ${task.id}: creating empty file is invalid when content is required; asking for write_file with content")
                    markTaskFailed(task.id, "empty_create_file_when_content_required")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                
                // Special handling for placeholder commands (echo noop, etc.) when should be running real commands
                val isRunShell = effectiveToolCall.type == "run_shell"
                val isPlaceholderCommand = isRunShell && obs?.lowercase()?.contains("noop") == true
                val shouldBeRunningRealCommand = shouldRunRealCommand(task)
                
                // For placeholder commands when should be running real commands, mark as done immediately
                if (isPlaceholderCommand && shouldBeRunningRealCommand) {
                    onStatus("Task ${task.id}: detected placeholder command when should be running real command - forcing agent to run actual command")
                    // Don't mark as done - force the agent to run the real command
                    markTaskFailed(task.id, "placeholder_command_detected")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                    
                    // For other repeated observations, fail after 2 attempts with detailed debugging (but be lenient during file generation/update)
                    val isWriteOp = effectiveToolCall.type == "write_file" || effectiveToolCall.type == "create_file" || effectiveToolCall.type == "apply_changes"
                    if (repeatedObservationCount >= 2) {
                        if (isWriteOp) {
                            onStatus("Task ${task.id}: repeated observation during file generation/update (${repeatedObservationCount}), allowing extra attempt")
                            lastObservation = obs
                            lastToolType = effectiveToolCall.type
                            stepsTaken++
                            continue
                        } else {
                            val debugInfo = """
                            Task ${task.id} FAILED - Debug Info:
                            - Task: ${task.description}
                            - Category: ${task.category}
                            - Tool Type: ${effectiveToolCall.type}
                            - Tool Args: ${effectiveToolCall.args}
                            - Observation: ${obs?.take(200)}...
                            - Repeated Count: $repeatedObservationCount
                            - Reason: repeated_non_modifying_observation
                        """.trimIndent()
                        
                            onStatus(debugInfo)
                            markTaskFailed(task.id, "repeated_non_modifying_observation")
                            endRunStatsAndReport(onStatus, verb = "thought")
                            return false
                        }
                    }
                    
                    // Allow one more attempt with debugging info
                    onStatus("Task ${task.id}: repeated observation (${repeatedObservationCount}/2), retrying... Tool: ${effectiveToolCall.type}")
                    lastObservation = obs
                    lastToolType = effectiveToolCall.type
                    stepsTaken++
                    continue
                }
                lastObservation = result.observation ?: lastObservation
                lastToolType = effectiveToolCall.type
                stepsTaken++

            } else {
                // Check for specific failure types before general failure
                val obs = result.observation?.lowercase() ?: ""
                
                // Handle PEP 668 errors even in failure case
                if (effectiveToolCall.type == "run_shell" && obs.contains("externally-managed-environment")) {
                    val cmd = effectiveToolCall.args.optString("command").lowercase()
                    if (cmd.contains("pip") && cmd.contains("install")) {
                        onStatus("Task ${task.id}: Python package installation attempted (PEP 668 environment detected) - suggesting virtual environment")
                        // Don't mark as done - let the agent try virtual environment approach
                        markTaskFailed(task.id, "pep668_externally_managed_env")
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return false
                    }
                }
                
                // Check for package installation failures and suggest virtual environment
                val isPackageInstallFailure = effectiveToolCall.type == "run_shell" && 
                                            (obs.contains("no such package") || 
                                             obs.contains("unable to select packages") ||
                                             obs.contains("package not found"))
                
                if (isPackageInstallFailure) {
                    onStatus("Task ${task.id}: Package installation failed - suggesting virtual environment approach")
                    // Don't mark as failed - let the agent try virtual environment
                    markTaskFailed(task.id, "package_installation_failed")
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return false
                }
                
                // Failure without exception - provide detailed debugging
                val failureDebugInfo = """
                    Task ${task.id} FAILED - General Failure:
                    - Task: ${task.description}
                    - Category: ${task.category}
                    - Tool Type: ${effectiveToolCall.type}
                    - Tool Args: ${effectiveToolCall.args}
                    - Result OK: ${result.ok}
                    - Observation: ${result.observation?.take(200)}...
                    - Reason: general_failure
                """.trimIndent()
                
                onStatus(failureDebugInfo)
                markTaskFailed(task.id, "general_failure")
                endRunStatsAndReport(onStatus, verb = "thought")
                return false
            }
        }

        // Step limit reached - provide detailed debugging and fail gracefully
        val stepLimitDebugInfo = """
            Task ${task.id} FAILED - Step Limit Reached:
            - Task: ${task.description}
            - Category: ${task.category}
            - Steps Taken: $stepsTaken
            - Max Steps: $maxSteps
            - Last Tool: ${lastToolType ?: "none"}
            - Last Observation: ${lastObservation?.take(200)}...
            - Reason: step_limit_exceeded
        """.trimIndent()
        
        onStatus(stepLimitDebugInfo)
        markTaskFailed(task.id, "step_limit_exceeded")
        endRunStatsAndReport(onStatus, verb = "thought")
        return false
    }

    private suspend fun informativeForTask(planGoal: String, task: Task, lastObservation: String?): JSONObject? = withContext(Dispatchers.IO) {
        if (!Settings.informative_agent_enabled) return@withContext null
        val sys = """
            You generate brief, friendly progress updates.
            Return ONLY minified JSON: {"what": string, "success": string}
        """.trimIndent()
        val user = """
            Goal: ${planGoal}
            Task: ${task.id} - ${task.description}
            Context: ${lastObservation?.take(600) ?: "(none)"}
        """.trimIndent()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        return@withContext runCatching { JSONObject(json) }.getOrNull()
    }

    private data class ToolCall(
        val type: String,
        val args: JSONObject
    )

    private data class ToolResult(
        val ok: Boolean,
        val observation: String?
    )

    private fun isModifyingTool(type: String): Boolean {
        return when (type) {
            "write_file", "apply_changes", "make_dir", "create_file", "search_replace", "delete_file", "copy_file", "move_file", "json_set" -> true
            else -> false
        }
    }

    private fun isDiscoveryTool(type: String): Boolean {
        return when (type) {
            "read_file", "list_dir", "grep", "read_file_lines", "stat_file", "read_file_section_by_markers", "read_files", "read_files_glob", "list_dir_recursive", "get_cached_command_output", "list_cached_commands", "run_shell", "head_file", "tail_file", "read_file_chunk", "json_get" -> true
            else -> false
        }
    }

    private fun isDiscoveryCategory(category: String?): Boolean {
        return when (category) {
            "read_file", "list_dir", "grep", "analyze" -> true
            else -> false
        }
    }
    
    private fun isWriteCategory(category: String?): Boolean {
        return when (category) {
            "write_file", "create_file" -> true
            else -> false
        }
    }
    
    private fun shouldUseWriteFile(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("write") || desc.contains("create") || desc.contains("add") || 
               desc.contains("generate") || desc.contains("build") || desc.contains("make")
    }
    
    private fun shouldUseMakeDir(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("create") && (desc.contains("directory") || desc.contains("dir") || desc.contains("folder")) ||
               task.category == "make_dir"
    }
    
    private fun shouldRunRealCommand(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("run") || desc.contains("start") || desc.contains("server") || 
               desc.contains("flask") || desc.contains("execute") || desc.contains("launch") ||
               desc.contains("install") || desc.contains("dependency") || task.category == "run_shell"
    }
    
    private fun shouldUseEditTool(task: Task): Boolean {
        val desc = task.description.lowercase()
        return desc.contains("add") || desc.contains("edit") || desc.contains("modify") || desc.contains("update") ||
               task.category == "json_edit" || task.category == "write_file" || task.category == "search_replace"
    }

    private suspend fun requestSingleToolCall(goal: String, task: Task): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            You are a senior software engineer responsible for writing complete, functional, and high-quality code.
            Your task is to orchestrate a short inner loop to complete the current task using the available tools.
            You must return ONLY a single minified JSON object describing ONE tool call to move the task forward.

            **CRITICAL DIRECTIVE: YOU MUST GENERATE FULL, WORKING CODE. NO PLACEHOLDERS.**
            - Never use pre-made templates for specific apps. Infer requirements strictly from the goal and a live codebase overview built via discovery tools.
            - When asked to create a file, provide complete, functional code coherent with existing files and expectations.
            - Do NOT use placeholder comments like "// TODO: implement", "// ...", or similar.
            - Do NOT create empty files. Use the `write_file` tool and provide the full content.
            - Ensure that all generated files work together to create a cohesive and functional application.

            **IMPORTANT: For any file modification, you must first read the file to understand its content and structure. Use the `read_file` tool before using `write_file` or `apply_changes` to ensure you are making the correct modifications.**

            OUTPUT FORMAT FOR UPDATES (MANDATORY):
            - Return ONLY one minified JSON tool call.
            - For modifications, prefer `apply_changes` with at most 4 targeted edits.
            - Each edit must include the `path` and one op among: `replace_exact`, `replace_between_markers`, `insert_after_anchor`, `insert_before_anchor`, `replace_regex`, `ensure_block_present`, `append_once`, `replace_lines`, `insert_lines_after`, `insert_lines_before`, or `write_if_missing`.
            - Include precise anchors/markers or regex patterns. Keep changes minimal and idempotent.
            - For full new files, use `write_file` with the entire file content.

            Allowed schemas:
             {"type":"create_file","args":{"path": string}}
             {"type":"write_file","args":{"path": string, "content": string, "mode": "overwrite"|"append", "if_not_exists": boolean, "encoding": "utf-8"|"base64"}}
             {"type":"make_dir","args":{"path": string}}
             {"type":"delete_file","args":{"path": string, "missing_ok": boolean}}
             {"type":"copy_file","args":{"src": string, "dest": string, "overwrite": boolean}}
             {"type":"move_file","args":{"src": string, "dest": string, "overwrite": boolean}}
             {"type":"run_shell","args":{"command": string, "timeout_ms": number, "env": {string: string}}}
             {"type":"get_cached_command_output","args":{"command": string, "max_age_ms": number}}
             {"type":"list_cached_commands","args":{"max": number}}
             {"type":"list_dir","args":{"path": string, "include_hidden": boolean, "max_entries": number}}
             {"type":"list_dir_recursive","args":{"path": string, "max_depth": number, "max_entries": number, "include_hidden": boolean}}
             {"type":"read_file","args":{"path": string, "max_bytes": number, "encoding": "utf-8"|"base64"}}
             {"type":"head_file","args":{"path": string, "lines": number}}
             {"type":"tail_file","args":{"path": string, "lines": number}}
             {"type":"read_file_lines","args":{"path": string, "start": number, "end": number, "max_bytes": number}}
             {"type":"read_file_chunk","args":{"path": string, "offset": number, "length": number, "encoding": "utf-8"|"base64"}}
             {"type":"read_file_section_by_markers","args":{"path": string, "start_marker": string, "end_marker": string, "include_markers": boolean}}
             {"type":"read_files","args":{"paths": [string,...], "max_bytes": number}}
             {"type":"read_files_glob","args":{"root": string, "glob": string, "max_files": number, "max_bytes": number, "max_depth": number}}
             {"type":"stat_file","args":{"path": string}}
             {"type":"grep","args":{"path": string, "pattern": string, "max_results": number, "include_binary": boolean}}
             {"type":"search_replace","args":{"path": string, "old": string, "new": string, "unique": boolean}}
             {"type":"json_get","args":{"path": string, "json_pointer": string}}
             {"type":"json_set","args":{"path": string, "json_pointer": string, "value": string, "value_is_json": boolean}}
             {"type":"apply_changes","args":{"edits": [
                 {"path": string, "op": "replace_exact", "old": string, "new": string},
                 {"path": string, "op": "replace_between_markers", "start_marker": string, "end_marker": string, "new_content": string, "include_markers": false},
                 {"path": string, "op": "insert_after_anchor", "anchor": string, "new_content": string},
                 {"path": string, "op": "insert_before_anchor", "anchor": string, "new_content": string},
                 {"path": string, "op": "replace_regex", "pattern": string, "replacement": string, "unique": boolean},
                 {"path": string, "op": "ensure_block_present", "block": string, "idempotent_marker": string, "anchor_before": string, "anchor_after": string},
                 {"path": string, "op": "append_once", "block": string, "idempotent_marker": string},
                 {"path": string, "op": "write_if_missing", "content": string},
                 {"path": string, "op": "replace_lines", "start_line": number, "end_line": number, "new_content": string},
                 {"path": string, "op": "insert_lines_after", "line_number": number, "new_content": string},
                 {"path": string, "op": "insert_lines_before", "line_number": number, "new_content": string}
             ]}}
             
             ## DEVELOPMENT STANDARDS & BEST PRACTICES
             
             ### Project Structure & Organization
             - Create proper project directories with clear organization (e.g., `src/`, `templates/`, `static/`).
             - Use standard naming conventions (e.g., `snake_case` for Python, `camelCase` for JavaScript).
             - Separate concerns: templates, static files, configuration, and tests should be in their own directories.
             - Always include a `README.md` with setup and usage instructions.
             - For Flask applications, create a proper structure with `templates/` and `static/` directories.
             
             ### Code Quality Standards
             - Write clean, readable, and well-documented code.
             - Include proper error handling and validation in all code.
             - Use type hints in Python where appropriate.
             - Follow language-specific best practices and conventions.
             - Add comments for complex logic.
             - Handle edge cases and provide meaningful error messages.
             - Use proper HTTP status codes and error responses in web applications.
             
             ### Python/Flask Applications
             - Always use virtual environments: `python3 -m venv venv && . venv/bin/activate`.
             - Create a `requirements.txt` file with all necessary packages and their versions.
             - Use the Flask application factory pattern for larger applications.
             - Implement proper template inheritance and organize static files correctly.
             - Manage configurations and secrets using environment variables, not hardcoded values.
             - Include comprehensive logging and debugging capabilities.
             
             ### Web Applications
             - Create responsive, mobile-friendly designs.
             - Use semantic HTML and ensure accessibility (e.g., alt tags for images).
             - Implement a clear CSS organization methodology (e.g., BEM).
             - Add robust JavaScript error handling and provide user feedback for all actions.
             - Include loading states and progress indicators for long-running operations.
             
             Return pure JSON on a single line without explanations.
         """.trimIndent()
        val wd = workingDirProvider()
        val prior = if (observations.isEmpty()) "(none)" else observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(500)}${if (v.length > 500) " …" else ""}" }
        val contextSummary = getContextSummary()
        val hints = buildString {
            if (!task.targets.isNullOrEmpty()) append("targets: ").append(task.targets.joinToString(", ")).append('\n')
            if (!task.search.isNullOrEmpty()) append("search: ").append(task.search.joinToString(", ")).append('\n')
            if (!task.markers.isNullOrEmpty()) append("markers: ").append(task.markers.joinToString(", ")).append('\n')
        }.ifBlank { "(none)" }
        val prompt = """
            Goal: ${goal}
            Project Requirements: ${projectRequirements ?: goal}
            Working directory: ${wd}
            Current task id: ${task.id}
            Task: ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Task hints: ${hints}
            
            ${contextSummary}
            
            Prior observations (latest first):
            ${prior}
            
            IMPORTANT: Never assume a specific app type or inject premade templates. Infer expectations strictly from the goal and observed codebase. Generate only code and edits that are coherent with the existing files and the stated expectations. No placeholders.
            
            Produce one tool call JSON now, following the Rules and leveraging hints and observations to avoid redundant discovery.
        """.trimIndent()
        val msgs = mutableListOf(
            LlmMessage("system", sys),
            LlmMessage("user", prompt)
        )
        val reco = helperRecommend("inner_loop", mapOf("goal" to goal.take(500), "task" to "${task.id}:${task.description}"))
        applyHelperToMessages(reco, msgs)
        val flow = LlmProvider.current().generate(msgs)
        val content = collectAllWithRetry(flowProvider = { flow })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val type = obj.optString("type")
        val args = obj.optJSONObject("args") ?: JSONObject()
        return@withContext ToolCall(type, args)
    }

    private fun resolvePath(raw: String): File {
        val base = File(workingDirProvider())
        val f = File(raw)
        return if (f.isAbsolute) f else File(base, raw).absoluteFile
    }

    private fun ensureParentDirs(file: File) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    }

    private fun executeToolCall(tc: ToolCall): ToolResult {
        currentRunStats?.let { st -> st.toolCounts[tc.type] = (st.toolCounts[tc.type] ?: 0) + 1 }
        
        // Add timeout protection for file operations
        val startTime = System.currentTimeMillis()
        val maxFileOpTime = 120000L // 120 seconds max for file operations
        
        // Add global timeout protection to prevent freezing
        val globalTimeout = 180000L // 180 seconds max for any operation
        // Fallback: if tool type is blank or unknown, attempt to coerce from current task category
        if (tc.type.isBlank() || tc.type.equals("unknown", ignoreCase = true)) {
            val task = currentTaskContext
            if (task != null) {
                val fallbackType = (task.category ?: "unknown").lowercase()
                val coerced = coerceToolCallForTaskCategory(task, ToolCall(fallbackType, tc.args))
                return executeToolCall(coerced)
            }
        }
        // Alias common synonyms to reduce failure due to type mismatches
        val normalizedType = when (tc.type.lowercase().trim()) {
            "ls", "dir", "list", "listdir", "list_directory", "read_dir", "read_directory", "scan_dir" -> "list_dir"
            "tree", "find", "list_recursive", "scan_recursive", "walk", "walk_dir", "walkdir" -> "list_dir_recursive"
            "stat" -> "stat_file"
            "cat" -> "read_file"
            "sed", "replace" -> "search_replace"
            "mkdir" -> "make_dir"
            "touch" -> "create_file"
            "shell", "bash", "sh" -> "run_shell"
            "json_edit" -> "apply_changes"
            else -> tc.type
        }
        val call = if (normalizedType == tc.type) tc else ToolCall(normalizedType, tc.args)
        appendTaskLog("tool_execute") {
            put("task_id", currentTaskContext?.id ?: JSONObject.NULL)
            put("type", call.type)
            put("args", call.args)
        }
        return when (call.type) {
            "create_file" -> {
                var path = call.args.optString("path")
                if (path.isBlank()) {
                    val t = currentTaskContext
                    val hinted = t?.targets?.firstOrNull()?.trim().orEmpty()
                    if (hinted.isNotBlank()) {
                        path = hinted
                    } else {
                        val wd = workingDirProvider()
                        val base = (t?.description ?: "NEW_FILE").lowercase().replace(Regex("[^a-z0-9._/\\-]+"), "-").trim('-')
                        val ext = when {
                            base.contains("python") || base.contains("flask") -> "py"
                            base.contains("html") || base.contains("template") -> "html"
                            base.contains("css") -> "css"
                            base.contains("js") || base.contains("javascript") -> "js"
                            base.contains("readme") -> "md"
                            base.contains("requirements") -> "txt"
                            else -> "txt"
                        }
                        val fileName = if (base.contains('.')) base else (base.ifBlank { "NEW_FILE" } + "." + ext)
                        path = File(wd, fileName).absolutePath
                    }
                }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "create_file_timeout: operation took too long")
                }
                
                try {
                    val f = resolvePath(path)
                    ensureParentDirs(f)
                    if (!f.exists()) f.createNewFile()
                    if (f.exists()) notifyWorkspaceChanged(f.absolutePath)
                    
                    // Auto-generate initial content for the new file using the AI and codebase context
                    if (f.exists() && f.length() == 0L) {
                        fun stripFences(txt: String): String {
                            val triple = Regex("```[a-zA-Z0-9]*\n([\u0000-\uFFFF]*?)```", RegexOption.DOT_MATCHES_ALL)
                            val m = triple.find(txt)
                            return if (m != null) m.groupValues[1] else txt.trim('\n', '\r', ' ')
                        }
                        val goal = projectRequirements ?: lastPlanGoal ?: ""
                        val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { "" }.take(120000)
                        val ctxSummary = getContextSummary()
                        val sys = """
                            You are writing a single complete file for a project. Return ONLY the file content. No backticks, no fences, no explanations.
                            Ensure coherence with the existing codebase summary provided.
                        """.trimIndent()
                        val user = """
                            Goal: ${goal}
                            Target path: ${f.absolutePath}
                            Project context summary:\n${ctxSummary}
                            Codebase cache (truncated):\n${codebase}
                            Write the full content for the file: ${f.name}
                        """.trimIndent()
                        val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
                        val generated = try { kotlinx.coroutines.runBlocking { collectAllWithRetry(flowProvider = { flow }) } } catch (e: Exception) { "" }
                        val content = stripFences(generated)
                        if (content.isNotBlank() && !content.contains("No AI API configured", ignoreCase = true)) {
                            f.writeText(content)
                            // Sync new file with codebase agent
                            runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, content, true) }
                        }
                    }
                    if (f.exists()) notifyWorkspaceChanged(f.absolutePath)
                    ToolResult(f.exists(), JSONObject().put("path", f.absolutePath).put("bytes", f.length()).toString())
                } catch (e: Exception) {
                    ToolResult(false, "create_file_error: ${e.message}")
                }
            }
            "write_file" -> {
                var path = call.args.optString("path")
                var contentRaw = call.args.optString("content")
                val encoding = call.args.optString("encoding", "utf-8").lowercase()
                val mode = call.args.optString("mode", "overwrite")
                val ifNotExists = call.args.optBoolean("if_not_exists", false)
                if (path.isBlank()) {
                    val t = currentTaskContext
                    val hinted = t?.targets?.firstOrNull()?.trim().orEmpty()
                    path = if (hinted.isNotBlank()) hinted else File(workingDirProvider(), "NEW_FILE.txt").absolutePath
                }
                
                // Generate content via API if missing or placeholder
                fun looksPlaceholder(txt: String): Boolean {
                    val low = txt.trim().lowercase()
                    return low.isBlank() || low.contains("todo") || low == "..." || low.contains("placeholder")
                }
                if (looksPlaceholder(contentRaw)) {
                    fun stripFences(txt: String): String {
                        val triple = Regex("```[a-zA-Z0-9]*\n([\u0000-\uFFFF]*?)```", RegexOption.DOT_MATCHES_ALL)
                        val m = triple.find(txt)
                        return if (m != null) m.groupValues[1] else txt.trim('\n', '\r', ' ')
                    }
                    val goal = projectRequirements ?: lastPlanGoal ?: ""
                    val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { "" }.take(120000)
                    val ctxSummary = getContextSummary()
                    val taskDesc = currentTaskContext?.description ?: "write file"
                    val sys = """
                        You are writing a complete file for a project. Return ONLY the file content without fences or explanations.
                        Use the codebase summary to integrate with existing files, functions, and routes.
                    """.trimIndent()
                    val user = """
                        Goal: ${goal}
                        Task: ${taskDesc}
                        Target path: ${path}
                        Project context summary:\n${ctxSummary}
                        Codebase cache (truncated):\n${codebase}
                        Write the full content for the file: ${File(path).name}
                    """.trimIndent()
                    val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
                    val generated = try { kotlinx.coroutines.runBlocking { collectAllWithRetry(flowProvider = { flow }) } } catch (e: Exception) { "" }
                    val content = stripFences(generated)
                    if (content.isNotBlank() && !content.contains("No AI API configured", ignoreCase = true)) {
                        contentRaw = content
                    }
                }
                
                // Check content size to prevent memory issues
                if (contentRaw.length > 1024 * 1024) { // 1MB limit
                    return ToolResult(false, "content_too_large: content exceeds 1MB limit")
                }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "write_file_timeout: operation took too long")
                }
                
                // Check global timeout
                if (System.currentTimeMillis() - startTime > globalTimeout) {
                    return ToolResult(false, "global_timeout: operation exceeded maximum time limit")
                }
                
                try {
                    val f = resolvePath(path)
                    ensureParentDirs(f)
                    if (ifNotExists && f.exists()) {
                        return ToolResult(true, "skipped_write_existing:${f.absolutePath}")
                    }
                    
                    if (contentRaw.isBlank()) {
                        // Attempt a back-plan corrective fix
                        val attempted = runCatching {
                            val t = currentTaskContext
                            val desc = t?.description ?: ""
                            kotlinx.coroutines.runBlocking {
                                runBackPlanFixIfNeeded(path, intended = "(intended write_file with content)", userInstruction = desc, failureNote = "empty_content_after_generation", onStatus = { })
                            }
                        }.getOrElse { false }
                        if (attempted) {
                            return ToolResult(true, "backplan_applied:${f.absolutePath}")
                        }
                        return ToolResult(false, "empty_content: write_file requires non-empty content after generation")
                    }
                    
                    // Write content in chunks to avoid memory issues
                    val bytes = if (encoding == "base64") {
                        try {
                            Base64.decode(contentRaw, Base64.DEFAULT)
                        } catch (e: Exception) {
                            return ToolResult(false, "base64_decode_error: ${e.message}")
                        }
                    } else {
                        contentRaw.toByteArray(StandardCharsets.UTF_8)
                    }
                    
                    if (mode == "append" && f.exists()) {
                        f.appendBytes(bytes)
                    } else {
                        f.writeBytes(bytes)
                    }
                    
                    val ok = f.exists() && f.length() >= 0
                    if (ok) {
                        notifyWorkspaceChanged(f.absolutePath)
                        
                        // Update context cache for code files and schedule codebase cache upgrade
                        val fileType = when {
                            f.extension.lowercase() == "py" -> "python"
                            f.extension.lowercase() == "js" -> "javascript"
                            f.extension.lowercase() == "html" -> "html"
                            f.extension.lowercase() == "css" -> "css"
                            f.name.lowercase() == "requirements.txt" -> "config"
                            f.name.lowercase() == "readme.md" -> "documentation"
                            else -> "unknown"
                        }
                        updateContextCache(f.absolutePath, contentRaw, fileType)
                        notifyWorkspaceChanged(f.absolutePath)
                        // Sync modified/new file with codebase agent
                        runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, contentRaw, !f.exists()) }
                    }
                    
                    // Skip hash calculation for large files to prevent freezing
                    val hash = if (f.length() < 100 * 1024) { // Only hash files smaller than 100KB
                        try {
                            val md = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                            md.joinToString("") { String.format("%02x", it) }
                        } catch (e: Exception) {
                            "hash_calculation_failed"
                        }
                    } else {
                        "file_too_large_for_hash"
                    }
                    
                    ToolResult(ok, JSONObject().put("path", f.absolutePath).put("bytes", f.length()).put("sha256", hash).toString())
                } catch (e: Exception) {
                    // Back-plan on write failure
                    val attempted = runCatching {
                        val t = currentTaskContext
                        val desc = t?.description ?: ""
                        kotlinx.coroutines.runBlocking {
                            runBackPlanFixIfNeeded(path, intended = contentRaw.take(4000), userInstruction = desc, failureNote = "write_file_error:${e.message}", onStatus = { })
                        }
                    }.getOrElse { false }
                    if (attempted) return ToolResult(true, "backplan_applied:${resolvePath(path).absolutePath}")
                    ToolResult(false, "write_file_error: ${e.message}")
                }
            }
            "make_dir" -> {
                val path = call.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "make_dir_timeout: operation took too long")
                }
                
                try {
                    val d = resolvePath(path)
                    d.mkdirs()
                    if (d.exists()) notifyWorkspaceChanged(d.absolutePath)
                    ToolResult(d.exists() && d.isDirectory, null)
                } catch (e: Exception) {
                    ToolResult(false, "make_dir_error: ${e.message}")
                }
            }
            "run_shell" -> {
                var command = call.args.optString("command")
                val timeoutMs = call.args.optLong("timeout_ms", 120_000L).coerceAtLeast(1_000L).coerceAtMost(300_000L) // Max 5 minutes
                val envObj = call.args.optJSONObject("env")
                if (command.isBlank()) {
                    val derived = deriveDefaultCommandForRunShell(currentTaskContext)
                    if (!derived.isNullOrBlank()) {
                        command = derived
                    }
                }
                require(command.isNotBlank()) { "command missing" }
                
                // Check for timeout before starting
                if (System.currentTimeMillis() - startTime > maxFileOpTime) {
                    return ToolResult(false, "run_shell_timeout: operation took too long")
                }
                
                // Check global timeout
                if (System.currentTimeMillis() - startTime > globalTimeout) {
                    return ToolResult(false, "global_timeout: operation exceeded maximum time limit")
                }
                val wd = workingDirProvider()
                val cacheKey = commandCacheKey(command, wd)
                // Normalize common typos like pip3--version -> pip3 --version
                command = command.replace(Regex("\\b(pip3?)--version\\b"), "$1 --version")
                command = command.replace(Regex("\\b(python3?)--version\\b"), "$1 --version")
                // Robustify python/pip version checks using fallbacks and non-failing tail
                fun robustifyPythonPip(cmd: String): String {
                    val hasPy = Regex("\\bpython(3)?\\s*--version").containsMatchIn(cmd)
                    val hasPip = Regex("\\bpip(3)?\\s*--version").containsMatchIn(cmd)
                    val parts = mutableListOf<String>()
                    if (hasPy) parts += "( (command -v python3 >/dev/null 2>&1 && python3 --version) || (command -v python >/dev/null 2>&1 && python --version) || echo 'python not found' )"
                    if (hasPip) parts += "( (command -v pip3 >/dev/null 2>&1 && pip3 --version) || (command -v pip >/dev/null 2>&1 && pip --version) || echo 'pip not found' )"
                    return if (parts.isNotEmpty()) parts.joinToString(" && ") + " || true" else cmd
                }
                command = robustifyPythonPip(command)
                // Always run inside the visible main terminal session
                val mainOut = MainShell.execInMainSession(context as? MainActivity, wd, command, timeoutMs)
                var output = mainOut.first
                var exit = mainOut.second
                
                // Check for PEP 668 externally managed environment error and provide better fallback
                if (exit != 0 && output.lowercase().contains("externally-managed-environment")) {
                    // Try to install Flask using apk if available
                    if (output.lowercase().contains("flask")) {
                        val apkCommand = "apk add py3-flask"
                        val apkOut = MainShell.execInMainSession(context as? MainActivity, wd, apkCommand, 30000L)
                        if (apkOut.second == 0) {
                            output = apkOut.first
                            exit = 0
                        }
                    }
                }
                // Heuristic upgrade on failure: try one improved command
                					fun suggestCommandUpgradeHeuristic(cmd: String, out: String): String? {
						val lower = out.lowercase()
						if (cmd.trim().startsWith("git ") && lower.contains("not a git repository")) {
							if (!cmd.contains("git init")) return "git init && ${cmd}"
						}
						if (cmd.contains("git commit") && (lower.contains("please tell me who you are") || lower.contains("user.name") && lower.contains("user.email"))) {
							return "git config user.email 'you@example.com' && git config user.name 'You' && ${cmd}"
						}
						// Flask import missing -> install Flask
						if (lower.contains("modulenotfounderror") && lower.contains("flask")) {
							return if (lastDetectedManagers.contains("apk")) "apk update && apk add py3-flask" else "python3 -m pip install --upgrade pip setuptools wheel && python3 -m pip install flask"
						}
						if ((lower.contains("no module named") || lower.contains("modulenotfounderror")) && lower.contains("flask")) {
							return if (lastDetectedManagers.contains("apk")) "apk update && apk add py3-flask" else "python3 -m pip install --upgrade pip setuptools wheel && python3 -m pip install flask"
						}
						// pip/Flask/Pygame upgrades
						val pipInstall = Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE).containsMatchIn(cmd)
						if (pipInstall) {
							val installing = cmd.substringAfter("install ").trim()
							val targetPkg = installing.split(" ").firstOrNull()?.trim()?.lowercase() ?: ""
							val pep668 = lower.contains("externally-managed-environment") || lower.contains("externally managed")
							val apkAvailable = lastDetectedManagers.contains("apk") || lower.contains("apk-tools")
							if (apkAvailable && targetPkg == "pygame") {
								return "apk update && apk add py3-pygame"
							}
							if (lower.contains("gcc: not found") || lower.contains("sdl2-config: not found")) {
								if (apkAvailable) return "apk update && apk add build-base sdl2-dev sdl2_image-dev sdl2_mixer-dev sdl2_ttf-dev"
							}
							if (pep668 && apkAvailable) {
								val apkName = when (targetPkg) {
									"flask" -> "py3-flask"
									else -> "py3-${'$'}{targetPkg}"
								}
								return "apk update && apk add ${apkName}"
							}
							if (pep668) {
								val pkgPart = cmd.substringAfter("install ")
								return "python3 -m venv .venv && . .venv/bin/activate && pip install --upgrade pip setuptools wheel && pip install ${pkgPart}"
							}
							if (!cmd.contains("python3 -m pip")) {
								return cmd.replaceFirst(Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE), "python3 -m pip install ")
							}
							if (lower.contains("externally-managed-environment") || lower.contains("permission denied") || lower.contains("not writeable") || lower.contains("is not owned by")) {
								val pkgPart = cmd.substringAfter("install ")
								return "python3 -m venv .venv && . .venv/bin/activate && pip install --upgrade pip setuptools wheel && pip install ${pkgPart}"
							}
							if (lower.contains("pip: not found") || lower.contains("no module named pip") || lower.contains("command not found: pip")) {
								val pkgPart = cmd.substringAfter("install ")
								return "(command -v apk >/dev/null 2>&1 && apk update && apk add py3-pip) || true && python3 -m pip install ${pkgPart}"
							}
						}
						return null
					}
if (exit != 0) {
                    val upgraded = suggestCommandUpgradeHeuristic(command, output)
                    if (upgraded != null) {
                        val retry = MainShell.execInMainSession(context as? MainActivity, wd, upgraded, timeoutMs)
                        val combined = StringBuilder()
                        combined.append(output)
                        combined.append("\n----- retry: ").append(upgraded).append(" -----\n")
                        combined.append(retry.first)
                        output = combined.toString()
                        exit = retry.second
                        if (exit == 0 && isInstallCommand(upgraded) && !output.lowercase().contains("externally-managed-environment")) {
                            lastInstallSuccess = true
                        }
                    }
                }
                if (exit == 0 && isInstallCommand(command) && !output.lowercase().contains("externally-managed-environment")) {
                    lastInstallSuccess = true
                }
                val obs = output.ifBlank { null }
                val payload = JSONObject()
                    .put("command", command)
                    .put("wd", wd)
                    .put("output", output)
                    .put("exit", exit)
                    .put("ts", System.currentTimeMillis())
                commandCache[cacheKey] = payload
                saveCommandCache()
                persistCliReport()
                currentRunStats?.commandsRun?.add(command)
                val isEnvCheck = isEnvPreflightCommand(command)
                appendTaskLog("run_shell_result") {
                    put("command", command)
                    put("wd", wd)
                    put("exit", exit)
                    put("output_preview", output.take(800))
                    put("bytes", output.length)
                }
                // Persist environment signals for later tool coercion
                runCatching {
                    val lower = output.lowercase()
                    if (lower.contains("id=alpine") || lower.contains("apk-tools")) lastDetectedOsId = "alpine"
                    if (lower.contains("apk-tools") || lower.contains("\napk ")) lastDetectedManagers.add("apk")
                    if (lower.contains("apt ") || lower.contains("apt-get ")) lastDetectedManagers.add("apt")
                    if (lower.contains("dnf ")) lastDetectedManagers.add("dnf")
                    if (lower.contains("yum ")) lastDetectedManagers.add("yum")
                    if (lower.contains("pacman ")) lastDetectedManagers.add("pacman")
                }
                // PEP 668 detected: mark failure to trigger remediation instead of false success
                if (Regex("\\bpip3?\\s+install\\s+", RegexOption.IGNORE_CASE).containsMatchIn(command) && output.lowercase().contains("externally-managed-environment")) {
                    lastInstallSuccess = false
                    currentTaskContext?.let { t -> markTaskFailed(t.id, "pep668_externally_managed_env") }
                }
                ToolResult(exit == 0 || isEnvCheck, obs)
            }
            "get_cached_command_output" -> {
                val command = call.args.optString("command")
                val maxAgeMs = call.args.optLong("max_age_ms", 10 * 60 * 1000L).coerceAtLeast(0L)
                require(command.isNotBlank()) { "command missing" }
                val wd = workingDirProvider()
                val cacheKey = commandCacheKey(command, wd)
                val entry = commandCache[cacheKey]
                val now = System.currentTimeMillis()
                val found = entry != null && (now - (entry?.optLong("ts") ?: 0L) <= maxAgeMs)
                val obj = JSONObject().apply {
                    put("command", command)
                    put("wd", wd)
                    put("found", found)
                    if (entry != null) {
                        put("ts", entry.optLong("ts"))
                        put("exit", entry.optInt("exit"))
                        put("output", entry.optString("output"))
                        put("age_ms", now - entry.optLong("ts"))
                    }
                }
                ToolResult(true, obj.toString())
            }
            "list_cached_commands" -> {
                val max = call.args.optInt("max", 50).coerceAtLeast(1)
                val arr = JSONArray()
                commandCache.entries.toList().takeLast(max).forEach { entry ->
                    arr.put(JSONObject().apply {
                        val v = entry.value
                        put("command", v.optString("command"))
                        put("wd", v.optString("wd"))
                        put("ts", v.optLong("ts"))
                        put("exit", v.optInt("exit"))
                        put("preview", v.optString("output").take(200))
                    })
                }
                val out = JSONObject().put("items", arr).toString()
                ToolResult(true, out)
            }
            "list_dir" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val d = resolvePath(path)
                val items = if (d.exists() && d.isDirectory) d.listFiles()?.filter { true }?.take(200).orEmpty() else emptyList()
                val arr = JSONArray()
                items.forEach { f ->
                    arr.put(JSONObject().put("name", f.name).put("type", if (f.isDirectory) "dir" else "file"))
                }
                val listing = JSONObject().put("path", d.absolutePath).put("items", arr).toString()
                currentRunStats?.dirsListed?.add(d.absolutePath)
                
                // Add special handling for empty directories to prevent loops
                if (items.isEmpty() && d.exists() && d.isDirectory) {
                    val emptyListing = JSONObject().put("path", d.absolutePath).put("items", arr).put("empty", true).put("message", "Directory is empty - ready for new files").toString()
                    ToolResult(true, emptyListing)
                } else {
                    ToolResult(true, listing)
                }
            }
            "list_dir_recursive" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                // Guard: only allow when preflight says ok
                val allow = shouldAllowRecursiveListing(currentTaskContext)
                val maxDepth = if (allow) call.args.optInt("max_depth", 3).coerceIn(1, 3) else 0
                val maxEntries = if (allow) call.args.optInt("max_entries", 300).coerceIn(1, 300) else 1
                val root = resolvePath(path)
                val arr = JSONArray()
                var count = 0
                fun walk(dir: File, depth: Int) {
                    if (depth > maxDepth || count >= maxEntries) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (count >= maxEntries) break
                        arr.put(JSONObject().put("path", f.absolutePath).put("type", if (f.isDirectory) "dir" else "file"))
                        count++
                        if (f.isDirectory) walk(f, depth + 1)
                    }
                }
                if (allow && root.exists() && root.isDirectory) walk(root, 0)
                val out = JSONObject().put("root", root.absolutePath).put("max_depth", maxDepth).put("items", arr).toString()
                currentRunStats?.dirsListed?.add(root.absolutePath)
                ToolResult(true, out)
            }
            "read_file" -> {
                val path = call.args.optString("path")
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val encoding = call.args.optString("encoding", "utf-8").lowercase()
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val content = if (f.exists() && f.isFile) {
                    val bytes = f.readBytes()
                    val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                    currentRunStats?.filesRead?.add(f.absolutePath)
                    val encoded = if (encoding == "base64") Base64.encodeToString(slice, Base64.NO_WRAP) else String(slice)
                    JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", encoded).put("truncated", bytes.size > maxBytes).put("encoding", encoding).toString()
                } else {
                    // Try fetching from external codebase agent
                    val fetched = runCatching { ControlApiClient.fetchFile(sessionId, f.absolutePath) }.getOrNull()
                    if (!fetched.isNullOrBlank()) {
                        val text = fetched
                        val bytes = text.toByteArray()
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val encoded = if (encoding == "base64") Base64.encodeToString(slice, Base64.NO_WRAP) else String(slice)
                        JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", encoded).put("truncated", bytes.size > maxBytes).put("encoding", encoding).toString()
                    } else {
                        JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                    }
                }
                ToolResult(true, content)
            }
            "read_files" -> {
                val arr = call.args.optJSONArray("paths") ?: JSONArray()
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val results = JSONArray()
                for (i in 0 until arr.length()) {
                    val rawPath = arr.optString(i)
                    if (rawPath.isNullOrBlank()) continue
                    val f = resolvePath(rawPath)
                    if (f.exists() && f.isFile) {
                        val bytes = runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val text = String(slice)
                        results.put(JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                    } else {
                        results.put(JSONObject().put("path", f.absolutePath).put("missing", true))
                    }
                }
                val out = JSONObject().put("files", results).toString()
                ToolResult(true, out)
            }
            "read_files_glob" -> {
                val rootPath = call.args.optString("root").ifBlank { workingDirProvider() }
                val glob = call.args.optString("glob")
                val maxFiles = call.args.optInt("max_files", 50).coerceAtLeast(1)
                val maxBytes = call.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                val maxDepth = call.args.optInt("max_depth", 5).coerceAtLeast(0)
                require(rootPath.isNotBlank()) { "root missing" }
                require(glob.isNotBlank()) { "glob missing" }
                val root = resolvePath(rootPath)
                val regex = globToRegex(glob)
                val results = JSONArray()
                var count = 0
                fun walk(dir: File, depth: Int) {
                    if (depth > maxDepth || count >= maxFiles) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (count >= maxFiles) break
                        if (f.isDirectory) {
                            walk(f, depth + 1)
                        } else {
                            val rel = f.absolutePath
                            if (regex.matcher(f.name).matches() || regex.matcher(rel).matches()) {
                                val bytes = runCatching { f.readBytes() }.getOrElse { ByteArray(0) }
                                val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                                val text = String(slice)
                                results.put(JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                                count++
                            }
                        }
                    }
                }
                if (root.isDirectory) walk(root, 0) else if (root.isFile) {
                    if (globToRegex(glob).matcher(root.name).matches()) {
                        val bytes = runCatching { root.readBytes() }.getOrElse { ByteArray(0) }
                        val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                        val text = String(slice)
                        results.put(JSONObject().put("path", root.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes))
                        count++
                    }
                }
                val out = JSONObject().put("root", root.absolutePath).put("glob", glob).put("files", results).toString()
                ToolResult(true, out)
            }
            "read_file_lines" -> {
                val path = call.args.optString("path")
                val start = call.args.optInt("start", 1).coerceAtLeast(1)
                val end = call.args.optInt("end", start + 500).coerceAtLeast(start)
                val maxBytes = call.args.optInt("max_bytes", 131072).coerceAtLeast(4096)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val obs = if (f.exists() && f.isFile) {
                    val lines = f.readLines()
                    val actualEnd = end.coerceAtMost(lines.size)
                    val slice = if (start <= lines.size) lines.subList(start - 1, actualEnd) else emptyList()
                    val text = slice.joinToString("\n")
                    val clipped = if (text.toByteArray().size > maxBytes) String(text.toByteArray().copyOf(maxBytes)) else text
                    JSONObject().put("path", f.absolutePath).put("start", start).put("end", actualEnd).put("content", clipped).put("truncated", clipped.length < text.length).toString()
                } else {
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                }
                ToolResult(true, obs)
            }
            "read_file_section_by_markers" -> {
                val path = call.args.optString("path")
                val startMarker = call.args.optString("start_marker")
                val endMarker = call.args.optString("end_marker")
                val includeMarkers = call.args.optBoolean("include_markers", false)
                require(path.isNotBlank()) { "path missing" }
                require(startMarker.isNotBlank() && endMarker.isNotBlank()) { "markers missing" }
                val f = resolvePath(path)
                val obs = if (f.exists() && f.isFile) {
                    val original = f.readText()
                    val sIdx = original.indexOf(startMarker)
                    if (sIdx < 0) {
                        JSONObject().put("path", f.absolutePath).put("start_found", false).toString()
                    } else {
                        val eIdx = original.indexOf(endMarker, sIdx + startMarker.length)
                        if (eIdx < 0) {
                            JSONObject().put("path", f.absolutePath).put("end_found", false).toString()
                        } else {
                            val content = if (includeMarkers) original.substring(sIdx, eIdx + endMarker.length) else original.substring(sIdx + startMarker.length, eIdx)
                            JSONObject().put("path", f.absolutePath).put("content", content).toString()
                        }
                    }
                } else {
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                }
                ToolResult(true, obs)
            }
            "stat_file" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val f = resolvePath(path)
                val obj = JSONObject().put("path", f.absolutePath)
                    .put("exists", f.exists())
                    .put("is_dir", f.isDirectory)
                    .put("is_file", f.isFile)
                    .put("size", if (f.exists() && f.isFile) f.length() else 0L)
                    .put("modified", if (f.exists()) f.lastModified() else 0L)
                ToolResult(true, obj.toString())
            }
            "grep" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val pattern = call.args.optString("pattern")
                val maxResults = call.args.optInt("max_results", 200).coerceAtLeast(1)
                require(path.isNotBlank()) { "path missing" }
                require(pattern.isNotBlank()) { "pattern missing" }
                val root = resolvePath(path)
                val regex = runCatching { Pattern.compile(pattern) }.getOrElse { Pattern.compile(Pattern.quote(pattern)) }
                val results = JSONArray()
                var count = 0
                fun isBinary(file: File): Boolean {
                    return try {
                        val bytes = file.inputStream().use { it.readNBytes(1024) }
                        bytes.any { b -> b.toInt() == 0 } // crude NUL check
                    } catch (e: Exception) { false }
                }
                fun scanFile(file: File) {
                    if (count >= maxResults) return
                    val sz = runCatching { file.length() }.getOrElse { 0L }
                    if (sz > 5_000_000L) return // skip files > 5MB
                    if (isBinary(file)) return
                    val lines = runCatching { file.readLines() }.getOrElse { emptyList() }
                    for ((idx, line) in lines.withIndex()) {
                        if (count >= maxResults) break
                        val m = regex.matcher(line)
                        if (m.find()) {
                            results.put(JSONObject().put("file", file.absolutePath).put("line", idx + 1).put("text", line.take(500)))
                            currentRunStats?.grepPatterns?.add(pattern)
                            count++
                        }
                    }
                }
                fun walk(dir: File) {
                    if (count >= maxResults) return
                    val list = dir.listFiles() ?: return
                    for (f in list) {
                        if (count >= maxResults) break
                        if (f.isDirectory) walk(f) else scanFile(f)
                    }
                }
                if (root.isDirectory) walk(root) else if (root.isFile) scanFile(root)
                val out = JSONObject().put("root", root.absolutePath).put("matches", results).toString()
                ToolResult(true, out)
            }
            "apply_changes" -> {
                val edits = call.args.optJSONArray("edits") ?: JSONArray()
                val includeDiffs = call.args.optBoolean("include_diffs", true)
                val previewOnly = call.args.optBoolean("preview_only", false)
                val results = mutableListOf<String>()
                val outArr = JSONArray()
                var createdAny = false
                var modifiedAny = false
                for (i in 0 until edits.length()) {
                    val e = edits.optJSONObject(i) ?: continue
                    val op = e.optString("op")
                    val path = e.optString("path")
                    if (path.isBlank()) { results.add("edit[$i]: missing path"); continue }
                    val file = resolvePath(path)
                    val entry = JSONObject().put("file", file.absolutePath).put("op", op)
                    if (!file.exists()) {
                        if (op == "write_if_missing") {
                            val content = e.optString("content")
                            ensureParentDirs(file)
                            if (!previewOnly) {
                                file.writeText(content)
                            }
                            entry.put("status", if (previewOnly) "planned_create" else "created")
                            createdAny = true
                            // Sync newly created file
                            if (!previewOnly) runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, e.optString("content"), true) }
                            if (includeDiffs) entry.put("diff", computeUnifiedDiff("", content, file.absolutePath))
                            outArr.put(entry)
                            continue
                        }
                        results.add("edit[$i]: file missing: ${path}")
                        entry.put("status", "missing")
                        outArr.put(entry)
                        continue
                    }
                    val original = runCatching { file.readText() }.getOrElse { "" }
                    val updated = when (op) {
                        "replace_exact" -> {
                            val old = e.optString("old")
                            val new = e.optString("new")
                            if (old.isEmpty()) { results.add("edit[$i]: old empty"); null } else {
                                val idx = original.indexOf(old)
                                if (idx < 0) { results.add("edit[$i]: old not found"); null } else original.replaceFirst(old, new)
                            }
                        }
                        "replace_between_markers" -> {
                            val start = e.optString("start_marker")
                            val end = e.optString("end_marker")
                            val newContent = e.optString("new_content")
                            val includeMarkers = e.optBoolean("include_markers", false)
                            val sIdx = original.indexOf(start)
                            if (sIdx < 0) { results.add("edit[$i]: start not found"); null } else {
                                val eIdx = original.indexOf(end, sIdx + start.length)
                                if (eIdx < 0) { results.add("edit[$i]: end not found"); null } else {
                                    if (includeMarkers) {
                                        val pre = original.substring(0, sIdx)
                                        val post = original.substring(eIdx + end.length)
                                        pre + start + newContent + end + post
                                    } else {
                                        val pre = original.substring(0, sIdx + start.length)
                                        val post = original.substring(eIdx)
                                        pre + newContent + post
                                    }
                                }
                            }
                        }
                        "insert_after_anchor" -> {
                            val anchor = e.optString("anchor")
                            val newContent = e.optString("new_content")
                            val aIdx = original.indexOf(anchor)
                            if (aIdx < 0) { results.add("edit[$i]: anchor not found"); null } else {
                                val insertPos = aIdx + anchor.length
                                original.substring(0, insertPos) + newContent + original.substring(insertPos)
                            }
                        }
                        "insert_before_anchor" -> {
                            val anchor = e.optString("anchor")
                            val newContent = e.optString("new_content")
                            val aIdx = original.indexOf(anchor)
                            if (aIdx < 0) { results.add("edit[$i]: anchor not found"); null } else original.substring(0, aIdx) + newContent + original.substring(aIdx)
                        }
                        "replace_regex" -> {
                            val regexObj = e.optJSONObject("regex")
                            val pattern = e.optString("pattern").ifBlank { regexObj?.optString("pattern").orEmpty() }
                            val replacement = e.optString("replacement").ifBlank { regexObj?.optString("replace").orEmpty() }
                            val unique = e.optBoolean("unique", true)
                            if (pattern.isBlank()) { results.add("edit[$i]: pattern empty"); null } else {
                                val regex = runCatching { Regex(pattern) }.getOrElse { Regex(Pattern.quote(pattern)) }
                                val count = regex.findAll(original).count()
                                if (unique && count != 1) { results.add("edit[$i]: non-unique matches=${'$'}count"); null } else original.replace(regex, replacement)
                            }
                        }
                        "ensure_block_present" -> {
                            val block = e.optString("block")
                            val idMarker = e.optString("idempotent_marker")
                            val before = e.optString("anchor_before")
                            val after = e.optString("anchor_after")
                            val contains = if (idMarker.isNotBlank()) original.contains(idMarker) else original.contains(block)
                            if (contains) { results.add("edit[$i]: already present"); null } else {
                                when {
                                    before.isNotBlank() -> {
                                        val idx = original.indexOf(before)
                                        if (idx < 0) { results.add("edit[$i]: anchor_before not found"); null } else original.substring(0, idx) + block + original.substring(idx)
                                    }
                                    after.isNotBlank() -> {
                                        val idx = original.indexOf(after)
                                        if (idx < 0) { results.add("edit[$i]: anchor_after not found"); null } else {
                                            val pos = idx + after.length
                                            original.substring(0, pos) + block + original.substring(pos)
                                        }
                                    }
                                    else -> original + block
                                }
                            }
                        }
                        "append_once" -> {
                            val block = e.optString("block")
                            val idMarker = e.optString("idempotent_marker")
                            val contains = if (idMarker.isNotBlank()) original.contains(idMarker) else original.contains(block)
                            if (contains) { results.add("edit[$i]: already present"); null } else original + block
                        }
                        "write_if_missing" -> {
                            results.add("edit[$i]: exists (skipped)")
                            null
                        }
                        "replace_lines" -> {
                            val start = e.optInt("start_line", -1)
                            val end = e.optInt("end_line", -1)
                            val newContent = e.optString("new_content")
                            if (start <= 0 || end < start) { results.add("edit[$i]: invalid line range"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val from = (start - 1).coerceAtLeast(0)
                                val to = end.coerceAtMost(lines.size)
                                val newLines = newContent.split("\n")
                                lines.subList(from, to).clear()
                                lines.addAll(from, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        "insert_lines_after" -> {
                            val line = e.optInt("line_number", -1)
                            val newContent = e.optString("new_content")
                            if (line < 0) { results.add("edit[$i]: invalid line number"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val idx = line.coerceAtMost(lines.size)
                                val newLines = newContent.split("\n")
                                lines.addAll(idx, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        "insert_lines_before" -> {
                            val line = e.optInt("line_number", -1)
                            val newContent = e.optString("new_content")
                            if (line <= 0) { results.add("edit[$i]: invalid line number"); null } else {
                                val lines = original.split("\n").toMutableList()
                                val idx = (line - 1).coerceAtLeast(0)
                                val newLines = newContent.split("\n")
                                lines.addAll(idx, newLines)
                                lines.joinToString("\n")
                            }
                        }
                        else -> { results.add("edit[$i]: unknown op ${'$'}op"); null }
                    }
                    if (updated != null && updated != original) {
                        if (!previewOnly) {
                            runCatching { file.writeText(updated) }.onSuccess {
                                results.add("edit[$i]: ok (${path})")
                                notifyWorkspaceChanged(file.absolutePath)
                                modifiedAny = true
                                // Sync modified file
                                runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, updated, false) }
                            }.onFailure { ex -> results.add("edit[$i]: write failed (${ex.message})") }
                        } else {
                            results.add("edit[$i]: planned_change (${path})")
                            modifiedAny = true
                        }
                        if (includeDiffs) {
                            entry.put("status", if (previewOnly) "planned_change" else "modified")
                            entry.put("diff", computeUnifiedDiff(original, updated, file.absolutePath))
                        }
                        outArr.put(entry)
                    } else if (updated == null) {
                        entry.put("status", "no_change")
                        outArr.put(entry)
                    }
                }
                val summary = if (includeDiffs) JSONObject().put("results", outArr).put("preview_only", previewOnly).toString() else (if (results.isEmpty()) "no edits" else results.joinToString("; "))
                val ok = createdAny || modifiedAny || previewOnly
                ToolResult(ok, summary)
            }
            "search_replace" -> {
                val path = call.args.optString("path")
                val old = call.args.optString("old")
                val new = call.args.optString("new")
                val unique = call.args.optBoolean("unique", true)
                require(path.isNotBlank()) { "path missing" }
                require(old.isNotBlank()) { "old missing" }
                val file = resolvePath(path)
                if (!file.exists()) return ToolResult(false, "file missing: ${file.path}")
                val original = file.readText()
                val occurrences = Regex(Pattern.quote(old)).findAll(original).count()
                if (unique && occurrences != 1) return ToolResult(false, "non-unique match count: ${occurrences}")
                val updated = original.replaceFirst(old, new)
                file.writeText(updated)
                notifyWorkspaceChanged(file.absolutePath)
                // Sync modified file content to codebase agent
                runCatching { ControlApiClient.syncFile(sessionId, file.absolutePath, updated, false) }
                ToolResult(true, "replaced ${if (unique) 1 else occurrences} occurrence(s) in ${file.absolutePath}")
            }
            "delete_file" -> {
                val path = call.args.optString("path")
                val missingOk = call.args.optBoolean("missing_ok", true)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val existed = f.exists()
                val ok = if (existed) f.delete() else missingOk
                if (ok) notifyWorkspaceChanged(f.absolutePath)
                ToolResult(ok, JSONObject().put("path", f.absolutePath).put("existed", existed).put("deleted", existed && ok).toString())
            }
            "copy_file" -> {
                val src = call.args.optString("src")
                val dest = call.args.optString("dest")
                val overwrite = call.args.optBoolean("overwrite", false)
                require(src.isNotBlank() && dest.isNotBlank()) { "src/dest missing" }
                val s = resolvePath(src)
                val d = resolvePath(dest)
                if (!s.exists() || !s.isFile) return ToolResult(false, "source missing")
                if (d.exists() && !overwrite) return ToolResult(false, "dest exists")
                ensureParentDirs(d)
                d.writeBytes(s.readBytes())
                notifyWorkspaceChanged(d.absolutePath)
                ToolResult(true, JSONObject().put("src", s.absolutePath).put("dest", d.absolutePath).put("bytes", d.length()).toString())
            }
            "move_file" -> {
                val src = call.args.optString("src")
                val dest = call.args.optString("dest")
                val overwrite = call.args.optBoolean("overwrite", false)
                require(src.isNotBlank() && dest.isNotBlank()) { "src/dest missing" }
                val s = resolvePath(src)
                val d = resolvePath(dest)
                if (!s.exists() || !s.isFile) return ToolResult(false, "source missing")
                if (d.exists()) {
                    if (!overwrite) return ToolResult(false, "dest exists") else d.delete()
                }
                ensureParentDirs(d)
                val ok = s.renameTo(d)
                if (!ok) {
                    d.writeBytes(s.readBytes())
                    s.delete()
                }
                notifyWorkspaceChanged(d.absolutePath)
                ToolResult(true, JSONObject().put("src", s.absolutePath).put("dest", d.absolutePath).toString())
            }
            "json_get" -> {
                val path = call.args.optString("path")
                val pointer = call.args.optString("json_pointer")
                require(path.isNotBlank()) { "path missing" }
                require(pointer.isNotBlank()) { "json_pointer missing" }
                val f = resolvePath(path)
                if (!f.exists()) return ToolResult(false, "file missing")
                val obj = runCatching { JSONObject(f.readText()) }.getOrElse { return ToolResult(false, "invalid json") }
                val parts = pointer.split('/').filter { it.isNotEmpty() }
                var cur: Any = obj
                for (p in parts) {
                    if (cur is JSONObject) cur = cur.opt(p) ?: return ToolResult(true, JSONObject().put("found", false).toString())
                    else return ToolResult(false, "non_object path")
                }
                val out = JSONObject().put("found", true).put("value", cur).toString()
                ToolResult(true, out)
            }
            "json_set" -> {
                val path = call.args.optString("path")
                val pointer = call.args.optString("json_pointer")
                val valueStr = call.args.optString("value")
                val valueIsJson = call.args.optBoolean("value_is_json", true)
                require(path.isNotBlank()) { "path missing" }
                require(pointer.isNotBlank()) { "json_pointer missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                val root = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
                val parts = pointer.split('/').filter { it.isNotEmpty() }
                var cur: JSONObject = root
                for ((idx, p) in parts.withIndex()) {
                    val isLast = idx == parts.size - 1
                    if (isLast) {
                        val v: Any = if (valueIsJson) runCatching { JSONObject(valueStr) }.getOrElse { valueStr } else valueStr
                        cur.put(p, v)
                    } else {
                        val next = cur.optJSONObject(p) ?: JSONObject().also { cur.put(p, it) }
                        cur = next
                    }
                }
                f.writeText(root.toString(2))
                ToolResult(true, JSONObject().put("path", f.absolutePath).put("updated", true).toString())
            }
            else -> ToolResult(false, "unknown_tool_type:${call.type}")
        }
    }

    private fun isEnvPreflightCommand(command: String): Boolean {
        val cmd = command.lowercase()
        val baseSignals = listOf("uname", "os-release", "command -v", "which", "echo \$shell", "echo \$path")
        if (baseSignals.any { cmd.contains(it) }) return true
        val managers = listOf("apt", "dnf", "yum", "pacman", "apk", "zypper", "brew")
        val hasMgr = managers.any { cmd.contains(it) }
        val hasVersionProbe = cmd.contains("--version") || cmd.matches(Regex(".*\\s-v(\\s|$).*"))
        return hasMgr && hasVersionProbe
    }

    private fun deriveAlpineRootFromWorkspace(wd: String): String? {
        val needle = "/local/alpine"
        val idx = wd.indexOf(needle)
        if (idx < 0) return null
        val root = wd.substring(0, idx + needle.length)
        val osRelease = File(root, "etc/os-release")
        val ok = runCatching { osRelease.readText().lowercase().contains("id=alpine") }.getOrElse { false }
        return if (ok) root else null
    }

    private fun shouldAllowRecursiveListing(task: Task?): Boolean {
        val cat = task?.category?.lowercase()?.trim()
        if (cat != "list_dir_recursive") return false
        val g = ((task?.description ?: "") + " " + (task?.search?.joinToString(" ") ?: "")).lowercase()
        val hints = listOf("android", "kotlin", "java", "src", "main", "androidmanifest")
        return hints.any { g.contains(it) }
    }

    private fun isInstallCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("apk add") || c.contains("apt-get install") || c.contains("apt install") ||
                c.contains("dnf install") || c.contains("yum install") ||
                Regex("\\bpacman\\s+-S(\n|\r| |$)").containsMatchIn(c) ||
                c.contains("pip install") || c.contains("pip3 install")
    }

    private fun appendTaskLog(type: String, build: (JSONObject.() -> Unit)? = null) {
        runCatching {
            val obj = JSONObject()
                .put("type", type)
                .put("ts", System.currentTimeMillis())
                .put("session_id", sessionId)
            build?.invoke(obj)
            taskLogFile.parentFile?.mkdirs()
            if (!taskLogFile.exists()) taskLogFile.createNewFile()
            taskLogFile.appendText(obj.toString() + "\n")
        }
    }

    private fun globToRegex(glob: String): java.util.regex.Pattern {
        // Convert simple glob to regex: * -> .*, ? -> ., escape others
        val sb = StringBuilder()
        sb.append('^')
        for (ch in glob.toCharArray()) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return java.util.regex.Pattern.compile(sb.toString())
    }

    private suspend fun collectAll(flow: Flow<String>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        flow.collect { sb.append(it) }
        sb.toString()
    }

    private suspend fun collectAllWithRetry(timeoutMs: Long = 20_000, maxRetries: Int = 1, flowProvider: () -> Flow<String>): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= maxRetries) {
            val sb = StringBuilder()
            var hadTokens = false
            var timedOut = false
            try {
                val flow = flowProvider()
                withTimeout(timeoutMs) {
                    flow.collect { chunk ->
                        if (chunk.isNotEmpty()) hadTokens = true
                        sb.append(chunk)
                    }
                }
                val out = sb.toString()
                if (out.isBlank() && attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext out
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                // fall through to evaluate below
            } catch (e: java.io.IOException) {
                if (hadTokens) {
                    return@withContext sb.toString()
                }
                if (attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext ""
            } catch (e: Exception) {
                val out = sb.toString()
                if (out.isBlank() && attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext out
            }
            if (timedOut) {
                // If we timed out but have partial output, return it; otherwise retry/exit
                if (hadTokens) {
                    return@withContext sb.toString()
                }
                if (attempt < maxRetries) {
                    attempt++
                    continue
                }
                return@withContext ""
            }
        }
        return@withContext ""
    }

    private fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            val c = text[i]
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1)
                }
            }
        }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else null
    }

    private fun commandCacheKey(command: String, wd: String): String = wd + "||" + command

    private fun buildCliReport(): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("version", "1.0")
        }
    }

    private fun persistCliReport() {
        runCatching { cliReportFile.writeText(buildCliReport().toString(2)) }
    }

    private suspend fun performCodebaseUpgradeIfPending(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!Settings.codebase_agent_enabled) return@withContext
        if (pendingCodebaseChanges.isEmpty()) return@withContext
        onStatus("Codebase: changes detected (${pendingCodebaseChanges.size}); updating cache…")
        runCatching { buildCodebaseCache(onStatus, includeRecursive = true) }
        pendingCodebaseChanges.clear()
        lastCodebaseRefreshMs = System.currentTimeMillis()
    }

    private suspend fun buildCodebaseCache(onStatus: (String) -> Unit, includeRecursive: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val root = File(workingDirProvider())
            val maxDepth = if (includeRecursive) 5 else 2
            val maxFiles = if (includeRecursive) 2000 else 400
            val files = mutableListOf<File>()
            fun walk(dir: File, depth: Int) {
                if (files.size >= maxFiles || depth > maxDepth) return
                dir.listFiles()?.forEach { f ->
                    if (files.size >= maxFiles) return
                    if (f.isDirectory) walk(f, depth + 1) else files.add(f)
                }
            }
            if (root.exists() && root.isDirectory) walk(root, 0) else return@withContext
            val summary = JSONArray()
            files.forEach { f ->
                val rel = f.absolutePath
                val size = runCatching { f.length() }.getOrElse { 0L }
                val head = runCatching {
                    val bytes = f.readBytes()
                    val slice = if (bytes.size > 120_000) bytes.copyOf(120_000) else bytes
                    String(slice)
                }.getOrElse { "" }
                // Prevent recursive inclusion of generated cache files and agent logs
                val isCacheOrLog = f.name.equals(Settings.codebase_cache_path, ignoreCase = true) || f.name.equals("task_log.jsonl", ignoreCase = true)
                if (isCacheOrLog) return@forEach
                val funcs = extractFunctions(head, when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".html") -> "html"
                    rel.endsWith(".css") -> "css"
                    else -> "unknown"
                })
                val classes = extractClasses(head, when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    else -> "unknown"
                })
                summary.put(JSONObject().apply {
                    put("path", rel)
                    put("bytes", size)
                    put("functions", JSONArray(funcs))
                    put("classes", JSONArray(classes))
                    put("preview", head.take(2000))
                })
                // update context cache for later coherence
                val fileType = when {
                    rel.endsWith(".py") -> "python"
                    rel.endsWith(".js") -> "javascript"
                    rel.endsWith(".html") -> "html"
                    rel.endsWith(".css") -> "css"
                    else -> "unknown"
                }
                updateContextCache(rel, head, fileType)
            }
            val codebase = JSONObject()
                .put("generated_at", System.currentTimeMillis())
                .put("root", root.absolutePath)
                .put("files", summary)
            val out = File(agentDir, Settings.codebase_cache_path)
            out.writeText(codebase.toString(2))
            // Also persist into working directory for visibility and for LLM context
            runCatching {
                val wdCopy = File(workingDirProvider(), Settings.codebase_cache_path)
                val text = codebase.toString(2)
                if (!wdCopy.exists() || runCatching { wdCopy.readText() }.getOrElse { "" } != text) {
                    wdCopy.writeText(text)
                }
            }
            onStatus("Codebase cache updated: ${summary.length()} files")
        } catch (_: Exception) {
            // ignore
        }
    }

    private suspend fun revisePlanBasedOnHistoryAndError(goal: String, errorNote: String): Plan? = withContext(Dispatchers.IO) {
        return@withContext requestUpdatedPlan(Plan(goal, emptyList()))
    }

    private suspend fun helperRecommend(kind: String, contextMap: Map<String,String>): JSONObject? = withContext(Dispatchers.IO) {
        if (!Settings.helper_agent_enabled) return@withContext null
        val (sys, user) = buildHelperRecommendationPrompt(kind, contextMap)
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val json = extractFirstJsonObject(content) ?: return@withContext null
        return@withContext runCatching { JSONObject(json) }.getOrNull()
    }

    private fun applyHelperToMessages(reco: JSONObject?, messages: MutableList<LlmMessage>) {
        if (reco == null) return
        val prefix = reco.optString("prompt_prefix").ifBlank { null }
        val suffix = reco.optString("prompt_suffix").ifBlank { null }
        if (prefix != null) messages.add(0, LlmMessage("system", prefix))
        if (suffix != null) messages.add(LlmMessage("user", suffix))
        val maxTok = reco.optInt("max_tokens", -1)
        if (maxTok > 0) Settings.ai_max_tokens = maxTok
        val temp = reco.optDouble("temperature", Double.NaN)
        if (!temp.isNaN()) Settings.ai_temperature_str = temp.toString()
        val model = reco.optString("model").ifBlank { null }
        if (model != null) Settings.api_model = model
    }

	suspend fun generatePlan(userGoal: String): Plan? = withContext(Dispatchers.IO) {
		return@withContext generatePlanWithContext(userGoal, null)
	}

	suspend fun requestUpdatedPlan(plan: Plan): Plan? = withContext(Dispatchers.IO) {
		return@withContext revisePlanBasedOnHistoryAndError(plan.goal, "update_request")
	}

	private fun notifyWorkspaceChanged(path: String) {
		pendingCodebaseChanges.add(path)
	}

	private fun computeUnifiedDiff(original: String, updated: String, path: String, maxLines: Int = 400): String {
		if (original == updated) return ""
		val oldLines = original.split("\n")
		val newLines = updated.split("\n")
		val sb = StringBuilder()
		sb.append("--- ").append(path).append("\n")
		sb.append("+++ ").append(path).append("\n")
		val max = kotlin.math.max(oldLines.size, newLines.size)
		var shown = 0
		for (i in 0 until max) {
			if (shown >= maxLines) { sb.append("... (truncated)\n"); break }
			val old = if (i < oldLines.size) oldLines[i] else null
			val neu = if (i < newLines.size) newLines[i] else null
			when {
				old == null && neu != null -> { sb.append("+").append(neu).append("\n"); shown++ }
				old != null && neu == null -> { sb.append("-").append(old).append("\n"); shown++ }
				old != neu -> { sb.append("-").append(old).append("\n"); sb.append("+").append(neu).append("\n"); shown += 2 }
				else -> { /* same line; skip to keep concise */ }
			}
		}
		return sb.toString()
	}

	private suspend fun writerSuggestTool(planGoal: String, task: Task, original: ToolCall): ToolCall? = withContext(Dispatchers.IO) { null }

	private fun buildHelperRecommendationPrompt(kind: String, contextMap: Map<String, String>): Pair<String,String> {
		val sys = """
			You are a side helper agent. Return ONLY compact JSON with keys you need to adjust the main agent call.
			Schema: {"prompt_prefix": string, "prompt_suffix": string, "suggested_tools": [string...], "max_tokens": number, "temperature": number, "model": string}
			Return minified JSON without extra text. Omit fields you don't adjust.
		""".trimIndent()
		val user = JSONObject().apply {
			put("kind", kind)
			contextMap.forEach { (k, v) -> put(k, v.take(2000)) }
		}.toString()
		return sys to user
	}

	private fun coerceToolCallForTaskCategory(task: Task, proposed: ToolCall): ToolCall {
		val cat = (task.category ?: "").lowercase().trim()
		val isModification = task.description.contains("modify", ignoreCase = true) ||
							 task.description.contains("update", ignoreCase = true) ||
							 task.description.contains("change", ignoreCase = true) ||
							 task.description.contains("edit", ignoreCase = true) ||
							 task.description.contains("upgrade", ignoreCase = true)

		if (isModification) {
			return proposed // Don't interfere with modification tasks
		}

		// If the agent wants to write a file and has provided content, trust it.
		if (proposed.type == "write_file" && proposed.args.has("content") && proposed.args.optString("content").isNotBlank()) {
			return proposed
		}

		val path = proposed.args.optString("path").ifBlank { task.targets?.firstOrNull() }
		if (path != null) {
			val file = resolvePath(path)
			if (file.exists()) {
				return proposed // File already exists, so it's a modification, don't interfere.
			}
		}


				return when (cat) {
			"make_dir" -> {
				// Ensure directory creation even if model proposed a file tool
				val suggested = proposed.args.optString("path")
				val derived = when {
					suggested.isNotBlank() -> suggested
					!task.targets.isNullOrEmpty() -> task.targets!!.first()
					else -> File(workingDirProvider(), "NEW_DIR").absolutePath
				}
				ToolCall("make_dir", JSONObject().put("path", derived))
			}
			"create_file" -> {
				proposed
			}
			"write_file" -> {
				// This case is now only for when the agent wants to write to a new file but hasn't provided content.
				val desc = (task.description ?: "").lowercase()
				val suggested = proposed.args.optString("path")
				val derived = when {
					suggested.isNotBlank() -> suggested
					!task.targets.isNullOrEmpty() -> task.targets!!.first()
					else -> File(workingDirProvider(), "NEW_FILE").absolutePath
				}
				
				// Content must be provided by the model based on the current codebase and expectations, not a premade template.
				ToolCall("write_file", JSONObject().put("path", derived).put("content", "").put("mode", "overwrite"))
			}
			else -> proposed
		}
}

	private fun preferredPackageManager(): String? = when {
		lastDetectedManagers.contains("apk") -> "apk"
		lastDetectedManagers.contains("apt") -> "apt"
		lastDetectedManagers.contains("dnf") -> "dnf"
		lastDetectedManagers.contains("yum") -> "yum"
		lastDetectedManagers.contains("pacman") -> "pacman"
		else -> null
	}

	private fun coerceInstallPythonIfNeeded(): ToolCall? = null

	private fun deriveDefaultCommandForRunShell(task: Task?): String? {
		val desc = (task?.description ?: "").lowercase()
		// Prefer discovered files in project structure
		fun findRequirements(): String? {
			val hit = projectStructure.keys.firstOrNull { it.endsWith("/requirements.txt") || it.endsWith("\\requirements.txt") || it.endsWith("requirements.txt") }
			return hit
		}
		if (desc.contains("install") && (desc.contains("pip") || desc.contains("python") || desc.contains("requirements"))) {
			val reqPath = findRequirements() ?: listOf("requirements.txt", "app/requirements.txt", "backend/requirements.txt").firstOrNull { resolvePath(it).exists() } ?: "requirements.txt"
			return "(command -v python3 >/dev/null 2>&1 && python3 -m pip install -r \"$reqPath\") || (command -v pip3 >/dev/null 2>&1 && pip3 install -r \"$reqPath\") || (command -v pip >/dev/null 2>&1 && pip install -r \"$reqPath\")"
		}
		if (desc.contains("run") || desc.contains("server") || desc.contains("start")) {
			val mainHit = projectStructure.keys.firstOrNull { it.endsWith("/main.py") && it.contains("/app/") } ?: listOf("app/main.py", "main.py", "app.py").firstOrNull { resolvePath(it).exists() }
			val mainPath = mainHit ?: "app/main.py"
			return "python3 \"$mainPath\""
		}
		return null
	}

	private fun generateInitialContentForFile(path: String, requirements: String): String {
		// Deprecated: avoid premade templates. Content should always come from the model based on the goal and codebase.
		return ""
	}

    // Task detection: determine if a task is full code generation or code update
    private fun detectTaskKind(task: Task): String {
        val desc = task.description.lowercase()
        val isFull = desc.contains("create new") || desc.contains("generate") || desc.contains("scaffold") ||
                desc.contains("new file") || isWriteCategory(task.category)
        return if (isFull) "full_code_generation" else "code_update"
    }

    private fun buildMainInstructionsJson(): String {
        val obj = JSONObject()
        obj.put("enabled", Settings.main_instructions_enabled)
        obj.put("expectations", projectRequirements ?: lastPlanGoal ?: "")
        val blueprint = runCatching { blueprintFile.readText() }.getOrElse { null }
        if (!blueprint.isNullOrBlank()) obj.put("blueprint", runCatching { JSONObject(blueprint) }.getOrElse { JSONObject() })
        val codebase = runCatching { File(workingDirProvider(), Settings.codebase_cache_path).readText() }.getOrElse { null }
        if (!codebase.isNullOrBlank()) obj.put("codebase", runCatching { JSONObject(codebase) }.getOrElse { JSONObject() })
        val writerTools = runCatching { writerToolsFile.takeIf { it.exists() }?.readText() }.getOrElse { null }
        if (!writerTools.isNullOrBlank()) obj.put("writer_tools", runCatching { JSONObject(writerTools) }.getOrElse { JSONObject() })
        return obj.toString()
    }

    private suspend fun runBackPlanFixIfNeeded(path: String, intended: String, userInstruction: String, failureNote: String, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!Settings.backplan_enabled) return@withContext false
        val f = resolvePath(path)
        val currentContent = runCatching { if (f.exists()) f.readText() else "" }.getOrElse { "" }
        val instructionsJson = buildMainInstructionsJson()
        appendTaskLog("backplan_attempt") {
            put("task_id", currentTaskContext?.id ?: JSONObject.NULL)
            put("path", f.absolutePath)
            put("failure", failureNote.take(500))
        }
        onStatus("Back-plan: analyzing failure and generating corrective patch…")
        val sys = """
            You are a corrective agent. Return ONLY one minified JSON for an apply_changes tool call.
            Schema: {"type":"apply_changes","args":{"edits":[{"path":string,"op":"replace_exact"|"replace_between_markers"|"insert_after_anchor"|"insert_before_anchor"|"replace_regex"|"ensure_block_present"|"append_once"|"replace_lines"|"insert_lines_after"|"insert_lines_before"|"write_if_missing", ...}]}}
            Rules:
            - Use minimal edits to satisfy the instruction and fix the file.
            - If file missing, use write_if_missing with full content.
            - Keep indentation and formatting from existing content.
            - Only output the JSON object, nothing else.
        """.trimIndent()
        val user = JSONObject().apply {
            put("file_path", f.absolutePath)
            put("current_content", currentContent.take(120000))
            put("instruction", userInstruction.take(4000))
            put("intended_change_preview", intended.take(4000))
            put("main_instructions", if (Settings.main_instructions_enabled) runCatching { JSONObject(instructionsJson) }.getOrElse { JSONObject() } else JSONObject())
        }.toString()
        val content = collectAllWithRetry(flowProvider = { LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))) })
        val jsonText = extractFirstJsonObject(content) ?: return@withContext false
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext false
        if (obj.optString("type") != "apply_changes") return@withContext false
        val toolCall = ToolCall("apply_changes", obj.optJSONObject("args") ?: JSONObject())
        val res = executeToolCall(toolCall)
        appendTaskLog("backplan_result") {
            put("task_id", currentTaskContext?.id ?: JSONObject.NULL)
            put("path", f.absolutePath)
            put("ok", res.ok)
            put("observation_preview", res.observation?.take(400))
        }
        if (res.ok) {
            // Update codebase cache and sync
            runCatching { ControlApiClient.syncFile(sessionId, f.absolutePath, runCatching { f.readText() }.getOrElse { "" }, !f.exists()) }
            notifyWorkspaceChanged(f.absolutePath)
        }
        return@withContext res.ok
    }

	private fun ensureFolderStructureFromBlueprint(onStatus: (String) -> Unit) {
		val bpText = runCatching { blueprintFile.takeIf { it.exists() }?.readText().orEmpty() }.getOrElse { "" }
		if (bpText.isBlank()) return
		val rootDir = File(workingDirProvider())
		val obj = runCatching { JSONObject(bpText) }.getOrNull() ?: return
		val modules = obj.optJSONArray("modules") ?: JSONArray()
		val created = mutableListOf<String>()
		for (i in 0 until modules.length()) {
			val m = modules.optJSONObject(i) ?: continue
			val raw = m.optString("id").ifBlank { m.optString("name") }
			if (raw.isBlank()) continue
			val slug = raw.lowercase().replace(Regex("[^a-z0-9._/\\-]+"), "-").trim('-')
			if (slug.isBlank()) continue
			val dir = File(rootDir, slug)
			if (!dir.exists()) {
				dir.mkdirs()
				if (dir.exists()) {
					created.add(dir.absolutePath)
					notifyWorkspaceChanged(dir.absolutePath)
				}
			}
		}
		if (created.isNotEmpty()) {
			onStatus("Ensured folder structure from blueprint: created ${created.size} dir(s)")
			appendTaskLog("folder_structure_ensured") { put("created", JSONArray(created)) }
		}
	}
}

object MainShell {
    fun execInMainSession(activity: MainActivity?, wd: String, command: String, timeoutMs: Long): Pair<String, Int> {
        if (activity == null || activity.sessionBinder == null) return Pair("Main session not available", -1)
        val binder = activity.sessionBinder!!
        val service = binder.getService()
        val currentId = service.currentSession.value.first
        val session = binder.getSession(currentId) ?: return Pair("Main session not available", -1)
        // Prepare output file and sentinel
        val preferredOut = runCatching { File(wd).takeIf { it.exists() && it.isDirectory && it.canWrite() } }.getOrNull()
        val outFile = runCatching { File(preferredOut ?: activity.cacheDir, ".main-${System.currentTimeMillis()}.out") }.getOrNull()
            ?: File(activity.cacheDir, ".main-${System.currentTimeMillis()}.out")
        runCatching { if (outFile.exists()) outFile.delete() }
        val outPath = outFile.absolutePath.replace("'", "'\\''")
        val sentinel = "__MAIN_DONE_${System.currentTimeMillis()}__"
        val cmdLine = "cd \"$wd\"; umask 022; ( $command ) > '$outPath' 2>&1; code=${'$'}?; printf '%s\\n' '$sentinel' >> '$outPath'; printf 'EXIT_CODE=%s\\n' ${'$'}code >> '$outPath'\n"
        try {
            session.write(cmdLine)
        } catch (e: Exception) {
            return Pair("write failed: ${e.message}", -1)
        }
        Log.d("MainShell", "wrote to main session id=${currentId} out=$outPath")
        val start = System.currentTimeMillis()
        var content = ""
        var saw = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (outFile.exists()) {
                content = runCatching { outFile.readText() }.getOrElse { "" }
                if (content.contains(sentinel)) { saw = true; break }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        val exit = Regex("(?m)^EXIT_CODE=(\\-?\\d+)").find(content)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (if (saw) 0 else -1)
        runCatching { outFile.delete() }
        Log.d("MainShell", "done exit=${exit} bytes=${content.length}")
        return Pair(content, exit)
    }
}