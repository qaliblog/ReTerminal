package com.rk.terminal.ui.screens.terminal

import android.content.Context
import com.rk.libcommons.application
import com.rk.terminal.llm.LlmMessage
import com.rk.terminal.llm.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
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
    private fun beginRunStats() { currentRunStats = RunStats() }
    private fun endRunStatsAndReport(onStatus: (String) -> Unit, verb: String = "thought") {
        val stats = currentRunStats ?: return
        stats.endedMs = System.currentTimeMillis()
        val secs = ((stats.endedMs - stats.startedMs).coerceAtLeast(0L) / 100L).toDouble() / 10.0
        val parts = mutableListOf<String>()
        if (stats.toolCounts.isNotEmpty()) parts.add(stats.toolCounts.entries.joinToString(", ") { (k, v) -> "${k}×${v}" })
        if (stats.filesRead.isNotEmpty()) parts.add("read ${stats.filesRead.size} file(s): ${stats.filesRead.take(3).joinToString(", ")}${if (stats.filesRead.size > 3) " …" else ""}")
        if (stats.filesModified.isNotEmpty()) parts.add("modified ${stats.filesModified.size} file(s): ${stats.filesModified.take(3).joinToString(", ")}${if (stats.filesModified.size > 3) " …" else ""}")
        if (stats.commandsRun.isNotEmpty()) parts.add("ran ${stats.commandsRun.size} command(s): ${stats.commandsRun.take(1).joinToString()}${if (stats.commandsRun.size > 1) " …" else ""}")
        if (stats.grepPatterns.isNotEmpty()) parts.add("grep ${stats.grepPatterns.size} pattern(s)")
        val summary = "${verb} for ${secs}s${if (parts.isNotEmpty()) "; " + parts.joinToString("; ") else ""}"
        onStatus(summary)
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
        val content = collectAll(LlmProvider.current().generate(messages))
        val jsonText = extractFirstJsonObject(content) ?: return@withContext "mini_plan"
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext "mini_plan"
        val action = obj.optString("action").ifBlank { "mini_plan" }
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
        val content = collectAll(LlmProvider.current().generate(messages))
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
        return@withContext mini
    }

    private suspend fun executeMiniPlanForTask(plan: Plan, parentTask: Task, onStatus: (String) -> Unit): Boolean {
        var mini = loadMiniPlan(parentTask.id)
        if (mini == null) {
            val created = requestMiniPlanForTask(plan, parentTask, observations[parentTask.id] ?: "no_note")
            if (created == null) return false
            mini = created
            onStatus("Mini-plan created for ${parentTask.id}: ${mini.tasks.size} step(s)")
        }
        var steps = 0
        val maxSteps = 12
        while (steps < maxSteps) {
            val mt = getNextPendingMiniTask(mini) ?: return true
            onStatus("Mini ${mini.parentTaskId}.${mt.id}: ${mt.description}")
            val attemptNo = incrementMiniTaskAttempts(mini.parentTaskId, mt.id)
            if (attemptNo > 3) {
                onStatus("Mini ${mini.parentTaskId}.${mt.id}: attempts exceeded")
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
            val toolCall = requestSingleToolCall(plan.goal, pseudoTask)
            if (toolCall == null) {
                observations[pseudoTask.id] = "mini could not determine action"
                saveObservations()
                steps++
                continue
            }
            val result = try {
                executeToolCall(toolCall)
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                observations[pseudoTask.id] = "mini error: ${err}"
                saveObservations()
                steps++
                continue
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
        val content = collectAll(LlmProvider.current().generate(messages))
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
        val content = collectAll(LlmProvider.current().generate(msgs))
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
        val content = collectAll(LlmProvider.current().generate(messages))
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
        val content = collectAll(LlmProvider.current().generate(messages))
        return@withContext content
    }

    private suspend fun generatePlanWithContext(userGoal: String, extraContext: String?): Plan? = withContext(Dispatchers.IO) {
        runCatching { if (progressFile.exists()) progressFile.delete() }
        observations.clear()
        saveObservations()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val sys = """
            You are an autonomous software agent that plans work as structured JSON only.
            Return ONLY a minified JSON object with the following shape and nothing else:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "targets": [string...], "search": [string...], "markers": [string...]}, ...]}
            - ids unique short strings (e.g., t1, t2)
            - category in: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell | json_edit
            - front-load discovery; prefer precise scopes; idempotent modifications
            - If tasks involve running commands or installing dependencies, include an initial environment discovery step (OS flavor, package manager, runtime versions) using run_shell.
            - Do not include code in the plan
        """.trimIndent()
        val user = """
            Goal: ${userGoal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
            Extra context: ${extraContext ?: "(none)"}
        """.trimIndent()
        val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
        val content = collectAll(flow)
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
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
            // Fallback minimal discovery plan to avoid zero-task output
            val fallback = mutableListOf<Task>()
            fallback.add(Task("t1", "List top-level workspace", "list_dir", listOf(wdPath), null, null))
            fallback.add(Task("t2", "Recursive listing of likely source directories", "list_dir_recursive", listOf(wdPath), null, null))
            fallback.add(Task("t3", "Search for common project entry files", "grep", listOf(wdPath), listOf("build\\.gradle|settings\\.gradle|package\\.json|README|Main|AndroidManifest"), null))
            tasks.addAll(fallback)
        }
        val plan = Plan(goal, tasks)
        persistPlanWithStatuses(plan)
        return@withContext plan
    }

    suspend fun thinkAndAct(prompt: String, onStatus: (String) -> Unit): ThinkResult {
        beginRunStats()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        if (Settings.codebase_agent_enabled) {
            runCatching { buildCodebaseCache(onStatus) }
        }
        // After executing a modifying tool, we already schedule cache upgrade via notifyWorkspaceChanged

        // Apply any pending codebase cache upgrades due to file changes
        performCodebaseUpgradeIfPending(onStatus)
        onStatus("Thinking about intent…")
        val intentObj = classifyUserIntent(prompt, workspaceInfo)
        val intent = intentObj.optString("intent", "plan_and_execute")
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
                while (steps < 10) {
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
        val suggestContent = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", suggestSys), LlmMessage("user", suggestUser))))
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
        val final = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", synthSys), LlmMessage("user", synthUser))))
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
        val content = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))))
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
        return plan.tasks.firstOrNull { !isTaskDone(it.id) }
    }

    suspend fun executeNextTask(
        plan: Plan,
        onStatus: (String) -> Unit
    ): Boolean {
        beginRunStats()
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
        while (stepsTaken < maxSteps) {
            val toolCall = requestSingleToolCall(plan.goal, task)
            if (toolCall == null) {
                observations[task.id] = "could not determine action for this task"
                saveObservations()
                onStatus("Task ${task.id}: no action suggested; revising plan…")
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
            val result = runCatching { executeToolCall(effectiveToolCall) }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                saveObservations()
                onStatus("Task ${task.id} failed: ${err}; revising plan…")
                val revised = revisePlanBasedOnHistoryAndError(plan.goal, err)
                if (revised != null) {
                    persistPlanWithStatuses(revised)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                onStatus("Task ${task.id}: plan revision unavailable; proceeding with remediation…")
                ToolResult(false, null)
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

                // If this is a discovery tool and the task category is discovery, or env preflight shell, complete the task now.
                val envPreflight = effectiveToolCall.type == "run_shell" && isEnvPreflightCommand(effectiveToolCall.args.optString("command"))
                if ((isDiscoveryTool(effectiveToolCall.type) && isDiscoveryCategory(task.category)) || envPreflight) {
                    markTaskDone(task.id)
                    onStatus("Task ${task.id}: done")
                    // Ensure UI sees latest statuses
                    persistPlanWithStatuses(plan)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }

                // Prevent loops on repeated identical non-modifying observations
                val obs = result.observation
                if (lastToolType == effectiveToolCall.type && obs != null && lastObservation == obs) {
                    markTaskFailed(task.id, "repeated_non_modifying_observation")
                    onStatus("Task ${task.id}: repeated observation; revising plan…")
                    val revised = revisePlanBasedOnHistoryAndError(plan.goal, "repeated_non_modifying_observation")
                    if (revised != null) {
                        persistPlanWithStatuses(revised)
                        endRunStatsAndReport(onStatus, verb = "thought")
                        return true
                    }
                    onStatus("Task ${task.id}: plan revision unavailable; deciding remediation…")
                    val decision = decideRemediationAction(plan.goal, task, "repeat_observation")
                    when (decision) {
                        "mini_plan" -> {
                            val ok = executeMiniPlanForTask(plan, task, onStatus)
                            if (ok) { onStatus("Mini-plan completed; retrying task ${task.id}"); stepsTaken++; continue } else return false
                        }
                        "revise_plan" -> {
                            val revised2 = revisePlanBasedOnHistoryAndError(plan.goal, "repeat_observation")
                            if (revised2 != null) { persistPlanWithStatuses(revised2); endRunStatsAndReport(onStatus, verb = "thought"); return true } else return false
                        }
                        "retry" -> { stepsTaken++; continue }
                        else -> { return false }
                    }
                }
                lastObservation = result.observation ?: lastObservation
                lastToolType = effectiveToolCall.type

                // Discovery-type call; iterate to request the next action using fresh observation
                stepsTaken++
                continue
            } else {
                if (!observations.containsKey(task.id)) {
                    observations[task.id] = "failed without exception"
                    saveObservations()
                }
                onStatus("Task ${task.id}: failed; revising plan…")
                val revised = revisePlanBasedOnHistoryAndError(plan.goal, "unknown_failure")
                if (revised != null) {
                    persistPlanWithStatuses(revised)
                    endRunStatsAndReport(onStatus, verb = "thought")
                    return true
                }
                onStatus("Task ${task.id}: plan revision unavailable; deciding remediation…")
                val decision = decideRemediationAction(plan.goal, task, "unknown_failure")
                when (decision) {
                    "mini_plan" -> {
                        val ok = executeMiniPlanForTask(plan, task, onStatus)
                        if (ok) { onStatus("Mini-plan completed; retrying task ${task.id}"); stepsTaken++; continue } else return false
                    }
                    "revise_plan" -> {
                        val revised2 = revisePlanBasedOnHistoryAndError(plan.goal, "unknown_failure")
                        if (revised2 != null) { persistPlanWithStatuses(revised2); endRunStatsAndReport(onStatus, verb = "thought"); return true } else return false
                    }
                    "retry" -> { stepsTaken++; continue }
                    else -> { return false }
                }
            }
        }

        onStatus("Task ${task.id}: reached step limit without completion; revising plan…")
        val revised = revisePlanBasedOnHistoryAndError(plan.goal, "step_limit")
        if (revised != null) {
            persistPlanWithStatuses(revised)
            endRunStatsAndReport(onStatus, verb = "thought")
            return true
        }
        onStatus("Task ${task.id}: plan revision unavailable; deciding remediation…")
        val decision = decideRemediationAction(plan.goal, task, "step_limit")
        val r = when (decision) {
            "mini_plan" -> executeMiniPlanForTask(plan, task, onStatus)
            "revise_plan" -> {
                val revised2 = revisePlanBasedOnHistoryAndError(plan.goal, "step_limit")
                if (revised2 != null) {
                    persistPlanWithStatuses(revised2)
                    true
                } else false
            }
            "retry" -> false
            else -> false
        }
        endRunStatsAndReport(onStatus, verb = "thought")
        return r
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
        val content = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))))
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

    private suspend fun requestSingleToolCall(goal: String, task: Task): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            You orchestrate a short inner loop to complete the current task using the available tools.
            The API is stateless. Never rely on hidden memory. Use ONLY the provided goal, task, working directory, observations, and optional task hints.
            Return ONLY a single minified JSON object describing ONE tool call to move the task forward.
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
                 {"path": string, "op": "write_if_missing", "content": string}
             ]}}
             Tool ordering guidance:
             - Prefer ABSOLUTE paths. Resolve relative paths against the working directory and then output absolute.
             - Plan discovery first (list_dir_recursive, grep, read_file(s)), then precise modifications (apply_changes/search_replace/write_file), then run_shell if needed.
             - For tasks that might rely on system state (package installation, CLI tools, compilers, runtimes), first run an environment preflight using run_shell to check OS flavor, package manager, and runtime availability.
             - Use get_cached_command_output before re-running heavy run_shell.
             - For multi-file reads, keep limits small and targeted.
             - For modifications, ensure idempotency: prefer apply_changes with unique anchors/markers and use ensure_block_present/append_once to avoid duplicates.
             - When context is missing, propose the minimal discovery call to fetch it.
             - If user goal involves running commands or installing packages, ask for environment details first via a run_shell preflight like:
               uname -a; cat /etc/os-release 2>/dev/null || true; (command -v apt || command -v dnf || command -v yum || command -v pacman || command -v apk || true); (command -v python3 || command -v python || true); (command -v node || true); (command -v npm || true); (command -v gcc || true); (command -v g++ || true); echo ${'$'}SHELL; echo ${'$'}PATH
             - Return pure JSON on a single line without explanations.
         """.trimIndent()
        val wd = workingDirProvider()
        val prior = if (observations.isEmpty()) "(none)" else observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(500)}${if (v.length > 500) " …" else ""}" }
        val hints = buildString {
            if (!task.targets.isNullOrEmpty()) append("targets: ").append(task.targets.joinToString(", ")).append('\n')
            if (!task.search.isNullOrEmpty()) append("search: ").append(task.search.joinToString(", ")).append('\n')
            if (!task.markers.isNullOrEmpty()) append("markers: ").append(task.markers.joinToString(", ")).append('\n')
        }.ifBlank { "(none)" }
        val prompt = """
            Goal: ${goal}
            Working directory: ${wd}
            Current task id: ${task.id}
            Task: ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Task hints: ${hints}
            Prior observations (latest first):
            ${prior}
            Produce one tool call JSON now, following the Rules and leveraging hints and observations to avoid redundant discovery.
        """.trimIndent()
        val msgs = mutableListOf(
            LlmMessage("system", sys),
            LlmMessage("user", prompt)
        )
        val reco = helperRecommend("inner_loop", mapOf("goal" to goal.take(500), "task" to "${task.id}:${task.description}"))
        applyHelperToMessages(reco, msgs)
        val flow = LlmProvider.current().generate(msgs)
        val content = collectAll(flow)
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
            else -> tc.type
        }
        val call = if (normalizedType == tc.type) tc else ToolCall(normalizedType, tc.args)
        return when (call.type) {
            "create_file" -> {
                val path = call.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                if (!f.exists()) f.createNewFile()
                if (f.exists()) notifyWorkspaceChanged(f.absolutePath)
                ToolResult(f.exists(), null)
            }
            "write_file" -> {
                val path = call.args.optString("path")
                val contentRaw = call.args.optString("content")
                val encoding = call.args.optString("encoding", "utf-8").lowercase()
                val mode = call.args.optString("mode", "overwrite")
                val ifNotExists = call.args.optBoolean("if_not_exists", false)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                if (ifNotExists && f.exists()) {
                    return ToolResult(true, "skipped_write_existing:${'$'}{f.absolutePath}")
                }
                val bytes = if (encoding == "base64") Base64.decode(contentRaw, Base64.DEFAULT) else contentRaw.toByteArray(StandardCharsets.UTF_8)
                if (mode == "append" && f.exists()) {
                    f.appendBytes(bytes)
                } else {
                    f.writeBytes(bytes)
                }
                val ok = f.exists() && f.length() >= 0
                val md = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                val hash = md.joinToString("") { String.format("%02x", it) }
                if (ok) notifyWorkspaceChanged(f.absolutePath)
                ToolResult(ok, JSONObject().put("path", f.absolutePath).put("bytes", f.length()).put("sha256", hash).toString())
            }
            "make_dir" -> {
                val path = call.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val d = resolvePath(path)
                d.mkdirs()
                if (d.exists()) notifyWorkspaceChanged(d.absolutePath)
                ToolResult(d.exists() && d.isDirectory, null)
            }
            "run_shell" -> {
                var command = call.args.optString("command")
                val timeoutMs = call.args.optLong("timeout_ms", 120_000L).coerceAtLeast(1_000L)
                val envObj = call.args.optJSONObject("env")
                require(command.isNotBlank()) { "command missing" }
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
                val output: String
                val exit: Int
                if (Settings.agent_use_terminal_session) {
                    // Use hidden terminal session via SessionService when available
                    val outHidden = runCatching { HiddenShell.execInHiddenSession(context as? MainActivity, wd, command, timeoutMs) }.getOrElse { it.message ?: it.toString() }
                    if (!outHidden.contains("Hidden session not available")) {
                        output = outHidden
                        exit = 0 // best-effort; hidden session does not provide exit code
                    } else {
                        // Fallback to ProcessBuilder
                        val fallback = runCatching {
                            val pb = ProcessBuilder("sh", "-c", command).directory(File(wd)).redirectErrorStream(true)
                            if (envObj != null) {
                                val env = pb.environment()
                                envObj.keys().forEach { k -> env[k] = envObj.optString(k) }
                            }
                            runCatching {
                                val alpineRoot = deriveAlpineRootFromWorkspace(wd)
                                if (alpineRoot != null) {
                                    val env = pb.environment()
                                    val currentPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                                    env["PATH"] = "$alpineRoot/usr/bin:$alpineRoot/bin:" + currentPath
                                }
                            }
                            val proc = pb.start()
                            val reader = proc.inputStream.bufferedReader()
                            val start = System.currentTimeMillis()
                            val sb = StringBuilder()
                            while (proc.isAlive) {
                                while (reader.ready()) sb.append(reader.readLine()).append('\n')
                                if (System.currentTimeMillis() - start > timeoutMs) { proc.destroyForcibly(); break }
                                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                            }
                            if (proc.isAlive) proc.destroyForcibly()
                            Pair(sb.toString(), runCatching { proc.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS); proc.exitValue() }.getOrElse { -1 })
                        }.getOrElse { Pair(it.message ?: it.toString(), -1) }
                        output = fallback.first
                        exit = fallback.second
                    }
                } else {
                    val pb = ProcessBuilder("sh", "-c", command).directory(File(wd)).redirectErrorStream(true)
                    if (envObj != null) {
                        val env = pb.environment()
                        envObj.keys().forEach { k -> env[k] = envObj.optString(k) }
                    }
                    // If workspace indicates an Alpine root, prepend its bin dirs to PATH
                    runCatching {
                        val alpineRoot = deriveAlpineRootFromWorkspace(wd)
                        if (alpineRoot != null) {
                            val env = pb.environment()
                            val currentPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                            env["PATH"] = "$alpineRoot/usr/bin:$alpineRoot/bin:" + currentPath
                        }
                    }
                    val proc = pb.start()
                    val reader = proc.inputStream.bufferedReader()
                    val start = System.currentTimeMillis()
                    val sb = StringBuilder()
                    while (proc.isAlive) {
                        while (reader.ready()) sb.append(reader.readLine()).append('\n')
                        if (System.currentTimeMillis() - start > timeoutMs) { proc.destroyForcibly(); break }
                        try { Thread.sleep(20) } catch (_: InterruptedException) {}
                    }
                    if (proc.isAlive) proc.destroyForcibly()
                    exit = runCatching { proc.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS); proc.exitValue() }.getOrElse { -1 }
                    output = sb.toString()
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
                ToolResult(true, listing)
            }
            "list_dir_recursive" -> {
                val raw = call.args.optString("path")
                val path = if (raw.isBlank()) workingDirProvider() else raw
                val maxDepth = call.args.optInt("max_depth", 3).coerceAtLeast(0)
                val maxEntries = call.args.optInt("max_entries", 500).coerceAtLeast(1)
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
                if (root.exists() && root.isDirectory) walk(root, 0)
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
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
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
                val results = mutableListOf<String>()
                for (i in 0 until edits.length()) {
                    val e = edits.optJSONObject(i) ?: continue
                    val op = e.optString("op")
                    val path = e.optString("path")
                    if (path.isBlank()) { results.add("edit[$i]: missing path"); continue }
                    val file = resolvePath(path)
                    if (!file.exists()) {
                        // allow write_if_missing as part of apply_changes
                        if (op == "write_if_missing") {
                            val content = e.optString("content")
                            ensureParentDirs(file)
                            file.writeText(content)
                            results.add("edit[$i]: created (${path})")
                            continue
                        }
                        results.add("edit[$i]: file missing: ${file.path}"); continue
                    }
                    val original = runCatching { file.readText() }.getOrElse { "" }
                    val updated = when (op) {
                        "replace_exact" -> {
                            val old = e.optString("old")
                            val new = e.optString("new")
                            if (old.isEmpty()) { results.add("edit[$i]: old empty"); null } else {
                                val idx = original.indexOf(old)
                                if (idx < 0) { results.add("edit[$i]: old not found"); null } else {
                                    original.replaceFirst(old, new)
                                }
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
                            if (aIdx < 0) { results.add("edit[$i]: anchor not found"); null } else {
                                original.substring(0, aIdx) + newContent + original.substring(aIdx)
                            }
                        }
                        "replace_regex" -> {
                            val pattern = e.optString("pattern")
                            val replacement = e.optString("replacement")
                            val unique = e.optBoolean("unique", true)
                            if (pattern.isBlank()) { results.add("edit[$i]: pattern empty"); null } else {
                                val regex = runCatching { Regex(pattern) }.getOrElse { Regex(Pattern.quote(pattern)) }
                                val count = regex.findAll(original).count()
                                if (unique && count != 1) { results.add("edit[$i]: non-unique matches=$count"); null } else {
                                    original.replace(regex, replacement)
                                }
                            }
                        }
                        "ensure_block_present" -> {
                            val block = e.optString("block")
                            val idMarker = e.optString("idempotent_marker")
                            val before = e.optString("anchor_before")
                            val after = e.optString("anchor_after")
                            val contains = if (idMarker.isNotBlank()) original.contains(idMarker) else original.contains(block)
                            if (contains) {
                                results.add("edit[$i]: already present")
                                null
                            } else {
                                when {
                                    before.isNotBlank() -> {
                                        val idx = original.indexOf(before)
                                        if (idx < 0) { results.add("edit[$i]: anchor_before not found"); null } else {
                                            original.substring(0, idx) + block + original.substring(idx)
                                        }
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
                        else -> { results.add("edit[$i]: unknown op ${op}"); null }
                    }
                    if (updated != null) {
                        runCatching { file.writeText(updated) }.onSuccess {
                            results.add("edit[$i]: ok (${path})")
                            notifyWorkspaceChanged(file.absolutePath)
                        }.onFailure { ex ->
                            results.add("edit[$i]: write failed (${ex.message})")
                        }
                    }
                }
                val summary = (if (results.isEmpty()) "no edits" else results.joinToString("; "))
                ToolResult(true, summary)
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

    private fun extractFirstJsonObject(text: String): String? {
        // naive extractor: find first '{'...' }' balanced
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
        // fallback: try to find last '}' and first '{'
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else null
    }

    suspend fun generatePlan(userGoal: String): Plan? = withContext(Dispatchers.IO) {
        // Starting a brand-new plan for this chat session. Clear any prior progress and
        // ephemeral observations so old task ids (e.g., t1, t2) from previous plans do not
        // incorrectly mark new plan tasks as done.
        runCatching { if (progressFile.exists()) progressFile.delete() }
        observations.clear()
        saveObservations()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val sys = """
            You are an autonomous software agent that plans work as structured JSON only.
            The API is stateless; never rely on hidden memory. Design the plan to front-load discovery, order tools well, and include optional hints to guide the inner loop.
            Return ONLY a minified JSON object with the following shape and nothing else:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "targets": [string...], "search": [string...], "markers": [string...]}, ...]}
            - ids must be unique short strings (e.g., t1, t2, t3)
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell | json_edit
            - descriptions must be concrete and atomic
            - Include discovery tasks (list_dir_recursive/grep/read_files_glob) before modification tasks (apply_changes/write_file) and prefer precise scopes
            - Optional fields (targets/search/markers) should propose concrete globs, regexes, or section markers you expect to use, to prevent disruption if memory is truncated later
            - Prefer minimal, safe, idempotent steps
            - Do not include code in the plan. Code will be generated later via tool calls.
        """.trimIndent()
        val user = """
            Goal: ${userGoal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
        """.trimIndent()
        val messages = mutableListOf(
            LlmMessage("system", sys),
            LlmMessage("user", user)
        )
        val reco = helperRecommend("plan", mapOf("goal" to userGoal.take(1000), "wd" to wdPath))
        applyHelperToMessages(reco, messages)
        val flow: Flow<String> = LlmProvider.current().generate(messages)
        val content = collectAll(flow)
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val goal = obj.optString("goal").ifBlank { userGoal }
        val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<Task>()
        for (i in 0 until tasksArr.length()) {
            val t = tasksArr.optJSONObject(i)
            if (t != null) {
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
        }
        if (tasks.isEmpty()) {
            val fallback = mutableListOf<Task>()
            fallback.add(Task("t1", "List top-level workspace", "list_dir", listOf(wdPath), null, null))
            fallback.add(Task("t2", "Recursive listing of likely source directories", "list_dir_recursive", listOf(wdPath), null, null))
            fallback.add(Task("t3", "Search for common project entry files", "grep", listOf(wdPath), listOf("build\\.gradle|settings\\.gradle|package\\.json|README|Main|AndroidManifest"), null))
            tasks.addAll(fallback)
        }
        val plan = Plan(goal, tasks)
        persistPlanWithStatuses(plan)
        return@withContext plan
    }

    suspend fun executePlanSequentially(
        plan: Plan,
        onStatus: (String) -> Unit
    ) {
        for (task in plan.tasks) {
            if (isTaskDone(task.id)) {
                onStatus("Skip ${task.id}: already done")
                continue
            }
            onStatus("Task ${task.id}: ${task.description}")
             val toolCall = requestSingleToolCall(plan.goal, task)
             if (toolCall == null) {
                 observations[task.id] = "could not determine action for this task"
                 saveObservations()
                 onStatus("Task ${task.id}: could not determine action")
                 return
            }
            val result = runCatching { executeToolCall(toolCall) }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                saveObservations()
                onStatus("Task ${task.id} failed: ${err}")
                ToolResult(false, null)
            }
            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[task.id] = result.observation
                    saveObservations()
                    val preview = result.observation.take(800)
                    onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                }
                markTaskDone(task.id)
                onStatus("Task ${task.id}: done")
                // Refresh persisted plan statuses after each task
                persistPlanWithStatuses(plan)
            } else {
                if (!observations.containsKey(task.id)) {
                    observations[task.id] = "failed without exception"
                    saveObservations()
                }
                onStatus("Task ${task.id}: failed")
                return
            }
        }
    }

    // Request an updated plan from the LLM, preserving completed tasks and allowing future tasks to adjust.
    suspend fun requestUpdatedPlan(plan: Plan): Plan? = withContext(Dispatchers.IO) {
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val completed = JSONArray().apply {
            plan.tasks.forEach { if (isTaskDone(it.id)) put(it.id) }
        }
        val failed = JSONArray().apply {
            val progress = loadProgress()
            val tasks = progress.optJSONObject("tasks") ?: JSONObject()
            tasks.keys().forEach { k ->
                if (tasks.optJSONObject(k)?.optString("status") == "failed") put(k)
            }
        }
        val currentPlanJson = runCatching { JSONObject(planFile.readText()) }.getOrNull()?.toString() ?: "{}"
        val obsJson = JSONObject().apply {
            observations.entries.forEach { (k, v) -> put(k, if (v.length > 5000) v.take(5000) + " …" else v) }
        }.toString()
        val sys = """
            You update task plans. Return ONLY a minified JSON with shape:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "status": "done"|"pending", "targets": [string...], "search": [string...], "markers": [string...]}, ...]}
            Rules:
            - Keep ids stable for already completed tasks and mark them status:"done".
            - For failed tasks, either refine them into safer, smaller discovery steps or replace them.
            - You may add, remove, or edit pending tasks if needed.
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell | json_edit
            - Prefer minimal safe changes. Avoid repeating identical discovery without new signals.
            - Include optional hints (targets/search/markers) to guide discovery and precise editing in a stateless environment.
            - Do not include explanations.
        """.trimIndent()
        val user = """
            Current working directory: ${wdPath}
            Workspace snapshot: ${workspaceInfo}
            Completed task ids: ${completed}
            Failed task ids: ${failed}
            Prior observations and failure notes: ${obsJson}
            Current plan JSON: ${currentPlanJson}
            Produce the updated plan JSON now.
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("plan_update", mapOf("goal" to plan.goal.take(500), "wd" to wdPath))
        applyHelperToMessages(reco, messages)
        val flow = LlmProvider.current().generate(messages)
        val content = collectAll(flow)
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val goal = obj.optString("goal").ifBlank { plan.goal }
        val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<Task>()
        for (i in 0 until tasksArr.length()) {
            val t = tasksArr.optJSONObject(i)
            if (t != null) {
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
        }
        val updated = Plan(goal, tasks)
        persistPlanWithStatuses(updated)
        // update plan signature and reset attempts upon new plan
        val progress = loadProgress()
        setPlanSignature(progress, computePlanSignature(updated))
        resetAllAttempts()
        saveProgress(progress)
        return@withContext updated
    }

    private fun commandCacheKey(command: String, wd: String): String = wd + "||" + command

    private fun buildCliReport(maxItems: Int = 100): JSONObject {
        val arr = JSONArray()
        commandCache.entries.toList().takeLast(maxItems).forEach { entry ->
            val v = entry.value
            arr.put(
                JSONObject()
                    .put("ts", v.optLong("ts"))
                    .put("wd", v.optString("wd"))
                    .put("command", v.optString("command"))
                    .put("exit", v.optInt("exit"))
                    .put("output", v.optString("output").take(4000))
            )
        }
        return JSONObject().put("items", arr)
    }

    private fun persistCliReport() {
        runCatching { cliReportFile.writeText(buildCliReport().toString(2)) }
    }

    private suspend fun revisePlanBasedOnHistoryAndError(goal: String, errorNote: String): Plan? = withContext(Dispatchers.IO) {
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd, limit = 200) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val obsJson = JSONObject().apply {
            observations.entries.forEach { (k, v) -> put(k, if (v.length > 2000) v.take(2000) + " …" else v) }
        }.toString()
        val cliJson = runCatching { cliReportFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: buildCliReport().toString()
        val sys = """
            You will revise a failing plan using recent command-line history, observations, and the error.
            Return ONLY a minified JSON object: {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "targets": [string...], "search": [string...], "markers": [string...]}, ...]}
            Rules:
            - Keep steps concise, discovery-first; include environment checks via run_shell if relevant
            - Use precise scopes and idempotent edits
            - Do not include code blocks; only the plan JSON
        """.trimIndent()
        val user = """
            Goal: ${goal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
            Last error: ${errorNote}
            Observations: ${obsJson}
            Command-line report (recent): ${cliJson}
        """.trimIndent()
        val messages = mutableListOf(LlmMessage("system", sys), LlmMessage("user", user))
        val reco = helperRecommend("revise_plan", mapOf("goal" to goal.take(500), "error" to errorNote.take(200)))
        applyHelperToMessages(reco, messages)
        val content = collectAll(LlmProvider.current().generate(messages))
        val jsonText = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null
        val goalOut = obj.optString("goal").ifBlank { goal }
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
            if (desc.isNotBlank()) tasks.add(Task(id, desc, cat, targets, search, markers))
        }
        val newPlan = Plan(goalOut, tasks)
        persistPlanWithStatuses(newPlan)
        return@withContext newPlan
    }

    private fun buildHelperRecommendationPrompt(kind: String, contextMap: Map<String, String>): Pair<String,String> {
        val sys = """
            You are a side helper agent. Return ONLY compact JSON with keys you need to adjust the main agent call.
            Schema: {"prompt_prefix": string, "prompt_suffix": string, "suggested_tools": [string...], "max_tokens": number, "temperature": number, "model": string}
            Return minified JSON without extra text. Omit fields you don't adjust.
        """.trimIndent()
        val user = JSONObject().apply {
            put("kind", kind)
            contextMap.forEach { (k,v) -> put(k, v.take(2000)) }
        }.toString()
        return sys to user
    }

    private suspend fun helperRecommend(kind: String, contextMap: Map<String,String>): JSONObject? = withContext(Dispatchers.IO) {
        if (!Settings.helper_agent_enabled) return@withContext null
        val (sys, user) = buildHelperRecommendationPrompt(kind, contextMap)
        val content = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))))
        val json = extractFirstJsonObject(content) ?: return@withContext null
        return@withContext runCatching { JSONObject(json) }.getOrNull()
    }

    private fun applyHelperToMessages(reco: JSONObject?, messages: MutableList<LlmMessage>) {
        if (reco == null) return
        val prefix = reco.optString("prompt_prefix").ifBlank { null }
        val suffix = reco.optString("prompt_suffix").ifBlank { null }
        if (prefix != null) messages.add(0, LlmMessage("system", prefix))
        if (suffix != null) messages.add(LlmMessage("user", suffix))
        // Save suggested overrides to settings (ephemeral; UI provides defaults)
        val maxTok = reco.optInt("max_tokens", -1)
        if (maxTok > 0) Settings.ai_max_tokens = maxTok
        val temp = reco.optDouble("temperature", Double.NaN)
        if (!temp.isNaN()) Settings.ai_temperature_str = temp.toString()
        val model = reco.optString("model").ifBlank { null }
        if (model != null) Settings.api_model = model
    }

    private fun detectLanguagesAndFrameworks(root: File, maxFiles: Int = 1000): JSONObject {
        val langCounts = mutableMapOf<String, Int>()
        val frameworks = mutableSetOf<String>()
        var scanned = 0
        fun extOf(f: File): String = f.name.substringAfterLast('.', "").lowercase()
        fun walk(d: File) {
            if (!d.isDirectory || scanned >= maxFiles) return
            d.listFiles()?.forEach { f ->
                if (scanned >= maxFiles) return
                if (f.isDirectory) walk(f) else {
                    scanned++
                    when (extOf(f)) {
                        "kt", "kts" -> langCounts["kotlin"] = (langCounts["kotlin"] ?: 0) + 1
                        "java" -> langCounts["java"] = (langCounts["java"] ?: 0) + 1
                        "ts", "tsx" -> langCounts["typescript"] = (langCounts["typescript"] ?: 0) + 1
                        "js", "jsx" -> langCounts["javascript"] = (langCounts["javascript"] ?: 0) + 1
                        "py" -> langCounts["python"] = (langCounts["python"] ?: 0) + 1
                        "rb" -> langCounts["ruby"] = (langCounts["ruby"] ?: 0) + 1
                        "go" -> langCounts["go"] = (langCounts["go"] ?: 0) + 1
                        "rs" -> langCounts["rust"] = (langCounts["rust"] ?: 0) + 1
                        "swift" -> langCounts["swift"] = (langCounts["swift"] ?: 0) + 1
                        "m", "mm" -> langCounts["objective-c"] = (langCounts["objective-c"] ?: 0) + 1
                        "cs" -> langCounts["csharp"] = (langCounts["csharp"] ?: 0) + 1
                        "cpp", "cc", "cxx", "c" -> langCounts["cpp/c"] = (langCounts["cpp/c"] ?: 0) + 1
                        "gradle", "gradle.kts" -> frameworks.add("gradle")
                        "xml" -> if (f.path.contains("src/main/AndroidManifest.xml")) frameworks.add("android")
                        "json" -> if (f.name == "package.json") frameworks.add("node")
                        "yaml", "yml" -> if (f.name == "pubspec.yaml") frameworks.add("flutter")
                    }
                    val name = f.name.lowercase()
                    if (name == "build.gradle" || name == "build.gradle.kts") frameworks.add("gradle")
                    if (name == "settings.gradle" || name == "settings.gradle.kts") frameworks.add("gradle")
                    if (name == "pom.xml") frameworks.add("maven")
                }
            }
        }
        walk(root)
        val langs = JSONArray()
        langCounts.entries.sortedByDescending { it.value }.forEach { (k, v) -> langs.put(JSONObject().put("lang", k).put("files", v)) }
        val fw = JSONArray(); frameworks.forEach { fw.put(it) }
        return JSONObject().put("languages", langs).put("frameworks", fw)
    }

    private fun selectImportantFiles(root: File, limit: Int = 100): JSONArray {
        val candidates = mutableListOf<File>()
        fun score(f: File): Int {
            val name = f.name.lowercase()
            var s = 0
            if (name.contains("readme") || name.contains("license")) s += 3
            if (name.contains("main") || name.contains("app") || name.contains("orchestrator") || name.contains("manager")) s += 2
            if (name.contains("build") || name.contains("gradle") || name.contains("manifest")) s += 2
            if (name.endsWith(".kt") || name.endsWith(".java")) s += 1
            if (f.length() > 0) s += 1
            return s
        }
        fun walk(d: File) {
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f) else candidates.add(f)
            }
        }
        walk(root)
        val arr = JSONArray()
        candidates.asSequence().sortedByDescending { score(it) }.take(limit).forEach { f ->
            arr.put(JSONObject().put("path", f.absolutePath).put("size", f.length()))
        }
        return arr
    }

    private suspend fun buildCodebaseCache(onStatus: (String) -> Unit, includeRecursive: Boolean = false) = withContext(Dispatchers.IO) {
        if (!Settings.codebase_agent_enabled) return@withContext
        val wd = File(workingDirProvider())
        if (!wd.exists() || !wd.isDirectory) return@withContext
        onStatus("Codebase: scanning ${wd.absolutePath}")
        val overview = detectLanguagesAndFrameworks(wd)
        val important = selectImportantFiles(wd)
        val summarySys = """
            You will summarize a repository at a high level.
            Return ONLY minified JSON: {"overview": string, "roles": [{"module": string, "role": string}], "notes": [string...]}
        """.trimIndent()
        val filesPreview = run {
            val limit = min(important.length(), 10)
            (0 until limit).joinToString("\n") { idx -> important.optJSONObject(idx)?.optString("path").orEmpty() }
        }
        val summaryUser = """
            Important files (sample):
            ${filesPreview}
            Languages/Frameworks: ${overview}
        """.trimIndent()
        val summaryContent = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", summarySys), LlmMessage("user", summaryUser))))
        val summaryJson = extractFirstJsonObject(summaryContent) ?: JSONObject().put("overview", "").put("roles", JSONArray()).put("notes", JSONArray()).toString()
        // If workspace appears empty, avoid hallucinated analysis
        val langsEmpty = (overview.optJSONArray("languages")?.length() ?: 0) == 0
        val fwsEmpty = (overview.optJSONArray("frameworks")?.length() ?: 0) == 0
        val importantEmpty = important.length() == 0
        val analysisObj = if (langsEmpty && fwsEmpty && importantEmpty) {
            JSONObject().put("overview", "Workspace appears empty.").put("roles", JSONArray()).put("notes", JSONArray())
        } else runCatching { JSONObject(summaryJson) }.getOrElse { JSONObject().put("overview", summaryContent.take(1000)) }
        // Build recursive scan targets based on frameworks and common asset directories
        val frameworks = overview.optJSONArray("frameworks") ?: JSONArray()
        val languages = overview.optJSONArray("languages") ?: JSONArray()
        val topLang = languages.optJSONObject(0)?.optString("lang") ?: ""
        fun collectDir(name: String): List<File> {
            val out = mutableListOf<File>()
            fun walk(d: File, depth: Int) {
                if (depth > 3) return
                d.listFiles()?.forEach { f ->
                    if (f.isDirectory) {
                        if (f.name.equals(name, ignoreCase = true)) out.add(f)
                        walk(f, depth + 1)
                    }
                }
            }
            walk(wd, 0)
            return out
        }
        val targetsArr = JSONArray()
        fun addTargets(bases: List<File>, reason: String, framework: String?) {
            bases.forEach { base ->
                targetsArr.put(
                    JSONObject()
                        .put("base", base.absolutePath)
                        .put("reason", reason)
                        .put("framework", framework ?: "")
                        .put("language", topLang)
                        .put("tool", "list_dir_recursive")
                        .put("max_depth", 4)
                        .put("max_entries", 1200)
                )
            }
        }
        val fwSet = (0 until frameworks.length()).mapNotNull { idx -> frameworks.optString(idx) }.toSet()
        if (fwSet.contains("android") || fwSet.contains("gradle") || fwSet.contains("maven")) {
            val bases = mutableListOf<File>()
            bases.addAll(collectDir("src"))
            bases.addAll(collectDir("app"))
            bases.addAll(collectDir("core"))
            bases.addAll(collectDir("module"))
            addTargets(bases.distinct(), "android/java project structure", fwSet.firstOrNull())
            addTargets(collectDir("assets"), "assets for android/java", fwSet.firstOrNull())
            addTargets(collectDir("res"), "resources (res) for android", fwSet.firstOrNull())
        }
        if (fwSet.contains("node")) {
            addTargets((collectDir("src") + collectDir("lib") + collectDir("public") + collectDir("assets")).distinct(), "node project dirs", "node")
        }
        if (fwSet.contains("flutter")) {
            addTargets((collectDir("lib") + collectDir("assets")).distinct(), "flutter project dirs", "flutter")
        }
        // Generic assets/resources/static/templates
        addTargets((collectDir("resources") + collectDir("static") + collectDir("templates")).distinct(), "common resource directories", fwSet.firstOrNull())

        val cache = JSONObject()
            .put("root", wd.absolutePath)
            .put("detected", overview)
            .put("important_files", important)
            .put("recursive_scan_targets", if (includeRecursive) targetsArr else JSONArray())
            .put("analysis", analysisObj)
        val cacheFile = File(wd, Settings.codebase_cache_path)
        cacheFile.writeText(cache.toString(2))
        onStatus("Codebase: cache written ${cacheFile.absolutePath}")
    }

    private fun notifyWorkspaceChanged(path: String) {
        pendingCodebaseChanges.add(path)
    }

    private suspend fun performCodebaseUpgradeIfPending(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!Settings.codebase_agent_enabled) return@withContext
        if (pendingCodebaseChanges.isEmpty()) return@withContext
        onStatus("Codebase: changes detected (${pendingCodebaseChanges.size}); updating cache…")
        runCatching { buildCodebaseCache(onStatus, includeRecursive = true) }
        pendingCodebaseChanges.clear()
        lastCodebaseRefreshMs = System.currentTimeMillis()
    }

    private suspend fun writerSuggestTool(planGoal: String, task: Task, original: ToolCall): ToolCall? = withContext(Dispatchers.IO) {
        if (!Settings.writer_agent_enabled) return@withContext null
        // Try to resolve a target file path for context
        var targetPath: String? = null
        if (original.args.has("path")) {
            targetPath = original.args.optString("path").ifBlank { null }
        } else if (original.type == "apply_changes") {
            val edits = original.args.optJSONArray("edits")
            if (edits != null && edits.length() > 0) {
                targetPath = edits.optJSONObject(0)?.optString("path")
            }
        }
        val fileObj = JSONObject()
        if (!targetPath.isNullOrBlank()) {
            val f = resolvePath(targetPath!!)
            if (f.exists() && f.isFile) {
                val content = runCatching { f.readText() }.getOrElse { "" }
                fileObj.put("path", f.absolutePath).put("content", content.take(40000)).put("bytes", f.length())
            } else {
                fileObj.put("path", f.absolutePath).put("missing", true)
            }
        }
        val sys = """
            You are a writer agent that selects the safest, most idempotent write tool for a change.
            Available tools: write_file, search_replace, apply_changes (ops: replace_exact, replace_between_markers, insert_after_anchor, insert_before_anchor, replace_regex, ensure_block_present, append_once, write_if_missing).
            Return ONLY minified JSON: {"tool_call": {"type": string, "args": object}, "reason": string, "proposed_new_tool": {"name": string, "example_regex": string, "purpose": string}}
        """.trimIndent()
        val user = """
            Goal: ${planGoal}
            Task: ${task.id} - ${task.description}
            Proposed tool: {"type":"${original.type}","args":${original.args}}
            Target file: ${fileObj}
            Suggest a better tool_call if needed; keep it idempotent and minimal.
        """.trimIndent()
        val content = collectAll(LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user))))
        val json = extractFirstJsonObject(content) ?: return@withContext null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val proposed = obj.optJSONObject("proposed_new_tool")
        if (proposed != null) appendWriterToolSuggestion(proposed)
        val tcObj = obj.optJSONObject("tool_call") ?: return@withContext null
        val t = tcObj.optString("type").ifBlank { original.type }
        val a = tcObj.optJSONObject("args") ?: original.args
        return@withContext ToolCall(t, a)
    }

    private fun loadWriterToolsRoot(): JSONObject {
        return runCatching { JSONObject(writerToolsFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrElse { JSONObject().put("suggestions", JSONArray()) }
    }

    private fun appendWriterToolSuggestion(sugg: JSONObject) {
        val root = loadWriterToolsRoot()
        val arr = root.optJSONArray("suggestions") ?: JSONArray().also { root.put("suggestions", it) }
        sugg.put("ts", System.currentTimeMillis())
        arr.put(sugg)
        writerToolsFile.writeText(root.toString(2))
    }

    private fun coerceToolCallForTaskCategory(task: Task, proposed: ToolCall): ToolCall {
        val wd = workingDirProvider()
        val cat = (task.category ?: "").lowercase()
        fun loadCodebaseCache(): JSONObject? {
            return runCatching { File(wd, Settings.codebase_cache_path).takeIf { it.exists() }?.readText() }
                .mapCatching { JSONObject(it!!) }.getOrNull()
        }
        fun preferredPackageManager(): String? {
            // Prefer explicit manager signal, else infer from OS ID
            if (lastDetectedManagers.contains("apk")) return "apk"
            if (lastDetectedManagers.contains("apt")) return "apt"
            if (lastDetectedManagers.contains("dnf")) return "dnf"
            if (lastDetectedManagers.contains("yum")) return "yum"
            if (lastDetectedManagers.contains("pacman")) return "pacman"
            when (lastDetectedOsId) {
                "alpine" -> return "apk"
                "debian", "ubuntu" -> return "apt"
                "fedora" -> return "dnf"
                "centos", "rhel" -> return "yum"
                "arch" -> return "pacman"
            }
            return null
        }
        fun coerceInstallPythonIfNeeded(): ToolCall? {
            val desc = task.description.lowercase()
            val installingPython = desc.contains("install") && (desc.contains("python") || desc.contains("pip"))
            val t = proposed.type.lowercase().trim()
            if (!installingPython && t != "run_shell") return null
            val mgr = preferredPackageManager() ?: return null
            val cmd = when (mgr) {
                "apk" -> "command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || (apk update && apk add --no-cache python3 py3-pip)"
                "apt" -> "command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || (apt-get update && apt-get install -y python3 python3-pip)"
                "dnf" -> "command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || (dnf install -y python3 python3-pip)"
                "yum" -> "command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || (yum install -y python3 python3-pip)"
                "pacman" -> "command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || (pacman -Sy --noconfirm python python-pip)"
                else -> null
            }
            return if (cmd != null) ToolCall("run_shell", JSONObject().put("command", cmd)) else null
        }
        return when (cat) {
            "list_dir" -> {
                val target = task.targets?.firstOrNull()?.takeIf { it.isNotBlank() } ?: wd
                val t = proposed.type.lowercase().trim()
                if (t == "list_dir" || t == "listdir" || t == "ls" || t == "dir") proposed
                else ToolCall("list_dir", JSONObject().put("path", target))
            }
            "read_file" -> {
                // If the tool isn't a read*, coerce to stat first to avoid failure
                val target = task.targets?.firstOrNull()?.takeIf { it.isNotBlank() } ?: wd
                val t = proposed.type.lowercase().trim()
                if (t.startsWith("read_")) proposed else ToolCall("stat_file", JSONObject().put("path", target))
            }
            "grep" -> {
                val target = task.targets?.firstOrNull()?.takeIf { it.isNotBlank() } ?: wd
                val pattern = task.search?.firstOrNull()?.ifBlank { null } ?: "."
                val t = proposed.type.lowercase().trim()
                if (t == "grep") proposed else ToolCall("grep", JSONObject().put("path", target).put("pattern", pattern).put("max_results", 200))
            }
            "list_dir_recursive" -> {
                val cache = loadCodebaseCache()
                val targets = cache?.optJSONArray("recursive_scan_targets")
                if (targets != null && targets.length() > 0) {
                    val first = targets.optJSONObject(0)
                    val base = first?.optString("base")?.ifBlank { null } ?: wd
                    val depth = first?.optInt("max_depth", 3) ?: 3
                    val entries = first?.optInt("max_entries", 500) ?: 500
                    ToolCall("list_dir_recursive", JSONObject().put("path", base).put("max_depth", depth).put("max_entries", entries))
                } else {
                    // Downgrade to grep for project files as a discovery step
                    val pattern = task.search?.firstOrNull()?.ifBlank { null }
                        ?: "build\\.gradle|settings\\.gradle|package\\.json|README|Main|AndroidManifest"
                    ToolCall("grep", JSONObject().put("path", wd).put("pattern", pattern).put("max_results", 200))
                }
            }
            else -> coerceInstallPythonIfNeeded() ?: proposed
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
}

object HiddenShell {
    fun execInHiddenSession(activity: MainActivity?, wd: String, command: String, timeoutMs: Long): String {
        if (activity == null || activity.sessionBinder == null) return "Hidden session not available"
        val binder: SessionService.SessionBinder = activity.sessionBinder!!
        val service = binder.getService()
        // Force Alpine working mode to ensure apk/git and PATH from Alpine are available
        val workingMode = com.rk.terminal.ui.screens.settings.WorkingMode.ALPINE
        val sessionId = "agent-bg-" + System.currentTimeMillis()

        // Prefer writing output next to the working directory for proot visibility; fallback to cache
        val preferredOut = runCatching { File(wd).takeIf { it.exists() && it.isDirectory && it.canWrite() } }.getOrNull()
        val outFile = runCatching { File(preferredOut ?: activity.cacheDir, ".${sessionId}.out") }.getOrNull() ?: File(activity.cacheDir, ".${sessionId}.out")
        runCatching { if (outFile.exists()) outFile.delete() }.getOrElse { }
        val outPath = outFile.absolutePath.replace("'", "'\\''")
        val sentinel = "__AGENT_DONE_${System.currentTimeMillis()}__"

        // Minimal client
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        // Create session on main thread
        val createLatch = java.util.concurrent.CountDownLatch(1)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var scheduledOk = false
        mainHandler.post {
            try {
                val session = binder.createHiddenSession(sessionId, client, activity, workingMode)
                val cmdLine = "cd \"$wd\"; umask 022; ( $command ) > '$outPath' 2>&1; echo $sentinel >> '$outPath'\n"
                // Delay writes so proot + login shell can initialize and attach to the pty
                mainHandler.postDelayed({
                    runCatching { session.write(cmdLine) }
                }, 800)
                mainHandler.postDelayed({
                    runCatching {
                        if (!outFile.exists() || runCatching { outFile.readText() }.getOrElse { "" }.contains(sentinel).not()) {
                            session.write(cmdLine)
                        }
                    }
                }, 1600)
                scheduledOk = true
            } catch (e: Exception) {
                runCatching {
                    outFile.parentFile?.mkdirs()
                    outFile.writeText("Hidden session error: ${e.message ?: e.toString()}\n")
                    outFile.appendText(sentinel)
                }
            } finally {
                createLatch.countDown()
            }
        }
        createLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val start = System.currentTimeMillis()

        // Poll the output file until sentinel appears or timeout
        var content: String = ""
        var sawSentinel = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (outFile.exists()) {
                content = runCatching { outFile.readText() }.getOrElse { "" }
                if (content.contains(sentinel)) { sawSentinel = true; break }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }

        // Terminate session on main thread (best-effort)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { binder.terminateHiddenSession(sessionId) } catch (_: Exception) {}
        }

        // Clean and return
        if (content.isBlank() && outFile.exists()) {
            content = runCatching { outFile.readText() }.getOrElse { "" }
        }
        content = content.replace(sentinel, "").trim()
        if (!sawSentinel && scheduledOk) {
            content = (if (content.isBlank()) "" else content + "\n") + "[hidden session timeout before sentinel]"
        }
        runCatching { outFile.delete() }
        return content
    }
}