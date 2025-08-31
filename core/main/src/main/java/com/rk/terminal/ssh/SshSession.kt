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
            Log.d(TAG, "Opening shell channel...")
            
            // Try different approaches for Termux compatibility
            shellChannel = try {
                Log.d(TAG, "Trying to open shell channel...")
                session?.openChannel("shell") as? ChannelShell
            } catch (e: Exception) {
                Log.w(TAG, "Shell channel failed, trying exec with bash: ${e.message}")
                try {
                    // For Termux, try exec channel with explicit bash
                    val execChannel = session?.openChannel("exec")
                    if (execChannel is com.jcraft.jsch.ChannelExec) {
                        execChannel.setCommand("/data/data/com.termux/files/usr/bin/bash -l")
                        execChannel.setPty(true)
                        execChannel as? ChannelShell
                    } else null
                } catch (e2: Exception) {
                    Log.w(TAG, "Exec channel also failed: ${e2.message}")
                    null
                }
            }
            
            if (shellChannel == null) {
                Log.e(TAG, "Failed to create shell or exec channel")
                return@withContext Pair(null, null)
            }
            
            Log.d(TAG, "Configuring shell channel...")
            
            // Configure shell channel with Termux-compatible settings
            try {
                Log.d(TAG, "Configuring shell channel for Termux compatibility...")
                
                // Termux-specific configuration
                shellChannel?.setPtyType("xterm") // Termux prefers xterm
                shellChannel?.setPtySize(80, 24, 640, 480)
                
                // Minimal forwarding settings for Termux
                shellChannel?.setAgentForwarding(false)
                shellChannel?.setXForwarding(false)
                
                // Set Termux-compatible environment
                try {
                    shellChannel?.setEnv("TERM", "xterm")
                    shellChannel?.setEnv("HOME", "/data/data/com.termux/files/home")
                    shellChannel?.setEnv("PREFIX", "/data/data/com.termux/files/usr")
                    shellChannel?.setEnv("PATH", "/data/data/com.termux/files/usr/bin")
                    Log.d(TAG, "Set Termux environment variables")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set Termux environment: ${e.message}")
                }
                
                Log.d(TAG, "Connecting shell channel...")
                
                // Connect without timeout first, then with timeout if that fails
                try {
                    shellChannel?.connect()
                    Log.d(TAG, "Shell channel connected without timeout")
                } catch (e: Exception) {
                    Log.w(TAG, "Connection without timeout failed, trying with timeout: ${e.message}")
                    shellChannel?.connect(10000) // 10 second timeout
                }
                
                // Verify channel is connected
                if (shellChannel?.isConnected != true) {
                    Log.e(TAG, "Shell channel failed to connect")
                    return@withContext Pair(null, null)
                }
                
                Log.d(TAG, "Shell channel connected successfully")
                
                // Get streams and verify they're available
                val inputStream = shellChannel?.inputStream
                val outputStream = shellChannel?.outputStream
                
                if (inputStream == null || outputStream == null) {
                    Log.e(TAG, "Shell channel streams are null")
                    shellChannel?.disconnect()
                    return@withContext Pair(null, null)
                }
                
                Log.d(TAG, "Shell channel streams obtained successfully")
                Pair(inputStream, outputStream)
                
            } catch (configException: Exception) {
                Log.e(TAG, "Error configuring shell channel", configException)
                shellChannel?.disconnect()
                Pair(null, null)
            }
            
        } catch (e: JSchException) {
            Log.e(TAG, "JSch error opening shell channel: ${e.message}", e)
            Pair(null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening shell channel: ${e.message}", e)
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