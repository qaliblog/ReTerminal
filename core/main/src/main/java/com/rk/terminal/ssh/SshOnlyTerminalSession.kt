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
        // Create terminal session with a command that never exits
        terminalSession = TerminalSession(
            "/system/bin/sleep", // Sleep command that runs for a long time
            "/",
            arrayOf("999999"), // Sleep for ~11 days (effectively infinite)
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
                // Wait for sleep command to start
                delay(500)
                
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
                        
                        // Set up input interception by replacing process streams
                        setupInputRedirection()
                        
                        // Send a test command to verify SSH is working
                        scope.launch {
                            delay(1000)
                            safeSshTerminal?.writeToSsh("echo 'SSH input redirection active'\n")
                        }
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
            // Replace the sleep process's stdin with SSH redirection
            val sessionClass = terminalSession.javaClass
            val processField = sessionClass.getDeclaredField("mProcess")
            processField.isAccessible = true
            val process = processField.get(terminalSession)
            
            if (process != null) {
                val processClass = process.javaClass
                val outputStreamField = processClass.getDeclaredField("mOutputStream")
                outputStreamField.isAccessible = true
                
                // Create SSH redirect stream
                val sshRedirectStream = object : OutputStream() {
                    override fun write(b: Int) {
                        safeSshTerminal?.writeToSsh(byteArrayOf(b.toByte()), 0, 1)
                        Log.d(TAG, "SSH redirect: ${b.toChar()}")
                    }
                    
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        safeSshTerminal?.writeToSsh(b, off, len)
                        val text = String(b, off, len)
                        Log.d(TAG, "SSH redirect: $text")
                    }
                    
                    override fun flush() {}
                    override fun close() {}
                }
                
                outputStreamField.set(process, sshRedirectStream)
                Log.d(TAG, "SSH input redirection set up successfully")
            }
            
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