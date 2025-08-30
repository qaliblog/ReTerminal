package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SshTerminalSession(
    private val sshConfig: SshConfig,
    private val sessionClient: TerminalSessionClient
) {
    private val terminalSession: TerminalSession
    private var sshSession: SshSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SshTerminalSession"
        private const val BUFFER_SIZE = 8192
    }
    
    init {
        // Create a custom session client that can handle SSH cleanup
        val sshSessionClient = SshTerminalSessionClient(sessionClient, this)
        
        // Create a regular terminal session that we'll bridge to SSH
        terminalSession = TerminalSession(
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
            sshSessionClient
        )
        
        // Initialize SSH connection in background
        scope.launch {
            initializeSshConnection()
        }
        
        // Set up input redirection by intercepting terminal session writes
        setupInputRedirection()
    }
    
    // Delegate all TerminalSession methods to the wrapped session
    val emulator get() = terminalSession.emulator
    val isRunning get() = terminalSession.isRunning
    val pid get() = terminalSession.pid
    
    // Method to get the wrapped TerminalSession for compatibility
    fun getTerminalSession(): TerminalSession = terminalSession
    
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
                            terminalSession.emulator?.append(buffer, bytesRead)
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
            terminalSession.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error message", e)
        }
    }
    
    fun write(data: ByteArray?, offset: Int, count: Int) {
        // Send data to SSH instead of local shell
        if (data != null && sshOutputStream != null && sshSession?.isConnected() == true) {
            scope.launch {
                try {
                    sshOutputStream?.write(data, offset, count)
                    sshOutputStream?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to SSH stream", e)
                }
            }
        } else {
            // Fallback to terminal session if SSH not ready
            terminalSession.write(data, offset, count)
        }
    }
    
    fun finishIfRunning() {
        scope.cancel()
        sshSession?.disconnect()
        terminalSession.finishIfRunning()
    }
    
    fun getSshSession(): SshSession? = sshSession
    
    fun isConnected(): Boolean = sshSession?.isConnected() == true
    
    fun getSshFileManager(): SshFileManager? {
        return sshSession?.let { SshFileManager(it) }
    }
    
    private fun setupInputRedirection() {
        // We'll handle input redirection through the terminal emulator's input stream
        // The key is that we need to intercept writes to the terminal session
        // For now, we'll rely on the write() method being called explicitly
        Log.d(TAG, "SSH input redirection set up")
    }
}