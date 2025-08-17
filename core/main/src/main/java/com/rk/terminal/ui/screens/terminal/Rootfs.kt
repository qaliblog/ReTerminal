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
        return reTerminal.exists() && reTerminal.child("proot").exists() && reTerminal.child("libtalloc.so.2").exists() && reTerminal.child("alpine.tar.gz").exists()
    }
    
    fun forceRedownload() {
        // Delete existing files to force re-download
        reTerminal.listFiles()?.forEach { file ->
            if (file.name in listOf("proot", "libtalloc.so.2", "alpine.tar.gz")) {
                file.delete()
            }
        }
        // Also clean up the Alpine directory
        File(reTerminal, "local/alpine").deleteRecursively()
        resetDownloadState()
    }
    
    fun resetDownloadState() {
        isDownloaded.value = isFilesDownloaded()
    }
}