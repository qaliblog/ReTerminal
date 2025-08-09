package com.rk.terminal.llm

import android.os.Environment
import com.rk.settings.Settings
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
        // Always refresh selection before generating
        ModelManager.refreshFromSettings()
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val root = ModelLocator.modelRoot()
        val selected = ModelManager.activeModel()
        val reply = buildString {
            appendLine("(offline stub) You said:")
            appendLine(lastUser)
            appendLine()
            appendLine("Assets root: ${root?.absolutePath ?: "<not found>"}")
            appendLine("Selected model: ${selected?.absolutePath ?: "<none>"}")
        }
        val chunks = reply.chunked(32)
        for (c in chunks) emit(c)
    }
}

object LlmProvider {
    fun current(): LlmEngine {
        ModelManager.refreshFromSettings()
        val active = ModelManager.activeModel()
        return if (active != null) MlcEngine(active) else EchoEngine
    }
}

object ModelLocator {
    fun modelRoot(): File? {
        val root = Environment.getExternalStorageDirectory()
        val candidate = File(root, "reterminalAssets")
        return candidate.takeIf { it.exists() && it.isDirectory }
    }

    fun customFolders(): List<File> {
        val csv = Settings.model_folders_csv
        if (csv.isBlank()) return emptyList()
        return csv.split(',').mapNotNull { p ->
            val f = File(p.trim())
            f.takeIf { it.exists() && it.isDirectory }
        }
    }

    fun selectedModelDir(): File? {
        val selected = Settings.selected_model_folder.trim()
        if (selected.isNotBlank()) {
            val f = File(selected)
            if (f.exists() && f.isDirectory) return f
        }
        modelRoot()?.let { root ->
            root.listFiles()?.firstOrNull { it.isDirectory }?.let { return it }
        }
        return customFolders().firstOrNull()
    }
}