package com.rk.terminal.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String
)

class SshFileManager(private val sshSession: SshSession) {
    private var sftpChannel: ChannelSftp? = null
    
    companion object {
        private const val TAG = "SshFileManager"
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            sftpChannel = sshSession.openSftpChannel()
            sftpChannel != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SFTP channel", e)
            false
        }
    }
    
    suspend fun listFiles(remotePath: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext emptyList()
            
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
            
            entries.filter { entry ->
                entry.filename != "." && entry.filename != ".."
            }.map { entry ->
                val attrs = entry.attrs
                RemoteFile(
                    name = entry.filename,
                    path = if (remotePath.endsWith("/")) "$remotePath${entry.filename}" else "$remotePath/${entry.filename}",
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                    lastModified = attrs.mTime * 1000L, // Convert to milliseconds
                    permissions = attrs.permissionsString
                )
            }.sortedWith(compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to list files in $remotePath", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error listing files", e)
            emptyList()
        }
    }
    
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.get(remotePath, localFile.absolutePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to download file $remotePath", e)
            false
        }
    }
    
    suspend fun uploadFile(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.put(localFile.absolutePath, remotePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to upload file to $remotePath", e)
            false
        }
    }
    
    suspend fun readFileContent(remotePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext null
            val inputStream = channel.get(remotePath)
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to read file content $remotePath", e)
            null
        }
    }
    
    suspend fun writeFileContent(remotePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            val outputStream = channel.put(remotePath)
            outputStream.bufferedWriter().use { it.write(content) }
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to write file content $remotePath", e)
            false
        }
    }
    
    suspend fun createDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.mkdir(remotePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to create directory $remotePath", e)
            false
        }
    }
    
    suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.rm(remotePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to delete file $remotePath", e)
            false
        }
    }
    
    suspend fun deleteDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.rmdir(remotePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to delete directory $remotePath", e)
            false
        }
    }
    
    suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.rename(oldPath, newPath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to rename file from $oldPath to $newPath", e)
            false
        }
    }
    
    suspend fun getCurrentWorkingDirectory(): String = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext "/"
            channel.pwd()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current working directory", e)
            "/"
        }
    }
    
    suspend fun changeDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val channel = sftpChannel ?: return@withContext false
            channel.cd(remotePath)
            true
        } catch (e: SftpException) {
            Log.e(TAG, "Failed to change directory to $remotePath", e)
            false
        }
    }
    
    fun disconnect() {
        try {
            sftpChannel?.disconnect()
            sftpChannel = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting SFTP channel", e)
        }
    }
    
    fun isConnected(): Boolean {
        return sftpChannel?.isConnected == true
    }
}