package com.github.avbox.osc.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

import androidx.core.content.ContextCompat

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadKind
import com.github.avbox.osc.bean.DownloadRequest
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.DownloadRepository
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object DownloadManager {

    const val WORKERS = 3
    private const val DB_WRITE_INTERVAL_MS = 2000L
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024
    private const val SPEED_WINDOW_MS = 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val repository: DownloadRepository
        get() = AppGraph.downloadRepository

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastDbWrite = ConcurrentHashMap<String, Long>()
    private val speedSamples = ConcurrentHashMap<String, SpeedSample>()
    private data class SpeedSample(val time: Long, val bytes: Long)
    private val running = AtomicInteger(0)
    private var workersStarted = false
    private var networkCallbackRegistered = false

    @Volatile
    private var bootstrapped = false

    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        ensureWorkers()
        scope.launch {
            runCatching {
                val unfinished = repository.getUnfinished()
                val autoResume = DownloadSettings.autoResume
                unfinished.forEach { task ->
                    val restored = task.copy(
                        status = if (autoResume) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
                        updatedAt = System.currentTimeMillis(),
                    )
                    repository.upsert(restored)
                    if (autoResume) queue.trySend(task.id)
                }
                refresh()
                if (autoResume && unfinished.isNotEmpty()) updateService()
            }.onFailure { AppLog.e("DownloadManager", it) }
        }
        registerNetworkCallback()
    }

    suspend fun enqueue(request: DownloadRequest): String {
        ensureWorkers()
        val existing = _tasks.value.firstOrNull { task ->
            task.status != DownloadStatus.COMPLETED &&
                task.status != DownloadStatus.CANCELED &&
                task.url == request.url &&
                task.vodId == request.vodId &&
                task.playFlag == request.playFlag &&
                task.episodeIndex == request.episodeIndex
        }
        if (existing != null) {
            // 同一条已暂停/失败的记录直接续传，别在列表里再堆一份。
            if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) {
                resume(existing.id)
            }
            return existing.id
        }

        val now = System.currentTimeMillis()
        val kind = request.kindHint ?: detectKind(request.url)
        val ext = when (kind) {
            DownloadKind.HLS -> "ts"
            DownloadKind.PROGRESSIVE -> DownloadStorage.extensionFromUrl(request.url) ?: "mp4"
        }
        val fileName = request.fileName ?: DownloadStorage.buildFileName(
            request.vodName,
            request.episodeName,
            request.title,
            ext,
        )
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            sourceKey = request.sourceKey,
            vodId = request.vodId,
            vodName = request.vodName,
            playFlag = request.playFlag,
            episodeIndex = request.episodeIndex,
            episodeName = request.episodeName,
            rawSeriesUrl = request.rawSeriesUrl,
            url = request.url,
            headers = request.headers,
            kind = kind,
            fileName = fileName,
            dirHint = request.vodName,
            totalBytes = 0L,
            downloadedBytes = 0L,
            segmentsDone = 0,
            segmentsTotal = 0,
            status = DownloadStatus.QUEUED,
            error = null,
            errorKind = null,
            etag = null,
            lastModified = null,
            contentUri = null,
            createdAt = now,
            updatedAt = now,
            completedAt = 0L,
        )
        withContext(NonCancellable) {
            repository.upsert(task)
            refresh()
        }
        queue.trySend(task.id)
        updateService()
        return task.id
    }

    suspend fun hlsHasSeparateAudio(url: String, headers: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = DownloadHttp.fetchText(url, headers)
                HlsPlaylistParser.parse(content, url).hasSeparateAudio
            }.getOrDefault(false)
        }

    fun pause(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.active) return
        scope.launch {
            jobs.remove(id)?.cancelAndJoin()
            val current = _tasks.value.firstOrNull { it.id == id }
            // 取消与下载完成可能同时发生，只有仍处于活动状态时才落暂停，避免把已完成的任务改回暂停。
            if (current != null && current.active) {
                flushProgress(current)
                transition(id, DownloadStatus.PAUSED)
            }
            updateService()
        }
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.PAUSED && task.status != DownloadStatus.FAILED) return
        scope.launch {
            jobs.remove(id)?.cancelAndJoin()
            transition(id, DownloadStatus.QUEUED)
            queue.trySend(id)
            updateService()
        }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.COMPLETED) return
        scope.launch {
            transition(id, DownloadStatus.CANCELED)
            jobs.remove(id)?.cancelAndJoin()
            DownloadStorage.clearTemp(id)
            updateService()
        }
    }

    suspend fun delete(id: String, deleteFile: Boolean) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: repository.get(id) ?: return
        // 先等下载线程真正退出，避免它在删除之后又把任务写回库。
        withContext(NonCancellable) {
            runCatching { jobs.remove(id)?.cancelAndJoin() }
        }
        if (deleteFile) {
            DownloadStorage.deletePublished(task)
        }
        DownloadStorage.clearTemp(id)
        withContext(NonCancellable) {
            runCatching { repository.delete(id) }
                .onFailure { AppLog.e("DownloadManager", it) }
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
        updateService()
    }

    fun pauseAll() {
        _tasks.value.filter { it.active }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        _tasks.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) }
    }

    /** 仅 Wi-Fi 之类的开关变化后，重新把排队中的任务投给工作线程。 */
    fun retryPending() {
        if (!bootstrapped) return
        scope.launch { dispatchQueued() }
    }

    fun isWifiConnected(): Boolean {
        val ctx = AppContextHolder.context() ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 走 allNetworks 而不是 activeNetwork：开 VPN 时默认网络是 VPN，但 Wi-Fi 仍在列表中。
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureWorkers() {
        synchronized(this) {
            if (workersStarted) return
            workersStarted = true
            repeat(WORKERS) {
                scope.launch {
                    for (id in queue) {
                        val job = launch {
                            runCatching { runTask(id) }
                                .onFailure { AppLog.e("DownloadManager", it) }
                        }
                        jobs[id] = job
                        job.join()
                        jobs.remove(id)
                    }
                }
            }
        }
    }

    private suspend fun runTask(id: String) {
        val queued = repository.get(id) ?: return
        if (queued.status != DownloadStatus.QUEUED) return
        // 让任务留在队列里等待网络，不能悄悄丢掉；Wi-Fi 回来时由网络回调重新投递。
        if (!canRun()) return
        // 同一个 id 可能被重复投递，避免两个线程同时写同一个分片文件。
        if (!inFlight.add(id)) return
        var slotHeld = false
        try {
            acquireSlot()
            slotHeld = true
            var task = repository.get(id) ?: return
            if (task.status != DownloadStatus.QUEUED) return
            // HLS 拿不到 Content-Length（totalBytes=0），这时至少留一个下限，别写到一半才失败。
            // freeBytes() 取不到（0）时不拦，避免误报。
            val freeBytes = DownloadStorage.freeBytes()
            val required = if (task.totalBytes > 0) task.totalBytes + MIN_FREE_BYTES else MIN_FREE_BYTES
            if (freeBytes > 0 && freeBytes < required) {
                throw DownloadException(DownloadErrorKind.STORAGE, "存储空间不足")
            }
            transition(id, DownloadStatus.DOWNLOADING)
            val output = try {
                downloadTask(task, id)
            } catch (e: HlsPlaylistDetectedException) {
                // 地址没有 .m3u8 后缀，内容却是播放列表：丢掉刚写下的文本，按 HLS 重来。
                AppLog.i("echo-download-kind-switch id=$id")
                DownloadStorage.clearTemp(id)
                task = task.copy(kind = DownloadKind.HLS)
                switchKind(id, DownloadKind.HLS)
                downloadTask(task, id)
            }
            transition(id, DownloadStatus.MERGING)
            val displayName = DownloadStorage.buildFileName(
                task.vodName,
                task.episodeName,
                output.nameHint ?: task.fileName,
                output.ext,
            )
            val published = DownloadStorage.publish(output.file, task, displayName)
                ?: throw DownloadException(DownloadErrorKind.STORAGE, "保存文件失败")
            val completed = task.copy(
                fileName = displayName,
                totalBytes = output.file.length(),
                downloadedBytes = output.file.length(),
                status = DownloadStatus.COMPLETED,
                contentUri = published,
                error = null,
                errorKind = null,
                updatedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            )
            var finished = false
            withContext(NonCancellable) {
                val current = _tasks.value.firstOrNull { it.id == id }
                if (current == null || current.status == DownloadStatus.CANCELED) {
                    // 用户在合并阶段取消了：已经写进公共目录的文件要删掉，避免留下孤立文件。
                    DownloadStorage.deletePublished(completed)
                } else {
                    runCatching { repository.upsert(completed) }
                        .onFailure { AppLog.e("DownloadManager", it) }
                    _tasks.update { list -> list.map { if (it.id == id) completed else it } }
                    DownloadStorage.clearTemp(id)
                    finished = true
                }
            }
            if (finished) {
                AppContextHolder.context()?.let { DownloadService.notifyCompleted(it, completed) }
            }
        } catch (e: CancellationException) {
            val current = _tasks.value.firstOrNull { it.id == id }
            if (current != null && current.active) {
                runCatching {
                    flushProgress(current)
                    transition(id, DownloadStatus.PAUSED)
                }
            }
            throw e
        } catch (e: DownloadException) {
            runCatching { failTask(id, e.errorKind, e.message) }
                .onFailure { AppLog.e("DownloadManager", it) }
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
            runCatching { failTask(id, DownloadErrorKind.NETWORK, th.message ?: "下载失败") }
                .onFailure { AppLog.e("DownloadManager", it) }
        } finally {
            inFlight.remove(id)
            if (slotHeld) releaseSlot()
            jobs.remove(id)
            lastDbWrite.remove(id)
            speedSamples.remove(id)
            updateService()
        }
    }

    private suspend fun downloadTask(task: DownloadTask, id: String): DownloadOutput =
        when (task.kind) {
            DownloadKind.PROGRESSIVE -> ProgressiveDownloader.download(task) { downloaded, total ->
                onProgress(id, downloaded, total, 0, 0)
            }
            DownloadKind.HLS -> HlsDownloader.download(
                task,
                onSegments = { done, total -> onProgress(id, -1L, -1L, done, total) },
                onProgress = { downloaded, total -> onProgress(id, downloaded, total, 0, 0) },
            )
        }

    private suspend fun switchKind(id: String, kind: DownloadKind) {
        withContext(NonCancellable) {
            runCatching { repository.updateKind(id, kind) }
                .onFailure { AppLog.e("DownloadManager", it) }
        }
        _tasks.update { list -> list.map { if (it.id == id) it.copy(kind = kind) else it } }
    }

    private suspend fun failTask(id: String, kind: DownloadErrorKind, message: String?) {
        transition(id, DownloadStatus.FAILED, message, kind)
    }

    /**
     * 状态只走这一条通道：只 UPDATE 状态列 + 同步内存。
     * 以前用整行快照 upsert，会与 2 秒一次的进度回写互相覆盖。
     */
    private suspend fun transition(
        id: String,
        status: DownloadStatus,
        error: String? = null,
        errorKind: DownloadErrorKind? = null,
    ) {
        val now = System.currentTimeMillis()
        withContext(NonCancellable) {
            runCatching { repository.updateStatus(id, status, error, errorKind, now) }
                .onFailure { AppLog.e("DownloadManager", it) }
        }
        if (status != DownloadStatus.DOWNLOADING) speedSamples.remove(id)
        _tasks.update { list ->
            list.map { task ->
                if (task.id == id) {
                    task.copy(
                        status = status,
                        error = error,
                        errorKind = errorKind,
                        updatedAt = now,
                        speedBytesPerSec = if (status == DownloadStatus.DOWNLOADING) {
                            task.speedBytesPerSec
                        } else {
                            0L
                        },
                    )
                } else {
                    task
                }
            }
        }
    }

    private suspend fun flushProgress(task: DownloadTask) = withContext(NonCancellable) {
        runCatching {
            repository.updateProgress(
                id = task.id,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                segmentsDone = task.segmentsDone,
                segmentsTotal = task.segmentsTotal,
                updatedAt = System.currentTimeMillis(),
            )
        }.onFailure { AppLog.e("DownloadManager", it) }
    }

    private fun onProgress(
        id: String,
        downloaded: Long,
        total: Long,
        segmentsDone: Int,
        segmentsTotal: Int,
    ) {
        val now = System.currentTimeMillis()
        _tasks.update { list ->
            list.map { task ->
                if (task.id != id) {
                    task
                } else {
                    task.copy(
                        // -1 表示“这次没有字节信息”（HLS 只报分段数）；0 是可信的起点，
                        // 临时文件被系统清掉后要能如实回落，而不是一直显示上次的旧进度。
                        downloadedBytes = if (downloaded >= 0) downloaded else task.downloadedBytes,
                        totalBytes = if (total > 0) total else task.totalBytes,
                        segmentsDone = if (segmentsTotal > 0) segmentsDone else task.segmentsDone,
                        segmentsTotal = if (segmentsTotal > 0) segmentsTotal else task.segmentsTotal,
                        updatedAt = now,
                    )
                }
            }
        }
        val last = lastDbWrite[id] ?: 0L
        if (now - last < DB_WRITE_INTERVAL_MS) return
        lastDbWrite[id] = now
        val snapshot = _tasks.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching {
                repository.updateProgress(
                    id = id,
                    downloadedBytes = snapshot.downloadedBytes,
                    totalBytes = snapshot.totalBytes,
                    segmentsDone = snapshot.segmentsDone,
                    segmentsTotal = snapshot.segmentsTotal,
                    updatedAt = now,
                )
            }.onFailure { AppLog.e("DownloadManager", it) }
        }
    }

    private fun sampleSpeed(id: String, downloaded: Long, now: Long): Long {
        val previous = speedSamples[id]
        if (previous == null) {
            speedSamples[id] = SpeedSample(now, downloaded)
            return 0L
        }
        val elapsed = now - previous.time
        if (elapsed < SPEED_WINDOW_MS) return 0L
        speedSamples[id] = SpeedSample(now, downloaded)
        val delta = downloaded - previous.bytes
        return if (delta > 0) delta * 1000 / elapsed else 0L
    }

    private suspend fun acquireSlot() {
        while (true) {
            val current = running.get()
            if (current < DownloadSettings.concurrency && running.compareAndSet(current, current + 1)) {
                return
            }
            delay(100)
        }
    }

    private fun releaseSlot() {
        running.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    private suspend fun refresh() {
        runCatching { _tasks.value = repository.getAll() }
            .onFailure { AppLog.e("DownloadManager", it) }
    }

    private fun detectKind(url: String): DownloadKind {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".m3u8")) DownloadKind.HLS else DownloadKind.PROGRESSIVE
    }

    private fun canRun(): Boolean = !DownloadSettings.wifiOnly || isWifiConnected()

    private fun dispatchQueued() {
        if (!canRun()) return
        _tasks.value.filter { it.status == DownloadStatus.QUEUED }.forEach { queue.trySend(it.id) }
        updateService()
    }

    private fun updateService() {
        val ctx = AppContextHolder.context() ?: return
        // 全部在等 Wi-Fi 时不要拉起前台服务，否则通知条会一直挂着 0%。
        if (!_tasks.value.any { it.active && canRun() }) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        val ctx = AppContextHolder.context() ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    dispatchQueued()
                }
            })
            // 只监听默认网络变化不够：排队时已经在移动数据上时不会再有 onAvailable，
            // 这里显式订阅 Wi-Fi / 以太网，网络出现时才把等待中的任务放行。
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    dispatchQueued()
                }
            })
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
        }
    }
}
