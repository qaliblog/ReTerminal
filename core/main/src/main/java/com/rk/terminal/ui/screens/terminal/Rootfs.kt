package com.rk.terminal.ui.screens.terminal

import android.os.Environment
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.terminal.App
import java.io.File

object Rootfs {
    val reTerminal = application!!.filesDir

    init {
        if (reTerminal.exists().not()){
            reTerminal.mkdirs()
        }
    }

    // This object was previously used to track downloaded rootfs components (proot, libtalloc, alpine.tar.gz).
    // Since we are now exclusively using bundled assets (Debian rootfs, proot, libtalloc copied by MkSession.kt),
    // we can assume the necessary components are always "available" via assets.
    // MkSession.kt handles the copying of these assets to their working directories when a session is created.
    // Therefore, isDownloaded can be true by default to bypass the Downloader screen.
    var isDownloaded = mutableStateOf(true)

    // The old isFilesDownloaded() check is no longer relevant as it looks for specific downloaded filenames
    // like "alpine.tar.gz" which are not used in the new asset-based Debian setup.
    // MkSession.kt ensures that "proot" and "libtalloc.so.2" from assets are copied to $PREFIX/files,
    // and "debian-rootfs.tar.gz" (from a differently named asset) is also copied there.
    // The init-host-debian.sh script then moves proot/libtalloc to $PREFIX/local/bin and $PREFIX/local/lib.
    /*
    fun isFilesDownloaded(): Boolean{
        // This old check is for:
        // Rootfs.reTerminal (filesDir)
        //  - proot
        //  - libtalloc.so.2
        //  - alpine.tar.gz
        // Our new setup (via MkSession copying from assets to filesDir):
        //  - proot
        //  - libtalloc.so.2
        //  - debian-rootfs.tar.gz (copied from the long-named asset)
        //  - loader (copied to $PREFIX/local/lib/libproot-loader.so)
        //  - loader32 (copied to $PREFIX/local/lib/libproot-loader32.so)
        return reTerminal.exists() &&
               reTerminal.child("proot").exists() && // This is still copied by MkSession to filesDir
               reTerminal.child("libtalloc.so.2").exists() && // Still copied by MkSession to filesDir
               reTerminal.child("debian-rootfs.tar.gz").exists() // This is the new marker
    }
    */
    // If a check is truly desired, it should verify assets are present or that MkSession successfully copies them.
    // For now, defaulting to true simplifies the flow.
}