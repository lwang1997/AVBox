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
     * 确保 baseUrl 指向的目录存在。坚果云等 WebDAV 服务禁止在根目录直接写文件，
     * 必须先有子目录；MKCOL 是幂等的：201=新建成功，405=已存在，二者都算就绪。
     */
    private suspend fun ensureDirectory() {
        if (directoryReady) return
        val dirUrl = baseUrl.trim().trimEnd('/') + "/"
        val request = Request.Builder()
            .url(dirUrl)
            .header("Authorization", authHeader)
            .method("MKCOL", "".toRequestBody(null))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 405) {
                    directoryReady = true
                } else {
                    AppLog.e("WebDavClient MKCOL $dirUrl failed: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient MKCOL io failed: ${e.message}")
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

    suspend fun put(fileName: String, content: String) = withContext(Dispatchers.IO) {
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
            }
        } catch (e: IOException) {
            AppLog.e("WebDavClient put $fileName failed: ${e.message}")
            throw e
        }
    }

    /** 连通性测试：PUT 一个临时文件再删除（WebDAV 最小实现不保证 DELETE，故用 PROPFIND 根目录） */
    suspend fun test(): Boolean = withContext(Dispatchers.IO) {
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
