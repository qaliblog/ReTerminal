package com.rk.terminal.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object MlcModuleFetcher {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun download(url: String, dst: File) {
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

    /**
     * Fetch a precompiled model module (.so) into [modelDir]. Returns the saved file, or null on failure.
     */
    suspend fun fetchModuleSo(modelDir: File, moduleUrl: String, suggestedName: String? = null): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = suggestedName?.ifBlank { null } ?: moduleUrl.substringAfterLast('/')
                val dst = File(modelDir, name)
                download(moduleUrl, dst)
                dst
            }.getOrNull()
        }

    /**
     * Fetch TVM runtime library into libs/<abi>/ under [modelDir]. Returns saved file or null.
     */
    suspend fun fetchRuntimeLib(modelDir: File, abi: String, soUrl: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dst = File(modelDir, "libs/$abi/${soUrl.substringAfterLast('/')}")
                download(soUrl, dst)
                dst
            }.getOrNull()
        }
}