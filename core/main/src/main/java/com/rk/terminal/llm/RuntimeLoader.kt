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

        // Detect compiled module in modelDir
        val modules = modelDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") } ?: emptyList()
        val vulkanMod = modules.firstOrNull { it.name.contains("vulkan", ignoreCase = true) }
        val cpuMod = modules.firstOrNull { it.name.contains("cpu", ignoreCase = true) || it.name.startsWith("model", true) }
        moduleSo = vulkanMod ?: cpuMod
        backend = when {
            vulkanMod != null -> "vulkan"
            cpuMod != null -> "cpu"
            else -> null
        }

        loaded = tvmLoaded
        return tvmLoaded
    }
}