package com.rk.terminal.llm

import com.rk.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Minimal message structure compatible with chat UIs.
 */
data class LlmMessage(val role: String, val content: String)

interface LlmEngine {
    fun generate(messages: List<LlmMessage>): Flow<String>
}

/**
 * Simple echo engine fallback when no API is configured.
 */
object EchoEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val reply = buildString {
            appendLine("No AI API configured. Echoing your message:")
            appendLine(lastUser)
        }
        val chunks = reply.chunked(64)
        for (c in chunks) emit(c)
    }
}

object LlmProvider {
    fun current(): LlmEngine {
        val provider = Settings.api_provider.lowercase()
        val primaryKey = Settings.api_key
        val requiresKey = provider == "openai" || provider == "openai_compatible" || provider == "anthropic" || provider == "gemini" || provider == "fireworks"
        val hasAnyKey = when (provider) {
            "gemini" -> primaryKey.isNotBlank() || (Settings.api_key_rotation_enabled && Settings.getGeminiApiKeys().any { it.isNotBlank() })
            else -> primaryKey.isNotBlank()
        }
        if (requiresKey && !hasAnyKey) return EchoEngine
        return when (provider) {
            "openai", "openai_compatible" -> OpenAIEngine
            "fireworks" -> OpenAIEngine
            "anthropic" -> AnthropicEngine
            "gemini" -> GeminiEngine
            "ollama" -> OllamaEngine
            else -> OpenAIEngine // default to OpenAI-compatible
        }
    }
}