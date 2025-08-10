package com.rk.terminal.llm

import com.rk.settings.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object ModelManager {
    private val activeModelRef = AtomicReference<File?>(null)

    fun activeModel(): File? = activeModelRef.get()

    fun setSelectedModel(path: String) {
        Settings.selected_model_folder = path
        val f = File(path)
        if (isValidModelDir(f)) {
            activeModelRef.set(f)
        } else {
            // Keep but not active if invalid
            activeModelRef.set(null)
        }
    }

    fun refreshFromSettings() {
        val selected = Settings.selected_model_folder.trim()
        if (selected.isNotEmpty()) {
            val f = File(selected)
            if (isValidModelDir(f)) {
                activeModelRef.set(f)
                return
            }
        }
        // fallback: first under default root
        ModelLocator.modelRoot()?.listFiles()?.firstOrNull { isValidModelDir(it) }?.let {
            activeModelRef.set(it)
            return
        }
        // fallback: first custom folder
        ModelLocator.customFolders().firstOrNull { isValidModelDir(it) }?.let {
            activeModelRef.set(it)
            return
        }
        activeModelRef.set(null)
    }

    fun isValidModelDir(dir: File?): Boolean {
        if (dir == null || !dir.exists() || !dir.isDirectory) return false
        val cfg = File(dir, "mlc-chat-config.json")
        if (!cfg.exists()) return false
        // at least one params shard
        return dir.listFiles()?.any { it.name.startsWith("params_shard_") && it.name.endsWith(".bin") } == true
    }

    suspend fun downloadFromHuggingFace(repoId: String, targetRoot: File, logger: (String) -> Unit = {}): File? {
        val modelFolderName = repoId.substringAfterLast('/') + "-MLC"
        val outDir = File(targetRoot, modelFolderName)
        val ok = HfDownloader.downloadMlcWeights(repoId, outDir, onLog = logger)
        if (!ok) return null
        if (isValidModelDir(outDir)) {
            Settings.selected_model_folder = outDir.absolutePath
            activeModelRef.set(outDir)
            return outDir
        }
        return null
    }
}