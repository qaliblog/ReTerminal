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
    private var inputStream: PipedInputStream? = null
    private var outputStream: PipedOutputStream? = null
    private var errorStream: PipedInputStream? = null
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
                ?: return@withContext Result.failure(Exception("Failed to create SSH shell channel"))
            
            // Create piped streams for terminal communication
            inputStream = PipedInputStream()
            outputStream = PipedOutputStream(inputStream!!)
            errorStream = PipedInputStream()
            
            // Connect SSH channel streams
            sshInputStream = shellChannel!!.inputStream
            sshOutputStream = shellChannel!!.outputStream
            // Note: JSch ChannelShell doesn't have setErrStream, errors are mixed with output
            
            // Configure shell channel
            shellChannel!!.setPtyType("xterm-256color")
            shellChannel!!.setPtySize(80, 24, 640, 480) // cols, rows, width, height
            
            // Connect the shell channel
            shellChannel!!.connect()
            
            // Create terminal session
            terminalSession = TerminalSession(
                "/system/bin/sh", // This won't be used since we override the streams
                "/", // Working directory (not used)
                arrayOf(), // Args (not used)
                arrayOf("TERM=xterm-256color"), // Environment
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                terminalSessionClient
            )
            
            // Replace terminal session streams with our SSH streams
            replaceTerminalStreams()
            
            isRunning = true
            
            // If reflection-based stream replacement failed, fall back to manual I/O forwarding
            if (!streamsReplaced) {
                startIoForwarding()
            }
            
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

            // Replace output stream from terminal to SSH
            val mTerminalInputField = terminalSessionClass.getDeclaredField("mTerminalInput")
            mTerminalInputField.isAccessible = true
            val sshOut = sshOutputStream!!
            val proxyOut = object : OutputStream() {
                override fun write(b: Int) {
                    sshOut.write(b)
                    sshOut.flush()
                }
                override fun write(b: ByteArray) {
                    sshOut.write(b)
                    sshOut.flush()
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    sshOut.write(b, off, len)
                    sshOut.flush()
                }
                override fun flush() { sshOut.flush() }
                override fun close() { sshOut.close() }
            }
            mTerminalInputField.set(terminalSession, proxyOut)

            // Replace input stream from SSH to terminal
            val mTerminalOutputField = terminalSessionClass.getDeclaredField("mTerminalOutput")
            mTerminalOutputField.isAccessible = true
            mTerminalOutputField.set(terminalSession, sshInputStream)

            streamsReplaced = true
            Log.d(TAG, "Successfully replaced TerminalSession streams with SSH streams")
        } catch (e: Exception) {
            streamsReplaced = false
            Log.w(TAG, "Could not replace terminal streams directly, using I/O forwarding", e)
        }
    }
    
    private fun startIoForwarding() {
        // Forward input from terminal to SSH
        scope.launch {
            try {
                val buffer = ByteArray(1024)
                while (isRunning && inputStream != null && sshOutputStream != null) {
                    val bytesRead = inputStream!!.read(buffer)
                    if (bytesRead > 0) {
                        sshOutputStream!!.write(buffer, 0, bytesRead)
                        sshOutputStream!!.flush()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error forwarding input to SSH", e)
                }
            }
        }
        
        // Forward output from SSH to terminal
        scope.launch {
            try {
                val buffer = ByteArray(1024)
                while (isRunning && sshInputStream != null) {
                    val bytesRead = sshInputStream!!.read(buffer)
                    if (bytesRead > 0) {
                        // Write to terminal emulator
                        terminalSession?.emulator?.append(buffer, bytesRead)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error forwarding output from SSH", e)
                }
            }
        }
    }
    
    fun sendInput(data: ByteArray) {
        try {
            if (isRunning && sshOutputStream != null) {
                sshOutputStream!!.write(data)
                sshOutputStream!!.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input to SSH", e)
        }
    }
    
    fun sendInput(text: String) {
        sendInput(text.toByteArray())
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
            
            inputStream?.close()
            outputStream?.close()
            errorStream?.close()
            
            sshInputStream?.close()
            sshOutputStream?.close()
            
            inputStream = null
            outputStream = null
            errorStream = null
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