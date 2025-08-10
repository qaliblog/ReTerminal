package com.rk.terminal.llm

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Best-effort fetcher that tries to download missing runtime and compiled model .so
 * from Hugging Face under mlc-ai/<modelFolderName>.
 *
 * This is heuristic and aims to "just work" without additional user input.
 */
object MlcAutoFetcher {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Ensures both the TVM runtime .so and the compiled model module .so exist locally.
     * Returns true if after this call both are present, false otherwise.
     */
    suspend fun ensureArtifactsPresent(modelDir: File): Boolean = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return@withContext false
        val libsDir = File(modelDir, "libs/$abi")
        var runtimeOk = ensureRuntimeSo(modelDir, libsDir)
        var moduleOk = ensureModelModuleSo(modelDir)
        if (!runtimeOk || !moduleOk) {
            val repo = resolveHfRepo(modelDir)
            if (repo != null) {
                val tree = fetchHfTree(repo)
                if (tree != null) {
                    // Try preferred android-arm64 bundles
                    val bundlePath = tree.firstOrNull { path ->
                        path.contains("android", ignoreCase = true) &&
                            (path.endsWith(".tar") || path.endsWith(".tar.gz") || path.endsWith(".zip")) &&
                            (path.contains("arm64") || path.contains("arm64-v8a") || path.contains("android-arm64"))
                    } ?: tree.firstOrNull { path ->
                        // Any android bundle
                        path.contains("android", ignoreCase = true) &&
                            (path.endsWith(".tar") || path.endsWith(".tar.gz") || path.endsWith(".zip"))
                    }
                    if (bundlePath != null) {
                        val tmp = File(modelDir, "_tmp_android_pkg" + when {
                            bundlePath.endsWith(".tar.gz") -> ".tar.gz"
                            bundlePath.endsWith(".tar") -> ".tar"
                            else -> ".zip"
                        })
                        if (downloadFromHf(repo, bundlePath, tmp)) {
                            extractArchive(tmp, modelDir)
                            tmp.delete()
                        }
                    }
                }
            }
            runtimeOk = ensureRuntimeSo(modelDir, libsDir)
            moduleOk = ensureModelModuleSo(modelDir)
        }
        return@withContext runtimeOk && moduleOk
    }

    private suspend fun ensureRuntimeSo(modelDir: File, libsDir: File): Boolean {
        val runtimeNames = listOf("libtvm4j_runtime_packed.so", "libtvm_runtime.so")
        // Already present?
        if (libsDir.exists() && libsDir.isDirectory) {
            if (runtimeNames.any { File(libsDir, it).exists() }) return true
        }
        // Try to fetch from HF
        val repo = resolveHfRepo(modelDir) ?: return false
        val tree = fetchHfTree(repo) ?: return false
        val runtimePath = tree.firstOrNull { path ->
            runtimeNames.any { path.endsWith(it) }
        }
        if (runtimePath != null) {
            libsDir.mkdirs()
            val fileName = runtimeNames.first { runtimePath.endsWith(it) }
            val dst = File(libsDir, fileName)
            return downloadFromHf(repo, runtimePath, dst)
        }
        return false
    }

    private suspend fun ensureModelModuleSo(modelDir: File): Boolean {
        // Already present?
        val existing = modelDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".so") && !it.name.startsWith("libtvm") }
        if (existing != null) return true
        // Try to fetch from HF
        val repo = resolveHfRepo(modelDir) ?: return false
        val tree = fetchHfTree(repo) ?: return false
        val moduleCandidates = tree.filter { path ->
            path.endsWith(".so") && !path.contains("libtvm")
        }
        if (moduleCandidates.isEmpty()) return false
        // Prefer vulkan, then cpu, otherwise first
        val selected = moduleCandidates.firstOrNull { it.contains("vulkan", ignoreCase = true) }
            ?: moduleCandidates.firstOrNull { it.contains("cpu", ignoreCase = true) }
            ?: moduleCandidates.first()
        val dst = File(modelDir, File(selected).name)
        return downloadFromHf(repo, selected, dst)
    }

    private suspend fun resolveHfRepo(modelDir: File): String? {
        // First try exact folder name
        val exact = "mlc-ai/${modelDir.name}"
        if (fetchHfTree(exact) != null) return exact
        // Try folder name without trailing -MLC
        val base = modelDir.name.removeSuffix("-MLC")
        val searchQuery = URLEncoder.encode(base, StandardCharsets.UTF_8)
        val results = searchHfRepos("mlc-ai", searchQuery)
        // Pick the first mlc-ai/<id> match
        return results.firstOrNull()
    }

    private suspend fun fetchHfTree(repo: String): List<String>? = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=1"
        val req = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val arr = JSONArray(body)
                val paths = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val path = obj.optString("path")
                    if (path.isNotBlank()) paths.add(path)
                }
                return@use paths
            }
        } catch (_: IOException) {
            return@withContext null
        }
    }

    private suspend fun searchHfRepos(author: String, query: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models?author=$author&search=$query&limit=10"
        val req = Request.Builder().url(url).get().build()
        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val body = resp.body?.string() ?: return@use emptyList<String>()
                val arr = JSONArray(body)
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj: JSONObject = arr.getJSONObject(i)
                    val id = obj.optString("id")
                    if (id.startsWith("mlc-ai/")) {
                        ids.add(id)
                    } else if (id.isNotBlank()) {
                        ids.add("mlc-ai/$id")
                    }
                }
                return@use ids
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    private suspend fun downloadFromHf(repo: String, path: String, dst: File): Boolean = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/$repo/resolve/main/$path?download=true"
        val req = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                dst.parentFile?.mkdirs()
                dst.outputStream().use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
                return@use true
            }
        } catch (_: IOException) {
            return@withContext false
        }
    }

    private fun extractArchive(archive: File, outputDir: File) {
        when {
            archive.name.endsWith(".tar.gz") -> extractTarGz(archive, outputDir)
            archive.name.endsWith(".tar") -> extractTar(archive, outputDir)
            archive.name.endsWith(".zip") -> extractZip(archive, outputDir)
        }
    }

    private fun extractTarGz(tarGzFile: File, outputDir: File) {
        GZIPInputStream(tarGzFile.inputStream()).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                extractTarStream(tarIn, outputDir)
            }
        }
    }

    private fun extractTar(tarFile: File, outputDir: File) {
        TarArchiveInputStream(tarFile.inputStream()).use { tarIn ->
            extractTarStream(tarIn, outputDir)
        }
    }

    private fun extractTarStream(tarIn: TarArchiveInputStream, outputDir: File) {
        var entry = tarIn.nextTarEntry
        val buffer = ByteArray(8 * 1024)
        while (entry != null) {
            val outFile = File(outputDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out ->
                    var read: Int
                    while (tarIn.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
                if (outFile.name.endsWith(".so")) outFile.setExecutable(true, false)
            }
            entry = tarIn.nextTarEntry
        }
    }

    private fun extractZip(zipFile: File, outputDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            val buffer = ByteArray(8 * 1024)
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        var read: Int
                        while (zipIn.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                    if (outFile.name.endsWith(".so")) outFile.setExecutable(true, false)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }
}