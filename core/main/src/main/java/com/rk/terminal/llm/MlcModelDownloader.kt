package com.rk.terminal.llm

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object MlcModelDownloader {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    data class Options(
        val includeWeights: Boolean = true,
        val preferVulkan: Boolean = false,
    )

    suspend fun downloadTo(modelRepoIdOrName: String, outParentDir: File, onProgress: (String) -> Unit, options: Options = Options()): File? = withContext(Dispatchers.IO) {
        val repo = resolveRepo(modelRepoIdOrName) ?: return@withContext null
        val tree = fetchTree(repo) ?: return@withContext null
        val modelName = repo.substringAfter('/')
        val destDir = File(outParentDir, modelName).apply { mkdirs() }

        // 1) Prefer Android bundle if present
        val androidBundle = tree.firstOrNull { path ->
            path.contains("android", true) && (path.endsWith(".tar") || path.endsWith(".tar.gz") || path.endsWith(".zip"))
        }
        if (androidBundle != null) {
            onProgress("Downloading Android bundle: $androidBundle")
            val tmp = File(destDir, "_tmp_bundle" + when {
                androidBundle.endsWith(".tar.gz") -> ".tar.gz"
                androidBundle.endsWith(".tar") -> ".tar"
                else -> ".zip"
            })
            if (download(repo, androidBundle, tmp, onProgress)) {
                extractArchive(tmp, destDir, onProgress)
                tmp.delete()
            }
        }

        // 2) Ensure runtime
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libsDir = File(destDir, "libs/$abi").apply { mkdirs() }
        val runtimeNames = listOf("libtvm4j_runtime_packed.so", "libtvm_runtime.so")
        if (!runtimeNames.any { File(libsDir, it).exists() }) {
            val runtimePath = tree.firstOrNull { p -> runtimeNames.any { p.endsWith(it) } }
            if (runtimePath != null) {
                onProgress("Downloading runtime: ${File(runtimePath).name}")
                download(repo, runtimePath, File(libsDir, File(runtimePath).name), onProgress)
            }
        }

        // 3) Ensure compiled module .so
        val existingModule = destDir.listFiles()?.any { it.isFile && it.name.endsWith(".so") && !it.name.startsWith("libtvm") } == true
        if (!existingModule) {
            val moduleCandidates = tree.filter { it.endsWith(".so") && !it.contains("libtvm") }
            val selected = when {
                options.preferVulkan -> moduleCandidates.firstOrNull { it.contains("vulkan", true) }
                    ?: moduleCandidates.firstOrNull { it.contains("cpu", true) }
                    ?: moduleCandidates.firstOrNull()
                else -> moduleCandidates.firstOrNull { it.contains("cpu", true) }
                    ?: moduleCandidates.firstOrNull { it.contains("vulkan", true) }
                    ?: moduleCandidates.firstOrNull()
            }
            if (selected != null) {
                onProgress("Downloading module: ${File(selected).name}")
                download(repo, selected, File(destDir, File(selected).name), onProgress)
            }
        }

        // 4) Optionally download weights
        if (options.includeWeights) {
            val shards = tree.filter { it.startsWith("params_shard_") && it.endsWith(".bin") }
            var done = 0
            for (p in shards) {
                done += 1
                onProgress("Downloading weights $done/${shards.size}: ${File(p).name}")
                download(repo, p, File(destDir, File(p).name), onProgress)
            }
        }

        // 5) Core configs/tokenizer
        val misc = listOf("mlc-chat-config.json", "tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt", "ndarray-cache.json")
        for (m in misc) {
            if (!File(destDir, m).exists()) {
                val hit = tree.firstOrNull { it == m || it.endsWith("/$m") }
                if (hit != null) {
                    onProgress("Downloading $m")
                    download(repo, hit, File(destDir, m), onProgress)
                }
            }
        }

        return@withContext destDir
    }

    private suspend fun resolveRepo(input: String): String? {
        if (input.contains('/')) return input
        val guess = "mlc-ai/$input"
        if (fetchTree(guess) != null) return guess
        val base = input.removeSuffix("-MLC")
        val q = URLEncoder.encode(base, StandardCharsets.UTF_8)
        val hits = search("mlc-ai", q)
        return hits.firstOrNull()
    }

    private suspend fun fetchTree(repo: String): List<String>? = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=1"
        val req = Request.Builder().url(url).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val txt = resp.body?.string() ?: return@use null
                val arr = JSONArray(txt)
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = o.optString("path")
                    if (p.isNotBlank()) out.add(p)
                }
                return@use out
            }
        } catch (_: IOException) { return@withContext null }
    }

    private suspend fun search(author: String, query: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models?author=$author&search=$query&limit=10"
        val req = Request.Builder().url(url).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val arr = JSONArray(resp.body?.string() ?: return@use emptyList<String>())
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (id.startsWith("mlc-ai/")) ids.add(id) else if (id.isNotBlank()) ids.add("mlc-ai/$id")
                }
                return@use ids
            }
        } catch (_: IOException) { return@withContext emptyList() }
    }

    private fun extractArchive(archive: File, outputDir: File, onProgress: (String) -> Unit) {
        when {
            archive.name.endsWith(".tar.gz") -> extractTarGz(archive, outputDir, onProgress)
            archive.name.endsWith(".tar") -> extractTar(archive, outputDir, onProgress)
            archive.name.endsWith(".zip") -> extractZip(archive, outputDir, onProgress)
        }
    }

    private fun extractTarGz(tarGzFile: File, outputDir: File, onProgress: (String) -> Unit) {
        onProgress("Extracting ${tarGzFile.name}")
        GZIPInputStream(tarGzFile.inputStream()).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                extractTarStream(tarIn, outputDir)
            }
        }
    }

    private fun extractTar(tarFile: File, outputDir: File, onProgress: (String) -> Unit) {
        onProgress("Extracting ${tarFile.name}")
        TarArchiveInputStream(tarFile.inputStream()).use { tarIn ->
            extractTarStream(tarIn, outputDir)
        }
    }

    private fun extractTarStream(tarIn: TarArchiveInputStream, outputDir: File) {
        var entry = tarIn.nextTarEntry
        val buffer = ByteArray(16 * 1024)
        while (entry != null) {
            val outFile = File(outputDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out ->
                    var r: Int
                    while (tarIn.read(buffer).also { r = it } != -1) {
                        out.write(buffer, 0, r)
                    }
                }
                if (outFile.name.endsWith(".so")) outFile.setExecutable(true, false)
            }
            entry = tarIn.nextTarEntry
        }
    }

    private fun extractZip(zipFile: File, outputDir: File, onProgress: (String) -> Unit) {
        onProgress("Extracting ${zipFile.name}")
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            val buffer = ByteArray(16 * 1024)
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        var r: Int
                        while (zipIn.read(buffer).also { r = it } != -1) {
                            out.write(buffer, 0, r)
                        }
                    }
                    if (outFile.name.endsWith(".so")) outFile.setExecutable(true, false)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private fun contentLength(repo: String, path: String): Long {
        val url = "https://huggingface.co/$repo/resolve/main/$path?download=true"
        val req = Request.Builder().url(url).head().build()
        return runCatching {
            http.newCall(req).execute().use { it.body?.contentLength() ?: -1L }
        }.getOrElse { -1L }
    }

    private fun download(repo: String, path: String, dst: File, onProgress: (String) -> Unit): Boolean {
        val url = "https://huggingface.co/$repo/resolve/main/$path?download=true"
        val req = Request.Builder().url(url).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val total = resp.body?.contentLength() ?: -1L
                var downloaded = 0L
                dst.parentFile?.mkdirs()
                dst.outputStream().use { out ->
                    resp.body?.byteStream()?.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var r: Int
                        while (input.read(buf).also { r = it } != -1) {
                            out.write(buf, 0, r)
                            downloaded += r
                            if (total > 0) onProgress("${File(path).name}: ${(downloaded * 100 / total).toInt()}%")
                        }
                    }
                }
                true
            }
        } catch (_: IOException) { false }
    }
}