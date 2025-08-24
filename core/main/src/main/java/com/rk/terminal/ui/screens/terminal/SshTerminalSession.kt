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
                ?: return@withContext Result.failure(Exception("Failed to create SSH shell channel"))
            
            // Connect SSH channel streams
            sshInputStream = shellChannel!!.inputStream
            sshOutputStream = shellChannel!!.outputStream
            // Note: JSch ChannelShell doesn't have setErrStream, errors are mixed with output
            
            // Configure shell channel with PTY
            shellChannel!!.setPty(true)
            shellChannel!!.setPtyType("xterm-256color")
            shellChannel!!.setPtySize(80, 24, 640, 480) // cols, rows, width, height
            
            // Connect the shell channel
            shellChannel!!.connect()

            // Create an inert local terminal session on the main thread
            val created = withContext(Dispatchers.Main) {
                try {
                    TerminalSession(
                        "/system/bin/sh",
                        "/",
                        arrayOf(
                            "-c",
                            // Silence local output and keep process alive indefinitely
                            "exec >/dev/null 2>&1; while true; do sleep 3600; done"
                        ),
                        arrayOf("TERM=xterm-256color"),
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        terminalSessionClient
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (created == null) {
                return@withContext Result.failure(Exception("Failed to create TerminalSession"))
            }

            terminalSession = created

            // Start output forwarding from SSH to terminal emulator
            isRunning = true
            startIoForwarding()
            
            Log.d(TAG, "SSH terminal session started successfully")
            Result.success(terminalSession!!)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSH terminal session", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    private fun replaceTerminalStreams() { /* no-op with inert session */ }
    
    private fun startIoForwarding() {
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