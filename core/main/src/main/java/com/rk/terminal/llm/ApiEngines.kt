package com.rk.terminal.llm

import com.rk.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
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
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
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
            val provider = Settings.api_provider.lowercase()
            val defaultBase = if (provider == "fireworks") "https://api.fireworks.ai" else "https://api.openai.com"
            val base = Settings.api_base_url.trim().ifBlank { defaultBase }.removeSuffix("/")
            val path = if (provider == "fireworks") "/inference/v1/chat/completions" else "/v1/chat/completions"
            val url = "$base$path"
            val model = Settings.api_model.ifBlank { if (provider == "fireworks") "accounts/fireworks/models/llama-v3p1-8b-instruct" else "gpt-4o-mini" }
            val forceJson = messages.any { it.content.contains("Return ONLY") && it.content.contains("JSON", ignoreCase = true) }
            val tempOverride = Settings.ai_temperature_str.trim().toDoubleOrNull()
            val maxTokens = Settings.ai_max_tokens.coerceAtLeast(64)
            val bodyJson = JSONObject().apply {
                put("model", model)
                if (provider != "fireworks") put("stream", true)
                put("messages", buildChatHistoryArray(messages))
                put("max_tokens", maxTokens)
                if (provider == "fireworks") {
                    put("top_p", 1)
                    put("top_k", 40)
                    put("presence_penalty", 0)
                    put("frequency_penalty", 0)
                    if (tempOverride == null && !forceJson) put("temperature", 0.6)
                }
                if (tempOverride != null) put("temperature", tempOverride)
                if (forceJson) {
                    put("response_format", JSONObject().put("type", "json_object"))
                    put("temperature", 0)
                }
            }
            val reqBody: RequestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val builder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${Settings.api_key}")
                .addHeader("Content-Type", "application/json")
            if (provider == "fireworks") builder.addHeader("Accept", "application/json")
            val req = builder
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
                if (provider == "fireworks") {
                    val txt = rb.string().orEmpty()
                    val obj = runCatching { JSONObject(txt) }.getOrNull()
                    val choices = obj?.optJSONArray("choices") ?: JSONArray()
                    val sb = StringBuilder()
                    for (i in 0 until choices.length()) {
                        val choice = choices.getJSONObject(i)
                        val msgContent = choice.optJSONObject("message")?.optString("content")
                        val textContent = choice.optString("text")
                        val fragment = when {
                            !msgContent.isNullOrBlank() -> msgContent
                            textContent.isNotBlank() -> textContent
                            else -> ""
                        }
                        if (fragment.isNotEmpty()) sb.append(fragment)
                    }
                    val out = sb.toString()
                    if (out.isEmpty()) emit("[OpenAI] Empty response\n") else out.chunked(64).forEach { emit(it) }
                } else {
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
            }
        } catch (e: java.io.IOException) {
            throw e
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
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            emit("[Anthropic] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}

object GeminiEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        try {
            val model = Settings.api_model.ifBlank { "gemini-1.5-flash" }
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

            val rotationEnabled = Settings.api_key_rotation_enabled
            val keys = Settings.getGeminiApiKeys().filter { it.isNotBlank() }
            val useRotation = rotationEnabled && keys.isNotEmpty()

            if (useRotation) {
                var index = 0
                var completed = false
                while (!completed) {
                    var exhaustedThisCycle = 0
                    for (i in keys.indices) {
                        val currentKey = keys[index]
                        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${currentKey}"
                        val req = Request.Builder()
                            .url(url)
                            .addHeader("content-type", "application/json")
                            .post(contents.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        ApiHttp.client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                val errTxt = resp.body?.string().orEmpty()
                                var isQuota = resp.code == 429
                                if (!errTxt.isBlank()) {
                                    val errorObj = runCatching { JSONObject(errTxt) }.getOrNull()?.optJSONObject("error")
                                    val statusStr = errorObj?.optString("status").orEmpty()
                                    if (statusStr == "RESOURCE_EXHAUSTED") isQuota = true
                                    val detailsArr = errorObj?.optJSONArray("details") ?: JSONArray()
                                    for (j in 0 until detailsArr.length()) {
                                        val det = detailsArr.optJSONObject(j)
                                        val typeStr = det?.optString("@type").orEmpty()
                                        if (typeStr.endsWith("google.rpc.QuotaFailure") || typeStr.endsWith("google.rpc.RetryInfo")) {
                                            isQuota = true
                                            break
                                        }
                                    }
                                    if (!isQuota && (errTxt.contains("GenerateContentInputTokensPerModelPerMinute-FreeTier", true) || errTxt.contains("generate_content_free_tier_input_token_count", true))) isQuota = true
                                }
                                if (isQuota) {
                                    exhaustedThisCycle += 1
                                    index = (index + 1) % keys.size
                                    return@use
                                } else {
                                    emit("[Gemini] HTTP ${resp.code}: ${resp.message}\n")
                                    if (errTxt.isNotBlank()) emit(errTxt.take(2000))
                                    completed = true
                                    return@use
                                }
                            }
                            val txt = resp.body?.string().orEmpty()
                            val obj = runCatching { JSONObject(txt) }.getOrNull()
                            val cand = obj?.optJSONArray("candidates")?.optJSONObject(0)
                            val parts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                            val sb = StringBuilder()
                            for (j in 0 until parts.length()) {
                                val part = parts.getJSONObject(j)
                                val fragment = part.optString("text")
                                if (fragment.isNotEmpty()) sb.append(fragment)
                            }
                            val out = sb.toString()
                            if (out.isEmpty()) emit("[Gemini] Empty response\n") else out.chunked(64).forEach { emit(it) }
                            completed = true
                        }
                        if (completed) break
                    }
                    if (!completed && exhaustedThisCycle >= keys.size) {
                        emit("All API keys exceeded RPM. Retrying again in 5 seconds...\n")
                        delay(5000)
                    }
                }
            } else {
                var completed = false
                while (!completed) {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${Settings.api_key}"
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("content-type", "application/json")
                        .post(contents.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    var shouldRetry = false
                    ApiHttp.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errTxt = resp.body?.string().orEmpty()
                            var isQuota = resp.code == 429
                            if (!errTxt.isBlank()) {
                                val errorObj = runCatching { JSONObject(errTxt) }.getOrNull()?.optJSONObject("error")
                                val statusStr = errorObj?.optString("status").orEmpty()
                                if (statusStr == "RESOURCE_EXHAUSTED") isQuota = true
                                val detailsArr = errorObj?.optJSONArray("details") ?: JSONArray()
                                for (i in 0 until detailsArr.length()) {
                                    val det = detailsArr.optJSONObject(i)
                                    val typeStr = det?.optString("@type").orEmpty()
                                    if (typeStr.endsWith("google.rpc.QuotaFailure") || typeStr.endsWith("google.rpc.RetryInfo")) {
                                        isQuota = true
                                        break
                                    }
                                }
                                if (!isQuota && (errTxt.contains("GenerateContentInputTokensPerModelPerMinute-FreeTier", true) || errTxt.contains("generate_content_free_tier_input_token_count", true))) isQuota = true
                            }
                            if (isQuota) {
                                emit("RPM exceeded, retrying to access the API again in 5 seconds...\n")
                                shouldRetry = true
                                return@use
                            } else {
                                emit("[Gemini] HTTP ${resp.code}: ${resp.message}\n")
                                if (errTxt.isNotBlank()) emit(errTxt.take(2000))
                                completed = true
                                return@use
                            }
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
                        completed = true
                    }
                    if (!completed && shouldRetry) {
                        delay(5000)
                    } else if (completed) {
                        break
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            emit("[Gemini] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}

object OllamaEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        try {
            val base = Settings.api_base_url.trim().ifBlank { "http://127.0.0.1:11434" }.removeSuffix("/")
            val url = "$base/api/chat"
            val model = Settings.api_model.ifBlank { "llama3.1" }
            val forceJson = messages.any { it.content.contains("Return ONLY") && it.content.contains("JSON", ignoreCase = true) }
            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", buildChatHistoryArray(messages))
                if (forceJson) put("format", "json_object")
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            ApiHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit("[Ollama] HTTP ${resp.code}: ${resp.message}\n")
                    val err = resp.body?.string()
                    if (!err.isNullOrBlank()) emit(err.take(2000))
                    return@use
                }
                val rb = resp.body ?: return@use
                val source = rb.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    var shouldBreak = false
                    try {
                        val obj = JSONObject(line)
                        val done = obj.optBoolean("done", false)
                        val msgObj = obj.optJSONObject("message")
                        val content = msgObj?.optString("content").orEmpty()
                        if (content.isNotEmpty()) emit(content)
                        if (done) shouldBreak = true
                    } catch (_: Exception) {
                    }
                    if (shouldBreak) break
                }
            }
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            emit("[Ollama] ${e::class.simpleName}: ${e.message}\n")
        }
    }.flowOn(Dispatchers.IO)
}