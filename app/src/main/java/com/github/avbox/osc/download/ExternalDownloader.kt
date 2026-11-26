package com.github.avbox.osc.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

import com.github.avbox.osc.util.AppLog

internal data class DownloaderTarget(
    val packageName: String,
    val label: String,
)

internal object ExternalDownloader {

    private val targets = listOf(
        DownloaderTarget("idm.internet.download.manager.plus", "1DM+"),
        DownloaderTarget("idm.internet.download.manager", "1DM"),
        DownloaderTarget("idm.internet.download.manager.lite", "1DM Lite"),
    )

    fun installedTargets(context: Context): List<DownloaderTarget> {
        val pm = context.packageManager
        return targets.filter { target ->
            try {
                pm.getPackageInfo(target.packageName, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun buildViewIntent(url: String, fileName: String?, packageName: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            // 1DM 的 UrlHandlerDownloader 只匹配带 MIME 的 http/https，缺类型会落到主界面。
            setDataAndType(Uri.parse(url), mimeForUrl(url))
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!fileName.isNullOrBlank()) putExtra(EXTRA_FILENAME, fileName)
        }

    private fun mimeForUrl(url: String): String {
        val ext = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase()
        return when (ext) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> "video/*"
        }
    }

    fun launch(context: Context, url: String, fileName: String?, target: DownloaderTarget): Boolean {
        return try {
            context.startActivity(buildViewIntent(url, fileName, target.packageName))
            true
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
            false
        }
    }

    /** 1DM/1DM+ 官方约定：通过 extra_filename 指定保存文件名。 */
    private const val EXTRA_FILENAME = "extra_filename"

    fun share(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
        }
    }

    fun copy(context: Context, text: String) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("download", text))
        } catch (th: Throwable) {
            AppLog.e("DownloadManager", th)
        }
    }

    fun headerText(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        return buildString {
            appendLine(url)
            headers.forEach { (key, value) -> appendLine("$key: $value") }
        }.trim()
    }
}
