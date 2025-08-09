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
        val libs = listOf("libtvm_runtime.so", "libmlc_llm.so")
        libs.forEach { name ->
            val src = File(srcDir, name)
            if (src.exists()) {
                val dst = File(destDir, name)
                if (!dst.exists() || dst.length() != src.length()) {
                    src.copyTo(dst, overwrite = true)
                }
                try {
                    System.load(dst.absolutePath)
                } catch (e: UnsatisfiedLinkError) {
                    e.printStackTrace()
                    return false
                }
            }
        }
        loaded = true
        return true
    }
}