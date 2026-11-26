package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.net.HttpClients

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.Locale

internal object DownloadHttp {

    private val skipHeaders = setOf(
        "host", "content-length", "range", "connection", "accept-encoding",
    )

    fun client(): OkHttpClient =
        HttpClients.defaultClient ?: throw DownloadException(
            DownloadErrorKind.NETWORK,
            "网络未初始化",
        )

    fun Request.Builder.withDownloadHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty()) return@forEach
            if (key.lowercase(Locale.ROOT) in skipHeaders) return@forEach
            if (key.any { it.code !in 32..126 }) return@forEach
            header(key, value)
        }
        return this
    }

    suspend fun fetchText(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .withDownloadHeaders(headers)
                .build()
            client().newCall(request).execute().use { response ->
                ensureSuccess(response.code)
                response.body.string()
            }
        }

    suspend fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        range: HlsByteRange? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
            .withDownloadHeaders(headers)
        if (range != null) {
            val end = range.offset + range.length - 1
            builder.header("Range", "bytes=${range.offset}-$end")
        }
        client().newCall(builder.build()).execute().use { response ->
            ensureSuccess(response.code)
            response.body.bytes()
        }
    }

    private fun ensureSuccess(code: Int) {
        if (code in 200..299) return
        if (code == 401 || code == 403 || code == 410) {
            throw DownloadException(DownloadErrorKind.EXPIRED, "下载链接已过期")
        }
        throw DownloadException(DownloadErrorKind.NETWORK, "HTTP $code")
    }
}
