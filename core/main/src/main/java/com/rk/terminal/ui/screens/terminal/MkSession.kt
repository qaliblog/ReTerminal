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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object MkSession {

    private fun extractTarGz(inputStream: InputStream, destDirectory: File) {
        destDirectory.mkdirs()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(inputStream))).use { tarInput ->
            var entry = tarInput.nextTarEntry
            while (entry != null) {
                val destPath = File(destDirectory, entry.name)
                if (entry.isDirectory) {
                    destPath.mkdirs()
                } else {
                    destPath.parentFile?.mkdirs()
                    FileOutputStream(destPath).use { fos ->
                        tarInput.copyTo(fos)
                    }
                    // Preserve executable permissions if set in the archive
                    if (entry.mode and "111".toInt(8) != 0) {
                        destPath.setExecutable(true, (entry.mode and "001".toInt(8)) == 0)
                    }
                }
                entry = tarInput.nextTarEntry
            }
        }
    }

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

            // Extract rootfs directly to the Debian directory
            val debianDir = File(localDir(), "debian")
            val rootfsAssetName = "debian-rootfs-main.tar.gz" // Corrected asset name
            // Check if extraction is needed (e.g., by checking for a key file)
            if (!File(debianDir, "bin/bash").exists()) {
                try {
                    assets.open(rootfsAssetName).use { inputStream ->
                        extractTarGz(inputStream, debianDir)
                    }
                } catch (e: IOException) {
                    e.printStackTrace() // Handle error appropriately
                    // Consider notifying the user or stopping the session creation
                }
            }


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
            // Revert to using /system/bin/sh as the main executable,
            // and pass the script as an argument to it.
            val shellPath: String = "/system/bin/sh"

            if (pendingCommand == null) {
                // Default execution: /system/bin/sh will execute initHostFile.
                // initHostFile.absolutePath becomes $0 for the script if no other args are given to sh.
                // Or, if sh needs -c, it would be arrayOf("-c", initHostFile.absolutePath),
                // but that treats the file content as a command string, which is not what we want for a full script.
                // Standard way: sh <script_file> <arg1_to_script> ...
                // So, initHostFile.absolutePath is the first argument to /system/bin/sh.
                args = arrayOf(initHostFile.absolutePath)
            } else {
                // Handle pendingCommand:
                // /system/bin/sh will execute initHostFile.
                // initHostFile.absolutePath is $0 to the script.
                // Subsequent arguments are the pending command and its arguments, passed to initHostFile.
                val commandParts = mutableListOf(pendingCommand!!.shell)
                commandParts.addAll(pendingCommand!!.args)
                // The script path comes first, then arguments for that script
                args = arrayOf(initHostFile.absolutePath) + commandParts.toTypedArray()
            }

            pendingCommand = null // Clear it after use
            return TerminalSession(
                shellPath, // This is now /system/bin/sh
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

    }
}