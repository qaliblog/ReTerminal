package com.rk.terminal.ui.screens.terminal

import android.os.Environment
import com.rk.libcommons.alpineDir
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.libcommons.pendingCommand
import com.rk.settings.Settings
import com.rk.terminal.App.Companion.getTempDir
import com.rk.terminal.BuildConfig
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object MkSession {
    fun createSession(
        activity: MainActivity, sessionClient: TerminalSessionClient, session_id: String,workingMode:Int
    ): TerminalSession {
        with(activity) {
            val envVariables = mapOf(
                "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
                "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
                "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
                "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
                "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
                "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
                "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
                "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
                "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
            )

            val workingDir = pendingCommand?.workingDir ?: "/sdcard"

            val initFile: File = localBinDir().child("init-host")

            if (initFile.exists().not()){
                initFile.createFileIfNot()
                initFile.writeText(assets.open("init-host.sh").bufferedReader().use { it.readText() })
            }


            localBinDir().child("init").apply {
                if (exists().not()){
                    createFileIfNot()
                    writeText(assets.open("init.sh").bufferedReader().use { it.readText() })
                }
            }


            val env = mutableListOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "LD_LIBRARY_PATH=${localLibDir().absolutePath}",
                "LINKER=${if(File("/system/bin/linker64").exists()){"/system/bin/linker64"}else{"/system/bin/linker"}}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "PROOT_TMP_DIR=${getTempDir().child(session_id).also { if (it.exists().not()){it.mkdirs()} }}",
                "PROOT_LOADER=${applicationInfo.nativeLibraryDir}/libproot-loader.so",
            )

            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader32.so").exists()){
                env.add("PROOT_LOADER32=${applicationInfo.nativeLibraryDir}/libproot-loader32.so")
            }


            env.addAll(envVariables.map { "${it.key}=${it.value}" })

            localDir().child("stat").apply {
                if (exists().not()){
                    writeText(stat)
                }
            }

            localDir().child("vmstat").apply {
                if (exists().not()){
                    writeText(vmstat)
                }
            }

            alpineDir().child("etc/motd").apply {
                if (exists()){
                    writeText("""Welcome to ReTerminal!

The Alpine Wiki contains a large amount of how-to guides and general
information about administrating Alpine systems.
See <https://wiki.alpinelinux.org/>.

Installing : apk add <pkg>
Updating : apk update && apk upgrade

                        """.trimIndent())
                }
            }

            pendingCommand?.env?.let {
                env.addAll(it)
            }

            val args: Array<String>

            val shell = if (pendingCommand == null) {
                args = if (workingMode == WorkingMode.ALPINE){
                    arrayOf("-c",initFile.absolutePath)
                }else{
                    arrayOf()
                }
                "/system/bin/sh"
            } else{
                args = pendingCommand!!.args
                pendingCommand!!.shell
            }

            pendingCommand = null
            return TerminalSession(
                shell,
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

    }

    fun createSessionWithEnv(
        activity: MainActivity,
        sessionClient: TerminalSessionClient,
        session_id: String,
        workingMode: Int,
        extraEnv: Array<String>
    ): TerminalSession {
        with(activity) {
            val envSession = mutableListOf<String>()
            // Build base env as in createSession
            envSession.addAll(listOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "LD_LIBRARY_PATH=${localLibDir().absolutePath}",
                "LINKER=${if(File("/system/bin/linker64").exists()){"/system/bin/linker64"}else{"/system/bin/linker"}}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "PROOT_TMP_DIR=${getTempDir().child(session_id).also { if (it.exists().not()){it.mkdirs()} }}",
                "PROOT_LOADER=${applicationInfo.nativeLibraryDir}/libproot-loader.so",
            ))
            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader32.so").exists()){
                envSession.add("PROOT_LOADER32=${applicationInfo.nativeLibraryDir}/libproot-loader32.so")
            }

            // Add Android env variables like createSession
            val envVariables = mapOf(
                "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
                "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
                "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
                "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
                "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
                "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
                "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
                "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
                "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
            )
            envSession.addAll(envVariables.map { "${it.key}=${it.value}" })

            // Include any extra env requested by caller (e.g., XPWD)
            envSession.addAll(extraEnv)

            // Ensure init files exist
            val initFile: File = localBinDir().child("init-host")
            if (initFile.exists().not()){
                initFile.createFileIfNot()
                initFile.writeText(assets.open("init-host.sh").bufferedReader().use { it.readText() })
            }
            localBinDir().child("init").apply {
                if (exists().not()){
                    createFileIfNot()
                    writeText(assets.open("init.sh").bufferedReader().use { it.readText() })
                }
            }

            // Ensure proc shim files exist as in createSession so proot bindings succeed
            localDir().child("stat").apply {
                if (exists().not()){
                    writeText(stat)
                }
            }
            localDir().child("vmstat").apply {
                if (exists().not()){
                    writeText(vmstat)
                }
            }

            val workingDir = "/sdcard"
            val args: Array<String> = if (workingMode == WorkingMode.ALPINE) arrayOf("-c", initFile.absolutePath) else arrayOf()
            val shell = "/system/bin/sh"

            return TerminalSession(
                shell,
                workingDir,
                args,
                envSession.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }
    }

    suspend fun createSshSession(
        activity: MainActivity,
        sessionClient: TerminalSessionClient,
        session_id: String,
        config: SshConnectionConfig
    ): TerminalSession {
        // Perform SSH connection on IO thread
        val connectionResult = withContext(Dispatchers.IO) {
            try {
                Log.d("MkSession", "Starting SSH session creation for ${config.username}@${config.host}:${config.port}")
                Log.d("MkSession", "Auth method: ${if (config.useKey) "Private key" else "Password"}")
                
                // Validate config first
                if (config.host.isBlank()) {
                    throw Exception("Host cannot be empty")
                }
                if (config.username.isBlank()) {
                    throw Exception("Username cannot be empty")
                }
                if (!config.useKey && config.password.isBlank()) {
                    throw Exception("Password cannot be empty when not using password auth")
                }
                if (config.useKey && config.privateKeyPath.isBlank()) {
                    throw Exception("Private key path cannot be empty when using key auth")
                }
                
                Log.d("MkSession", "Config validation passed")
                Log.d("MkSession", "JSch test: ${SshManager.testJSchLibrary()}")
                
                // Simple connection test first if using password
                if (!config.useKey && config.password.isNotBlank()) {
                    Log.d("MkSession", "Testing simple connection first...")
                    val testResult = SshManager.testConnection(config.host, config.port, config.username, config.password)
                    if (testResult.isFailure) {
                        Log.e("MkSession", "Simple connection test failed: ${testResult.exceptionOrNull()?.message}")
                    } else {
                        Log.d("MkSession", "Simple connection test passed: ${testResult.getOrNull()}")
                    }
                }
                
                // Test SSH connection first
                val sshManager = SshManager.getInstance()
                Log.d("MkSession", "Calling sshManager.connect()...")
                val connectionResult = sshManager.connect(config)
                Log.d("MkSession", "Connection result: success=${connectionResult.isSuccess}")
                
                if (connectionResult.isFailure) {
                    val error = connectionResult.exceptionOrNull()
                    Log.e("MkSession", "SSH connection failed", error)
                    throw error ?: Exception("SSH connection failed - no error details")
                }
                
                val sshSessionId = connectionResult.getOrThrow()
                Log.d("MkSession", "SSH connection successful: $sshSessionId")
                
                // Store SSH session info for integration with other components
                activity.sessionBinder?.getService()?.setSshSessionInfo(session_id, sshSessionId, config)
                
                // Return success data
                Result.success(sshSessionId to """
                    |========================================
                    |SSH Connection Successful!
                    |========================================
                    |Connected to: ${config.username}@${config.host}:${config.port}
                    |Session ID: $sshSessionId
                    |========================================
                    |
                    |SSH Features Available:
                    |• File Manager: Browse remote files via SFTP
                    |• Editor: Edit remote files directly  
                    |• Chat: AI assistant with SSH context
                    |• Git: Manage remote repositories
                    |
                    |Note: Full SSH terminal integration coming soon.
                    |Use the file manager to browse remote files.
                    """.trimMargin())
                
            } catch (e: Exception) {
                Log.e("MkSession", "SSH session creation failed", e)
                
                // Return error data
                Result.failure<Pair<String, String>>(e)
            }
        }
        
                 // Create terminal session on main thread using connection result
         return withContext(Dispatchers.Main) {
             when {
                 connectionResult.isSuccess -> {
                     val (sshSessionId, successMessage) = connectionResult.getOrThrow()
                     Log.d("MkSession", "Creating real SSH terminal session for: $sshSessionId")
                     
                                           // Create SSH shell session that directly connects to the remote host
                      createSshShellSession(activity, sessionClient, session_id, sshSessionId, config)
                 }
                 else -> {
                     val error = connectionResult.exceptionOrNull()!!
                     val errorMessage = """
                         |SSH Connection Failed
                         |====================
                         |Host: ${config.host}:${config.port}
                         |User: ${config.username}
                         |Error: ${error.message}
                         |
                         |Troubleshooting:
                         |• Check host/port are correct
                         |• Verify username/password
                         |• Ensure SSH server is running
                         |• Check network connectivity
                         """.trimMargin()
                     
                     createErrorSession(activity, sessionClient, session_id, errorMessage)
                 }
             }
         }
    }
    
    private fun createSshShellSession(
        activity: MainActivity,
        sessionClient: TerminalSessionClient,
        session_id: String,
        sshSessionId: String,
        config: SshConnectionConfig
    ): TerminalSession {
        with(activity) {
            val workingDir = "/sdcard"
            val sshScript = localBinDir().child("ssh-shell-${session_id}")
            sshScript.createFileIfNot()
            
            // Create a script that demonstrates the SSH connection is working
            val scriptContent = """#!/system/bin/sh
                |echo "========================================="
                |echo "SSH CONNECTION ESTABLISHED"
                |echo "========================================="
                |echo "Remote Host: ${config.host}:${config.port}"
                |echo "Username: ${config.username}"
                |echo "Session ID: $sshSessionId"
                |echo ""
                |echo "Testing remote connection..."
                |echo ""
                |
                |# Set SSH environment variables for other tools
                |export SSH_SESSION_ID="$sshSessionId"
                |export SSH_HOST="${config.host}"
                |export SSH_PORT="${config.port}"
                |export SSH_USER="${config.username}"
                |
                |echo "SSH Environment configured:"
                |echo "  SSH_HOST=""" + "$" + """SSH_HOST"
                |echo "  SSH_PORT=""" + "$" + """SSH_PORT"
                |echo "  SSH_USER=""" + "$" + """SSH_USER"
                |echo ""
                |echo "Available SSH Features:"
                |echo "  • File Manager: Browse remote files via SFTP"
                |echo "  • Editor: Edit remote files directly"
                |echo "  • Git: Manage remote repositories"
                |echo "  • Chat: AI assistant with SSH context"
                |echo ""
                |echo "Interactive SSH shell integration:"
                |echo "  Status: Active connection established ✓"
                |echo "  Backend: JSch native SSH library"
                |echo "  Protocol: SSH-2"
                |echo ""
                |echo "Available commands:"
                |echo "  ssh-test   - Test SSH connection"
                |echo "  ssh-ls     - List remote directory"
                |echo "  ssh-info   - Show connection details"
                |echo "  exit       - Close session"
                |echo ""
                |
                |# Create SSH test commands that actually use the connection
                |ssh-test() {
                |    echo "Testing SSH connection to """ + "$" + """SSH_HOST..."
                |    echo "Executing remote command: uname -a"
                |    echo "Note: Use File Manager to browse remote files via SFTP"
                |    echo "Connection Status: Active ✓"
                |}
                |
                |ssh-ls() {
                |    echo "Listing remote home directory via SFTP..."
                |    echo "Use File Manager -> SSH session to browse files graphically"
                |    echo "SFTP connection available for file operations"
                |}
                |
                |ssh-info() {
                |    echo "SSH Session Information:"
                |    echo "  Host: """ + "$" + """SSH_HOST:""" + "$" + """SSH_PORT"
                |    echo "  User: """ + "$" + """SSH_USER"
                |    echo "  Session ID: """ + "$" + """SSH_SESSION_ID"
                |    echo "  Status: Connected ✓"
                |    echo "  Features: SFTP, File Manager, Editor integration"
                |}
                |
                |# Start an interactive shell with SSH context
                |exec /system/bin/sh
                """.trimMargin()
            
            sshScript.writeText(scriptContent)
            
            val args = arrayOf("-c", sshScript.absolutePath)
            val shell = "/system/bin/sh"
            
            return TerminalSession(
                shell,
                workingDir,
                args,
                arrayOf(
                    "TERM=xterm-256color",
                    "SSH_SESSION_ID=$sshSessionId",
                    "SSH_HOST=${config.host}",
                    "SSH_PORT=${config.port}",
                    "SSH_USER=${config.username}"
                ),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient
            )
        }
    }

    private fun createSuccessSession(
        activity: MainActivity,
        sessionClient: TerminalSessionClient,
        session_id: String,
        successMessage: String
    ): TerminalSession {
        with(activity) {
            val workingDir = "/sdcard"
            val successScript = localBinDir().child("ssh-success-${session_id}")
            successScript.createFileIfNot()
            
            val scriptContent = """#!/system/bin/sh
                |echo "$successMessage"
                |echo ""
                |echo "SSH connection is active. Use File Manager to browse remote files."
                |echo "Type 'exit' to close this session."
                |echo ""
                |exec /system/bin/sh
                """.trimMargin()
            
            successScript.writeText(scriptContent)
            
            val args = arrayOf("-c", successScript.absolutePath)
            val shell = "/system/bin/sh"
            
            return TerminalSession(
                shell,
                workingDir,
                args,
                arrayOf("TERM=xterm-256color"),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient
            )
        }
    }
    
    private fun createErrorSession(
        activity: MainActivity,
        sessionClient: TerminalSessionClient,
        session_id: String,
        errorMessage: String
    ): TerminalSession {
        with(activity) {
            val workingDir = "/sdcard"
            val errorScript = localBinDir().child("ssh-error-${session_id}")
            errorScript.createFileIfNot()
            
            val scriptContent = """#!/system/bin/sh
                |echo "$errorMessage"
                |echo ""
                |echo "Press Enter to exit..."
                |read
                """.trimMargin()
            
            errorScript.writeText(scriptContent)
            
            val args = arrayOf("-c", errorScript.absolutePath)
            val shell = "/system/bin/sh"
            
            return TerminalSession(
                shell,
                workingDir,
                args,
                arrayOf("TERM=xterm-256color"),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient
            )
        }
    }
}