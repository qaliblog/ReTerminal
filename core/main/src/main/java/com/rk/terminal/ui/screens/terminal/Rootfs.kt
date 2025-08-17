package com.rk.terminal.ui.screens.terminal

import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.terminal.App
import java.io.File

object Rootfs {
    val reTerminal = application!!.filesDir

    init {
        if (reTerminal.exists().not()){
            reTerminal.mkdirs()
        }
    }

    var isDownloaded = mutableStateOf(isFilesDownloaded())
    
    fun isFilesDownloaded(): Boolean{
        return try {
            reTerminal.exists() && 
            reTerminal.child("proot").exists() && 
            reTerminal.child("libtalloc.so.2").exists() && 
            reTerminal.child("alpine.tar.gz").exists()
        } catch (e: Exception) {
            // Log error and return false to trigger download
            android.util.Log.e("Rootfs", "Error checking files: ${e.message}")
            false
        }
    }
    
    fun resetDownloadState() {
        isDownloaded.value = isFilesDownloaded()
    }
    
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== ReTerminal Diagnostic Information ===")
            appendLine("ReTerminal directory: ${reTerminal.absolutePath}")
            appendLine("Directory exists: ${reTerminal.exists()}")
            
            val files = listOf("proot", "libtalloc.so.2", "alpine.tar.gz")
            files.forEach { file ->
                val fileObj = reTerminal.child(file)
                appendLine("$file: ${if (fileObj.exists()) "EXISTS" else "MISSING"}")
                if (fileObj.exists()) {
                    appendLine("  Size: ${fileObj.length()} bytes")
                }
            }
            
            val alpineDir = File(reTerminal, "local/alpine")
            appendLine("Alpine rootfs directory: ${alpineDir.absolutePath}")
            appendLine("Alpine rootfs exists: ${alpineDir.exists()}")
            
            if (alpineDir.exists()) {
                val contents = alpineDir.listFiles()?.map { it.name } ?: emptyList()
                appendLine("Alpine rootfs contents: ${contents.joinToString(", ")}")
                
                // Check for essential Alpine directories
                val essentialDirs = listOf("bin", "sbin", "usr", "etc")
                essentialDirs.forEach { dir ->
                    val dirObj = File(alpineDir, dir)
                    appendLine("  $dir/: ${if (dirObj.exists()) "EXISTS" else "MISSING"}")
                }
            }
            
            appendLine("=== End Diagnostic ===")
        }
    }
    
    fun forceRedownload() {
        // Delete existing files to force re-download
        reTerminal.listFiles()?.forEach { file ->
            if (file.name in listOf("proot", "libtalloc.so.2", "alpine.tar.gz")) {
                file.delete()
            }
        }
        File(reTerminal, "local/alpine").deleteRecursively()
        resetDownloadState()
    }
}