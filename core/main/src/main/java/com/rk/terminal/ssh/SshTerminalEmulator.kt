package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

class SshTerminalEmulator(
    private val sshConfig: SshConfig,
    private val sessionClient: TerminalSessionClient
) {
    private var sshSession: SshSession? = null
    private var terminalSession: TerminalSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // SSH streams
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    
    companion object {
        private const val TAG = "SshTerminalEmulator"
        private const val BUFFER_SIZE = 8192
    }
    
    suspend fun createTerminalSession(): TerminalSession? = withContext(Dispatchers.IO) {
        try {
            // Initialize SSH connection
            sshSession = SshSession(sshConfig)
            val connected = sshSession!!.connect()
            
            if (!connected) {
                Log.e(TAG, "Failed to establish SSH connection")
                return@withContext null
            }
            
            // Open shell channel
            val (inputStream, outputStream) = sshSession!!.openShellChannel()
            
            if (inputStream == null || outputStream == null) {
                Log.e(TAG, "Failed to open SSH shell channel")
                sshSession?.disconnect()
                return@withContext null
            }
            
            sshInputStream = inputStream
            sshOutputStream = outputStream
            
            // Create a custom terminal session that bridges to SSH
            withContext(Dispatchers.Main) {
                terminalSession = TerminalSession(
                    "/system/bin/cat", // Use cat as a dummy shell - we'll override its behavior
                    sshConfig.workingDirectory,
                    arrayOf("/dev/null"), // Dummy args
                    arrayOf(
                        "TERM=xterm-256color", 
                        "SSH_CONNECTION=${sshConfig.hostname}",
                        "SSH_USER=${sshConfig.username}",
                        "SSH_HOST=${sshConfig.hostname}",
                        "SSH_PORT=${sshConfig.port}"
                    ),
                    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                    sessionClient
                )
                
                // Start bridging data between SSH and Terminal
                startDataBridging()
                
                // Send initial setup commands
                scope.launch {
                    delay(500) // Wait for connection to stabilize
                    
                    // Change to working directory
                    if (sshConfig.workingDirectory != "~") {
                        writeToSsh("cd ${sshConfig.workingDirectory}\n".toByteArray())
                    }
                    
                    // Set up environment variables
                    sshConfig.environmentVariables.forEach { (key, value) ->
                        writeToSsh("export $key=\"$value\"\n".toByteArray())
                    }
                    
                    // Clear screen and show welcome message
                    writeToSsh("clear\n".toByteArray())
                    writeToSsh("echo 'SSH session connected to ${sshSession!!.getSessionInfo()}'\n".toByteArray())
                    writeToSsh("echo 'Working directory: ${sshConfig.workingDirectory}'\n".toByteArray())
                }
            }
            
            terminalSession
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SSH terminal session", e)
            cleanup()
            null
        }
    }
    
    private fun startDataBridging() {
        // Bridge data from SSH to terminal (command output)
        scope.launch {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (scope.isActive && sshSession?.isConnected() == true) {
                    val bytesRead = sshInputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        // Send to terminal emulator for display
                        withContext(Dispatchers.Main) {
                            terminalSession?.emulator?.append(buffer, bytesRead)
                        }
                    } else if (bytesRead == -1) {
                        // Connection closed
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error bridging remote to local data", e)
            }
        }
    }
    
    fun writeToSsh(data: ByteArray) {
        scope.launch {
            try {
                sshOutputStream?.write(data)
                sshOutputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to SSH", e)
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
        sshSession?.disconnect()
        try {
            sshInputStream?.close()
            sshOutputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SSH streams", e)
        }
    }
    
    fun isConnected(): Boolean {
        return sshSession?.isConnected() == true
    }
    
    fun getSshSession(): SshSession? {
        return sshSession
    }
}