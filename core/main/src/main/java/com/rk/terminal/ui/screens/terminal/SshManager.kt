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
    }
    
    suspend fun connect(config: SshConnectionConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            connectionStatus.value = "Connecting..."
            
            val jsch = JSch()
            
            // Add private key if using key authentication
            if (config.useKey && config.privateKeyPath.isNotBlank()) {
                if (File(config.privateKeyPath).exists()) {
                    jsch.addIdentity(config.privateKeyPath)
                } else {
                    return@withContext Result.failure(Exception("Private key file not found: ${config.privateKeyPath}"))
                }
            }
            
            val session = jsch.getSession(config.username, config.host, config.port)
            
            // Set password if not using key authentication
            if (!config.useKey && config.password.isNotBlank()) {
                session.setPassword(config.password)
            }
            
            // Configure session properties
            val sessionConfig = Properties()
            sessionConfig["StrictHostKeyChecking"] = "no"
            sessionConfig["PreferredAuthentications"] = if (config.useKey) "publickey" else "password"
            session.setConfig(sessionConfig)
            
            // Set timeout
            session.connect(30000) // 30 seconds timeout
            
            val sessionId = generateSessionId(config)
            sessions[sessionId] = session
            currentSession.value = sessionId
            isConnected.value = true
            connectionStatus.value = "Connected to ${config.host}"
            
            Log.d(TAG, "Successfully connected to ${config.username}@${config.host}:${config.port}")
            
            Result.success(sessionId)
            
        } catch (e: Exception) {
            connectionStatus.value = "Connection failed: ${e.message}"
            Log.e(TAG, "SSH connection failed", e)
            Result.failure(e)
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
    
    suspend fun executeCommand(sessionId: String, command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = sessions[sessionId] ?: return@withContext Result.failure(Exception("Session not found"))
            
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.errStream
            
            channel.connect()
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            // Read output
            val outputReader = BufferedReader(InputStreamReader(inputStream))
            val errorReader = BufferedReader(InputStreamReader(errorStream))
            
            var line: String?
            while (outputReader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            
            while (errorReader.readLine().also { line = it } != null) {
                error.appendLine(line)
            }
            
            channel.disconnect()
            
            val result = if (error.isNotEmpty()) {
                "STDOUT:\n$output\nSTDERR:\n$error"
            } else {
                output.toString()
            }
            
            Result.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            Result.failure(e)
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