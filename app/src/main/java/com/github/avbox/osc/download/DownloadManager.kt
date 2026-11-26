package com.github.avbox.osc.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object DownloadManager {

    const val WORKERS = 3
    private const val DB_WRITE_INTERVAL_MS = 2000L
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val repository: DownloadRepository
        get() = AppGraph.downloadRepository

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val lastDbWrite = ConcurrentHashMap<String, Long>()
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
            task.active && task.url == request.url &&
                task.vodId == request.vodId &&
                task.playFlag == request.playFlag &&
                task.episodeIndex == request.episodeIndex
        }
        if (existing != null) return existing.id

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
            updateStatus(task.copy(status = DownloadStatus.PAUSED))
            jobs.remove(id)?.cancelAndJoin()
            refresh()
            updateService()
        }
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.PAUSED && task.status != DownloadStatus.FAILED) return
        scope.launch {
            jobs.remove(id)?.cancelAndJoin()
            updateStatus(task.copy(status = DownloadStatus.QUEUED, error = null, errorKind = null))
            queue.trySend(id)
            updateService()
        }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.COMPLETED) return
        scope.launch {
            updateStatus(task.copy(status = DownloadStatus.CANCELED, error = null, errorKind = null))
            jobs.remove(id)?.cancelAndJoin()
            DownloadStorage.clearTemp(id)
            refresh()
            updateService()
        }
    }

    suspend fun delete(id: String, deleteFile: Boolean) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: repository.get(id) ?: return
        jobs.remove(id)?.cancel()
        if (deleteFile) {
            DownloadStorage.deletePublished(task)
        }
        DownloadStorage.clearTemp(id)
        withContext(NonCancellable) {
            repository.delete(id)
            refresh()
        }
        updateService()
    }

    fun pauseAll() {
        _tasks.value.filter { it.active }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        _tasks.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) }
    }

    fun isWifiConnected(): Boolean {
        val ctx = AppContextHolder.context() ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
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
        if (DownloadSettings.wifiOnly && !isWifiConnected()) {
            return
        }
        acquireSlot()
        try {
            val task = repository.get(id) ?: return
            if (task.status != DownloadStatus.QUEUED) return
            if (task.totalBytes > 0 && DownloadStorage.freeBytes() < task.totalBytes + MIN_FREE_BYTES) {
                throw DownloadException(DownloadErrorKind.STORAGE, "存储空间不足")
            }
            withContext(NonCancellable) { updateStatus(task.copy(status = DownloadStatus.DOWNLOADING)) }
            val output = when (task.kind) {
                DownloadKind.PROGRESSIVE -> ProgressiveDownloader.download(task) { downloaded, total ->
                    onProgress(id, downloaded, total, 0, 0)
                }
                DownloadKind.HLS -> HlsDownloader.download(
                    task,
                    onSegments = { done, total -> onProgress(id, 0L, 0L, done, total) },
                    onProgress = { downloaded, total -> onProgress(id, downloaded, total, 0, 0) },
                )
            }
            withContext(NonCancellable) {
                updateStatus(task.copy(status = DownloadStatus.MERGING))
            }
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
            withContext(NonCancellable) {
                repository.upsert(completed)
                refresh()
                DownloadStorage.clearTemp(id)
            }
        } catch (e: CancellationException) {
            val current = withContext(NonCancellable) {
                runCatching { repository.get(id) }.getOrNull()
            }
            if (current != null && current.active) {
                withContext(NonCancellable) {
                    runCatching {
                        repository.upsert(
                            current.copy(
                                status = DownloadStatus.PAUSED,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        refresh()
                    }
                }
            }
            throw e
        } catch (e: DownloadException) {
            failTaskFromDb(id, e.errorKind, e.message)
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
            failTaskFromDb(id, DownloadErrorKind.NETWORK, th.message ?: "下载失败")
        } finally {
            releaseSlot()
            jobs.remove(id)
            updateService()
        }
    }

    private suspend fun failTaskFromDb(id: String, kind: DownloadErrorKind, message: String?) {
        val latest = withContext(NonCancellable) {
            runCatching { repository.get(id) }.getOrNull()
        } ?: return
        failTask(latest, kind, message)
    }

    private suspend fun failTask(task: DownloadTask, kind: DownloadErrorKind, message: String?) {
        val failed = task.copy(
            status = DownloadStatus.FAILED,
            error = message,
            errorKind = kind,
            updatedAt = System.currentTimeMillis(),
        )
        withContext(NonCancellable) {
            repository.upsert(failed)
            refresh()
        }
    }

    private suspend fun updateStatus(task: DownloadTask) {
        repository.upsert(task.copy(updatedAt = System.currentTimeMillis()))
        refresh()
    }

    private fun onProgress(
        id: String,
        downloaded: Long,
        total: Long,
        segmentsDone: Int,
        segmentsTotal: Int,
    ) {
        val now = System.currentTimeMillis()
        _tasks.value = _tasks.value.map { task ->
            if (task.id != id) return@map task
            task.copy(
                downloadedBytes = if (downloaded > 0) downloaded else task.downloadedBytes,
                totalBytes = if (total > 0) total else task.totalBytes,
                segmentsDone = if (segmentsTotal > 0) segmentsDone else task.segmentsDone,
                segmentsTotal = if (segmentsTotal > 0) segmentsTotal else task.segmentsTotal,
                updatedAt = now,
            )
        }
        val last = lastDbWrite[id] ?: 0L
        if (now - last < DB_WRITE_INTERVAL_MS) return
        lastDbWrite[id] = now
        val snapshot = _tasks.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching { repository.upsert(snapshot) }
        }
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

    private fun updateService() {
        val ctx = AppContextHolder.context() ?: return
        if (!_tasks.value.any { it.active }) return
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
                    if (!DownloadSettings.wifiOnly || isWifiConnected()) {
                        _tasks.value.filter { it.status == DownloadStatus.QUEUED }
                            .forEach { queue.trySend(it.id) }
                    }
                }
            })
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
        }
    }
}
