package com.rk.terminal.ssh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

// Bus for Alpine SSH file opening
object AlpineSshFileOpenBus {
    private val selectedFileState = mutableStateOf<String?>(null)
    private val sshConfigState = mutableStateOf<SshConfig?>(null)
    
    fun open(filePath: String, sshConfig: SshConfig) { 
        selectedFileState.value = filePath
        sshConfigState.value = sshConfig
    }
    
    @Composable
    fun current(): Pair<String?, SshConfig?> = 
        Pair(selectedFileState.value, sshConfigState.value)
    
    fun clear() {
        selectedFileState.value = null
        sshConfigState.value = null
    }
}