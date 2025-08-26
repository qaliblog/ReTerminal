package com.rk.terminal.ui.screens.terminal

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.*
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class SshManager {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sftpChannels = ConcurrentHashMap<String, ChannelSftp>()
    private val execChannels = ConcurrentHashMap<String, ChannelExec>()
    private val shellChannels = ConcurrentHashMap<String, ChannelShell>()
    
    val isConnected = mutableStateOf(false)
    val currentSession = mutableStateOf<String?>(null)
    val connectionStatus = mutableStateOf("Disconnected")
    
    companion object {
        private const val TAG = "SshManager"
        private var instance: SshManager? = null
        
        fun getInstance(): SshManager {
            if (instance == null) {
                instance = SshManager()
            }
            return instance!!
        }
        
        // Simple test function to verify JSch is working
            fun testJSchLibrary(): String {
        return try {
            val jsch = JSch()
            "JSch library loaded successfully"
        } catch (e: Exception) {
            "JSch library error: ${e.message}"
        }
    }
    
    private fun getJSchInfo(): String {
        return try {
            val jsch = JSch()
            "JSch library available"
        } catch (e: Exception) {
            "JSch library error: ${e.message}"
        }
    }
        
        // Simple connection test
        suspend fun testConnection(host: String, port: Int, username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing connection to $username@$host:$port")
                Log.d(TAG, "Password provided: ${password.isNotEmpty()}, length: ${password.length}")
                
                val jsch = JSch()
                val session = jsch.getSession(username, host, port)
                session.setPassword(password)
                
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                config["UserKnownHostsFile"] = "/dev/null"
                config["PreferredAuthentications"] = "password,keyboard-interactive"
                config["PasswordAuthentication"] = "yes"
                config["KbdInteractiveAuthentication"] = "yes"
                config["PubkeyAuthentication"] = "no"
                session.setConfig(config)
                
                Log.d(TAG, "Attempting test connection with enhanced auth settings...")
                session.connect(10000) // 10 second timeout for test
                
                val result = if (session.isConnected) {
                    "✓ Authentication successful! Credentials are valid."
                } else {
                    "✗ Connection failed - session not connected"
                }
                
                session.disconnect()
                Log.d(TAG, result)
                Result.success(result)
            } catch (e: Exception) {
                val error = "✗ Auth test failed: ${e.message}"
                Log.e(TAG, error, e)
                
                // Provide specific authentication guidance
                val specificError = when {
                    e.message?.contains("Auth fail") == true -> 
                        "Authentication failed - verify username/password are exactly correct"
                    e.message?.contains("Connection refused") == true -> 
                        "Connection refused - check if SSH server is running on port $port"
                    e.message?.contains("timeout") == true -> 
                        "Connection timeout - check network connectivity to $host"
                    else -> "Connection error: ${e.message}"
                }
                
                Result.failure(Exception(specificError))
            }
        }
    }
    
    suspend fun connect(config: SshConnectionConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting SSH connection to ${config.username}@${config.host}:${config.port}")
            connectionStatus.value = "Connecting..."
            
            val jsch = JSch()
            
            // Add private key if using key authentication
            if (config.useKey && config.privateKeyPath.isNotBlank()) {
                Log.d(TAG, "Using SSH key authentication: ${config.privateKeyPath}")
                if (File(config.privateKeyPath).exists()) {
                    jsch.addIdentity(config.privateKeyPath)
                } else {
                    Log.e(TAG, "Private key file not found: ${config.privateKeyPath}")
                    return@withContext Result.failure(Exception("Private key file not found: ${config.privateKeyPath}"))
                }
            } else {
                Log.d(TAG, "Using password authentication")
            }
            
            val session = jsch.getSession(config.username, config.host, config.port)
            Log.d(TAG, "Created JSch session")
            
            // Set password if not using key authentication
            if (!config.useKey && config.password.isNotBlank()) {
                session.setPassword(config.password)
                Log.d(TAG, "Password set for session")
            }
            
            // Configure session properties with enhanced settings for reliability
            val sessionConfig = Properties()
            sessionConfig["StrictHostKeyChecking"] = "no"
            sessionConfig["UserKnownHostsFile"] = "/dev/null"
            sessionConfig["PreferredAuthentications"] = if (config.useKey) "publickey" else "password,keyboard-interactive"
            sessionConfig["PasswordAuthentication"] = "yes"
            sessionConfig["KbdInteractiveAuthentication"] = "yes"
            sessionConfig["PubkeyAuthentication"] = if (config.useKey) "yes" else "no"
            sessionConfig["HostKeyAlgorithms"] = "+ssh-rsa,ssh-dss"
            sessionConfig["server_host_key"] = "ssh-rsa,ssh-dss,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521"
            
            // Add keepalive and connection stability settings
            sessionConfig["ServerAliveInterval"] = "60"
            sessionConfig["ServerAliveCountMax"] = "3"
            sessionConfig["TCPKeepAlive"] = "yes"
            sessionConfig["ConnectTimeout"] = "15"
            sessionConfig["Compression"] = "yes"
            sessionConfig["CompressionLevel"] = "6"
            
            // Terminal-specific settings
            sessionConfig["RequestTTY"] = "force"
            sessionConfig["SendEnv"] = "TERM,LANG,LC_ALL"
            
            session.setConfig(sessionConfig)
            Log.d(TAG, "Enhanced session configuration applied")
            Log.d(TAG, "Auth methods: ${sessionConfig["PreferredAuthentications"]}")
            Log.d(TAG, "Keepalive: ${sessionConfig["ServerAliveInterval"]}s")
            
            // Set timeout and connect
            Log.d(TAG, "Attempting to connect with 15s timeout...")
            Log.d(TAG, "Connection parameters:")
            Log.d(TAG, "  Host: ${config.host}")
            Log.d(TAG, "  Port: ${config.port}")
            Log.d(TAG, "  Username: ${config.username}")
            Log.d(TAG, "  Password provided: ${config.password.isNotEmpty()}")
            Log.d(TAG, "  Use key: ${config.useKey}")
            
            try {
                session.connect(15000) // 15 seconds timeout
            } catch (e: com.jcraft.jsch.JSchException) {
                Log.e(TAG, "JSch connection failed with specific error: ${e.message}")
                Log.e(TAG, "JSch error code: ${e.javaClass.simpleName}")
                
                // Provide more specific error information
                when {
                    e.message?.contains("Auth fail") == true -> {
                        throw Exception("Authentication failed. Please verify username and password are correct.")
                    }
                    e.message?.contains("timeout") == true -> {
                        throw Exception("Connection timeout. Check network connectivity and host address.")
                    }
                    e.message?.contains("Connection refused") == true -> {
                        throw Exception("Connection refused. Check if SSH server is running on ${config.host}:${config.port}")
                    }
                    e.message?.contains("UnknownHostException") == true -> {
                        throw Exception("Unknown host: ${config.host}. Check the hostname or IP address.")
                    }
                    e.message?.contains("Network is unreachable") == true -> {
                        throw Exception("Network unreachable. Check your internet connection.")
                    }
                    else -> {
                        throw Exception("SSH connection failed: ${e.message}")
                    }
                }
            }
            
            if (!session.isConnected) {
                throw Exception("SSH session failed to connect (timeout or auth failure)")
            }
            
            Log.d(TAG, "SSH session connected successfully")
            
            // Validate connection with a simple test
            try {
                val testChannel = session.openChannel("exec") as ChannelExec
                testChannel.setCommand("echo 'connection_test_ok'")
                testChannel.connect(5000) // 5 second timeout for test
                
                val inputStream = testChannel.inputStream
                val output = StringBuilder()
                val buffer = ByteArray(1024)
                
                var attempts = 0
                while (testChannel.isConnected && attempts < 10) {
                    if (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            output.append(String(buffer, 0, bytesRead))
                        }
                    }
                    if (testChannel.isClosed) break
                    Thread.sleep(100)
                    attempts++
                }
                
                testChannel.disconnect()
                
                val result = output.toString().trim()
                if (result.contains("connection_test_ok")) {
                    Log.d(TAG, "SSH connection validation successful")
                } else {
                    Log.w(TAG, "SSH connection validation returned unexpected output: '$result'")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "SSH connection validation failed, but proceeding: ${e.message}")
                // Don't fail the connection for validation issues
            }
            
            val sessionId = generateSessionId(config)
            sessions[sessionId] = session
            currentSession.value = sessionId
            isConnected.value = true
            connectionStatus.value = "Connected to ${config.host}"
            
            Log.d(TAG, "Successfully connected to ${config.username}@${config.host}:${config.port}")
            
            // Start connection monitoring
            startConnectionMonitoring(sessionId, session)
            
            Result.success(sessionId)
            
        } catch (e: Exception) {
            connectionStatus.value = "Connection failed: ${e.message}"
            Log.e(TAG, "SSH connection failed", e)
            Log.e(TAG, "Connection details: ${config.username}@${config.host}:${config.port}")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Error cause: ${e.cause}")
            Log.e(TAG, "JSch version info: ${getJSchInfo()}")
            
            // Additional debugging for authentication failures
            if (e.message?.contains("Auth fail") == true) {
                Log.e(TAG, "===== AUTHENTICATION FAILURE DEBUG =====")
                Log.e(TAG, "Username: '${config.username}' (length: ${config.username.length})")
                Log.e(TAG, "Password: ${if (config.password.isEmpty()) "EMPTY" else "PROVIDED (length: ${config.password.length})"}")
                Log.e(TAG, "Host: '${config.host}'")
                Log.e(TAG, "Port: ${config.port}")
                Log.e(TAG, "Using key auth: ${config.useKey}")
                Log.e(TAG, "==========================================")
                Log.e(TAG, "Try this command to test manually:")
                Log.e(TAG, "ssh -p ${config.port} ${config.username}@${config.host}")
                Log.e(TAG, "==========================================")
            }
            
            Result.failure(e)
        }
    }
    
    private fun startConnectionMonitoring(sessionId: String, session: Session) {
        Log.d(TAG, "Starting connection monitoring for session: $sessionId")
        
        CoroutineScope(Dispatchers.IO).launch {
            while (sessions.containsKey(sessionId) && session.isConnected) {
                try {
                    // Send keepalive every 30 seconds
                    session.sendKeepAliveMsg()
                    Log.v(TAG, "Sent keepalive for session: $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send keepalive for session: $sessionId", e)
                    // Connection might be dead, remove from sessions
                    if (!session.isConnected) {
                        Log.w(TAG, "Session $sessionId appears disconnected, cleaning up")
                        disconnect(sessionId)
                        break
                    }
                }
                
                // Wait 30 seconds before next keepalive
                delay(30000)
            }
            
            Log.d(TAG, "Connection monitoring ended for session: $sessionId")
        }
    }
    
    fun getSession(sessionId: String): Session? {
        return sessions[sessionId]
    }
    
    suspend fun getSftpChannel(sessionId: String): ChannelSftp? = withContext(Dispatchers.IO) {
        try {
            if (sftpChannels.containsKey(sessionId)) {
                val channel = sftpChannels[sessionId]
                if (channel?.isConnected == true) {
                    return@withContext channel
                } else {
                    sftpChannels.remove(sessionId)
                }
            }
            
            val session = sessions[sessionId] ?: return@withContext null
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()
            sftpChannels[sessionId] = channel
            channel
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SFTP channel", e)
            null
        }
    }
    
    suspend fun getShellChannel(sessionId: String): ChannelShell? = withContext(Dispatchers.IO) {
        try {
            if (shellChannels.containsKey(sessionId)) {
                val channel = shellChannels[sessionId]
                if (channel?.isConnected == true) {
                    return@withContext channel
                } else {
                    shellChannels.remove(sessionId)
                }
            }
            
            val session = sessions[sessionId] ?: return@withContext null
            val channel = session.openChannel("shell") as ChannelShell
            shellChannels[sessionId] = channel
            channel
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create shell channel", e)
            null
        }
    }
    

    
    suspend fun listFiles(sessionId: String, path: String): Result<List<SshFileInfo>> = withContext(Dispatchers.IO) {
        try {
            val sftpChannel = getSftpChannel(sessionId) ?: return@withContext Result.failure(Exception("SFTP channel not available"))
            
            val files = mutableListOf<SshFileInfo>()
            val entries = sftpChannel.ls(path)
            
            for (entry in entries) {
                val lsEntry = entry as ChannelSftp.LsEntry
                if (lsEntry.filename != "." && lsEntry.filename != "..") {
                    files.add(
                        SshFileInfo(
                            name = lsEntry.filename,
                            path = if (path.endsWith("/")) path + lsEntry.filename else "$path/${lsEntry.filename}",
                            isDirectory = lsEntry.attrs.isDir,
                            size = lsEntry.attrs.size,
                            lastModified = lsEntry.attrs.mTime * 1000L,
                            permissions = lsEntry.attrs.permissionsString
                        )
                    )
                }
            }
            
            Result.success(files.sortedWith(compareBy<SshFileInfo> { !it.isDirectory }.thenBy { it.name }))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files in path: $path", e)
            Result.failure(e)
        }
    }
    
    suspend fun readFile(sessionId: String, filePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sftpChannel = getSftpChannel(sessionId) ?: return@withContext Result.failure(Exception("SFTP channel not available"))
            
            val inputStream = sftpChannel.get(filePath)
            val content = inputStream.bufferedReader().use { it.readText() }
            
            Result.success(content)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: $filePath", e)
            Result.failure(e)
        }
    }
    
    suspend fun writeFile(sessionId: String, filePath: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sftpChannel = getSftpChannel(sessionId) ?: return@withContext Result.failure(Exception("SFTP channel not available"))
            
            val outputStream = sftpChannel.put(filePath)
            outputStream.write(content.toByteArray())
            outputStream.close()
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file: $filePath", e)
            Result.failure(e)
        }
    }
    
    suspend fun createDirectory(sessionId: String, dirPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sftpChannel = getSftpChannel(sessionId) ?: return@withContext Result.failure(Exception("SFTP channel not available"))
            sftpChannel.mkdir(dirPath)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create directory: $dirPath", e)
            Result.failure(e)
        }
    }
    
    suspend fun deleteFile(sessionId: String, filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sftpChannel = getSftpChannel(sessionId) ?: return@withContext Result.failure(Exception("SFTP channel not available"))
            sftpChannel.rm(filePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: $filePath", e)
            Result.failure(e)
        }
    }
    
    fun disconnect(sessionId: String) {
        // Close all channels for this session
        sftpChannels[sessionId]?.disconnect()
        sftpChannels.remove(sessionId)
        
        execChannels[sessionId]?.disconnect()
        execChannels.remove(sessionId)
        
        shellChannels[sessionId]?.disconnect()
        shellChannels.remove(sessionId)
        
        // Close session
        sessions[sessionId]?.disconnect()
        sessions.remove(sessionId)
        
        if (currentSession.value == sessionId) {
            currentSession.value = null
            isConnected.value = sessions.isNotEmpty()
            connectionStatus.value = if (sessions.isEmpty()) "Disconnected" else "Connected"
        }
        
        Log.d(TAG, "Disconnected SSH session: $sessionId")
    }
    
    fun disconnectAll() {
        val sessionIds = sessions.keys.toList()
        sessionIds.forEach { disconnect(it) }
    }
    
    suspend fun executeCommand(sessionId: String, command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionId] ?: return@withContext Result.failure(Exception("SSH session not found"))
            
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.errStream
            
            channel.connect(5000) // 5 second timeout
            
            // Read output
            val output = StringBuilder()
            val buffer = ByteArray(1024)
            
            while (channel.isConnected) {
                while (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                }
                
                while (errorStream.available() > 0) {
                    val bytesRead = errorStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                }
                
                if (channel.isClosed) {
                    break
                }
                
                Thread.sleep(100)
            }
            
            // Read any remaining output
            while (inputStream.available() > 0) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    output.append(String(buffer, 0, bytesRead))
                }
            }
            
            val exitCode = channel.exitStatus
            channel.disconnect()
            
            val result = output.toString()
            Log.d(TAG, "Command '$command' executed, exit code: $exitCode")
            
            if (exitCode == 0) {
                Result.success(result)
            } else {
                Result.failure(Exception("Command failed with exit code $exitCode: $result"))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            Result.failure(e)
        }
    }

    private fun generateSessionId(config: SshConnectionConfig): String {
        return "${config.username}@${config.host}:${config.port}_${System.currentTimeMillis()}"
    }
}

data class SshFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String
)