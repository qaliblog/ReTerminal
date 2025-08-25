package com.rk.terminal.ui.activities.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.rk.libcommons.toast
import com.rk.terminal.service.SessionService
import com.rk.terminal.ui.navHosts.MainActivityNavHost
import com.rk.terminal.ui.screens.terminal.TerminalScreen
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.ui.theme.KarbonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    var sessionBinder:SessionService.SessionBinder? = null
    var isBound = false

    // Observable storage access state for Compose
    val storageAccessGranted = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SessionService.SessionBinder
            sessionBinder = binder
            isBound = true

            lifecycleScope.launch(Dispatchers.Main){
                setContent {
                    KarbonTheme {
                        Surface {
                            val navController = rememberNavController()
                            MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)
                        }
                    }
                }
            }


        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            sessionBinder = null
        }
    }

    private var lastBackPressed: Long = 0L

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SessionService::class.java))
        }else{
            startService(Intent(this, SessionService::class.java))
        }
        Intent(this, SessionService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Initialize current permission state
        storageAccessGranted.value = hasStorageAccess()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressed < 2000) {
                    moveTaskToBack(true)
                } else {
                    lastBackPressed = now
                    toast("Press back again to exit")
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        storageAccessGranted.value = hasStorageAccess()
    }

    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED
            read && write
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = android.net.Uri.parse("package:" + this.packageName)
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                2001
            )
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        android.util.Log.d("MainActivity", "🔥🔥 ACTIVITY onKeyDown - keyCode: $keyCode, char: '${event?.unicodeChar?.toChar()}'")
        
        // Try to forward input to SSH session if available
        val service = sessionBinder?.getService()
        if (service != null) {
            try {
                // Get the current session and check if it's SSH
                val currentSessionId = service.currentSession.value.first
                val sessionList = service.sessionList
                
                // Find SSH sessions and forward input
                for ((sessionId, workingMode) in sessionList) {
                    if (workingMode == WorkingMode.SSH) {
                        val sshTerm = service.getSshTerminalSessionById(sessionId)
                        
                        if (sshTerm != null) {
                            android.util.Log.d("MainActivity", "🔥🔥 Forwarding key to SSH: $keyCode")
                            
                            // Handle specific keys
                            when (keyCode) {
                                android.view.KeyEvent.KEYCODE_ENTER -> {
                                    sshTerm.sendInput("\r\n")
                                    return true
                                }
                                android.view.KeyEvent.KEYCODE_DEL -> {
                                    sshTerm.sendInput("\u007f")
                                    return true
                                }
                                in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z -> {
                                    val char = ('a' + (keyCode - android.view.KeyEvent.KEYCODE_A)).toString()
                                    android.util.Log.d("MainActivity", "🔥🔥 Sending letter: $char")
                                    sshTerm.sendInput(char)
                                    return true
                                }
                                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                                    val char = ('0' + (keyCode - android.view.KeyEvent.KEYCODE_0)).toString()
                                    android.util.Log.d("MainActivity", "🔥🔥 Sending number: $char")
                                    sshTerm.sendInput(char)
                                    return true
                                }
                                android.view.KeyEvent.KEYCODE_SPACE -> {
                                    android.util.Log.d("MainActivity", "🔥🔥 Sending space")
                                    sshTerm.sendInput(" ")
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error forwarding input to SSH", e)
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
}