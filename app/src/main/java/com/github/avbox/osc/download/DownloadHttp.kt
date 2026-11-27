package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.net.HttpClients

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.Locale
import java.util.concurrent.TimeUnit

internal object DownloadHttp {

    private val skipHeaders = setOf(
        "host", "content-length", "range", "connection", "accept-encoding",
    )

    private const val READ_TIMEOUT_SECONDS = 30L

    @Volatile
    private var downloadClient: OkHttpClient? = null

    fun client(): OkHttpClient {
        downloadClient?.let { return it }
        val base = HttpClients.defaultClient ?: throw DownloadException(
            DownloadErrorKind.NETWORK,
            "网络未初始化",
        )
        return synchronized(this) {
            downloadClient ?: base.newBuilder()
                // 下载是长时间连续读取，通用 client 的 10s 读超时对慢速 CDN 太短。
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .also { downloadClient = it }
        }
    }

    /**
     * 能安全转发的请求头：去掉 Host/Range/Content-Length 这类会破坏请求的头。
     * 内置下载与交给 1DM（extra_headers）共用同一份过滤规则。
     */
    fun forwardableHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(headers.size)
        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty()) return@forEach
            if (key.lowercase(Locale.ROOT) in skipHeaders) return@forEach
            if (key.any { it.code !in 32..126 }) return@forEach
            result[key] = value
        }
        return result
    }

    fun Request.Builder.withDownloadHeaders(headers: Map<String, String>): Request.Builder {
        forwardableHeaders(headers).forEach { (key, value) -> header(key, value) }
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

    /** 需要自己读流、自己发 Range 的调用方用这个（直链断点续传）。 */
    fun streamRequest(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder()
            .url(url)
            .get()
            // 偏移量必须针对原始实体，不能让 OkHttp 协商 gzip。
            .header("Accept-Encoding", "identity")
            .withDownloadHeaders(headers)

    suspend fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        range: HlsByteRange? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
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
