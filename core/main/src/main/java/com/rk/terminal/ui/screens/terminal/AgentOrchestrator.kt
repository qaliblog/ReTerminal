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
        val category: String? = null
    )

    data class Plan(
        val goal: String,
        val tasks: List<Task>
    )

    private val agentDir: File by lazy {
        // Store per chat session
        File(application!!.filesDir, "chat/${sessionId}").apply { mkdirs() }
    }
    private val planFile: File by lazy { File(agentDir, "plan.json") }
    private val progressFile: File by lazy { File(agentDir, "progress.json") }

    // Per-run observations (taskId -> observation text)
    private val observations: MutableMap<String, String> = linkedMapOf()

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
        tasks.put(taskId, JSONObject().apply {
            put("status", "done")
            put("ts", System.currentTimeMillis())
        })
        saveProgress(progress)
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
        val task = getNextPendingTask(plan) ?: return false
        onStatus("Task ${task.id}: ${task.description}")

        var stepsTaken = 0
        val maxSteps = 5
        while (stepsTaken < maxSteps) {
            val toolCall = requestSingleToolCall(plan.goal, task)
            if (toolCall == null) {
                observations[task.id] = "could not determine action for this task"
                onStatus("Task ${task.id}: could not determine action")
                return false
            }
            val result = runCatching { executeToolCall(toolCall) }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                onStatus("Task ${'$'}{task.id} failed: ${'$'}{err}")
                return false
            }

            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[task.id] = result.observation
                    val preview = result.observation.take(800)
                    onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                }

                // If the tool modified the workspace, consider the task complete.
                if (isModifyingTool(toolCall.type)) {
                    markTaskDone(task.id)
                    onStatus("Task ${'$'}{task.id}: done")
                    return true
                }

                // Discovery-type call; iterate to request the next action using fresh observation
                stepsTaken++
                continue
            } else {
                if (!observations.containsKey(task.id)) {
                    observations[task.id] = "failed without exception"
                }
                onStatus("Task ${'$'}{task.id}: failed")
                return false
            }
        }

        onStatus("Task ${'$'}{task.id}: reached step limit without completion")
        return false
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
            "write_file", "apply_changes", "make_dir", "create_file" -> true
            else -> false
        }
    }

    private suspend fun requestSingleToolCall(goal: String, task: Task): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            You are orchestrating a short inner loop to complete the current task.
            Return ONLY a single minified JSON object describing ONE tool call to move the task forward.
            Allowed schemas:
            {"type":"create_file","args":{"path": string}}
            {"type":"write_file","args":{"path": string, "content": string, "mode": "overwrite"|"append"}}
            {"type":"make_dir","args":{"path": string}}
            {"type":"run_shell","args":{"command": string}}
            {"type":"list_dir","args":{"path": string}}
            {"type":"read_file","args":{"path": string, "max_bytes": number}}
            {"type":"apply_changes","args":{"edits": [
                {"path": string, "op": "replace_exact", "old": string, "new": string},
                {"path": string, "op": "replace_between_markers", "start_marker": string, "end_marker": string, "new_content": string, "include_markers": false},
                {"path": string, "op": "insert_after_anchor", "anchor": string, "new_content": string},
                {"path": string, "op": "insert_before_anchor", "anchor": string, "new_content": string}
            ]}}
            Rules:
            - Use relative paths with respect to the current working directory unless absolute is required.
            - When writing source code that implements the goal, include the full file content in "content" for write_file. Do not use placeholders.
            - For updating existing files, prefer apply_changes with minimal edits over sending full file content. Use unique anchors and exact old text for precise replacements.
            - Consider the provided observations from prior steps. If a relevant file's content has already been observed, propose the write/apply_changes directly.
            - If the task's category suggests discovery (e.g., read, list, grep) and you lack the relevant observation, propose a discovery call first.
            - If the task's category suggests modification (e.g., write/apply_changes), and needed context is missing, first propose a minimal discovery call to fetch it.
            - Do not return markdown code fences. Return pure JSON on a single line.
            - Do not include explanations.
        """.trimIndent()
        val wd = workingDirProvider()
        val prior = if (observations.isEmpty()) "(none)" else observations.entries.joinToString("\n") { (k, v) -> "${k}: ${v.take(500)}${if (v.length > 500) " …" else ""}" }
        val prompt = """
            Goal: ${goal}
            Working directory: ${wd}
            Current task id: ${task.id}
            Task: ${task.description}
            Task category: ${task.category ?: "unspecified"}
            Prior observations (latest first):
            ${prior}
            Produce one tool call JSON now, following the Rules and leveraging the observations to avoid redundant discovery.
        """.trimIndent()
        val flow = LlmProvider.current().generate(
            listOf(
                LlmMessage("system", sys),
                LlmMessage("user", prompt)
            )
        )
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
        return if (f.isAbsolute) f else File(base, raw)
    }

    private fun ensureParentDirs(file: File) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    }

    private fun executeToolCall(tc: ToolCall): ToolResult {
        return when (tc.type) {
            "create_file" -> {
                val path = tc.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                if (!f.exists()) f.createNewFile()
                ToolResult(f.exists(), null)
            }
            "write_file" -> {
                val path = tc.args.optString("path")
                val content = tc.args.optString("content")
                val mode = tc.args.optString("mode", "overwrite")
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                if (mode == "append" && f.exists()) {
                    f.appendText(content)
                } else {
                    f.writeText(content)
                }
                val ok = f.exists() && f.length() >= 0
                ToolResult(ok, null)
            }
            "make_dir" -> {
                val path = tc.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val d = resolvePath(path)
                d.mkdirs()
                ToolResult(d.exists() && d.isDirectory, null)
            }
            "run_shell" -> {
                val command = tc.args.optString("command")
                require(command.isNotBlank()) { "command missing" }
                val wd = workingDirProvider()
                val proc = ProcessBuilder("sh", "-c", command)
                    .directory(File(wd))
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                ToolResult(exit == 0, output.ifBlank { null })
            }
            "list_dir" -> {
                val path = tc.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val d = resolvePath(path)
                val listing = if (d.exists() && d.isDirectory) listTopLevel(d, limit = 200) else JSONObject().put("path", d.absolutePath).put("items", JSONArray()).toString()
                ToolResult(true, listing)
            }
            "read_file" -> {
                val path = tc.args.optString("path")
                val maxBytes = tc.args.optInt("max_bytes", 65536).coerceAtLeast(1024)
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                val content = if (f.exists() && f.isFile) {
                    val bytes = f.readBytes()
                    val slice = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
                    val text = String(slice)
                    JSONObject().put("path", f.absolutePath).put("bytes", bytes.size).put("content", text).put("truncated", bytes.size > maxBytes).toString()
                } else {
                    JSONObject().put("path", f.absolutePath).put("missing", true).toString()
                }
                ToolResult(true, content)
            }
            "apply_changes" -> {
                val edits = tc.args.optJSONArray("edits") ?: JSONArray()
                val results = mutableListOf<String>()
                for (i in 0 until edits.length()) {
                    val e = edits.optJSONObject(i) ?: continue
                    val op = e.optString("op")
                    val path = e.optString("path")
                    if (path.isBlank()) { results.add("edit[$i]: missing path"); continue }
                    val file = resolvePath(path)
                    if (!file.exists()) { results.add("edit[$i]: file missing: ${file.path}"); continue }
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
                        else -> { results.add("edit[$i]: unknown op ${op}"); null }
                    }
                    if (updated != null) {
                        runCatching { file.writeText(updated) }.onSuccess {
                            results.add("edit[$i]: ok (${path})")
                        }.onFailure { ex ->
                            results.add("edit[$i]: write failed (${ex.message})")
                        }
                    }
                }
                val summary = (if (results.isEmpty()) "no edits" else results.joinToString("; "))
                ToolResult(true, summary)
            }
            else -> ToolResult(false, null)
        }
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
        return null
    }

    suspend fun generatePlan(userGoal: String): Plan? = withContext(Dispatchers.IO) {
        // Starting a brand-new plan for this chat session. Clear any prior progress and
        // ephemeral observations so old task ids (e.g., t1, t2) from previous plans do not
        // incorrectly mark new plan tasks as done.
        runCatching { if (progressFile.exists()) progressFile.delete() }
        observations.clear()
        val wdPath = workingDirProvider()
        val wd = File(wdPath)
        val workspaceInfo = if (wd.exists() && wd.isDirectory) listTopLevel(wd) else JSONObject().put("path", wdPath).put("items", JSONArray()).toString()
        val sys = """
            You are an autonomous software agent that plans work as structured JSON only.
            Return ONLY a minified JSON object with the following shape and nothing else:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string}, ...]}
            - ids must be unique short strings (e.g., t1, t2, t3)
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell
            - descriptions must be concrete and atomic
            - Include discovery tasks when needed (list_dir/read_file/grep) before modification tasks (write_file/apply_changes).
            - If a task requires reading then fixing a file, categorize the task based on the dominant action (e.g., apply_changes), and the inner loop will do discovery first if needed.
            - Prefer minimal, safe, idempotent steps.
            - Do not include code in the plan. Code will be generated later via tool calls.
        """.trimIndent()
        val user = """
            Goal: ${userGoal}
            Working directory: ${wdPath}
            Workspace snapshot (top-level): ${workspaceInfo}
        """.trimIndent()
        val messages = listOf(
            LlmMessage("system", sys),
            LlmMessage("user", user)
        )
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
                if (desc.isNotBlank()) {
                    tasks.add(Task(id, desc, cat))
                }
            }
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
                onStatus("Skip ${'$'}{task.id}: already done")
                continue
            }
            onStatus("Task ${task.id}: ${task.description}")
             val toolCall = requestSingleToolCall(plan.goal, task)
             if (toolCall == null) {
                 observations[task.id] = "could not determine action for this task"
                 onStatus("Task ${task.id}: could not determine action")
                 return
            }
            val result = runCatching { executeToolCall(toolCall) }.getOrElse { e ->
                val err = e.message ?: e.toString()
                observations[task.id] = "error: ${err}"
                onStatus("Task ${'$'}{task.id} failed: ${'$'}{err}")
                ToolResult(false, null)
            }
            if (result.ok) {
                if (!result.observation.isNullOrBlank()) {
                    observations[task.id] = result.observation
                    val preview = result.observation.take(800)
                    onStatus("Observed (${task.id}): ${preview}${if (result.observation.length > 800) " …" else ""}")
                }
                markTaskDone(task.id)
                onStatus("Task ${'$'}{task.id}: done")
                // Refresh persisted plan statuses after each task
                persistPlanWithStatuses(plan)
            } else {
                if (!observations.containsKey(task.id)) {
                    observations[task.id] = "failed without exception"
                }
                onStatus("Task ${'$'}{task.id}: failed")
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
        val currentPlanJson = runCatching { JSONObject(planFile.readText()) }.getOrNull()?.toString() ?: "{}"
        val obsJson = JSONObject().apply {
            observations.entries.forEach { (k, v) -> put(k, if (v.length > 5000) v.take(5000) + " …" else v) }
        }.toString()
        val sys = """
            You update task plans. Return ONLY a minified JSON with shape:
            {"goal": string, "tasks": [{"id": string, "category": string, "description": string, "status": "done"|"pending"}, ...]}
            Rules:
            - Keep ids stable for already completed tasks and mark them status:"done".
            - You may add, remove, or edit pending tasks if needed.
            - category must be one of: list_dir | read_file | grep | analyze | write_file | apply_changes | make_dir | create_file | run_shell
            - Prefer minimal safe changes.
            - Do not include explanations.
        """.trimIndent()
        val user = """
            Current working directory: ${wdPath}
            Workspace snapshot: ${workspaceInfo}
            Completed task ids: ${completed}
            Prior observations: ${obsJson}
            Current plan JSON: ${currentPlanJson}
            Produce the updated plan JSON now.
        """.trimIndent()
        val flow = LlmProvider.current().generate(listOf(LlmMessage("system", sys), LlmMessage("user", user)))
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
                if (desc.isNotBlank()) {
                    tasks.add(Task(id, desc, cat))
                }
            }
        }
        val updated = Plan(goal, tasks)
        persistPlanWithStatuses(updated)
        return@withContext updated
    }
}