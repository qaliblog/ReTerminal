package com.rk.terminal.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class MlcEngine(private val modelDir: File) : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        if (!ModelManager.isValidModelDir(modelDir)) {
            emit("[MLC] Invalid model folder: ${modelDir.absolutePath}\n")
            return@flow
        }
        val ok = RuntimeLoader.ensureLoaded(modelDir)
        if (!ok) {
            emit("[MLC] Missing runtime libs under ${modelDir.absolutePath}/libs/<abi>\n")
            emit("Place libtvm_runtime.so and libmlc_llm.so there.\n")
            return@flow
        }
        // Placeholder for actual MLC runtime binding
        emit("[MLC] Vulkan GPU enabled (if available)\n")
        val sysPrompt = ""
        val user = messages.lastOrNull { it.role == "user" }?.content ?: ""
        emit("You asked:\n$user\n\n")
        val reply = "(runtime linked) pending API wiring; replace this with real token streaming"
        for (chunk in reply.chunked(32)) {
            emit(chunk)
            delay(10)
        }
    }
}