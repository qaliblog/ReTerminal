import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AssetDeployer(private val context: Context) {

    companion object {
        private const val TAG = "AssetDeployer"
        private const val PREFS_NAME = "AppPrefs"
        private const val LAST_DEPLOYED_VERSION_KEY = "lastDeployedVersion"
    }

    // Call this method, e.g., from your MainActivity's onCreate or Application's onCreate
    fun deployAssetsIfNeeded() {
        val currentAppVersion = getCurrentAppVersion()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDeployedVersion = prefs.getInt(LAST_DEPLOYED_VERSION_KEY, 0)

        if (currentAppVersion > lastDeployedVersion) {
            Log.i(TAG, "New version detected (current: $currentAppVersion, last deployed: $lastDeployedVersion). Deploying assets.")
            deployCoreAssets(prefs, currentAppVersion)
        } else {
            Log.i(TAG, "Assets already deployed for version $currentAppVersion. Verifying executables.")
            // Even if not deploying, ensure critical files are still executable (permissions can sometimes reset)
            verifyExecutablePermissions()
        }
    }

    private fun deployCoreAssets(prefs: SharedPreferences, currentAppVersion: Int) {
        val appDataDir = context.applicationInfo.dataDir

        val filesDir = File(appDataDir, "files")
        val localDir = File(appDataDir, "local") // $PREFIX often points here
        val localBinDir = File(localDir, "bin")

        // Ensure target directories exist
        listOf(filesDir, localDir, localBinDir).forEach { dir ->
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    Log.i(TAG, "Created directory: ${dir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create directory: ${dir.absolutePath}")
                    // Handle error: perhaps stop deployment or notify user
                    return
                }
            }
        }

        // IMPORTANT: Adjust assetSourceName to the actual name of your files in the assets folder
        val assetsToDeploy = listOf(
            AssetDetail(assetSourceName = "1640716280-1-linux-rootfs-sid-bookworm-debootstrap-5.14.0-4-arm64-cln-nokern-2021.tar.gz", targetDir = filesDir, targetName = "debian-rootfs.tar.gz", executable = false),
            AssetDetail(assetSourceName = "proot", targetDir = filesDir, targetName = "proot", executable = true),
            AssetDetail(assetSourceName = "libtalloc.so.2", targetDir = filesDir, targetName = "libtalloc.so.2", executable = false),
            // Add other .so files here if needed, e.g.:
            // AssetDetail(assetSourceName = "another-lib.so", targetDir = filesDir, targetName = "another-lib.so", executable = false),
            AssetDetail(assetSourceName = "init-debian", targetDir = localBinDir, targetName = "init-debian", executable = true),
            AssetDetail(assetSourceName = "init-host-debian", targetDir = localBinDir, targetName = "init-host-debian", executable = true)
        )

        val assetManager = context.assets
        var allSuccessful = true
        for (asset in assetsToDeploy) {
            if (!copyAsset(assetManager, asset, true)) { // forceOverwrite = true for deployment
                allSuccessful = false
            }
        }

        if (allSuccessful) {
            prefs.edit().putInt(LAST_DEPLOYED_VERSION_KEY, currentAppVersion).apply()
            Log.i(TAG, "All assets deployed successfully for version $currentAppVersion.")
        } else {
            Log.e(TAG, "One or more assets failed to deploy for version $currentAppVersion.")
            // Consider how to handle partial deployment failure
        }
    }

    private fun verifyExecutablePermissions() {
        val appDataDir = context.applicationInfo.dataDir
        val filesDir = File(appDataDir, "files")
        val localBinDir = File(File(appDataDir, "local"), "bin")

        val executablesToVerify = listOf(
            File(filesDir, "proot"),
            File(localBinDir, "init-debian"),
            File(localBinDir, "init-host-debian")
        )

        executablesToVerify.forEach { file ->
            if (file.exists() && !file.canExecute()) {
                Log.w(TAG, "File ${file.name} was not executable. Setting permission.")
                file.setExecutable(true, false)
            }
        }
    }

    private fun copyAsset(assetManager: AssetManager, detail: AssetDetail, forceOverwrite: Boolean): Boolean {
        val outFile = File(detail.targetDir, detail.targetName)

        if (outFile.exists()) {
            if (forceOverwrite) {
                Log.i(TAG, "File ${outFile.name} exists. Deleting to overwrite for new version.")
                if (!outFile.delete()) {
                    Log.e(TAG, "Failed to delete existing file: ${outFile.absolutePath}")
                    return false // Stop if we can't delete the old one
                }
            } else {
                Log.d(TAG, "${outFile.name} already exists at ${outFile.absolutePath}. Skipping copy.")
                // Still ensure permissions if not overwriting but file is executable type
                if (detail.executable && !outFile.canExecute()) {
                     outFile.setExecutable(true, false)
                }
                return true // Successfully "skipped"
            }
        }

        try {
            assetManager.open(detail.assetSourceName).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.i(TAG, "Copied ${detail.assetSourceName} to ${outFile.absolutePath}")

            if (detail.executable) {
                if (outFile.setExecutable(true, false)) { // Owner+group can execute, others can read
                    Log.i(TAG, "Set ${outFile.name} as executable.")
                } else {
                    Log.e(TAG, "Failed to set ${outFile.name} as executable.")
                    return false // Failed to set permissions
                }
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset file: ${detail.assetSourceName} to ${outFile.absolutePath}", e)
            return false
        }
    }

    private fun getCurrentAppVersion(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not get package info to determine app version", e)
            0 // Fallback version
        }
    }

    private data class AssetDetail(
        val assetSourceName: String,
        val targetDir: File,
        val targetName: String,
        val executable: Boolean
    )
}
