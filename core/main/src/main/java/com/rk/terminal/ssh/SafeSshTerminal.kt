package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SafeSshTerminal(
    private val sshConfig: SshConfig
) {
    private var sshSession: SshSession? = null
    private var termuxSshSession: TermuxSshSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SafeSshTerminal"
        private const val BUFFER_SIZE = 8192
    }
    
    fun connectAsync(
        terminalSession: TerminalSession,
        onProgress: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                onProgress("🔗 Connecting to ${sshConfig.hostname}:${sshConfig.port}...")
                
                // Try Termux-specific SSH approach first
                val termuxSsh = TermuxSshSession(sshConfig)
                val termuxConnected = termuxSsh.connect()
                
                if (termuxConnected) {
                    Log.d(TAG, "Termux SSH connection successful, opening shell...")
                    onProgress("🔐 Opening Termux shell...")
                    
                    val (inputStream, outputStream) = termuxSsh.openTermuxShell()
                    
                    if (inputStream != null && outputStream != null) {
                        // Use Termux SSH session
                        sshInputStream = inputStream
                        sshOutputStream = outputStream
                        
                        // Store Termux session reference
                        termuxSshSession = termuxSsh
                        
                        Log.d(TAG, "Termux SSH shell opened successfully")
                    } else {
                        Log.w(TAG, "Termux shell failed, trying regular SSH...")
                        termuxSsh.disconnect()
                        
                        // Fallback to regular SSH
                        sshSession = SshSession(sshConfig)
                        val connected = sshSession!!.connect()
                        
                        if (!connected) {
                            Log.e(TAG, "Failed to establish regular SSH connection")
                            onError("Failed to connect to SSH server")
                            return@launch
                        }
                        
                        onProgress("🔐 Authenticating user ${sshConfig.username}...")
                        
                        // Open shell channel
                        val (regInputStream, regOutputStream) = sshSession!!.openShellChannel()
                        sshInputStream = regInputStream
                        sshOutputStream = regOutputStream
                    }
                } else {
                    Log.w(TAG, "Termux SSH failed, trying regular SSH...")
                    
                    // Fallback to regular SSH
                    sshSession = SshSession(sshConfig)
                    val connected = sshSession!!.connect()
                    
                    if (!connected) {
                        Log.e(TAG, "Failed to establish SSH connection")
                        onError("Failed to connect to SSH server")
                        return@launch
                    }
                    
                    onProgress("🔐 Authenticating user ${sshConfig.username}...")
                    
                    // Open shell channel
                    val (inputStream, outputStream) = sshSession!!.openShellChannel()
                    sshInputStream = inputStream
                    sshOutputStream = outputStream
                }
                
                if (sshInputStream == null || sshOutputStream == null) {
                    Log.e(TAG, "Failed to open SSH shell channel")
                    sshSession?.disconnect()
                    termuxSshSession?.disconnect()
                    onError("Failed to open SSH shell channel")
                    return@launch
                }
                

                
                onProgress("🚀 Setting up SSH shell...")
                
                // Start output bridge (SSH → Terminal)
                startOutputBridge(terminalSession)
                
                // Start keep-alive mechanism
                startKeepAlive()
                
                // Send initial commands
                delay(500)
                sendInitialCommands()
                
                onSuccess()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH connection", e)
                onError("SSH connection error: ${e.message}")
                cleanup()
            }
        }
    }
    
    private fun startOutputBridge(terminalSession: TerminalSession) {
        scope.launch {
            try {
                Log.d(TAG, "Starting SSH output bridge")
                val buffer = ByteArray(BUFFER_SIZE)
                var connectionStable = false
                
                while (scope.isActive && sshSession?.isConnected() == true) {
                    try {
                        val bytesRead = sshInputStream?.read(buffer) ?: -1
                        
                        if (bytesRead > 0) {
                            connectionStable = true
                            val text = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                            Log.d(TAG, "Received from SSH: $text")
                            
                            withContext(Dispatchers.Main) {
                                try {
                                    terminalSession.emulator?.append(buffer, bytesRead)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error appending to terminal", e)
                                }
                            }
                        } else if (bytesRead == -1) {
                            Log.w(TAG, "SSH input stream returned EOF")
                            if (connectionStable) {
                                withContext(Dispatchers.Main) {
                                    try {
                                        val errorMsg = "\n🔌 SSH connection closed by server\n"
                                        terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error showing disconnect message", e)
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    try {
                                        val errorMsg = "\n⚠️ SSH connection failed to establish properly\nTry reconnecting or check server settings\n"
                                        terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error showing connection failure", e)
                                    }
                                }
                            }
                            break
                        } else {
                            // bytesRead == 0, continue reading
                            delay(10) // Small delay to prevent busy waiting
                        }
                    } catch (readException: Exception) {
                        Log.e(TAG, "Error reading from SSH stream", readException)
                        delay(100) // Wait before retrying
                    }
                }
                
                Log.d(TAG, "SSH output bridge ended")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH output bridge", e)
                withContext(Dispatchers.Main) {
                    try {
                        val errorMsg = "\n❌ SSH bridge error: ${e.message}\n"
                        terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                    } catch (bridgeError: Exception) {
                        Log.e(TAG, "Error showing bridge error message", bridgeError)
                    }
                }
            }
        }
    }
    
    private fun startKeepAlive() {
        // Send periodic keep-alive to prevent connection timeout
        scope.launch {
            try {
                while (scope.isActive && sshSession?.isConnected() == true) {
                    delay(30000) // Send keep-alive every 30 seconds
                    
                    if (sshSession?.isConnected() == true) {
                        // Send a harmless command to keep connection alive
                        writeToSsh("# keep-alive\n")
                        Log.d(TAG, "Sent SSH keep-alive")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in keep-alive mechanism", e)
            }
        }
    }
    
    private suspend fun sendInitialCommands() {
        try {
            writeToSsh("clear\n")
            delay(200)
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
            writeToSsh("echo 'SSH session is active - type commands to execute remotely'\n")
            writeToSsh("echo 'Connection status: Connected to ${sshConfig.hostname}'\n")
            
            // Test if the connection stays alive
            delay(1000)
            writeToSsh("echo 'Testing connection stability...'\n")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending initial commands", e)
        }
    }
    
    fun writeToSsh(command: String) {
        scope.launch {
            try {
                if (sshOutputStream != null && sshSession?.isConnected() == true) {
                    sshOutputStream?.write(command.toByteArray(StandardCharsets.UTF_8))
                    sshOutputStream?.flush()
                    Log.d(TAG, "Sent to SSH: ${command.trim()}")
                } else {
                    Log.w(TAG, "SSH not connected, cannot send: ${command.trim()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to SSH", e)
            }
        }
    }
    
    fun writeToSsh(data: ByteArray, offset: Int, count: Int) {
        scope.launch {
            try {
                if (sshOutputStream != null && sshSession?.isConnected() == true) {
                    sshOutputStream?.write(data, offset, count)
                    sshOutputStream?.flush()
                    val text = String(data, offset, count, StandardCharsets.UTF_8)
                    Log.d(TAG, "Sent bytes to SSH: $text")
                } else {
                    Log.w(TAG, "SSH not connected, cannot send bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing bytes to SSH", e)
            }
        }
    }
    
    fun cleanup() {
        try {
            scope.cancel()
            sshSession?.disconnect()
            termuxSshSession?.disconnect()
            sshInputStream?.close()
            sshOutputStream?.close()
            Log.d(TAG, "SSH terminal cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    fun getSshSession(): SshSession? = sshSession
    fun getTermuxSshSession(): TermuxSshSession? = termuxSshSession
    fun isConnected(): Boolean = sshSession?.isConnected() == true || termuxSshSession?.isConnected() == true
    fun getSshFileManager(): SshFileManager? = sshSession?.let { SshFileManager(it) }
}