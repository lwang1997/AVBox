package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.player.usecase.AdContentProbe
import com.github.avbox.osc.player.usecase.M3u8
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

import com.google.gson.Gson

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object HlsDownloader {

    private const val SEGMENT_CONCURRENCY = 3
    private const val SEGMENT_ATTEMPTS = 3

    private val gson = Gson()

    private data class HlsMeta(
        val count: Int = 0,
        val initUri: String? = null,
        val segments: List<String> = emptyList(),
    )

    suspend fun download(
        task: DownloadTask,
        onSegments: (done: Int, total: Int) -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadOutput = withContext(Dispatchers.IO) {
        val dir = DownloadStorage.tempDir(task.id)
        if (!dir.exists() && !dir.mkdirs()) {
            throw DownloadException(DownloadErrorKind.STORAGE, "无法创建下载目录")
        }
        var playlistUrl = task.url
        var content = DownloadHttp.fetchText(playlistUrl, task.headers)
        var playlist = HlsPlaylistParser.parse(content, playlistUrl)
        // 主列表里声明了独立音轨时，切到清晰度子列表后这个标记会丢，单独记下来。
        var separateAudio = playlist.hasSeparateAudio
        if (playlist.master) {
            val best = playlist.variants.maxByOrNull { it.bandwidth }
                ?: throw DownloadException(DownloadErrorKind.UNSUPPORTED, "没有可用清晰度")
            playlistUrl = best.uri
            content = DownloadHttp.fetchText(playlistUrl, task.headers)
            playlist = HlsPlaylistParser.parse(content, playlistUrl)
            separateAudio = separateAudio || playlist.hasSeparateAudio
        }
        if (playlist.master) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "无法解析多级播放列表")
        }
        if (playlist.live) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "直播流不支持内置下载")
        }
        if (KV.get(KVKeys.M3U8_PURIFY, false)) {
            content = purifyIfNeeded(content, playlistUrl, task.headers)
            playlist = HlsPlaylistParser.parse(content, playlistUrl)
            separateAudio = separateAudio || playlist.hasSeparateAudio
            if (playlist.live) {
                throw DownloadException(DownloadErrorKind.UNSUPPORTED, "直播流不支持内置下载")
            }
        }
        if (separateAudio) {
            // 音轨在另一个播放列表里，内置下载只会拼出视频分片，产出无声文件。
            throw DownloadException(
                DownloadErrorKind.UNSUPPORTED,
                "该 HLS 使用独立音轨，内置下载会没有声音",
            )
        }
        playlist.segments.forEach { segment ->
            val method = segment.key?.method ?: return@forEach
            if (method != "AES-128") {
                throw DownloadException(
                    DownloadErrorKind.UNSUPPORTED,
                    "不支持的 HLS 加密方式：$method",
                )
            }
        }

        val metaFile = File(dir, "segments.json")
        val expectedMeta = HlsMeta(
            count = playlist.segments.size,
            initUri = playlist.initUri,
            segments = playlist.segments.map { it.uri },
        )
        val existingMeta = runCatching {
            gson.fromJson(metaFile.readText(), HlsMeta::class.java)
        }.getOrNull()
        if (existingMeta != null && existingMeta != expectedMeta) {
            clearSegments(dir)
        }
        runCatching { metaFile.writeText(gson.toJson(expectedMeta)) }

        val initFile = File(dir, "init.bin")
        if (playlist.initUri != null && !initFile.exists()) {
            val bytes = DownloadHttp.fetchBytes(playlist.initUri, task.headers, playlist.initRange)
            initFile.writeBytes(bytes)
        }

        val total = playlist.segments.size
        var done = 0
        var existingBytes = 0L
        playlist.segments.forEach { segment ->
            val length = segmentFile(dir, segment.index).length()
            if (length > 0L) done++
            existingBytes += length
        }
        // 续传时进度要带上已经落盘的分段，否则暂停再继续会把已下载的几百 MB 归零。
        val resumedBytes = existingBytes
        onSegments(done, total)
        onProgress(resumedBytes, 0L)
        val lock = Any()
        val bytesLocked = java.util.concurrent.atomic.AtomicLong(0L)
        val segmentFiles = arrayOfNulls<File>(total)
        playlist.segments.forEach { segment ->
            segmentFiles[segment.index] = segmentFile(dir, segment.index)
        }
        val semaphore = Semaphore(SEGMENT_CONCURRENCY)
        coroutineScope {
            playlist.segments.map { segment ->
                async {
                    if (segmentFiles[segment.index]?.length() ?: 0L > 0L) return@async
                    semaphore.withPermit {
                        downloadSegment(task, segment, segmentFiles[segment.index]!!)
                        val nowDone = synchronized(lock) {
                            done++
                            done
                        }
                        val size = segmentFiles[segment.index]?.length() ?: 0L
                        bytesLocked.addAndGet(size)
                        onSegments(nowDone, total)
                        onProgress(resumedBytes + bytesLocked.get(), 0L)
                    }
                }
            }.awaitAll()
        }

        val ext = if (playlist.initUri != null) "mp4" else "ts"
        val mime = if (ext == "mp4") "video/mp4" else "video/mp2t"
        val output = File(dir, "payload.$ext")
        output.outputStream().buffered().use { out ->
            if (playlist.initUri != null && initFile.exists()) {
                initFile.inputStream().use { it.copyTo(out) }
            }
            playlist.segments.forEach { segment ->
                currentCoroutineContext().ensureActive()
                val file = segmentFile(dir, segment.index)
                if (!file.exists()) {
                    throw DownloadException(DownloadErrorKind.NETWORK, "分片缺失：${segment.index}")
                }
                file.inputStream().use { it.copyTo(out) }
            }
        }
        DownloadOutput(output, ext, mime, null)
    }

    private suspend fun purifyIfNeeded(
        content: String,
        url: String,
        headers: Map<String, String>,
    ): String {
        val working = try {
            AdContentProbe.dropAdSegments(content, url, headers)
        } catch (_: Throwable) {
            content
        }
        val basePath = getBasePath(url)
        val purified = try {
            M3u8.purify(basePath, working, true)
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
            null
        }
        return purified ?: content
    }

    private suspend fun downloadSegment(
        task: DownloadTask,
        segment: HlsSegment,
        target: File,
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..SEGMENT_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                val raw = DownloadHttp.fetchBytes(segment.uri, task.headers, segment.range)
                val data = decryptIfNeeded(segment, raw, task.headers)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
                return
            } catch (e: DownloadException) {
                if (e.errorKind != DownloadErrorKind.NETWORK) throw e
                lastError = e
            } catch (th: Throwable) {
                lastError = th
            }
            if (attempt < SEGMENT_ATTEMPTS) {
                kotlinx.coroutines.delay(if (attempt == 1) 800L else 2000L)
            }
        }
        throw DownloadException(
            DownloadErrorKind.NETWORK,
            "分片下载失败：${segment.index} ${lastError?.message ?: ""}",
        )
    }

    private suspend fun decryptIfNeeded(
        segment: HlsSegment,
        raw: ByteArray,
        headers: Map<String, String>,
    ): ByteArray {
        val key = segment.key ?: return raw
        val keyUri = key.uri ?: throw DownloadException(DownloadErrorKind.UNSUPPORTED, "HLS 密钥地址为空")
        val keyBytes = keyCache[keyUri] ?: DownloadHttp.fetchBytes(keyUri, headers).also {
            keyCache[keyUri] = it
        }
        if (keyBytes.size < 16) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "HLS 密钥长度不正确")
        }
        val iv = key.iv ?: sequenceIv(segment.sequence)
        val secret = SecretKeySpec(keyBytes.copyOfRange(0, 16), "AES")
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
            cipher.doFinal(raw)
        } catch (_: Throwable) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
            cipher.doFinal(raw)
        }
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun segmentFile(dir: File, index: Int): File =
        File(dir, String.format(java.util.Locale.ROOT, "seg_%05d.ts", index))

    private fun clearSegments(dir: File) {
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("seg_") || name == "init.bin" || name == "payload.ts" || name == "payload.mp4") {
                runCatching { file.delete() }
            }
        }
    }

    private fun getBasePath(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val idx = clean.lastIndexOf('/')
        if (idx < 0) return "$clean/"
        val dir = clean.substring(0, idx + 1)
        return if (dir.endsWith("://")) "$clean/" else dir
    }

    private val keyCache = ConcurrentHashMap<String, ByteArray>()
}
