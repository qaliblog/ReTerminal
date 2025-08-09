package com.rk.terminal.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
            emit("Place libtvm_runtime.so or libtvm4j_runtime_packed.so there.\n")
            return@flow
        }
        val backend = RuntimeLoader.backend ?: "cpu"
        val module = RuntimeLoader.moduleSo
        emit("[MLC] Backend: $backend\n")
        if (module == null) {
            emit("[MLC] Compiled module (.so) not found in model folder; running placeholder.\n\n")
        }
        val user = messages.lastOrNull { it.role == "user" }?.content ?: ""
        emit("You asked:\n$user\n\n")
        val reply = "(runtime linked: $backend) pending API wiring; replace this with real token streaming"
        for (chunk in reply.chunked(32)) {
            emit(chunk)
            delay(10)
        }
    }
}