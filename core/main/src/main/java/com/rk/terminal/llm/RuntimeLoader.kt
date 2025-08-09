package com.rk.terminal.llm

import android.os.Build
import com.blankj.utilcode.util.Utils
import java.io.File

object RuntimeLoader {
    private var loaded = false

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

        // Accept common filenames
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
        // MLC library optional in some bundles; try best-effort
        for (name in mlcCandidates) {
            val src = File(srcDir, name)
            if (src.exists()) {
                val dst = File(destDir, name)
                if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
                runCatching { System.load(dst.absolutePath) }
            }
        }

        loaded = tvmLoaded
        return tvmLoaded
    }
}