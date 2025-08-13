package com.rk.terminal.llm

import com.rk.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private object ApiHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

private fun buildChatHistoryArray(messages: List<LlmMessage>): JSONArray {
    val arr = JSONArray()
    messages.forEach { m ->
        val o = JSONObject()
        o.put("role", m.role)
        o.put("content", m.content)
        arr.put(o)
    }
    return arr
}

object OpenAIEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        try {
            val base = Settings.api_base_url.trim().ifBlank { "https://api.openai.com" }.removeSuffix("/")
            val url = "$base/v1/chat/completions"
            val model = Settings.api_model.ifBlank { "gpt-4o-mini" }
            val forceJson = messages.any { it.content.contains("Return ONLY") && it.content.contains("JSON", ignoreCase = true) }
            val tempOverride = Settings.ai_temperature_str.trim().toDoubleOrNull()
            val maxTokens = Settings.ai_max_tokens.coerceAtLeast(64)
            val bodyJson = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", buildChatHistoryArray(messages))
                put("max_tokens", maxTokens)
                if (tempOverride != null) put("temperature", tempOverride)
                if (forceJson) {
                    put("response_format", JSONObject().put("type", "json_object"))
                    put("temperature", 0)
                }
            }
            val reqBody: RequestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${Settings.api_key}")
                .addHeader("Content-Type", "application/json")
                .post(reqBody)
                .build()

            ApiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit("[OpenAI] HTTP ${resp.code}: ${resp.message}\n")
                    val err = resp.body?.string()
                    if (!err.isNullOrBlank()) emit(err.take(2000))
                    return@use
                }
                val rb = resp.body
                if (rb == null) {
                    emit("[OpenAI] Empty body\n")
                    return@use
                }
                val source: BufferedSource = rb.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    runCatching {
                        val obj = JSONObject(payload)
                        val choices = obj.optJSONArray("choices") ?: JSONArray()
                        for (i in 0 until choices.length()) {
                            val delta = choices.getJSONObject(i).optJSONObject("delta")
                            val content = delta?.optString("content")
                            if (!content.isNullOrEmpty()) emit(content)
                        }
                    }.onFailure {
                        runCatching {
                            val obj = JSONObject(payload)
                            val choices = obj.optJSONArray("choices")
                            val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                            if (!content.isNullOrEmpty()) emit(content)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("[OpenAI] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}

object AnthropicEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        try {
            val url = "https://api.anthropic.com/v1/messages"
            val model = Settings.api_model.ifBlank { "claude-3-haiku-20240307" }
            val sys = messages.firstOrNull { it.role == "system" }?.content
            val conv = JSONArray()
            messages.filter { it.role == "user" || it.role == "assistant" }.forEach { m ->
                val item = JSONObject()
                item.put("role", if (m.role == "assistant") "assistant" else "user")
                item.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.content)))
                conv.put(item)
            }
            val forceJson = messages.any { it.content.contains("Return ONLY") && it.content.contains("JSON", ignoreCase = true) }
            val tempOverride = Settings.ai_temperature_str.trim().toDoubleOrNull()
            val maxTokens = Settings.ai_max_tokens.coerceAtLeast(64)
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("messages", conv)
                if (!sys.isNullOrBlank()) put("system", sys)
                if (tempOverride != null) put("temperature", tempOverride)
                if (forceJson) put("temperature", 0)
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("x-api-key", Settings.api_key)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            ApiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit("[Anthropic] HTTP ${resp.code}: ${resp.message}\n")
                    val err = resp.body?.string()
                    if (!err.isNullOrBlank()) emit(err.take(2000))
                    return@use
                }
                val txt = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(txt) }.getOrNull()
                val contentArr = obj?.optJSONArray("content") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val part = contentArr.getJSONObject(i)
                    if (part.optString("type") == "text") {
                        val fragment = part.optString("text")
                        if (fragment.isNotEmpty()) sb.append(fragment)
                    }
                }
                val out = sb.toString()
                if (out.isEmpty()) emit("[Anthropic] Empty response\n") else out.chunked(64).forEach { emit(it) }
            }
        } catch (e: Exception) {
            emit("[Anthropic] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}

object GeminiEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        try {
            val model = Settings.api_model.ifBlank { "gemini-1.5-flash" }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${Settings.api_key}"
            val userText = messages.filter { it.role == "user" }.joinToString("\n\n") { it.content }
            val sys = messages.firstOrNull { it.role == "system" }?.content
            val forceJson = messages.any { it.content.contains("Return ONLY") && it.content.contains("JSON", ignoreCase = true) }
            val tempOverride = Settings.ai_temperature_str.trim().toDoubleOrNull()
            val contents = JSONObject().apply {
                put("generationConfig", JSONObject().apply {
                    if (forceJson) put("temperature", 0)
                    else if (tempOverride != null) put("temperature", tempOverride)
                })
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().apply {
                        if (!sys.isNullOrBlank()) put(JSONObject().put("text", sys))
                        put(JSONObject().put("text", userText))
                    })
                ))
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json")
                .post(contents.toString().toRequestBody("application/json".toMediaType()))
                .build()

            ApiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit("[Gemini] HTTP ${resp.code}: ${resp.message}\n")
                    val err = resp.body?.string()
                    if (!err.isNullOrBlank()) emit(err.take(2000))
                    return@use
                }
                val txt = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(txt) }.getOrNull()
                val cand = obj?.optJSONArray("candidates")?.optJSONObject(0)
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val fragment = part.optString("text")
                    if (fragment.isNotEmpty()) sb.append(fragment)
                }
                val out = sb.toString()
                if (out.isEmpty()) emit("[Gemini] Empty response\n") else out.chunked(64).forEach { emit(it) }
            }
        } catch (e: Exception) {
            emit("[Gemini] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}