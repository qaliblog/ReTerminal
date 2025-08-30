package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SshTerminalEmulator(
    private val sshConfig: SshConfig,
    private val sessionClient: TerminalSessionClient
) {
    private var sshSession: SshSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private var terminalSession: TerminalSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "SshTerminalEmulator"
        private const val BUFFER_SIZE = 8192
    }
    
    suspend fun createSession(): TerminalSession? = withContext(Dispatchers.IO) {
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
            
            // Create terminal session with custom client
            withContext(Dispatchers.Main) {
                val customClient = SshAwareTerminalSessionClient(sessionClient, this@SshTerminalEmulator)
                
                terminalSession = TerminalSession(
                    "/system/bin/cat", // Dummy command
                    sshConfig.workingDirectory,
                    arrayOf("/dev/null"),
                    arrayOf(
                        "TERM=xterm-256color",
                        "SSH_CONNECTION=${sshConfig.hostname}",
                        "SSH_USER=${sshConfig.username}",
                        "SSH_HOST=${sshConfig.hostname}",
                        "SSH_PORT=${sshConfig.port}"
                    ),
                    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                    customClient
                )
                
                // Override the terminal session's write method
                overrideTerminalInput()
                
                // Start SSH bridge
                startSshBridge()
                
                // Send initial commands
                scope.launch {
                    delay(1000)
                    sendInitialCommands()
                }
            }
            
            terminalSession
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SSH terminal session", e)
            cleanup()
            null
        }
    }
    
    private fun overrideTerminalInput() {
        try {
            // Use reflection to replace the terminal session's process output stream
            val sessionClass = terminalSession!!.javaClass
            val processField = sessionClass.getDeclaredField("mProcess")
            processField.isAccessible = true
            val process = processField.get(terminalSession)
            
            if (process != null) {
                val processClass = process.javaClass
                val outputStreamField = processClass.getDeclaredField("mOutputStream")
                outputStreamField.isAccessible = true
                
                // Create SSH redirect stream
                val sshRedirectStream = SshOutputStream()
                outputStreamField.set(process, sshRedirectStream)
                
                Log.d(TAG, "Successfully overrode terminal input stream")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not override terminal input: ${e.message}")
        }
    }
    
    private inner class SshOutputStream : OutputStream() {
        override fun write(b: Int) {
            if (sshOutputStream != null && sshSession?.isConnected() == true) {
                scope.launch {
                    try {
                        sshOutputStream?.write(b)
                        sshOutputStream?.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing byte to SSH", e)
                    }
                }
            }
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (sshOutputStream != null && sshSession?.isConnected() == true) {
                scope.launch {
                    try {
                        sshOutputStream?.write(b, off, len)
                        sshOutputStream?.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing bytes to SSH", e)
                    }
                }
            }
        }
        
        override fun flush() {
            if (sshOutputStream != null) {
                scope.launch {
                    try {
                        sshOutputStream?.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error flushing SSH", e)
                    }
                }
            }
        }
        
        override fun close() {
            // Don't close SSH stream
        }
    }
    
    private fun startSshBridge() {
        // Bridge SSH output to terminal
        scope.launch {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (scope.isActive && sshSession?.isConnected() == true) {
                    val bytesRead = sshInputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        withContext(Dispatchers.Main) {
                            terminalSession?.emulator?.append(buffer, bytesRead)
                        }
                    } else if (bytesRead == -1) {
                        withContext(Dispatchers.Main) {
                            showErrorMessage("SSH connection closed")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSH bridge", e)
                withContext(Dispatchers.Main) {
                    showErrorMessage("SSH bridge error: ${e.message}")
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
            terminalSession?.emulator?.append(errorMsg.toByteArray(), errorMsg.length)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error message", e)
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

class SshAwareTerminalSessionClient(
    private val originalClient: TerminalSessionClient,
    private val sshEmulator: SshTerminalEmulator
) : TerminalSessionClient {
    
    override fun onTextChanged(changedSession: TerminalSession) {
        originalClient.onTextChanged(changedSession)
    }
    
    override fun onTitleChanged(changedSession: TerminalSession) {
        originalClient.onTitleChanged(changedSession)
    }
    
    override fun onSessionFinished(finishedSession: TerminalSession) {
        sshEmulator.cleanup()
        originalClient.onSessionFinished(finishedSession)
    }
    
    override fun onBell(session: TerminalSession) {
        originalClient.onBell(session)
    }
    
    override fun onColorsChanged(session: TerminalSession) {
        originalClient.onColorsChanged(session)
    }
    
    override fun onTerminalCursorStateChange(state: Boolean) {
        originalClient.onTerminalCursorStateChange(state)
    }
    
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        originalClient.onCopyTextToClipboard(session, text)
    }
    
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        originalClient.onPasteTextFromClipboard(session)
    }
    
    override fun getTerminalCursorStyle(): Int {
        return originalClient.getTerminalCursorStyle()
    }
    
    override fun logError(tag: String?, message: String?) {
        originalClient.logError(tag, message)
    }
    
    override fun logWarn(tag: String?, message: String?) {
        originalClient.logWarn(tag, message)
    }
    
    override fun logInfo(tag: String?, message: String?) {
        originalClient.logInfo(tag, message)
    }
    
    override fun logDebug(tag: String?, message: String?) {
        originalClient.logDebug(tag, message)
    }
    
    override fun logVerbose(tag: String?, message: String?) {
        originalClient.logVerbose(tag, message)
    }
    
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        originalClient.logStackTraceWithMessage(tag, message, e)
    }
    
    override fun logStackTrace(tag: String?, e: Exception?) {
        originalClient.logStackTrace(tag, e)
    }
}