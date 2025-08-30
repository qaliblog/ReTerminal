package com.rk.terminal.ssh

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.UserInfo
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SshSession(private val config: SshConfig) {
    private var jsch: JSch? = null
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var sftpChannel: ChannelSftp? = null
    
    companion object {
        private const val TAG = "SshSession"
    }
    
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            jsch = JSch()
            
            // Add private key if specified
            if (config.authMethod != AuthMethod.PASSWORD && config.privateKeyPath.isNotEmpty()) {
                if (config.passphrase.isNotEmpty()) {
                    jsch?.addIdentity(config.privateKeyPath, config.passphrase)
                } else {
                    jsch?.addIdentity(config.privateKeyPath)
                }
            }
            
            session = jsch?.getSession(config.username, config.hostname, config.port)
            
            // Set password if using password authentication
            if (config.authMethod != AuthMethod.PRIVATE_KEY && config.password.isNotEmpty()) {
                session?.setPassword(config.password)
            }
            
            // Configure session properties for stability
            val sessionConfig = Properties().apply {
                put("StrictHostKeyChecking", if (config.strictHostKeyChecking) "yes" else "no")
                put("compression.s2c", if (config.compressionEnabled) "zlib,none" else "none")
                put("compression.c2s", if (config.compressionEnabled) "zlib,none" else "none")
                
                // Add connection stability settings
                put("TCPKeepAlive", "yes")
                put("ServerAliveCountMax", "3")
                put("ConnectTimeout", (config.connectTimeout / 1000).toString())
                
                if (config.forwardX11) {
                    put("ForwardX11", "yes")
                }
                
                // Add additional stability settings
                put("PreferredAuthentications", "publickey,password")
                put("GSSAPIAuthentication", "no")
                put("HashKnownHosts", "no")
            }
            session?.setConfig(sessionConfig)
            
            // Set timeouts and keep-alive
            session?.setTimeout(config.connectTimeout)
            session?.setServerAliveInterval(config.keepAliveInterval)
            session?.setServerAliveCountMax(3)
            
            // Set user info for interactive authentication if needed
            session?.setUserInfo(object : UserInfo {
                override fun getPassword(): String = config.password
                override fun promptYesNo(str: String): Boolean = !config.strictHostKeyChecking
                override fun getPassphrase(): String = config.passphrase
                override fun promptPassphrase(message: String): Boolean = config.passphrase.isNotEmpty()
                override fun promptPassword(message: String): Boolean = config.password.isNotEmpty()
                override fun showMessage(message: String) {
                    Log.d(TAG, "SSH Message: $message")
                }
            })
            
            session?.connect()
            Log.d(TAG, "SSH session connected successfully to ${config.hostname}:${config.port}")
            true
        } catch (e: JSchException) {
            Log.e(TAG, "Failed to connect SSH session", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during SSH connection", e)
            false
        }
    }
    
    suspend fun openShellChannel(): Pair<InputStream?, OutputStream?> = withContext(Dispatchers.IO) {
        try {
            shellChannel = session?.openChannel("shell") as? ChannelShell
            
            // Configure shell channel for stability
            shellChannel?.setPtyType("xterm-256color")
            shellChannel?.setPtySize(120, 30, 960, 720) // Larger terminal size
            shellChannel?.setAgentForwarding(false)
            shellChannel?.setXForwarding(config.forwardX11)
            
            // Set environment variables
            config.environmentVariables.forEach { (key, value) ->
                shellChannel?.setEnv(key, value)
            }
            
            // Add standard environment variables for better shell experience
            shellChannel?.setEnv("LANG", "en_US.UTF-8")
            shellChannel?.setEnv("LC_ALL", "en_US.UTF-8")
            shellChannel?.setEnv("SHELL", "/bin/bash")
            
            // Connect with timeout
            shellChannel?.connect(config.connectTimeout)
            Log.d(TAG, "Shell channel opened successfully with enhanced configuration")
            
            Pair(shellChannel?.inputStream, shellChannel?.outputStream)
        } catch (e: JSchException) {
            Log.e(TAG, "Failed to open shell channel", e)
            Pair(null, null)
        }
    }
    
    suspend fun openSftpChannel(): ChannelSftp? = withContext(Dispatchers.IO) {
        try {
            sftpChannel = session?.openChannel("sftp") as? ChannelSftp
            sftpChannel?.connect()
            Log.d(TAG, "SFTP channel opened successfully")
            sftpChannel
        } catch (e: JSchException) {
            Log.e(TAG, "Failed to open SFTP channel", e)
            null
        }
    }
    
    fun isConnected(): Boolean {
        return session?.isConnected == true
    }
    
    fun disconnect() {
        try {
            shellChannel?.disconnect()
            sftpChannel?.disconnect()
            session?.disconnect()
            Log.d(TAG, "SSH session disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during SSH disconnect", e)
        }
    }
    
    fun getRemoteWorkingDirectory(): String {
        return config.workingDirectory
    }
    
    fun getSessionInfo(): String {
        return "${config.username}@${config.hostname}:${config.port}"
    }
}