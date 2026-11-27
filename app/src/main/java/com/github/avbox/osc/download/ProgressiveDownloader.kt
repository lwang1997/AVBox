package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.util.AppLog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import okhttp3.Response

import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Locale

internal class DownloadException(
    val errorKind: DownloadErrorKind,
    message: String,
) : Exception(message)

/** 地址后缀不像 m3u8、下载内容却是播放列表：由调用方切换成 HLS 重新下。 */
internal class HlsPlaylistDetectedException : Exception("内容是 m3u8 播放列表")

internal data class DownloadOutput(
    val file: File,
    val ext: String,
    val mime: String,
    val nameHint: String?,
)

internal object ProgressiveDownloader {

    private const val BUFFER_SIZE = 128 * 1024
    private const val MAX_ATTEMPTS = 3
    private const val META_NAME = "payload.meta"
    private const val HEAD_SNIFF_BYTES = 64

    private data class Validators(
        val etag: String? = null,
        val lastModified: String? = null,
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
            } catch (e: HlsPlaylistDetectedException) {
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
        val client = DownloadHttp.client()
        var existing = if (part.exists()) part.length() else 0L
        val validators = readValidators(part)
        val builder = DownloadHttp.streamRequest(task.url, task.headers)
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
            // 上次记录的校验器还在就用 If-Range：远端文件已变时服务端会返回 200，我们整段重下。
            (validators.etag ?: validators.lastModified)?.let { builder.header("If-Range", it) }
        }
        val response = client.newCall(builder.build()).execute()
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
                resetPart(part)
                throw DownloadException(DownloadErrorKind.NETWORK, "继续下载失败，重新开始")
            }
            if (code != 200 && code != 206) {
                throw DownloadException(DownloadErrorKind.NETWORK, "HTTP $code")
            }
            if (code == 206 && existing > 0) {
                val start = parseRangeStart(response.header("Content-Range"))
                if (start != null && start != existing) {
                    // 服务端给的起点和我们预期不一致，接着写只会拼出坏文件。
                    resetPart(part)
                    existing = 0L
                    throw DownloadException(DownloadErrorKind.NETWORK, "续传位置不一致，重新开始")
                }
            }
            val body = response.body
            val contentLength = body.contentLength()
            val total = if (code == 206) {
                parseTotalFromContentRange(response.header("Content-Range"))
                    ?: if (contentLength > 0) existing + contentLength else 0L
            } else {
                if (contentLength > 0) contentLength else 0L
            }
            if (code == 200) {
                if (existing > 0) resetPart(part)
                existing = 0L
                writeValidators(part, response)
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                if (code == 200) raf.setLength(0)
                var downloaded = existing
                var lastReport = 0L
                var signatureChecked = existing > 0
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    if (!signatureChecked) {
                        // 网络首块可能只有几个字节，先凑够长度再判断是不是播放列表。
                        val head = ByteArray(HEAD_SNIFF_BYTES)
                        var filled = 0
                        while (filled < head.size) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(head, filled, head.size - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        if (looksLikePlaylist(head, filled)) throw HlsPlaylistDetectedException()
                        if (filled > 0) {
                            raf.write(head, 0, filled)
                            downloaded += filled
                        }
                    }
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

    internal fun looksLikePlaylist(buffer: ByteArray, length: Int): Boolean {
        var index = 0
        if (length >= 3 &&
            buffer[0] == 0xEF.toByte() &&
            buffer[1] == 0xBB.toByte() &&
            buffer[2] == 0xBF.toByte()
        ) {
            index = 3
        }
        while (index < length) {
            val ch = buffer[index].toInt().toChar()
            if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') break
            index++
        }
        val tag = "#EXTM3U"
        if (index + tag.length > length) return false
        for (offset in tag.indices) {
            if (buffer[index + offset] != tag[offset].code.toByte()) return false
        }
        return true
    }

    private fun resetPart(part: File) {
        runCatching { part.delete() }
        runCatching { metaFile(part).delete() }
    }

    private fun metaFile(part: File): File = File(part.parentFile, META_NAME)

    private fun readValidators(part: File): Validators {
        val file = metaFile(part)
        if (!file.exists()) return Validators()
        return runCatching {
            val lines = file.readLines()
            Validators(
                etag = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                lastModified = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.getOrDefault(Validators())
    }

    private fun writeValidators(part: File, response: Response) {
        val etag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        if (etag == null && lastModified == null) {
            runCatching { metaFile(part).delete() }
            return
        }
        runCatching {
            metaFile(part).writeText((etag ?: "") + "\n" + (lastModified ?: ""))
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

    private fun parseRangeStart(range: String?): Long? {
        if (range.isNullOrEmpty()) return null
        // 形如 "bytes 100-199/1000"
        val body = range.substringAfter(' ', "")
        return body.substringBefore('-').trim().toLongOrNull()
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
