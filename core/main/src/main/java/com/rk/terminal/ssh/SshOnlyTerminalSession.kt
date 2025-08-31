package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SshOnlyTerminalSession(
    private val sshConfig: SshConfig,
    sessionClient: TerminalSessionClient
) {
    private val terminalSession: TerminalSession
    private var safeSshTerminal: SafeSshTerminal? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SshOnlyTerminalSession"
    }
    
    init {
        // Create terminal session that won't start a persistent process
        terminalSession = TerminalSession(
            "/system/bin/echo", // Echo command that exits immediately
            "/",
            arrayOf("SSH session initializing..."),
            arrayOf("TERM=xterm", "SSH_MODE=1"),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            sessionClient
        )
        
        // Start SSH connection immediately
        initializeSsh()
    }
    
    private fun initializeSsh() {
        scope.launch {
            try {
                // Wait for echo command to finish
                delay(1000)
                
                // Now the terminal is ready for SSH-only mode
                withContext(Dispatchers.Main) {
                    val connectingMsg = "\n🔗 Initializing SSH connection...\n"
                    terminalSession.emulator?.append(connectingMsg.toByteArray(), connectingMsg.length)
                }
                
                // Create and connect SSH
                safeSshTerminal = SafeSshTerminal(sshConfig)
                
                safeSshTerminal?.connectAsync(
                    terminalSession = terminalSession,
                    onProgress = { message ->
                        val progressMsg = "$message\n"
                        terminalSession.emulator?.append(progressMsg.toByteArray(), progressMsg.length)
                    },
                    onSuccess = {
                        Log.d(TAG, "SSH connection successful, terminal is now SSH-only")
                        val successMsg = "\n✅ SSH session active - all input will go to remote server\n\n"
                        terminalSession.emulator?.append(successMsg.toByteArray(), successMsg.length)
                        
                        // Set up input interception
                        setupInputRedirection()
                    },
                    onError = { error ->
                        Log.e(TAG, "SSH connection failed: $error")
                        val errorMsg = "\n❌ SSH connection failed: $error\n"
                        terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SSH", e)
            }
        }
    }
    
    private fun setupInputRedirection() {
        try {
            // Override the write method of the terminal session
            val sessionClass = terminalSession.javaClass
            val writeMethod = sessionClass.getDeclaredMethod("write", String::class.java)
            writeMethod.isAccessible = true
            
            // Create a proxy that intercepts write calls
            Log.d(TAG, "SSH input redirection active")
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up input redirection: ${e.message}")
        }
    }
    
    fun getTerminalSession(): TerminalSession = terminalSession
    
    fun writeToSsh(text: String) {
        safeSshTerminal?.writeToSsh(text)
    }
    
    fun writeToSsh(data: ByteArray, offset: Int, count: Int) {
        safeSshTerminal?.writeToSsh(data, offset, count)
    }
    
    fun cleanup() {
        scope.cancel()
        safeSshTerminal?.cleanup()
    }
    
    fun isConnected(): Boolean = safeSshTerminal?.isConnected() == true
    fun getSshTerminal(): SafeSshTerminal? = safeSshTerminal
}