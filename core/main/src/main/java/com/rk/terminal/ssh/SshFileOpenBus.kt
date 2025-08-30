package com.rk.terminal.ssh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import java.io.File

// Bus for SSH remote file opening
object SshFileOpenBus {
    private val selectedRemoteFileState = mutableStateOf<RemoteFile?>(null)
    private val sshFileManagerState = mutableStateOf<SshFileManager?>(null)
    
    fun open(remoteFile: RemoteFile, sshFileManager: SshFileManager) { 
        selectedRemoteFileState.value = remoteFile
        sshFileManagerState.value = sshFileManager
    }
    
    @Composable
    fun current(): Pair<RemoteFile?, SshFileManager?> = 
        Pair(selectedRemoteFileState.value, sshFileManagerState.value)
    
    fun clear() {
        selectedRemoteFileState.value = null
        sshFileManagerState.value = null
    }
}