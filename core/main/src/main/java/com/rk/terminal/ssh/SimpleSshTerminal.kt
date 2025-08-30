package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SimpleSshTerminal(
    private val sshConfig: SshConfig,
    private val sessionClient: TerminalSessionClient
) {
    private var sshSession: SshSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SimpleSshTerminal"
        private const val BUFFER_SIZE = 8192
    }
    
    fun createSessionAsync(onResult: (TerminalSession?) -> Unit) {
        // Start SSH connection on background thread to avoid freezing
        scope.launch {
            try {
                Log.d(TAG, "Starting SSH connection to ${sshConfig.hostname}:${sshConfig.port}")
                
                // Initialize SSH connection
                sshSession = SshSession(sshConfig)
                val connected = sshSession!!.connect()
                
                if (!connected) {
                    Log.e(TAG, "Failed to establish SSH connection")
                    withContext(Dispatchers.Main) {
                        onResult(null)
                    }
                    return@launch
                }
                
                // Open shell channel
                val (inputStream, outputStream) = sshSession!!.openShellChannel()
                
                if (inputStream == null || outputStream == null) {
                    Log.e(TAG, "Failed to open SSH shell channel")
                    sshSession?.disconnect()
                    withContext(Dispatchers.Main) {
                        onResult(null)
                    }
                    return@launch
                }
                
                sshInputStream = inputStream
                sshOutputStream = outputStream
                
                // Create terminal session on main thread with SSH input handler
                withContext(Dispatchers.Main) {
                    val sshInputHandler = SshInputHandler(sessionClient, this@SimpleSshTerminal)
                    
                    val terminalSession = TerminalSession(
                        "/system/bin/sleep", // Use sleep command that won't interfere
                        sshConfig.workingDirectory,
                        arrayOf("3600"), // Sleep for 1 hour (effectively infinite)
                        arrayOf(
                            "TERM=xterm-256color",
                            "SSH_CONNECTION=${sshConfig.hostname}",
                            "SSH_USER=${sshConfig.username}",
                            "SSH_HOST=${sshConfig.hostname}",
                            "SSH_PORT=${sshConfig.port}"
                        ),
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        sshInputHandler
                    )
                    
                    // Kill the local sleep process immediately and set up SSH redirection
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(100) // Let the process start briefly
                        terminalSession.finishIfRunning() // Kill the sleep process
                        
                        // Now all input should go through our SSH handler
                        Log.d(TAG, "Local process terminated, SSH input redirection active")
                    }
                    
                    // Set up input interception for SSH
                    SshTerminalBridge.interceptTerminalInput(terminalSession, this@SimpleSshTerminal)
                    
                    // Start SSH bridge immediately
                    startSshBridge(terminalSession)
                    
                    // Send initial commands
                    scope.launch {
                        delay(1000) // Wait for connection to stabilize
                        sendInitialCommands()
                    }
                    
                    Log.d(TAG, "SSH session created successfully")
                    onResult(terminalSession)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating SSH session", e)
                cleanup()
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }
    
    private fun startSshBridge(terminalSession: TerminalSession) {
        // Bridge SSH output to terminal display
        scope.launch {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (scope.isActive && sshSession?.isConnected() == true) {
                    val bytesRead = sshInputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        withContext(Dispatchers.Main) {
                            terminalSession.emulator?.append(buffer, bytesRead)
                        }
                    } else if (bytesRead == -1) {
                        withContext(Dispatchers.Main) {
                            val errorMsg = "\nSSH connection closed\n"
                            terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH bridge", e)
                withContext(Dispatchers.Main) {
                    val errorMsg = "\nSSH bridge error: ${e.message}\n"
                    terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                }
            }
        }
    }
    
    private suspend fun sendInitialCommands() {
        try {
            writeToSsh("echo '=== SSH Session Connected ==='\n")
            writeToSsh("echo 'Host: ${sshConfig.hostname}:${sshConfig.port}'\n")
            writeToSsh("echo 'User: ${sshConfig.username}'\n")
            writeToSsh("echo 'Working Directory: ${sshConfig.workingDirectory}'\n")
            writeToSsh("echo '================================'\n")
            
            if (sshConfig.workingDirectory != "~") {
                writeToSsh("cd ${sshConfig.workingDirectory}\n")
            }
            
            sshConfig.environmentVariables.forEach { (key, value) ->
                writeToSsh("export $key=\"$value\"\n")
            }
            
            writeToSsh("pwd\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending initial commands", e)
        }
    }
    
    fun writeToSsh(command: String) {
        scope.launch {
            try {
                sshOutputStream?.write(command.toByteArray(StandardCharsets.UTF_8))
                sshOutputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to SSH", e)
            }
        }
    }
    
    fun writeToSsh(data: ByteArray, offset: Int, count: Int) {
        scope.launch {
            try {
                sshOutputStream?.write(data, offset, count)
                sshOutputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing bytes to SSH", e)
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
    
    fun getSshSession(): SshSession? = sshSession
    fun isConnected(): Boolean = sshSession?.isConnected() == true
    fun getSshFileManager(): SshFileManager? = sshSession?.let { SshFileManager(it) }
}