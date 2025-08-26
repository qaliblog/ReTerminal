package com.rk.terminal.ssh

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager class that integrates native SSH with ReTerminal architecture
 * Provides compatibility layer between new native SSH and existing JSch-based code
 */
class NativeSSHManager private constructor() {
    
    companion object {
        private const val TAG = "NativeSSHManager"
        
        @Volatile
        private var INSTANCE: NativeSSHManager? = null
        
        fun getInstance(): NativeSSHManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NativeSSHManager().also { INSTANCE = it }
            }
        }
    }
    
    // Session management
    private val activeSessions = ConcurrentHashMap<String, NativeSSH>()
    private val sessionViews = ConcurrentHashMap<String, NativeSSHTerminalView>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // State
    val isConnected = mutableStateOf(false)
    val currentSession = mutableStateOf<String?>(null)
    val connectionStatus = mutableStateOf("Disconnected")
    
    // Connection callbacks
    var onConnectionStateChanged: ((String, NativeSSH.ConnectionState) -> Unit)? = null
    var onConnectionError: ((String, String) -> Unit)? = null
    
    /**
     * Create a new native SSH session
     */
    suspend fun createSession(
        sessionId: String,
        config: NativeSSH.ConnectionConfig,
        context: Context? = null
    ): Result<String> {
        return try {
            Log.i(TAG, "Creating native SSH session: $sessionId")
            
            if (activeSessions.containsKey(sessionId)) {
                Log.w(TAG, "Session $sessionId already exists")
                return Result.failure(Exception("Session already exists"))
            }
            
            connectionStatus.value = "Connecting..."
            
            // Create native SSH instance
            val nativeSSH = NativeSSH().apply {
                onConnectionStateChanged = { state ->
                    scope.launch {
                        handleConnectionStateChange(sessionId, state)
                    }
                }
                onError = { error ->
                    scope.launch {
                        handleConnectionError(sessionId, error)
                    }
                }
            }
            
            // Connect to SSH server
            val connectResult = nativeSSH.connect(config)
            if (connectResult.isFailure) {
                nativeSSH.destroy()
                connectionStatus.value = "Connection failed"
                return connectResult.map { sessionId }
            }
            
            // Create shell
            val terminalConfig = NativeSSH.TerminalConfig(
                cols = 80,
                rows = 24,
                termType = "xterm-256color"
            )
            
            val shellResult = nativeSSH.createShell(terminalConfig)
            if (shellResult.isFailure) {
                nativeSSH.destroy()
                connectionStatus.value = "Shell creation failed"
                return shellResult.map { sessionId }
            }
            
            // Store session
            activeSessions[sessionId] = nativeSSH
            currentSession.value = sessionId
            isConnected.value = true
            connectionStatus.value = "Connected to ${config.hostname}"
            
            Log.i(TAG, "Native SSH session created successfully: $sessionId")
            Result.success(sessionId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSH session: $sessionId", e)
            connectionStatus.value = "Connection error"
            Result.failure(e)
        }
    }
    
    /**
     * Create a terminal view for a session
     */
    fun createTerminalView(context: Context, sessionId: String): NativeSSHTerminalView? {
        val session = activeSessions[sessionId] ?: return null
        
        val terminalView = NativeSSHTerminalView(context).apply {
            onConnectionStateChanged = { state ->
                scope.launch {
                    handleConnectionStateChange(sessionId, state)
                }
            }
            onError = { error ->
                scope.launch {
                    handleConnectionError(sessionId, error)
                }
            }
        }
        
        sessionViews[sessionId] = terminalView
        Log.d(TAG, "Terminal view created for session: $sessionId")
        
        return terminalView
    }
    
    /**
     * Connect a terminal view to an existing session
     */
    suspend fun connectTerminalView(
        terminalView: NativeSSHTerminalView,
        config: NativeSSH.ConnectionConfig
    ): Result<Unit> {
        return terminalView.connectSSH(config)
    }
    
    /**
     * Send input to a specific session
     */
    fun sendInput(sessionId: String, data: ByteArray): Boolean {
        val session = activeSessions[sessionId]
        if (session?.isConnected() == true) {
            return session.sendInput(data)
        }
        Log.w(TAG, "Cannot send input to session $sessionId - not connected")
        return false
    }
    
    /**
     * Send input as string
     */
    fun sendInput(sessionId: String, text: String): Boolean {
        return sendInput(sessionId, text.toByteArray())
    }
    
    /**
     * Resize terminal for a session
     */
    fun resizeTerminal(sessionId: String, cols: Int, rows: Int) {
        activeSessions[sessionId]?.resizeTerminal(cols, rows)
        sessionViews[sessionId]?.let { view ->
            // Trigger view resize if needed
            view.post { view.requestLayout() }
        }
    }
    
    /**
     * Disconnect a specific session
     */
    fun disconnectSession(sessionId: String) {
        Log.i(TAG, "Disconnecting session: $sessionId")
        
        activeSessions[sessionId]?.let { session ->
            session.disconnect()
            session.destroy()
        }
        
        sessionViews[sessionId]?.disconnectSSH()
        
        activeSessions.remove(sessionId)
        sessionViews.remove(sessionId)
        
        // Update global state if this was the current session
        if (currentSession.value == sessionId) {
            if (activeSessions.isEmpty()) {
                currentSession.value = null
                isConnected.value = false
                connectionStatus.value = "Disconnected"
            } else {
                // Switch to another active session
                currentSession.value = activeSessions.keys.first()
            }
        }
        
        Log.i(TAG, "Session disconnected: $sessionId")
    }
    
    /**
     * Disconnect all sessions
     */
    fun disconnectAll() {
        Log.i(TAG, "Disconnecting all SSH sessions")
        
        val sessionIds = activeSessions.keys.toList()
        sessionIds.forEach { sessionId ->
            disconnectSession(sessionId)
        }
        
        currentSession.value = null
        isConnected.value = false
        connectionStatus.value = "Disconnected"
    }
    
    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): NativeSSH? {
        return activeSessions[sessionId]
    }
    
    /**
     * Get terminal view by session ID
     */
    fun getTerminalView(sessionId: String): NativeSSHTerminalView? {
        return sessionViews[sessionId]
    }
    
    /**
     * Check if a session is connected
     */
    fun isSessionConnected(sessionId: String): Boolean {
        return activeSessions[sessionId]?.isConnected() == true
    }
    
    /**
     * Get all active session IDs
     */
    fun getActiveSessionIds(): List<String> {
        return activeSessions.keys.toList()
    }
    
    /**
     * Get connection info for a session
     */
    fun getConnectionInfo(sessionId: String): String {
        val session = activeSessions[sessionId]
        return if (session != null) {
            "Session: $sessionId, Connected: ${session.isConnected()}"
        } else {
            "Session not found: $sessionId"
        }
    }
    
    /**
     * Compatibility method for existing JSch-based code
     */
    fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String
    ): suspend () -> Result<String> = {
        try {
            val config = NativeSSH.ConnectionConfig(
                hostname = host,
                port = port,
                username = username,
                password = password,
                timeoutSeconds = 10
            )
            
            // Create a temporary session for testing
            val testSSH = NativeSSH()
            val result = testSSH.connect(config)
            
            testSSH.destroy()
            
            if (result.isSuccess) {
                Result.success("✓ Authentication successful! Credentials are valid.")
            } else {
                Result.failure(Exception("✗ Authentication test failed: ${testSSH.getLastError()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("✗ Connection test failed: ${e.message}"))
        }
    }
    
    /**
     * Handle connection state changes
     */
    private fun handleConnectionStateChange(sessionId: String, state: NativeSSH.ConnectionState) {
        Log.d(TAG, "Session $sessionId state changed to: $state")
        
        when (state) {
            NativeSSH.ConnectionState.CONNECTED -> {
                connectionStatus.value = "Connected"
                isConnected.value = true
            }
            NativeSSH.ConnectionState.DISCONNECTED -> {
                if (currentSession.value == sessionId) {
                    connectionStatus.value = "Disconnected"
                    isConnected.value = activeSessions.any { it.value.isConnected() }
                }
            }
            NativeSSH.ConnectionState.ERROR -> {
                connectionStatus.value = "Connection error"
            }
            else -> {
                connectionStatus.value = state.name.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        
        onConnectionStateChanged?.invoke(sessionId, state)
    }
    
    /**
     * Handle connection errors
     */
    private fun handleConnectionError(sessionId: String, error: String) {
        Log.e(TAG, "Session $sessionId error: $error")
        onConnectionError?.invoke(sessionId, error)
    }
    
    /**
     * Cleanup all resources
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up NativeSSHManager")
        
        disconnectAll()
        scope.cancel()
        
        // Clear callbacks
        onConnectionStateChanged = null
        onConnectionError = null
    }
}