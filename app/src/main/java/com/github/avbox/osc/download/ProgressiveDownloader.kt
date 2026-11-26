package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import okhttp3.Request
import okhttp3.Response

import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Locale

internal class DownloadException(
    val errorKind: DownloadErrorKind,
    message: String,
) : Exception(message)

internal data class DownloadOutput(
    val file: File,
    val ext: String,
    val mime: String,
    val nameHint: String?,
)

internal object ProgressiveDownloader {

    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_ATTEMPTS = 3

    private val skipHeaders = setOf(
        "host", "content-length", "range", "connection", "accept-encoding",
    )

    suspend fun download(
        task: DownloadTask,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadOutput = withContext(Dispatchers.IO) {
        val dir = DownloadStorage.tempDir(task.id)
        if (!dir.exists() && !dir.mkdirs()) {
            throw DownloadException(DownloadErrorKind.STORAGE, "无法创建下载目录")
        }
        val part = File(dir, "payload.part")
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                return@withContext downloadOnce(task, part, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                if (e.errorKind != DownloadErrorKind.NETWORK) throw e
                lastError = e
                AppLog.i("progressive retry ${task.id} attempt=$attempt: ${e.message}")
                if (attempt < MAX_ATTEMPTS) delay(if (attempt == 1) 1000L else 3000L)
            } catch (th: Throwable) {
                lastError = th
                AppLog.e("DownloadManager", th)
                if (attempt < MAX_ATTEMPTS) delay(if (attempt == 1) 1000L else 3000L)
            }
        }
        throw DownloadException(
            DownloadErrorKind.NETWORK,
            lastError?.message ?: "下载失败",
        )
    }

    private suspend fun downloadOnce(
        task: DownloadTask,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutput {
        val client = HttpClients.defaultClient
            ?: throw DownloadException(DownloadErrorKind.NETWORK, "网络未初始化")
        var existing = if (part.exists()) part.length() else 0L
        val request = Request.Builder()
            .url(task.url)
            .get()
            .applyHeaders(task.headers)
            .apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }
            .build()
        val response = client.newCall(request).execute()
        try {
            val code = response.code
            if (code == 401 || code == 403 || code == 410) {
                throw DownloadException(DownloadErrorKind.EXPIRED, "下载链接已过期")
            }
            if (code == 416 && existing > 0) {
                val total = parseTotalFromContentRange(response.header("Content-Range")) ?: existing
                if (total > 0 && existing >= total) {
                    return buildOutput(task, part, response)
                }
                existing = 0L
                part.delete()
                throw DownloadException(DownloadErrorKind.NETWORK, "继续下载失败，重新开始")
            }
            if (code != 200 && code != 206) {
                throw DownloadException(DownloadErrorKind.NETWORK, "HTTP $code")
            }
            val body = response.body
            val contentLength = body.contentLength()
            val total = if (code == 206) {
                parseTotalFromContentRange(response.header("Content-Range"))
                    ?: if (contentLength > 0) existing + contentLength else 0L
            } else {
                if (contentLength > 0) contentLength else 0L
            }
            if (code == 200 && existing > 0) {
                part.delete()
                existing = 0L
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                if (code == 200) raf.setLength(0)
                var downloaded = existing
                var lastReport = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 300) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                }
                onProgress(downloaded, total)
            }
            return buildOutput(task, part, response)
        } finally {
            response.close()
        }
    }

    private fun buildOutput(task: DownloadTask, part: File, response: Response): DownloadOutput {
        val dispositionName = parseContentDisposition(response.header("Content-Disposition"))
        val urlExt = DownloadStorage.extensionFromUrl(task.url)
        val nameExt = DownloadStorage.extensionFromUrl(dispositionName)
        val mimeExt = DownloadStorage.extensionFromMime(response.header("Content-Type"))
        val ext = (nameExt ?: urlExt ?: mimeExt ?: "mp4").lowercase(Locale.ROOT)
        val mime = DownloadStorage.mimeForExtension(ext)
        return DownloadOutput(part, ext, mime, dispositionName)
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
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

    private fun parseTotalFromContentRange(range: String?): Long? {
        if (range.isNullOrEmpty()) return null
        val total = range.substringAfterLast('/', "")
        return total.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun parseContentDisposition(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val utf8 = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        if (!utf8.isNullOrEmpty()) {
            return runCatching { URLDecoder.decode(utf8, "UTF-8") }.getOrNull()
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
    }
}
