package com.rk.terminal.ssh

import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.OutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class SshInputInterceptor(
    private val terminalSession: TerminalSession,
    private val safeSshTerminal: SafeSshTerminal
) {
    companion object {
        private const val TAG = "SshInputInterceptor"
    }
    
    fun setupInputRedirection() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Setting up SSH input redirection...")
                
                // Wait for terminal session to initialize
                delay(500)
                
                // Don't kill the process, just redirect its streams
                Log.d(TAG, "Setting up SSH stream redirection without killing process")
                
                // Use reflection to replace the process streams
                replaceProcessStreams()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up input redirection", e)
            }
        }
    }
    
    private fun replaceProcessStreams() {
        try {
            val sessionClass = terminalSession.javaClass
            val processField = sessionClass.getDeclaredField("mProcess")
            processField.isAccessible = true
            val process = processField.get(terminalSession)
            
            if (process != null) {
                val processClass = process.javaClass
                
                // Replace output stream (terminal → SSH)
                try {
                    val outputStreamField = processClass.getDeclaredField("mOutputStream")
                    outputStreamField.isAccessible = true
                    outputStreamField.set(process, SshRedirectOutputStream())
                    Log.d(TAG, "Replaced process output stream with SSH redirect")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not replace output stream: ${e.message}")
                }
                
                // Replace input stream (SSH → terminal) - create a dummy input
                try {
                    val inputStreamField = processClass.getDeclaredField("mInputStream")
                    inputStreamField.isAccessible = true
                    inputStreamField.set(process, DummyInputStream())
                    Log.d(TAG, "Replaced process input stream with dummy")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not replace input stream: ${e.message}")
                }
                
            } else {
                Log.w(TAG, "No process found to replace streams")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing process streams", e)
        }
    }
    
    private inner class SshRedirectOutputStream : OutputStream() {
        override fun write(b: Int) {
            val data = byteArrayOf(b.toByte())
            safeSshTerminal.writeToSsh(data, 0, 1)
            Log.d(TAG, "Redirected byte to SSH: ${b.toChar()}")
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            safeSshTerminal.writeToSsh(b, off, len)
            val text = String(b, off, len)
            Log.d(TAG, "Redirected to SSH: $text")
        }
        
        override fun flush() {
            // SSH handles flushing internally
        }
        
        override fun close() {
            // Don't close SSH connection
        }
    }
    
    private class DummyInputStream : InputStream() {
        override fun read(): Int {
            // Return EOF to prevent local process from reading
            return -1
        }
        
        override fun available(): Int = 0
    }
}