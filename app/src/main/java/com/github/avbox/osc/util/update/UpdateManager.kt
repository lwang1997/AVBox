package com.github.avbox.osc.util.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

import androidx.core.content.FileProvider

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.google.gson.Gson

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import okhttp3.OkHttpClient
import okhttp3.Request

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 自更新状态机：启动/手动检查、版本节流、镜像测速下载、拉起系统安装器。
 * 状态只在这里产生，UI 只读 [state]。
 */
object UpdateManager {

    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val MIN_APK_BYTES = 1024L * 1024L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CONNECT_TIMEOUT_SECONDS = 15L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var downloadJob: Job? = null

    @Volatile
    private var lastResult: CheckResult = CheckResult.Failed("not checked")

    @Volatile
    private var currentPart: File? = null

    @Volatile
    private var downloadClient: OkHttpClient? = null

    /** 启动时调用：先用缓存即时提示，超过 24h 再后台刷新。 */
    fun checkOnLaunch() {
        runCatching {
            val cached = loadCached()
            if (cached != null &&
                UpdateChecker.isNewer(cached) &&
                cached.versionName != promptedVersion()
            ) {
                _state.update { it.copy(available = cached, prompt = cached) }
            }
        }.onFailure { AppLog.e("UpdateManager", "cached update decode failed", it) }

        val lastCheckAt = KV.get(KVKeys.UPDATE_LAST_CHECK_AT, 0L)
        if (System.currentTimeMillis() - lastCheckAt < CHECK_INTERVAL_MS) return
        startRefresh(showPrompt = true)
    }

    /** 手动检查（设置行 / 点击版本号）：绕过节流，结果回调到主线程。 */
    fun checkNow(onResult: (CheckResult) -> Unit) {
        scope.launch {
            val job = synchronized(this@UpdateManager) {
                refreshJob?.takeIf { it.isActive } ?: startRefreshLocked(showPrompt = false)
            }
            job.join()
            val result = lastResult
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** 记录该版本已提示过，同一版本启动时不再自动弹窗。 */
    fun dismissPrompt() {
        val versionName = _state.value.prompt?.versionName ?: _state.value.available?.versionName
        if (!versionName.isNullOrBlank()) {
            KV.put(KVKeys.UPDATE_PROMPTED_VERSION, versionName)
        }
        _state.update { it.copy(prompt = null) }
    }

    fun startDownload(context: Context, info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        val target = File(updateDir(appContext), info.apkName)
        if (target.isFile && target.length() >= MIN_APK_BYTES && verifyPackage(appContext, target, info)) {
            _state.update { it.copy(download = DownloadState.Ready(target, info)) }
            return
        }

        downloadJob = scope.launch {
            _state.update { it.copy(download = DownloadState.Preparing) }
            val urls = runCatching { UpdateChecker.rankedApkUrls(info.apkUrl) }
                .getOrDefault(listOf(info.apkUrl))
            for (url in urls) {
                try {
                    val file = downloadOne(appContext, info, url)
                    if (!verifyPackage(appContext, file, info)) {
                        file.delete()
                        throw IOException("apk verify failed")
                    }
                    _state.update { it.copy(download = DownloadState.Ready(file, info)) }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLog.e("UpdateManager", "download failed: $url", t)
                }
            }
            _state.update { it.copy(download = DownloadState.Failed("all mirrors failed")) }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        currentPart?.delete()
        currentPart = null
        _state.update { it.copy(download = DownloadState.Idle) }
    }

    fun install(context: Context, file: File): InstallResult {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching {
                context.startActivity(intent)
                InstallResult.NeedPermission
            }.getOrDefault(InstallResult.Failed)
        }

        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull() ?: return InstallResult.Failed

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            InstallResult.Started
        }.getOrDefault(InstallResult.Failed)
    }

    private fun startRefresh(showPrompt: Boolean) {
        synchronized(this) {
            refreshJob?.takeIf { it.isActive } ?: startRefreshLocked(showPrompt)
        }
    }

    private fun startRefreshLocked(showPrompt: Boolean): Job {
        refreshJob?.let { if (it.isActive) return it }
        val job = scope.launch { refreshInternal(showPrompt) }
        refreshJob = job
        return job
    }

    private suspend fun refreshInternal(showPrompt: Boolean) {
        _state.update { it.copy(checking = true) }
        val info = try {
            UpdateChecker.fetchLatestRelease()
        } catch (t: Throwable) {
            AppLog.e("UpdateManager", "check failed", t)
            null
        }
        if (info == null) {
            lastResult = CheckResult.Failed("network")
            _state.update { it.copy(checking = false) }
            return
        }

        KV.put(KVKeys.UPDATE_LAST_CHECK_AT, System.currentTimeMillis())
        KV.put(KVKeys.UPDATE_LAST_RESULT, gson.toJson(info))

        if (!UpdateChecker.isNewer(info)) {
            lastResult = CheckResult.UpToDate
            _state.update { it.copy(checking = false, available = null) }
            return
        }

        lastResult = CheckResult.Available(info)
        val shouldPrompt = showPrompt && info.versionName != promptedVersion()
        _state.update { current ->
            current.copy(
                checking = false,
                available = info,
                prompt = if (shouldPrompt) info else current.prompt,
            )
        }
    }

    private suspend fun downloadOne(context: Context, info: UpdateInfo, url: String): File =
        withContext(Dispatchers.IO) {
            val dir = updateDir(context).apply { mkdirs() }
            val part = File(dir, info.apkName + ".part")
            val target = File(dir, info.apkName)
            currentPart = part
            part.delete()

            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .build()
            client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                var downloaded = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                _state.update {
                                    it.copy(download = DownloadState.Downloading(downloaded, total))
                                }
                            }
                        }
                    }
                }
                if (downloaded <= 0) throw IOException("empty body")
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed")
            currentPart = null
            target
        }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File, info: UpdateInfo): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        if (archive.packageName != context.packageName) return false
        val expected = info.versionCode ?: return true
        val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        return actual >= expected
    }

    private fun loadCached(): UpdateInfo? {
        val raw = KV.get(KVKeys.UPDATE_LAST_RESULT, "")
        if (raw.isBlank()) return null
        return runCatching { gson.fromJson(raw, UpdateInfo::class.java) }.getOrNull()
    }

    private fun promptedVersion(): String = KV.get(KVKeys.UPDATE_PROMPTED_VERSION, "")

    private fun updateDir(context: Context): File = File(context.cacheDir, "update")

    private fun client(): OkHttpClient {
        downloadClient?.let { return it }
        val base = HttpClients.defaultClient ?: OkHttpClient()
        return base.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .also { downloadClient = it }
    }
}
