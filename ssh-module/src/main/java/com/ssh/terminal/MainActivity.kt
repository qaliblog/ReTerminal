package com.ssh.terminal

import android.app.Activity
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    
    private lateinit var terminalView: TerminalView
    private lateinit var hostEdit: EditText
    private lateinit var usernameEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    
    private var sshConnection: SSHConnection? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create layout programmatically (no XML needed)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        
        // Connection form
        val formLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        hostEdit = EditText(this).apply {
            hint = "Host (e.g., your.server.com)"
        }
        
        usernameEdit = EditText(this).apply {
            hint = "Username"
        }
        
        passwordEdit = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { toggleConnection() }
        }
        
        statusText = TextView(this).apply {
            text = "Ready to connect"
            setTextColor(android.graphics.Color.WHITE)
        }
        
        // Terminal view
        terminalView = TerminalView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnClickListener { showKeyboard() }
        }
        
        formLayout.addView(hostEdit)
        formLayout.addView(usernameEdit)
        formLayout.addView(passwordEdit)
        formLayout.addView(connectBtn)
        formLayout.addView(statusText)
        
        layout.addView(formLayout)
        layout.addView(terminalView)
        
        setContentView(layout)
    }
    
    private fun toggleConnection() {
        if (sshConnection?.isConnected() == true) {
            disconnect()
        } else {
            connect()
        }
    }
    
    private fun connect() {
        val host = hostEdit.text.toString().trim()
        val username = usernameEdit.text.toString().trim()
        val password = passwordEdit.text.toString()
        
        if (host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Please enter host and username", Toast.LENGTH_SHORT).show()
            return
        }
        
        connectBtn.text = "Connecting..."
        connectBtn.isEnabled = false
        
        Thread {
            sshConnection = SSHConnection(host, 22, username, password).apply {
                onStatusChanged = { status ->
                    runOnUiThread {
                        statusText.text = status
                        if (status.startsWith("Connected")) {
                            connectBtn.text = "Disconnect"
                            connectBtn.isEnabled = true
                        } else if (status.startsWith("Connection failed")) {
                            connectBtn.text = "Connect"
                            connectBtn.isEnabled = true
                        }
                    }
                }
            }
            
            terminalView.attachSSH(sshConnection!!)
            
            val connected = sshConnection!!.connect()
            if (!connected) {
                runOnUiThread {
                    connectBtn.text = "Connect"
                    connectBtn.isEnabled = true
                }
            }
        }.start()
    }
    
    private fun disconnect() {
        sshConnection?.disconnect()
        sshConnection = null
        connectBtn.text = "Connect"
        statusText.text = "Disconnected"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sshConnection?.disconnect()
    }
}
