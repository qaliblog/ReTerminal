package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class SshTerminalSession(
    private val sshConfig: SshConfig,
    sessionClient: TerminalSessionClient
) : TerminalSession(
    "/system/bin/sh", // Dummy shell
    sshConfig.workingDirectory,
    arrayOf(),
    arrayOf(
        "TERM=xterm-256color",
        "SSH_CONNECTION=${sshConfig.hostname}",
        "SSH_USER=${sshConfig.username}",
        "SSH_HOST=${sshConfig.hostname}",
        "SSH_PORT=${sshConfig.port}"
    ),
    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
    sessionClient
) {
    private var sshSession: SshSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SshTerminalSession"
        private const val BUFFER_SIZE = 8192
    }
    
    init {
        // Initialize SSH connection in background
        scope.launch {
            initializeSshConnection()
        }
    }
    
    private suspend fun initializeSshConnection() {
        try {
            sshSession = SshSession(sshConfig)
            val connected = sshSession!!.connect()
            
            if (!connected) {
                Log.e(TAG, "Failed to establish SSH connection")
                showErrorMessage("Failed to connect to ${sshConfig.hostname}:${sshConfig.port}")
                return
            }
            
            // Open shell channel
            val (inputStream, outputStream) = sshSession!!.openShellChannel()
            
            if (inputStream == null || outputStream == null) {
                Log.e(TAG, "Failed to open SSH shell channel")
                showErrorMessage("Failed to open SSH shell channel")
                sshSession?.disconnect()
                return
            }
            
            sshInputStream = inputStream
            sshOutputStream = outputStream
            
            // Start reading from SSH and writing to terminal
            startSshToTerminalBridge()
            
            // Send initial setup
            delay(500)
            sendInitialCommands()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SSH connection", e)
            showErrorMessage("SSH connection error: ${e.message}")
        }
    }
    
    private fun startSshToTerminalBridge() {
        scope.launch {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (scope.isActive && sshSession?.isConnected() == true) {
                    val bytesRead = sshInputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        // Send to terminal emulator for display
                        withContext(Dispatchers.Main) {
                            emulator?.append(buffer, bytesRead)
                        }
                    } else if (bytesRead == -1) {
                        // Connection closed
                        withContext(Dispatchers.Main) {
                            showErrorMessage("SSH connection closed")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH to terminal bridge", e)
                withContext(Dispatchers.Main) {
                    showErrorMessage("SSH bridge error: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun sendInitialCommands() {
        try {
            // Change to working directory
            if (sshConfig.workingDirectory != "~") {
                writeToSsh("cd ${sshConfig.workingDirectory}\n")
            }
            
            // Set up environment variables
            sshConfig.environmentVariables.forEach { (key, value) ->
                writeToSsh("export $key=\"$value\"\n")
            }
            
            // Show welcome message
            writeToSsh("echo '=== SSH Session Connected ==='\n")
            writeToSsh("echo 'Host: ${sshConfig.hostname}:${sshConfig.port}'\n")
            writeToSsh("echo 'User: ${sshConfig.username}'\n")
            writeToSsh("echo 'Working Directory: ${sshConfig.workingDirectory}'\n")
            writeToSsh("echo '================================'\n")
            writeToSsh("pwd\n") // Show current directory
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending initial commands", e)
        }
    }
    
    private fun writeToSsh(command: String) {
        try {
            sshOutputStream?.write(command.toByteArray(StandardCharsets.UTF_8))
            sshOutputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to SSH", e)
        }
    }
    
    private fun showErrorMessage(message: String) {
        try {
            val errorMsg = "\n$message\n"
            emulator?.append(errorMsg.toByteArray(), errorMsg.length)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error message", e)
        }
    }
    
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        // Override write to send data to SSH instead of local shell
        if (data != null && sshOutputStream != null) {
            scope.launch {
                try {
                    sshOutputStream?.write(data, offset, count)
                    sshOutputStream?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to SSH stream", e)
                }
            }
        } else {
            // Fallback to super if SSH not ready
            super.write(data, offset, count)
        }
    }
    
    override fun finishIfRunning() {
        scope.cancel()
        sshSession?.disconnect()
        super.finishIfRunning()
    }
    
    fun getSshSession(): SshSession? = sshSession
    
    fun isConnected(): Boolean = sshSession?.isConnected() == true
    
    fun getSshFileManager(): SshFileManager? {
        return sshSession?.let { SshFileManager(it) }
    }
}