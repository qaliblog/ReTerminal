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
        return withContext<TerminalSession>(Dispatchers.IO) {
            try {
                // Use native SSH implementation with JSch
                val sshManager = SshManager.getInstance()
                
                // Connect to SSH server
                val connectionResult = sshManager.connect(config)
                if (connectionResult.isFailure) {
                    throw connectionResult.exceptionOrNull() ?: Exception("SSH connection failed")
                }
                
                val sshSessionId = connectionResult.getOrThrow()
                
                // Create SSH terminal session
                val sshTerminalSession = SshTerminalSession(sshSessionId, sessionClient)
                val terminalResult = sshTerminalSession.start()
                
                if (terminalResult.isFailure) {
                    sshManager.disconnect(sshSessionId)
                    throw terminalResult.exceptionOrNull() ?: Exception("Failed to start SSH terminal")
                }
                
                val terminalSession = terminalResult.getOrThrow()
                
                // Store SSH session info for integration with other components
                activity.sessionBinder?.getService()?.setSshSessionInfo(session_id, sshSessionId, config)
                
                terminalSession
                
            } catch (e: Exception) {
                // Fallback to a simple error session
                val errorMessage = """
                    |SSH Connection Failed
                    |====================
                    |Host: ${config.host}:${config.port}
                    |User: ${config.username}
                    |Error: ${e.message}
                    |
                    |Please check your connection details and try again.
                    """.trimMargin()
                
                // Create a simple terminal session that shows the error
                createErrorSession(activity, sessionClient, session_id, errorMessage)
            }
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