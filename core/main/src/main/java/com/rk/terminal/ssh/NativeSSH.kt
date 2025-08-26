package com.rk.terminal.ssh

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native SSH implementation using libssh2
 * Provides robust SSH connectivity with direct terminal control
 */
class NativeSSH {
    
    companion object {
        private const val TAG = "NativeSSH"
        
        init {
            try {
                System.loadLibrary("reterminal_ssh")
                Log.i(TAG, "Native SSH library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native SSH library", e)
                throw RuntimeException("Failed to load native SSH library", e)
            }
        }
    }
    
    // Native handle
    private val nativeHandle = AtomicLong(0)
    private val isInitialized = AtomicBoolean(false)
    private val outputScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback for receiving output data
    var onOutputReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING, 
        AUTHENTICATING,
        CONNECTED,
        ERROR,
        TERMINATED
    }
    
    data class ConnectionConfig(
        val hostname: String,
        val port: Int = 22,
        val username: String,
        val password: String = "",
        val privateKeyPath: String = "",
        val useKeyAuth: Boolean = false,
        val timeoutSeconds: Int = 15
    )
    
    data class TerminalConfig(
        val cols: Int = 80,
        val rows: Int = 24,
        val termType: String = "xterm-256color"
    )
    
    init {
        val handle = nativeCreate()
        if (handle != 0L) {
            nativeHandle.set(handle)
            isInitialized.set(true)
            Log.d(TAG, "Native SSH instance created with handle: $handle")
        } else {
            Log.e(TAG, "Failed to create native SSH instance")
            throw RuntimeException("Failed to create native SSH instance")
        }
    }
    
    /**
     * Connect to SSH server
     */
    suspend fun connect(config: ConnectionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to ${config.username}@${config.hostname}:${config.port}")
            
            if (!isInitialized.get()) {
                return@withContext Result.failure(Exception("Native SSH not initialized"))
            }
            
            val success = nativeConnect(
                nativeHandle.get(),
                config.hostname,
                config.port,
                config.username,
                config.password,
                config.privateKeyPath,
                config.useKeyAuth,
                config.timeoutSeconds
            )
            
            if (success) {
                Log.i(TAG, "SSH connection successful")
                onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
                Result.success(Unit)
            } else {
                val error = nativeGetLastError(nativeHandle.get())
                Log.e(TAG, "SSH connection failed: $error")
                onConnectionStateChanged?.invoke(ConnectionState.ERROR)
                onError?.invoke(error)
                Result.failure(Exception("SSH connection failed: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            onConnectionStateChanged?.invoke(ConnectionState.ERROR)
            onError?.invoke(e.message ?: "Unknown connection error")
            Result.failure(e)
        }
    }
    
    /**
     * Create shell with terminal
     */
    suspend fun createShell(config: TerminalConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Creating shell ${config.cols}x${config.rows}")
            
            if (!isConnected()) {
                return@withContext Result.failure(Exception("Not connected to SSH server"))
            }
            
            val success = nativeCreateShell(
                nativeHandle.get(),
                config.cols,
                config.rows,
                config.termType
            )
            
            if (success) {
                Log.i(TAG, "SSH shell created successfully")
                startOutputPolling()
                Result.success(Unit)
            } else {
                val error = nativeGetLastError(nativeHandle.get())
                Log.e(TAG, "Failed to create shell: $error")
                onError?.invoke(error)
                Result.failure(Exception("Failed to create shell: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell creation error", e)
            onError?.invoke(e.message ?: "Unknown shell error")
            Result.failure(e)
        }
    }
    
    /**
     * Send input to SSH session
     */
    fun sendInput(data: ByteArray): Boolean {
        return try {
            if (!isConnected()) {
                Log.w(TAG, "Cannot send input - not connected")
                return false
            }
            
            Log.d(TAG, "Sending input: ${data.size} bytes")
            val success = nativeSendInput(nativeHandle.get(), data)
            
            if (!success) {
                Log.e(TAG, "Failed to send input")
                val error = nativeGetLastError(nativeHandle.get())
                onError?.invoke("Input send failed: $error")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending input", e)
            onError?.invoke("Input error: ${e.message}")
            false
        }
    }
    
    /**
     * Send input as string
     */
    fun sendInput(text: String): Boolean {
        return sendInput(text.toByteArray())
    }
    
    /**
     * Resize terminal
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        try {
            if (isConnected()) {
                Log.d(TAG, "Resizing terminal to ${cols}x${rows}")
                nativeResize(nativeHandle.get(), cols, rows)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resizing terminal", e)
        }
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return try {
            isInitialized.get() && nativeIsConnected(nativeHandle.get())
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connection status", e)
            false
        }
    }
    
    /**
     * Get last error
     */
    fun getLastError(): String {
        return try {
            if (isInitialized.get()) {
                nativeGetLastError(nativeHandle.get())
            } else {
                "Native SSH not initialized"
            }
        } catch (e: Exception) {
            "Error getting last error: ${e.message}"
        }
    }
    
    /**
     * Disconnect from SSH server
     */
    fun disconnect() {
        try {
            Log.i(TAG, "Disconnecting SSH session")
            
            // Cancel output polling
            outputScope.cancel()
            
            if (isInitialized.get()) {
                nativeDisconnect(nativeHandle.get())
            }
            
            onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
            Log.i(TAG, "SSH session disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
    
    /**
     * Cleanup native resources
     */
    fun destroy() {
        try {
            Log.i(TAG, "Destroying native SSH instance")
            
            disconnect()
            outputScope.cancel()
            
            if (isInitialized.get()) {
                nativeDestroy(nativeHandle.get())
                nativeHandle.set(0)
                isInitialized.set(false)
            }
            
            Log.i(TAG, "Native SSH instance destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during destroy", e)
        }
    }
    
    /**
     * Start polling for output data
     */
    private fun startOutputPolling() {
        outputScope.launch {
            Log.d(TAG, "Starting output polling")
            
            try {
                while (isActive && isConnected()) {
                    if (nativeHasOutput(nativeHandle.get())) {
                        val outputData = nativeReadOutput(nativeHandle.get())
                        if (outputData.isNotEmpty()) {
                            Log.d(TAG, "Received output: ${outputData.size} bytes")
                            onOutputReceived?.invoke(outputData)
                        }
                    } else {
                        // No output available, small delay
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Output polling error", e)
                    onError?.invoke("Output polling error: ${e.message}")
                }
            }
            
            Log.d(TAG, "Output polling ended")
        }
    }
    
    // Native method declarations
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConnect(
        handle: Long,
        hostname: String,
        port: Int,
        username: String,
        password: String,
        keyPath: String,
        useKey: Boolean,
        timeout: Int
    ): Boolean
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeCreateShell(
        handle: Long,
        cols: Int,
        rows: Int,
        termType: String
    ): Boolean
    private external fun nativeSendInput(handle: Long, data: ByteArray): Boolean
    private external fun nativeReadOutput(handle: Long): ByteArray
    private external fun nativeHasOutput(handle: Long): Boolean
    private external fun nativeIsConnected(handle: Long): Boolean
    private external fun nativeGetLastError(handle: Long): String
    private external fun nativeResize(handle: Long, cols: Int, rows: Int)
}