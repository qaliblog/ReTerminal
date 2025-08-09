package com.rk.terminal.llm

import android.os.Environment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

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
        val reply = "(offline stub) You said: \n$lastUser\n\nModel dir: ${ModelLocator.modelRoot()?.absolutePath ?: "<not found>"}"
        // Stream in chunks to exercise UI
        val chunks = reply.chunked(32)
        for (c in chunks) emit(c)
    }
}

object LlmProvider {
    // Swap this with a real MLC-based engine implementation
    var engine: LlmEngine = EchoEngine
}

object ModelLocator {
    // Expected: /sdcard/reterminalAssets/<model_folder>
    fun modelRoot(): File? {
        val root = Environment.getExternalStorageDirectory()
        val candidate = File(root, "reterminalAssets")
        return candidate.takeIf { it.exists() && it.isDirectory }
    }
}