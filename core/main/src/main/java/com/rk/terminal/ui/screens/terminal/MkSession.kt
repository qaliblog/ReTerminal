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
import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object MkSession {

    private fun copyAssetToFile(context: Context, assetName: String, targetFile: File, overwrite: Boolean = false) {
        if (!targetFile.exists() || overwrite) {
            try {
                context.assets.open(assetName).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                // Try to make it executable - might fail, but good for proot/loaders
                targetFile.setExecutable(true)
            } catch (e: IOException) {
                e.printStackTrace() // Handle error appropriately
            }
        }
    }

    fun createSession(
        activity: MainActivity, sessionClient: TerminalSessionClient, session_id: String /* workingMode:Int - Removed as we only support Debian */
    ): TerminalSession {
        with(activity) {

            // Prepare directories and copy assets
            val localFilesDir = filesDir // This is $PREFIX/files in shell script terms
            val localInternalBinDir = File(localDir(), "bin") // $PREFIX/local/bin
            val localInternalLibDir = File(localDir(), "lib") // $PREFIX/local/lib

            localInternalBinDir.mkdirs()
            localInternalLibDir.mkdirs()

            // Copy assets that init-host.sh will then move to $PREFIX/local/bin and $PREFIX/local/lib
            copyAssetToFile(this, "proot", File(localFilesDir, "proot"))
            copyAssetToFile(this, "libtalloc.so.2", File(localFilesDir, "libtalloc.so.2"))

            // Copy rootfs where init-host-debian.sh expects it
            copyAssetToFile(this, "1640716280-1-linux-rootfs-sid-bookworm-debootstrap-5.14.0-4-arm64-cln-nokern-2021.tar.gz", File(localFilesDir, "debian-rootfs.tar.gz"))

            // Copy loaders directly to their final destination (as init-host-debian.sh doesn't handle these)
            // and ensure they are executable. PROOT_LOADER variable will point here.
            val prootLoaderFile = File(localInternalLibDir, "libproot-loader.so")
            copyAssetToFile(this, "loader", prootLoaderFile)

            val prootLoader32File = File(localInternalLibDir, "libproot-loader32.so")
            var prootLoader32Path: String? = null
            if (assets.list("")?.contains("loader32") == true) {
                copyAssetToFile(this, "loader32", prootLoader32File)
                if (prootLoader32File.exists()) {
                    prootLoader32Path = prootLoader32File.absolutePath
                }
            }

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

            val workingDir = pendingCommand?.workingDir ?: "/sdcard" // Or Environment.getExternalStorageDirectory().path

            // Always use Debian settings
            val initHostScriptName = "init-host-debian.sh"
            val initScriptInsideProotName = "init-debian.sh"
            val initHostFileBaseName = "init-host-debian"
            val initFileInsideProotBaseName = "init-debian"

            val initHostFile: File = localInternalBinDir.child(initHostFileBaseName)
            // Always copy/overwrite to ensure the correct version is present and executable
            initHostFile.createFileIfNot() // Ensure it exists before copy, or copyAssetToFile handles it
            copyAssetToFile(this, initHostScriptName, initHostFile, overwrite = true)

            val initFileInsideProot: File = localInternalBinDir.child(initFileInsideProotBaseName)
            // Always copy/overwrite
            initFileInsideProot.createFileIfNot()
            copyAssetToFile(this, initScriptInsideProotName, initFileInsideProot, overwrite = true)

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
                "PROOT_LOADER=${prootLoaderFile.absolutePath}",
            )

            prootLoader32Path?.let {
                env.add("PROOT_LOADER32=$it")
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
            // Make the initHostFile (init-host-debian.sh) the main executable.
            // It must have a shebang like #!/system/bin/sh or #!/bin/sh (if proot provides /bin/sh early)
            // Assuming init-host-debian.sh starts with #!/system/bin/sh or similar.
            val shellPath: String = initHostFile.absolutePath

            if (pendingCommand == null) {
                // Default execution: run init-host-debian.sh.
                // Pass its own path as argv[0], no other arguments initially.
                args = arrayOf(initHostFile.absolutePath)
            } else {
                // Handle pendingCommand:
                // init-host-debian.sh will be executed.
                // Its argv[0] will be its own path.
                // Subsequent arguments (argv[1], argv[2], ...) will be the pending command and its arguments.
                // init-host-debian.sh needs to be able_to handle these and pass them to init-debian.sh.
                val commandParts = mutableListOf(pendingCommand!!.shell)
                commandParts.addAll(pendingCommand!!.args)
                args = arrayOf(initHostFile.absolutePath) + commandParts.toTypedArray()
            }

            pendingCommand = null // Clear it after use
            return TerminalSession(
                shellPath, // This is now initHostFile.absolutePath
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

    }
}