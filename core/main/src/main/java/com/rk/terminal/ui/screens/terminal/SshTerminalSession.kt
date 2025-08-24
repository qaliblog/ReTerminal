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

            // Create a local terminal session used only as a UI container. We'll replace its I/O streams.
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

            // Replace output stream from terminal to SSH (keyboard -> SSH)
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
                override fun close() { sshOut.flush(); /* don't close SSH out here */ }
            }
            mTerminalInputField.set(terminalSession, proxyOut)

            // Replace input stream from SSH to terminal (SSH -> screen)
            val mTerminalOutputField = terminalSessionClass.getDeclaredField("mTerminalOutput")
            mTerminalOutputField.isAccessible = true
            mTerminalOutputField.set(terminalSession, sshInputStream)

            streamsReplaced = true
            Log.d(TAG, "Replaced TerminalSession I/O streams with SSH channel streams")
        } catch (e: Exception) {
            streamsReplaced = false
            Log.w(TAG, "Failed to replace TerminalSession streams; interactive I/O may be limited", e)
        }
    }
    
    private fun startIoForwarding() {
        // Forward output from SSH to terminal emulator if reflection replacement failed
        scope.launch {
            try {
                val buffer = ByteArray(4096)
                while (isRunning && sshInputStream != null) {
                    val bytesRead = sshInputStream!!.read(buffer)
                    if (bytesRead > 0) {
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