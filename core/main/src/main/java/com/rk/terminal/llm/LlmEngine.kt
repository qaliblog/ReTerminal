package com.rk.terminal.llm

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
 * Temporary stub engine that just echoes the last user message.
 */
object EchoEngine : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val reply = "(offline stub) You said: \n$lastUser"
        // Stream in chunks to exercise UI
        val chunks = reply.chunked(32)
        for (c in chunks) emit(c)
    }
}

object LlmProvider {
    // Swap this with a real MLC-based engine implementation
    var engine: LlmEngine = EchoEngine
}