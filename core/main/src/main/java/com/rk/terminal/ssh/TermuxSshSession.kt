package com.rk.terminal.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class TermuxSshSession(private val config: SshConfig) {
    private var jsch: JSch? = null
    private var session: Session? = null
    private var execChannel: ChannelExec? = null
    
    companion object {
        private const val TAG = "TermuxSshSession"
    }
    
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing JSch for Termux...")
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
            
            // Configure session for Termux compatibility
            val sessionConfig = Properties().apply {
                put("StrictHostKeyChecking", "no") // Termux often has dynamic host keys
                put("TCPKeepAlive", "yes")
                put("ServerAliveInterval", "30")
                put("ServerAliveCountMax", "3")
                put("PreferredAuthentications", "password,publickey")
                put("GSSAPIAuthentication", "no")
                put("HashKnownHosts", "no")
            }
            session?.setConfig(sessionConfig)
            
            // Set timeouts
            session?.setTimeout(config.connectTimeout)
            session?.setServerAliveInterval(30000) // 30 seconds
            
            // Set user info for Termux
            session?.setUserInfo(object : UserInfo {
                override fun getPassword(): String = config.password
                override fun promptYesNo(str: String): Boolean = true // Accept Termux host keys
                override fun getPassphrase(): String = config.passphrase
                override fun promptPassphrase(message: String): Boolean = config.passphrase.isNotEmpty()
                override fun promptPassword(message: String): Boolean = config.password.isNotEmpty()
                override fun showMessage(message: String) {
                    Log.d(TAG, "SSH Message: $message")
                }
            })
            
            Log.d(TAG, "Connecting to Termux SSH server...")
            session?.connect()
            Log.d(TAG, "SSH session connected successfully to Termux")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Termux SSH", e)
            false
        }
    }
    
    suspend fun openTermuxShell(): Pair<InputStream?, OutputStream?> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Opening Termux shell using exec channel...")
            
            // Use exec channel with bash for Termux compatibility
            execChannel = session?.openChannel("exec") as? ChannelExec
            
            if (execChannel == null) {
                Log.e(TAG, "Failed to create exec channel for Termux")
                return@withContext Pair(null, null)
            }
            
            // Configure exec channel for interactive bash session
            execChannel?.setCommand("/data/data/com.termux/files/usr/bin/bash -l -i")
            execChannel?.setPty(true)
            execChannel?.setPtyType("xterm")
            execChannel?.setPtySize(80, 24, 640, 480)
            
            // Set Termux environment
            try {
                execChannel?.setEnv("TERM", "xterm")
                execChannel?.setEnv("HOME", "/data/data/com.termux/files/home")
                execChannel?.setEnv("PREFIX", "/data/data/com.termux/files/usr")
                execChannel?.setEnv("PATH", "/data/data/com.termux/files/usr/bin:/system/bin")
                Log.d(TAG, "Set Termux environment for exec channel")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set Termux environment: ${e.message}")
            }
            
            Log.d(TAG, "Connecting Termux exec channel...")
            execChannel?.connect()
            
            if (execChannel?.isConnected != true) {
                Log.e(TAG, "Termux exec channel failed to connect")
                return@withContext Pair(null, null)
            }
            
            val inputStream = execChannel?.inputStream
            val outputStream = execChannel?.outputStream
            
            if (inputStream == null || outputStream == null) {
                Log.e(TAG, "Termux exec channel streams are null")
                execChannel?.disconnect()
                return@withContext Pair(null, null)
            }
            
            Log.d(TAG, "Termux shell channel opened successfully")
            Pair(inputStream, outputStream)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Termux shell", e)
            Pair(null, null)
        }
    }
    
    fun isConnected(): Boolean {
        return session?.isConnected == true && execChannel?.isConnected == true
    }
    
    fun disconnect() {
        try {
            execChannel?.disconnect()
            session?.disconnect()
            Log.d(TAG, "Termux SSH session disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during Termux SSH disconnect", e)
        }
    }
    
    fun getSessionInfo(): String {
        return "${config.username}@${config.hostname}:${config.port}"
    }
}