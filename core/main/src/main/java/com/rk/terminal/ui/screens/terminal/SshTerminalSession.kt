package com.rk.terminal.ui.screens.terminal

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.*

class SshTerminalSession(
    private val sshSessionId: String,
    private val terminalSessionClient: TerminalSessionClient
) {
    private val sshManager = SshManager.getInstance()
    private var shellChannel: ChannelShell? = null
    private var terminalSession: TerminalSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamsReplaced = false
    
    companion object {
        private const val TAG = "SshTerminalSession"
    }
    
    suspend fun start(): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting SSH terminal session: $sshSessionId")
            
            // Get shell channel from SSH manager
            shellChannel = sshManager.getShellChannel(sshSessionId)
            if (shellChannel == null) {
                Log.e(TAG, "Failed to create SSH shell channel for session: $sshSessionId")
                return@withContext Result.failure(Exception("Failed to create SSH shell channel"))
            }
            
            Log.d(TAG, "SSH shell channel created successfully")
            
            // Connect SSH channel streams
            sshInputStream = shellChannel!!.inputStream
            sshOutputStream = shellChannel!!.outputStream
            
            if (sshInputStream == null || sshOutputStream == null) {
                Log.e(TAG, "SSH streams are null after channel creation")
                return@withContext Result.failure(Exception("SSH streams are null"))
            }
            
            Log.d(TAG, "SSH streams obtained successfully")
            // Note: JSch ChannelShell doesn't have setErrStream, errors are mixed with output
            
            // Configure shell channel with enhanced PTY settings
            shellChannel!!.setPty(true)
            shellChannel!!.setPtyType("xterm-256color")
            shellChannel!!.setPtySize(80, 24, 640, 480) // cols, rows, width, height
            
            // Set additional PTY environment variables for better compatibility
            val env = mutableMapOf<String, String>()
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["LC_ALL"] = "en_US.UTF-8"
            env["SHELL"] = "/bin/bash"
            
            // Apply environment variables
            for ((key, value) in env) {
                try {
                    shellChannel!!.setEnv(key, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set environment variable $key=$value", e)
                }
            }
            
            // Connect the shell channel
            shellChannel!!.connect()

            // Create a local terminal session used only as a UI container. We'll replace its I/O streams.
            val created = withContext(Dispatchers.Main) {
                try {
                    val session = TerminalSession(
                        "/system/bin/sh",
                        "/",
                        arrayOf(),
                        arrayOf("TERM=xterm-256color"),
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        terminalSessionClient
                    )
                    
                    // Override the session's write method to forward to SSH
                    overrideSessionWrite(session)
                    session
                } catch (e: Exception) {
                    null
                }
            }

            if (created == null) {
                return@withContext Result.failure(Exception("Failed to create TerminalSession"))
            }

            terminalSession = created

            // Replace TerminalSession streams with SSH channel streams so Terminal handles I/O natively
            replaceTerminalStreams()
            isRunning = true
            
            // Always start I/O forwarding to ensure input works properly
            startIoForwarding()
            
            // Set up direct input handling as backup
            setupDirectInputHandling()
            
            // Log the current status
            Log.d(TAG, "SSH session setup - Running: $isRunning, Streams replaced: $streamsReplaced")
            
            Log.d(TAG, "SSH terminal session started successfully")
            Result.success(terminalSession!!)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSH terminal session", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    private fun replaceTerminalStreams() {
        try {
            if (terminalSession == null || sshInputStream == null || sshOutputStream == null) return
            val terminalSessionClass = terminalSession!!.javaClass

            // Try multiple possible field names for terminal input/output streams
            val inputFieldNames = listOf("mTerminalInput", "terminalInput", "mTerminalToProcessIOQueue", "mProcessToTerminalIOQueue")
            val outputFieldNames = listOf("mTerminalOutput", "terminalOutput", "mProcessToTerminalIOQueue", "mTerminalToProcessIOQueue")
            
            var inputFieldSet = false
            var outputFieldSet = false

            // Try to find and replace input stream (terminal to SSH)
            for (fieldName in inputFieldNames) {
                try {
                    val field = terminalSessionClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val sshOut = sshOutputStream!!
                    val proxyOut = object : OutputStream() {
                        override fun write(b: Int) {
                            try {
                                sshOut.write(b)
                                sshOut.flush()
                                Log.v(TAG, "SSH input: ${b.toChar()}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing to SSH output stream", e)
                            }
                        }
                        override fun write(b: ByteArray) {
                            try {
                                sshOut.write(b)
                                sshOut.flush()
                                Log.v(TAG, "SSH input: ${String(b)}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing to SSH output stream", e)
                            }
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            try {
                                sshOut.write(b, off, len)
                                sshOut.flush()
                                Log.v(TAG, "SSH input: ${String(b, off, len)}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing to SSH output stream", e)
                            }
                        }
                        override fun flush() { 
                            try {
                                sshOut.flush()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error flushing SSH output stream", e)
                            }
                        }
                        override fun close() { 
                            try {
                                sshOut.flush() 
                            } catch (e: Exception) {
                                Log.e(TAG, "Error closing SSH output stream", e)
                            }
                        }
                    }
                    field.set(terminalSession, proxyOut)
                    inputFieldSet = true
                    Log.d(TAG, "Successfully replaced terminal input field: $fieldName")
                    break
                } catch (e: NoSuchFieldException) {
                    Log.v(TAG, "Field $fieldName not found, trying next")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set field $fieldName", e)
                }
            }

            // Try to find and replace output stream (SSH to terminal)
            for (fieldName in outputFieldNames) {
                try {
                    val field = terminalSessionClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(terminalSession, sshInputStream)
                    outputFieldSet = true
                    Log.d(TAG, "Successfully replaced terminal output field: $fieldName")
                    break
                } catch (e: NoSuchFieldException) {
                    Log.v(TAG, "Field $fieldName not found, trying next")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set field $fieldName", e)
                }
            }

            streamsReplaced = inputFieldSet && outputFieldSet
            if (streamsReplaced) {
                Log.d(TAG, "Successfully replaced TerminalSession I/O streams with SSH channel streams")
            } else {
                Log.w(TAG, "Could not replace all terminal streams (input: $inputFieldSet, output: $outputFieldSet)")
            }
        } catch (e: Exception) {
            streamsReplaced = false
            Log.w(TAG, "Failed to replace TerminalSession streams; will use I/O forwarding fallback", e)
        }
    }
    
    private fun startIoForwarding() {
        // Enhanced I/O forwarding as fallback when stream replacement fails
        Log.d(TAG, "Starting I/O forwarding for SSH session (streams replaced: $streamsReplaced)")
        
        // Forward output from SSH to terminal emulator
        scope.launch {
            try {
                val buffer = ByteArray(256) // Smaller buffer for immediate responsiveness
                Log.d(TAG, "Starting SSH output forwarding")
                while (isRunning && sshInputStream != null) {
                    val bytesRead = sshInputStream!!.read(buffer)
                    if (bytesRead > 0) {
                        val output = String(buffer, 0, bytesRead)
                        Log.v(TAG, "SSH output ($bytesRead bytes): '$output'")
                        
                        // Forward to terminal emulator
                        terminalSession?.emulator?.append(buffer, bytesRead)
                        
                        // Force screen update
                        terminalSession?.let { session ->
                            withContext(Dispatchers.Main) {
                                terminalSessionClient.onTextChanged(session)
                            }
                        }
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "SSH input stream closed")
                        break
                    } else {
                        // No data available, small delay to prevent busy waiting
                        kotlinx.coroutines.delay(10)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error forwarding output from SSH", e)
                }
            }
            Log.d(TAG, "SSH output forwarding ended")
        }
        
        // Initialize the terminal with a welcome message and prompt
        scope.launch {
            try {
                // Wait a bit for the connection to stabilize
                kotlinx.coroutines.delay(500)
                
                // Send initial commands to set up the terminal properly
                val initCommands = listOf(
                    "stty echo",           // Ensure echo is enabled
                    "stty icanon",         // Enable canonical input processing
                    "export TERM=xterm-256color", // Set proper terminal type
                    "cd /data/data/com.termux/files/home 2>/dev/null || cd ~", // Set proper working directory
                    "export PS1='\\u@\\h:\\w\\$ '", // Set a proper prompt
                    "pwd",                 // Show current directory
                    ""                     // Empty line to finish initialization
                )
                
                for (cmd in initCommands) {
                    if (isRunning && sshOutputStream != null && cmd.isNotEmpty()) {
                        Log.d(TAG, "Sending init command: $cmd")
                        sshOutputStream!!.write((cmd + "\r\n").toByteArray())
                        sshOutputStream!!.flush()
                        kotlinx.coroutines.delay(200) // Small delay between commands
                    }
                }
                
                // Send a test command to verify the connection is interactive
                if (isRunning && sshOutputStream != null) {
                    Log.d(TAG, "Sending test command: echo")
                    sshOutputStream!!.write("echo 'SSH connection ready - type commands:'\r\n".toByteArray())
                    sshOutputStream!!.flush()
                    
                    // Wait a moment and then show status
                    kotlinx.coroutines.delay(500)
                    Log.d(TAG, "SSH terminal ready for input: ${getConnectionInfo()}")
                    
                    // Test that input forwarding works by sending a simple character
                    kotlinx.coroutines.delay(1000)
                    Log.d(TAG, "Testing input forwarding with character 'a'")
                    sendInput("a")
                    
                    // Give user a hint about input
                    kotlinx.coroutines.delay(500)
                    sshOutputStream!!.write("# Try typing 'ls' and press Enter\r\n".toByteArray())
                    sshOutputStream!!.flush()
                    
                    // Immediate test to verify SSH pipeline
                    kotlinx.coroutines.delay(1000)
                    Log.d(TAG, "Immediate test: sending direct command")
                    sendCommand("echo 'TEST: SSH pipeline working'")
                    
                    // After a delay, simulate typing 'ls' to test the pipeline
                    kotlinx.coroutines.delay(2000)
                    Log.d(TAG, "Auto-testing: simulating 'ls' command")
                    simulateCommand("ls")
                    
                    // Additional test after more delay
                    kotlinx.coroutines.delay(3000)
                    Log.d(TAG, "Auto-testing: simulating 'whoami' command")
                    simulateCommand("whoami")
                    
                    // Test activity input by sending a message
                    kotlinx.coroutines.delay(2000)
                    sendCommand("echo 'Now try typing on keyboard - activity input should work'")
                    
                    // Final test after a longer delay
                    kotlinx.coroutines.delay(5000)
                    sendCommand("echo 'If you see this, SSH works but keyboard input may need fixing'")
                }
                
                Log.d(TAG, "SSH terminal initialization completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SSH terminal", e)
            }
        }
    }
    
    fun sendInput(data: ByteArray) {
        try {
            if (isRunning && sshOutputStream != null) {
                val inputStr = String(data)
                Log.d(TAG, "Sending input to SSH: '$inputStr' (${data.size} bytes)")
                Log.d(TAG, "SSH status - Running: $isRunning, Channel connected: ${shellChannel?.isConnected}, Streams replaced: $streamsReplaced")
                
                // Write to SSH output stream
                sshOutputStream!!.write(data)
                sshOutputStream!!.flush()
                
                // Always manually echo the input so user can see typing immediately
                if (terminalSession != null) {
                    Log.d(TAG, "Echoing input to terminal display")
                    // Echo the input to the terminal so user can see what they're typing
                    if (inputStr.isNotEmpty() && !inputStr.contains('\n') && !inputStr.contains('\r')) {
                        // Echo character by character for immediate visual feedback
                        for (byte in data) {
                            terminalSession!!.emulator?.append(byteArrayOf(byte), 1)
                        }
                        
                        // Force terminal update
                        terminalSessionClient.onTextChanged(terminalSession!!)
                    }
                }
                
                Log.d(TAG, "Input sent successfully to SSH and echoed to terminal")
            } else {
                Log.w(TAG, "Cannot send input - Running: $isRunning, OutputStream null: ${sshOutputStream == null}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input to SSH", e)
        }
    }
    
    fun sendInput(text: String) {
        sendInput(text.toByteArray())
    }
    
    fun sendInputWithEcho(text: String) {
        try {
            if (isRunning && sshOutputStream != null) {
                Log.d(TAG, "Sending input with echo: $text")
                val data = text.toByteArray()
                sshOutputStream!!.write(data)
                sshOutputStream!!.flush()
                
                // Always echo for interactive commands
                if (terminalSession != null) {
                    terminalSession!!.emulator?.append(data, data.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input with echo to SSH", e)
        }
    }
    
    fun sendCommand(command: String) {
        try {
            if (isRunning && sshOutputStream != null) {
                Log.d(TAG, "Sending command: $command")
                val fullCommand = command + "\r\n"
                sshOutputStream!!.write(fullCommand.toByteArray())
                sshOutputStream!!.flush()
                Log.d(TAG, "Command sent successfully")
            } else {
                Log.w(TAG, "Cannot send command - SSH not running or output stream null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command to SSH", e)
        }
    }
    
    fun getConnectionInfo(): String {
        return "SSH Session: $sshSessionId, Running: $isRunning, Streams replaced: $streamsReplaced, Channel connected: ${shellChannel?.isConnected}, Input stream: ${sshInputStream != null}, Output stream: ${sshOutputStream != null}"
    }
    
    fun testInput() {
        Log.d(TAG, "Testing SSH input with 'ls' command")
        sendCommand("ls")
    }
    
    fun simulateTyping(text: String) {
        Log.d(TAG, "Simulating typing: '$text'")
        scope.launch {
            for (char in text) {
                if (isRunning) {
                    sendInput(char.toString())
                    kotlinx.coroutines.delay(100) // Simulate typing speed
                }
            }
        }
    }
    
    fun simulateCommand(command: String) {
        Log.d(TAG, "Simulating command: '$command'")
        scope.launch {
            simulateTyping(command)
            kotlinx.coroutines.delay(200)
            sendInput("\r\n") // Send Enter
        }
    }
    
    fun isReady(): Boolean {
        return isRunning && shellChannel?.isConnected == true && sshInputStream != null && sshOutputStream != null
    }
    
    private fun overrideSessionWrite(session: TerminalSession) {
        Log.d(TAG, "Overriding session write method for SSH")
        
        try {
            // Use reflection to override the write method
            val sessionClass = session.javaClass
            
            // Create a dynamic proxy for the TerminalSession
            val writeMethod = sessionClass.getMethod("write", String::class.java)
            Log.d(TAG, "Found write method: ${writeMethod.name}")
            
            // Store original method for potential fallback
            // Note: We'll intercept calls to write() via other mechanisms
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not override session write method", e)
        }
    }
    
    private fun setupDirectInputHandling() {
        Log.d(TAG, "Setting up direct input handling for SSH session")
        
        // Try to replace the terminal session's write method to intercept ALL input
        try {
            terminalSession?.let { session ->
                // Get the TerminalSession class
                val sessionClass = session.javaClass
                
                // Try to find and replace the mTerminalInput field with our proxy
                val possibleInputFields = listOf("mTerminalInput", "terminalInput", "mTerminalToProcessIOQueue")
                for (fieldName in possibleInputFields) {
                    try {
                        val field = sessionClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        
                        // Create a proxy OutputStream that forwards to SSH
                        val proxyOut = object : java.io.OutputStream() {
                            override fun write(b: Int) {
                                Log.d(TAG, "Direct input intercepted: ${b.toChar()}")
                                sendInput(byteArrayOf(b.toByte()))
                            }
                            override fun write(b: ByteArray) {
                                Log.d(TAG, "Direct input intercepted: ${String(b)}")
                                sendInput(b)
                            }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                Log.d(TAG, "Direct input intercepted: ${String(b, off, len)}")
                                sendInput(b.copyOfRange(off, off + len))
                            }
                            override fun flush() { /* no-op */ }
                            override fun close() { /* no-op */ }
                        }
                        
                        field.set(session, proxyOut)
                        Log.d(TAG, "Successfully replaced $fieldName with SSH proxy")
                        break
                    } catch (e: NoSuchFieldException) {
                        Log.v(TAG, "Field $fieldName not found")
                    }
                }
                
                Log.d(TAG, "Direct input handling setup completed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not setup direct input handling", e)
        }
    }
    
    fun resize(cols: Int, rows: Int) {
        try {
            shellChannel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize SSH terminal", e)
        }
    }
    
    fun stop() {
        isRunning = false
        cleanup()
    }
    
    private fun cleanup() {
        try {
            scope.cancel()
            
            shellChannel?.disconnect()
            shellChannel = null
            
            sshInputStream?.close()
            sshOutputStream?.close()
            
            sshInputStream = null
            sshOutputStream = null
            
            Log.d(TAG, "SSH terminal session cleanup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during SSH terminal cleanup", e)
        }
    }
    
    fun isConnected(): Boolean {
        return isRunning && shellChannel?.isConnected == true
    }
    
    fun getSession(): TerminalSession? {
        return terminalSession
    }
}