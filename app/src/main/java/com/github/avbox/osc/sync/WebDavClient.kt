package com.github.avbox.osc.sync

import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 最小 WebDAV 客户端：只依赖 GET / PUT，Basic 认证。
 * 用独立 OkHttpClient，避免复用宿主 clients 上的代理 / 重试 / 重定向策略影响同步语义。
 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val authHeader: String by lazy { Credentials.basic(user, password) }

    /** MKCOL 只做一次；不支持 MKCOL 的服务不阻断主流程，由后续 PUT/GET 自行报错。 */
    private var directoryReady = false

    private fun urlOf(fileName: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return "$base/$fileName"
    }

    /**
     * 确保 baseUrl 指向的目录存在（含中间父目录，逐级 MKCOL）。
     * 坚果云等 WebDAV 服务禁止在根目录直接写文件，必须先有子目录；
     * MKCOL 是幂等的：201=新建成功，405=已存在，二者都算就绪。
     * 单级失败只记日志不抛出 —— 不支持 MKCOL 的服务由后续 PUT/GET 自行报错。
     */
    private suspend fun ensureDirectory() {
        if (directoryReady) return
        val base = baseUrl.trim().trimEnd('/')
        val schemeIdx = base.indexOf("://")
        val pathStart = base.indexOf('/', schemeIdx + 3).let { if (it < 0) base.length else it }
        val root = base.substring(0, pathStart)                      // scheme://host[:port]
        val segments = base.substring(pathStart).split('/').filter { it.isNotEmpty() }
        var prefix = root
        var allOk = true
        for (segment in segments) {
            prefix = "$prefix/$segment"
            if (!mkcol("$prefix/")) {
                allOk = false
                break
            }
        }
        // 全部层级就绪才置位；中途失败保持 false，下次 PUT 前会整链重试
        directoryReady = allOk
        if (!allOk) AppLog.e("WebDavClient ensureDirectory incomplete: $base")
    }

    /** MKCOL 单级目录：201/405 视为就绪；其余（403 禁止等）返回 false。 */
    private suspend fun mkcol(dirUrl: String): Boolean {
        val request = Request.Builder()
            .url(dirUrl)
            .header("Authorization", authHeader)
            .method("MKCOL", "".toRequestBody(null))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 405 -> true
                    else -> {
                        AppLog.e("WebDavClient MKCOL $dirUrl failed: HTTP ${response.code}")
                        false
                    }
                }
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient MKCOL io failed: ${e.message}")
            false
        }
    }

    suspend fun get(fileName: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlOf(fileName))
            .header("Authorization", authHeader)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string()
                    response.code == 404 -> null
                    else -> throw IOException("WebDAV GET failed: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient get $fileName failed: ${e.message}")
            throw e
        }
    }

    /** 条件 GET 结果：304 = 远端未变（复用本地缓存）；404 = 不存在；否则带正文与新 ETag。 */
    sealed class ConditionalGet {
        object NotModified : ConditionalGet()
        object NotFound : ConditionalGet()
        data class Content(val body: String, val etag: String?) : ConditionalGet()
    }

    /** 条件 GET：etag 非空时带 If-None-Match（远端未变只回 304，不传正文）；为空则退化为普通 GET。 */
    suspend fun getConditional(fileName: String, etag: String?): ConditionalGet = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(urlOf(fileName))
            .header("Authorization", authHeader)
            .get()
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        val request = builder.build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> ConditionalGet.NotModified
                    response.code == 404 -> ConditionalGet.NotFound
                    response.isSuccessful -> ConditionalGet.Content(
                        response.body?.string() ?: "",
                        response.header("ETag"),
                    )
                    else -> throw IOException("WebDAV GET failed: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient getConditional $fileName failed: ${e.message}")
            throw e
        }
    }

    /**
     * PUT 并返回服务端签发的新 ETag（多数服务在响应头回 ETag；不回则 null，
     * 调用方应清空本地 ETag，下次退回全量 GET，不影响正确性）。
     */
    suspend fun put(fileName: String, content: String): String? = withContext(Dispatchers.IO) {
        ensureDirectory()
        val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(urlOf(fileName))
            .header("Authorization", authHeader)
            .put(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("WebDAV PUT failed: HTTP ${response.code}")
                response.header("ETag")
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient put $fileName failed: ${e.message}")
            throw e
        }
    }

    /** 连通性测试：PUT 一个临时文件再删除（WebDAV 最小实现不保证 DELETE，故用 PROPFIND 根目录） */
    suspend fun test(): Boolean = withContext(Dispatchers.IO) {
        // 先确保目录存在：首次使用时 AVBox 目录尚未创建，直接 PROPFIND 会 404 误报失败
        ensureDirectory()
        val request = Request.Builder()
            .url(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .header("Authorization", authHeader)
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody(null))
            .build()
        try {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: IOException) {
            AppLog.e("WebDavClient test failed: ${e.message}")
            false
        }
    }
}
