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
}