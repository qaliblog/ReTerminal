package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.reflect.Field

object SshTerminalBridge {
    private const val TAG = "SshTerminalBridge"
    
    fun interceptTerminalInput(terminalSession: TerminalSession, sshTerminal: SimpleSshTerminal) {
        try {
            // Use reflection to intercept the terminal session's process input
            val sessionClass = terminalSession.javaClass
            val processField = sessionClass.getDeclaredField("mProcess")
            processField.isAccessible = true
            val process = processField.get(terminalSession)
            
            if (process != null) {
                val processClass = process.javaClass
                val outputStreamField = processClass.getDeclaredField("mOutputStream")
                outputStreamField.isAccessible = true
                
                // Get the original output stream
                val originalOutputStream = outputStreamField.get(process)
                
                // Create a proxy output stream that redirects to SSH
                val sshProxyStream = SshProxyOutputStream(sshTerminal, originalOutputStream)
                outputStreamField.set(process, sshProxyStream)
                
                Log.d(TAG, "Successfully intercepted terminal input for SSH redirection")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not intercept terminal input: ${e.message}")
            // Fallback: manual input handling
        }
    }
    
    private class SshProxyOutputStream(
        private val sshTerminal: SimpleSshTerminal,
        private val originalStream: Any?
    ) : java.io.OutputStream() {
        
        override fun write(b: Int) {
            if (sshTerminal.isConnected()) {
                sshTerminal.writeToSsh(byteArrayOf(b.toByte()), 0, 1)
            } else {
                // Fallback to original stream if available
                try {
                    if (originalStream is java.io.OutputStream) {
                        originalStream.write(b)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to original stream", e)
                }
            }
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (sshTerminal.isConnected()) {
                sshTerminal.writeToSsh(b, off, len)
            } else {
                // Fallback to original stream if available
                try {
                    if (originalStream is java.io.OutputStream) {
                        originalStream.write(b, off, len)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing bytes to original stream", e)
                }
            }
        }
        
        override fun flush() {
            // SSH terminal handles flushing internally
            try {
                if (originalStream is java.io.OutputStream) {
                    originalStream.flush()
                }
            } catch (e: Exception) {
                // Ignore flush errors
            }
        }
        
        override fun close() {
            // Don't close SSH stream
            try {
                if (originalStream is java.io.OutputStream) {
                    originalStream.close()
                }
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }
}