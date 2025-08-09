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
        val runtimeOk = ensureRuntimeSo(modelDir, libsDir)
        val moduleOk = ensureModelModuleSo(modelDir)
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
        } ?: return false
        libsDir.mkdirs()
        val fileName = runtimeNames.first { runtimePath.endsWith(it) }
        val dst = File(libsDir, fileName)
        return downloadFromHf(repo, runtimePath, dst)
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
        // Pick first hit
        return results.firstOrNull()?.let { it }
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
                    if (id.isNotBlank()) ids.add(id)
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
}