package com.rk.terminal.llm

import android.os.Build
import com.blankj.utilcode.util.Utils
import java.io.File

object RuntimeLoader {
    private var loaded = false
    var backend: String? = null
        private set
    var moduleSo: File? = null
        private set

    fun isLoaded(): Boolean = loaded

    fun ensureLoaded(modelDir: File): Boolean {
        if (loaded) return true
        val ctx = Utils.getApp()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val srcDir = File(modelDir, "libs/$abi")
        if (!srcDir.exists() || !srcDir.isDirectory) {
            return false
        }
        val destDir = File(ctx.filesDir, "rt/$abi").apply { mkdirs() }

        val tvmCandidates = listOf("libtvm_runtime.so", "libtvm4j_runtime_packed.so")
        val mlcCandidates = listOf("libmlc_llm.so", "libmlc_llm_vulkan.so")

        var tvmLoaded = false
        for (name in tvmCandidates) {
            val src = File(srcDir, name)
            if (src.exists()) {
                val dst = File(destDir, name)
                if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
                try {
                    System.load(dst.absolutePath)
                    tvmLoaded = true
                    break
                } catch (_: UnsatisfiedLinkError) {}
            }
        }
        // Best-effort load MLC helper if present
        for (name in mlcCandidates) {
            val src = File(srcDir, name)
            if (src.exists()) {
                val dst = File(destDir, name)
                if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
                runCatching { System.load(dst.absolutePath) }
            }
        }

        // Detect compiled module in modelDir (search recursively, exclude libs/ and lib*.so)
        val modules = modelDir
            .walkTopDown()
            .filter { f ->
                f.isFile && f.name.endsWith(".so") &&
                !f.name.startsWith("lib") &&
                !f.absolutePath.contains("${File.separator}libs${File.separator}")
            }
            .toList()

        val vulkanMod = modules.firstOrNull { it.name.contains("vulkan", ignoreCase = true) }
        val cpuMod = modules.firstOrNull { it.name.contains("cpu", ignoreCase = true) }
        val modelNamedMod = modules.firstOrNull {
            it.name.startsWith("model", ignoreCase = true) ||
            it.name.startsWith("mod", ignoreCase = true) ||
            it.name.contains("module", ignoreCase = true)
        }
        val anyMod = modules.firstOrNull()

        moduleSo = vulkanMod ?: cpuMod ?: modelNamedMod ?: anyMod
        backend = when {
            vulkanMod != null -> "vulkan"
            cpuMod != null -> "cpu"
            // Heuristic: if we found a module but no hint, assume CPU
            moduleSo != null -> "cpu"
            else -> null
        }

        loaded = tvmLoaded
        return tvmLoaded
    }
}