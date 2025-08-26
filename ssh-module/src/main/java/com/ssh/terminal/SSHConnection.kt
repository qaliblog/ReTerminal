package com.ssh.terminal

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class SSHConnection(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String? = null
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val isConnected = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    var onOutputReceived: ((String) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "SSHConnection"
    }
    
    fun connect(): Boolean {
        return try {
            Log.d(TAG, "Connecting to $username@$host:$port")
            
            val jsch = JSch()
            session = jsch.getSession(username, host, port)
            
            session?.let { sess ->
                if (password != null) {
                    sess.setPassword(password)
                }
                
                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                config["ServerAliveInterval"] = "30"
                config["TCPKeepAlive"] = "yes"
                sess.setConfig(config)
                
                sess.connect(10000)
                
                if (sess.isConnected) {
                    channel = sess.openChannel("shell") as ChannelShell
                    channel?.let { ch ->
                        ch.setPty(true)
                        ch.setPtyType("xterm-256color")
                        ch.connect()
                        
                        if (ch.isConnected) {
                            inputStream = ch.inputStream
                            outputStream = ch.outputStream
                            isConnected.set(true)
                            
                            startReading()
                            initTerminal()
                            
                            mainHandler.post {
                                onStatusChanged?.invoke("Connected to $host")
                            }
                            
                            return true
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            mainHandler.post {
                onStatusChanged?.invoke("Connection failed: ${e.message}")
            }
            false
        }
    }
    
    private fun startReading() {
        executor.execute {
            try {
                val buffer = ByteArray(1024)
                while (isConnected.get() && inputStream != null) {
                    val bytesRead = inputStream!!.read(buffer)
                    if (bytesRead > 0) {
                        val output = String(buffer, 0, bytesRead)
                        mainHandler.post {
                            onOutputReceived?.invoke(output)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reading error", e)
            }
        }
    }
    
    private fun initTerminal() {
        executor.execute {
            try {
                Thread.sleep(1000)
                sendCommand("stty sane")
                Thread.sleep(300)
                sendCommand("export TERM=xterm-256color")
                Thread.sleep(300)
                sendCommand("echo 'SSH Terminal Ready - Type away!'")
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
            }
        }
    }
    
    fun sendInput(text: String) {
        executor.execute {
            try {
                if (isConnected.get() && outputStream != null) {
                    outputStream!!.write(text.toByteArray())
                    outputStream!!.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
            }
        }
    }
    
    fun sendCommand(command: String) {
        sendInput("$command\r\n")
    }
    
    fun disconnect() {
        isConnected.set(false)
        channel?.disconnect()
        session?.disconnect()
        mainHandler.post {
            onStatusChanged?.invoke("Disconnected")
        }
    }
    
    fun isConnected(): Boolean = isConnected.get()
}
