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
 * - Executes the tool call (create file, write file, mkdir, run shell)
 * - Marks tasks done in a progress cache to ensure idempotency
 */
class AgentOrchestrator(
    private val context: Context,
    private val sessionId: String,
    private val workingDirProvider: () -> String
) {

    data class Task(
        val id: String,
        val description: String
    )

    data class Plan(
        val goal: String,
        val tasks: List<Task>
    )

    private val agentDir: File by lazy {
        File(application!!.filesDir, "agent/${sessionId}").apply { mkdirs() }
    }
    private val planFile: File by lazy { File(agentDir, "plan.json") }
    private val progressFile: File by lazy { File(agentDir, "progress.json") }

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

    suspend fun generatePlan(userGoal: String): Plan? = withContext(Dispatchers.IO) {
        val sys = """
            You are an autonomous software agent that plans work as structured JSON only.
            Return ONLY a minified JSON object with the following shape and nothing else:
            {"goal": string, "tasks": [{"id": string, "description": string}, ...]}
            - ids must be unique short strings (e.g., t1, t2, t3)
            - descriptions must be concrete and atomic
            - Do not include code in the plan. Code will be provided later via tool calls.
        """.trimIndent()
        val messages = listOf(
            LlmMessage("system", sys),
            LlmMessage("user", userGoal)
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
                if (desc.isNotBlank()) {
                    tasks.add(Task(id, desc))
                }
            }
        }
        val plan = Plan(goal, tasks)
        planFile.writeText(JSONObject().apply {
            put("goal", goal)
            put("tasks", JSONArray().apply {
                tasks.forEach { put(JSONObject().put("id", it.id).put("description", it.description)) }
            })
        }.toString(2))
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
            onStatus("Task ${'$'}{task.id}: ${'$'}{task.description}")
            val toolCall = requestSingleToolCall(plan.goal, task)
            if (toolCall == null) {
                onStatus("Task ${'$'}{task.id}: could not determine action")
                return
            }
            val ok = runCatching { executeToolCall(toolCall) }.getOrElse { e ->
                onStatus("Task ${'$'}{task.id} failed: ${'$'}{e.message}")
                false
            }
            if (ok) {
                markTaskDone(task.id)
                onStatus("Task ${'$'}{task.id}: done")
            } else {
                onStatus("Task ${'$'}{task.id}: failed")
                return
            }
        }
    }

    private data class ToolCall(
        val type: String,
        val args: JSONObject
    )

    private suspend fun requestSingleToolCall(goal: String, task: Task): ToolCall? = withContext(Dispatchers.IO) {
        val sys = """
            Return ONLY a single minified JSON object describing ONE tool call to complete the given task.
            Allowed schemas:
            {"type":"create_file","args":{"path": string}}
            {"type":"write_file","args":{"path": string, "content": string, "mode": "overwrite"|"append"}}
            {"type":"make_dir","args":{"path": string}}
            {"type":"run_shell","args":{"command": string}}
            Rules:
            - Use relative paths with respect to the current working directory unless absolute is required.
            - When writing source code, include full file content in "content".
            - Do not return markdown code fences. Return pure JSON on a single line.
            - Do not include explanations.
        """.trimIndent()
        val wd = workingDirProvider()
        val prompt = """
            Goal: ${goal}
            Working directory: ${wd}
            Current task id: ${task.id}
            Task: ${task.description}
            Produce one tool call JSON now.
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

    private fun executeToolCall(tc: ToolCall): Boolean {
        return when (tc.type) {
            "create_file" -> {
                val path = tc.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val f = resolvePath(path)
                ensureParentDirs(f)
                if (!f.exists()) f.createNewFile()
                f.exists()
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
                ok
            }
            "make_dir" -> {
                val path = tc.args.optString("path")
                require(path.isNotBlank()) { "path missing" }
                val d = resolvePath(path)
                d.mkdirs()
                d.exists() && d.isDirectory
            }
            "run_shell" -> {
                val command = tc.args.optString("command")
                require(command.isNotBlank()) { "command missing" }
                // Use internal busybox/sh if available in environment; here we execute via sh -c in app sandbox.
                // For broader commands, integrate Termux run if present.
                val wd = workingDirProvider()
                val proc = ProcessBuilder("sh", "-c", command)
                    .directory(File(wd))
                    .redirectErrorStream(true)
                    .start()
                val exit = proc.waitFor()
                exit == 0
            }
            else -> false
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
}