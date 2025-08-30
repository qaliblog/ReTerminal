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
                
                // Initialize SSH connection
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
                
                if (inputStream == null || outputStream == null) {
                    Log.e(TAG, "Failed to open SSH shell channel")
                    sshSession?.disconnect()
                    onError("Failed to open SSH shell channel")
                    return@launch
                }
                
                sshInputStream = inputStream
                sshOutputStream = outputStream
                
                onProgress("🚀 Setting up SSH shell...")
                
                // Start output bridge (SSH → Terminal)
                startOutputBridge(terminalSession)
                
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
                val buffer = ByteArray(BUFFER_SIZE)
                while (scope.isActive && sshSession?.isConnected() == true) {
                    val bytesRead = sshInputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        withContext(Dispatchers.Main) {
                            try {
                                terminalSession.emulator?.append(buffer, bytesRead)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error appending to terminal", e)
                            }
                        }
                    } else if (bytesRead == -1) {
                        withContext(Dispatchers.Main) {
                            try {
                                val errorMsg = "\n🔌 SSH connection closed\n"
                                terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error showing disconnect message", e)
                            }
                        }
                        break
                    }
                }
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
            writeToSsh("echo 'Type commands to execute on remote server'\n")
            
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
            sshInputStream?.close()
            sshOutputStream?.close()
            Log.d(TAG, "SSH terminal cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    fun getSshSession(): SshSession? = sshSession
    fun isConnected(): Boolean = sshSession?.isConnected() == true
    fun getSshFileManager(): SshFileManager? = sshSession?.let { SshFileManager(it) }
}