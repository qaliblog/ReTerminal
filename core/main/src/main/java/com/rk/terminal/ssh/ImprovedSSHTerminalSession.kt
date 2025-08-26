package com.rk.terminal.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Improved SSH Terminal Session that fixes input/output issues
 * This is a temporary solution while the native implementation is being built
 */
class ImprovedSSHTerminalSession(
    private val sshSessionId: String,
    private val terminalSessionClient: TerminalSessionClient
) {
    private val sshManager = com.rk.terminal.ui.screens.terminal.SshManager.getInstance()
    private var shellChannel: ChannelShell? = null
    private var terminalSession: TerminalSession? = null
    private var sshInputStream: InputStream? = null
    private var sshOutputStream: OutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Input/Output queues for better handling
    private val inputQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val outputQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    
    companion object {
        private const val TAG = "ImprovedSSHTerminalSession"
    }
    
    suspend fun start(): Result<TerminalSession> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting improved SSH terminal session: $sshSessionId")
            
            // Get shell channel from SSH manager
            shellChannel = sshManager.getShellChannel(sshSessionId)
            if (shellChannel == null) {
                Log.e(TAG, "Failed to create SSH shell channel for session: $sshSessionId")
                return@withContext Result.failure(Exception("Failed to create SSH shell channel"))
            }
            
            Log.d(TAG, "SSH shell channel created successfully")
            
            // Configure shell channel with better settings
            shellChannel!!.setPty(true)
            shellChannel!!.setPtyType("xterm-256color")
            shellChannel!!.setPtySize(80, 24, 640, 480)
            
            // Set environment variables for better compatibility
            try {
                shellChannel!!.setEnv("TERM", "xterm-256color")
                shellChannel!!.setEnv("LANG", "en_US.UTF-8")
                shellChannel!!.setEnv("LC_ALL", "en_US.UTF-8")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set some environment variables", e)
            }
            
            // Connect the shell channel
            shellChannel!!.connect()
            
            // Get streams after connection
            sshInputStream = shellChannel!!.inputStream
            sshOutputStream = shellChannel!!.outputStream
            
            if (sshInputStream == null || sshOutputStream == null) {
                Log.e(TAG, "SSH streams are null after channel connection")
                return@withContext Result.failure(Exception("SSH streams are null"))
            }
            
            Log.d(TAG, "SSH streams obtained successfully")

            // Create a local terminal session
            val created = withContext(Dispatchers.Main) {
                try {
                    TerminalSession(
                        "/system/bin/sh",
                        "/",
                        arrayOf(),
                        arrayOf("TERM=xterm-256color"),
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        terminalSessionClient
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create TerminalSession", e)
                    null
                }
            }

            if (created == null) {
                return@withContext Result.failure(Exception("Failed to create TerminalSession"))
            }

            terminalSession = created
            isRunning.set(true)
            
            // Start improved I/O handling
            startImprovedIOHandling()
            
            // Initialize terminal properly
            initializeTerminal()
            
            Log.d(TAG, "Improved SSH terminal session started successfully")
            Result.success(terminalSession!!)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start improved SSH terminal session", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    private fun startImprovedIOHandling() {
        Log.d(TAG, "Starting improved I/O handling")
        
        // Input processing thread
        scope.launch {
            try {
                while (isRunning.get()) {
                    val inputData = inputQueue.poll()
                    if (inputData != null && sshOutputStream != null) {
                        try {
                            sshOutputStream!!.write(inputData)
                            sshOutputStream!!.flush()
                            Log.v(TAG, "Sent input: ${inputData.size} bytes")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending input", e)
                            if (!isRunning.get()) break
                        }
                    } else {
                        delay(10) // Small delay to prevent busy waiting
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Input processing thread error", e)
                }
            }
            Log.d(TAG, "Input processing thread ended")
        }
        
        // Output processing thread
        scope.launch {
            try {
                val buffer = ByteArray(1024)
                while (isRunning.get() && sshInputStream != null && shellChannel?.isConnected == true) {
                    try {
                        val available = sshInputStream!!.available()
                        if (available > 0) {
                            val bytesRead = sshInputStream!!.read(buffer, 0, minOf(available, buffer.size))
                            if (bytesRead > 0) {
                                val output = buffer.copyOfRange(0, bytesRead)
                                Log.v(TAG, "SSH output received: ${bytesRead} bytes")
                                
                                // Forward to terminal emulator on main thread
                                withContext(Dispatchers.Main) {
                                    terminalSession?.emulator?.append(output, bytesRead)
                                    terminalSessionClient.onTextChanged(terminalSession!!)
                                }
                            }
                        } else {
                            delay(10) // No data available, small delay
                        }
                    } catch (e: IOException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "SSH input stream error", e)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Output processing thread error", e)
                }
            }
            Log.d(TAG, "Output processing thread ended")
        }
    }
    
    private suspend fun initializeTerminal() {
        try {
            Log.d(TAG, "Initializing SSH terminal environment")
            
            // Wait for connection to stabilize
            delay(1000)
            
            // Send initialization commands one by one with delays
            val initCommands = listOf(
                "stty sane",
                "export TERM=xterm-256color", 
                "stty echo icanon",
                "export PS1='\\u@\\h:\\w\\$ '"
            )
            
            for (command in initCommands) {
                if (isRunning.get()) {
                    sendCommandDirect(command)
                    delay(300) // Delay between commands
                }
            }
            
            // Send welcome message
            delay(500)
            sendCommandDirect("echo 'SSH connection ready - type commands:'")
            
            isInitialized.set(true)
            Log.d(TAG, "SSH terminal initialization completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SSH terminal", e)
        }
    }
    
    private suspend fun sendCommandDirect(command: String) {
        try {
            if (isRunning.get() && sshOutputStream != null) {
                val fullCommand = "$command\r\n"
                sshOutputStream!!.write(fullCommand.toByteArray())
                sshOutputStream!!.flush()
                Log.d(TAG, "Sent command directly: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command directly: $command", e)
        }
    }
    
    fun sendInput(data: ByteArray) {
        try {
            if (!isRunning.get()) {
                Log.w(TAG, "Cannot send input - session not running")
                return
            }
            
            val inputStr = String(data)
            Log.d(TAG, "Queuing input: '${inputStr.replace('\r', '↵').replace('\n', '⏎')}' (${data.size} bytes)")
            
            // Add to input queue for processing
            inputQueue.offer(data)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue input", e)
        }
    }
    
    fun sendInput(text: String) {
        sendInput(text.toByteArray())
    }
    
    fun sendCommand(command: String) {
        try {
            if (!isRunning.get()) {
                Log.w(TAG, "Cannot send command - session not running")
                return
            }
            
            Log.d(TAG, "Sending command: $command")
            val fullCommand = "$command\r\n"
            inputQueue.offer(fullCommand.toByteArray())
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
        }
    }
    
    fun getConnectionInfo(): String {
        return "Improved SSH Session: $sshSessionId, Running: ${isRunning.get()}, Initialized: ${isInitialized.get()}, Channel connected: ${shellChannel?.isConnected}, Streams: ${sshInputStream != null && sshOutputStream != null}"
    }
    
    fun resize(cols: Int, rows: Int) {
        try {
            if (shellChannel?.isConnected == true) {
                shellChannel!!.setPtySize(cols, rows, cols * 8, rows * 16)
                Log.d(TAG, "Terminal resized to ${cols}x${rows}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize SSH terminal", e)
        }
    }
    
    fun isReady(): Boolean {
        return isRunning.get() && isInitialized.get() && shellChannel?.isConnected == true
    }
    
    fun isConnected(): Boolean {
        return isRunning.get() && shellChannel?.isConnected == true
    }
    
    fun getSession(): TerminalSession? {
        return terminalSession
    }
    
    fun stop() {
        isRunning.set(false)
        cleanup()
    }
    
    private fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up improved SSH terminal session")
            
            isRunning.set(false)
            isInitialized.set(false)
            
            scope.cancel()
            
            // Clear queues
            inputQueue.clear()
            outputQueue.clear()
            
            shellChannel?.disconnect()
            shellChannel = null
            
            sshInputStream?.close()
            sshOutputStream?.close()
            
            sshInputStream = null
            sshOutputStream = null
            
            Log.d(TAG, "Improved SSH terminal session cleanup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during improved SSH terminal cleanup", e)
        }
    }
}