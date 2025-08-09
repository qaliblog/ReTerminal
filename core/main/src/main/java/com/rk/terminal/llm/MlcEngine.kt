package com.rk.terminal.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class MlcEngine(private val modelDir: File) : LlmEngine {
    override fun generate(messages: List<LlmMessage>): Flow<String> = flow {
        val prompt = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val modelName = modelDir.name
        if (!ModelManager.isValidModelDir(modelDir)) {
            emit("[MLC] Invalid model folder: ${modelDir.absolutePath}\n")
            return@flow
        }
        // Placeholder until MLC runtime binding is added
        emit("[MLC] Loaded model: $modelName\n")
        emit("[MLC] Vulkan GPU: pending runtime integration\n\n")
        emit("You asked: \n")
        // Simulate token streaming
        val reply = "(placeholder) MLC engine will respond once runtime is linked."
        for (chunk in reply.chunked(24)) {
            emit(chunk)
            delay(20)
        }
    }
}