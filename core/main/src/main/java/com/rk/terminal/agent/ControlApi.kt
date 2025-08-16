package com.rk.terminal.agent

import com.rk.settings.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ControlApiClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun isEnabled(): Boolean = Settings.control_api_enabled && Settings.control_api_base_url.trim().isNotEmpty()

    private fun base(): String = Settings.control_api_base_url.trim().removeSuffix("/")

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        if (!isEnabled()) return null
        return runCatching {
            val url = base() + path
            val req = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string().orEmpty()
                if (txt.isBlank()) return null
                JSONObject(txt)
            }
        }.getOrNull()
    }

    fun recordInitialSetup(sessionId: String, goal: String, expectations: String, workspaceInfoJson: String? = null): Boolean {
        if (!isEnabled()) return false
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("goal", goal)
            put("expectations", expectations)
            if (!workspaceInfoJson.isNullOrBlank()) runCatching { put("workspace_snapshot", JSONObject(workspaceInfoJson)) }
        }
        return postJson("/init", payload) != null
    }

    fun requestPlan(sessionId: String, goal: String, expectations: String, workspaceInfoJson: String? = null): JSONObject? {
        if (!isEnabled()) return null
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("goal", goal)
            put("expectations", expectations)
            if (!workspaceInfoJson.isNullOrBlank()) runCatching { put("workspace_snapshot", JSONObject(workspaceInfoJson)) }
        }
        return postJson("/plan", payload)
    }

    fun syncFile(sessionId: String, path: String, content: String, isNew: Boolean): Boolean {
        if (!isEnabled()) return false
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("path", path)
            put("is_new", isNew)
            put("bytes", content.toByteArray().size)
            put("content", content)
        }
        return postJson("/codebase/sync", payload) != null
    }

    fun fetchFile(sessionId: String, path: String): String? {
        if (!isEnabled()) return null
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("path", path)
        }
        val res = postJson("/codebase/fetch", payload) ?: return null
        return res.optString("content").takeIf { it.isNotBlank() }
    }

    fun recordTaskDetection(sessionId: String, taskId: String, kind: String, description: String, category: String?): Boolean {
        if (!isEnabled()) return false
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("task_id", taskId)
            put("kind", kind)
            put("description", description)
            if (!category.isNullOrBlank()) put("category", category)
        }
        return postJson("/task/detect", payload) != null
    }
}