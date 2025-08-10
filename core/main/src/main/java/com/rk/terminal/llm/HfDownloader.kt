package com.rk.terminal.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Minimal Hugging Face weights downloader tailored for MLC-converted repos.
 *
 * Replicates the essential logic of MLC's download_and_cache_mlc_weights:
 * - Fetch mlc-chat-config.json
 * - Fetch ndarray-cache.json and read shard records
 * - Download all param shards concurrently, validating md5 when present
 */
object HfDownloader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    data class Repo(val user: String, val repo: String) {
        val baseResolve: String get() = "https://huggingface.co/$user/$repo/resolve/main/"
        val baseRaw: String get() = "https://huggingface.co/$user/$repo/raw/main/"
        val display: String get() = "$user/$repo"
    }

    private fun parseRepo(input: String): Repo {
        val trimmed = input.trim()
        val prefix = when {
            trimmed.startsWith("HF://", ignoreCase = true) -> "HF://"
            trimmed.startsWith("https://huggingface.co/") -> "https://huggingface.co/"
            else -> ""
        }
        val rest = trimmed.removePrefix(prefix)
        val parts = rest.split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "Invalid HF repo: $input" }
        return Repo(parts[0], parts[1])
    }

    private fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return BigInteger(1, md.digest()).toString(16).padStart(32, '0')
    }

    private fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun downloadTo(url: String, dst: File) {
        dst.parentFile?.mkdirs()
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty body for $url")
            dst.outputStream().sink().buffer().use { sink ->
                sink.writeAll(body.source())
            }
        }
    }

    suspend fun downloadMlcWeights(repoId: String, destDir: File, onLog: (String) -> Unit = {}): Boolean {
        return withContext(Dispatchers.IO) {
            val repo = parseRepo(repoId)
            destDir.mkdirs()

            // 1) Fetch mlc-chat-config.json
            val configUrls = listOf(
                repo.baseResolve + "mlc-chat-config.json",
                repo.baseRaw + "mlc-chat-config.json"
            )
            var configBytes: ByteArray? = null
            for (u in configUrls) {
                runCatching { configBytes = get(u) }.onSuccess { break }
            }
            if (configBytes == null) {
                onLog("Failed to fetch mlc-chat-config.json from ${repo.display}")
                return@withContext false
            }
            File(destDir, "mlc-chat-config.json").outputStream().use { it.write(configBytes) }
            onLog("Saved mlc-chat-config.json")

            // 2) Fetch ndarray-cache.json for bin shard list
            val ndarrayUrls = listOf(
                repo.baseResolve + "ndarray-cache.json",
                repo.baseRaw + "ndarray-cache.json"
            )
            var ndarrayBytes: ByteArray? = null
            for (u in ndarrayUrls) {
                runCatching { ndarrayBytes = get(u) }.onSuccess { break }
            }
            if (ndarrayBytes == null) {
                onLog("Failed to fetch ndarray-cache.json; repository may be invalid")
                return@withContext false
            }
            val ndarray = JSONObject(String(ndarrayBytes!!))
            val records: JSONArray = ndarray.optJSONArray("records") ?: JSONArray()
            if (records.length() == 0) {
                onLog("ndarray-cache.json has no records")
                return@withContext false
            }

            // 3) Download each shard concurrently (up to CPU count)
            val scope = CoroutineScope(Dispatchers.IO)
            val tasks = (0 until records.length()).map { idx ->
                scope.async {
                    val rec = records.getJSONObject(idx)
                    val path = rec.getString("dataPath")
                    val md5sum = rec.optString("md5sum", null).takeIf { it?.isNotBlank() == true }
                    val url = repo.baseResolve + path
                    val dst = File(destDir, path)

                    // Skip if already exists and matches size/hash
                    if (dst.exists() && md5sum != null) {
                        runCatching {
                            if (md5(dst) == md5sum) {
                                onLog("OK (cached): $path")
                                return@async true
                            }
                        }
                    }

                    onLog("Downloading: $path")
                    downloadTo(url, dst)
                    if (md5sum != null) {
                        val got = md5(dst)
                        if (!got.equals(md5sum, ignoreCase = true)) {
                            dst.delete()
                            throw IOException("MD5 mismatch for $path expected=$md5sum got=$got")
                        }
                    }
                    onLog("OK: $path")
                    true
                }
            }

            runCatching { tasks.awaitAll() }.onFailure { e ->
                onLog("Download failed: ${e.message}")
                return@withContext false
            }

            onLog("All shards downloaded for ${repo.display} -> ${destDir.absolutePath}")
            true
        }
    }
}