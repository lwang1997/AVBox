package com.github.avbox.osc.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

import com.github.avbox.osc.util.AppLog

internal data class DownloaderTarget(
    val packageName: String,
    val label: String,
)

internal object ExternalDownloader {

    /** 1DM / 1DM+ / 1DM Lite 共用的下载入口 Activity：类名一致，只有包名不同。 */
    private const val IDM_DOWNLOADER_CLASS = "idm.internet.download.manager.Downloader"

    /**
     * 1DM 约定的 extra：
     * - extra_filename 保存文件名；
     * - extra_headers 是 Bundle（HTTP 头名 → 值），带防盗链的链接靠它才不会 403。
     */
    private const val EXTRA_FILENAME = "extra_filename"
    private const val EXTRA_HEADERS = "extra_headers"

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

    fun buildViewIntent(
        url: String,
        fileName: String?,
        headers: Map<String, String> = emptyMap(),
        packageName: String,
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage(packageName)
        // 显式指向 1DM 的下载入口：不依赖它 VIEW 过滤器对 MIME 的匹配，装了就能拉起来。
        component = ComponentName(packageName, IDM_DOWNLOADER_CLASS)
        // 1DM 的 UrlHandler 只匹配带 MIME 的 http/https，缺类型会落到主界面。
        setDataAndType(Uri.parse(url), mimeForUrl(url))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!fileName.isNullOrBlank()) putExtra(EXTRA_FILENAME, fileName)
        val headerBundle = headersBundle(headers)
        if (!headerBundle.isEmpty) putExtra(EXTRA_HEADERS, headerBundle)
    }

    private fun headersBundle(headers: Map<String, String>): Bundle =
        Bundle().apply {
            DownloadHttp.forwardableHeaders(headers).forEach { (key, value) -> putString(key, value) }
        }

    private fun mimeForUrl(url: String): String {
        val ext = DownloadStorage.extensionFromUrl(url) ?: return "video/*"
        val mime = DownloadStorage.mimeForExtension(ext)
        return if (mime == "application/octet-stream") "video/*" else mime
    }

    fun launch(
        context: Context,
        url: String,
        fileName: String?,
        headers: Map<String, String> = emptyMap(),
        target: DownloaderTarget,
    ): Boolean {
        val explicit = buildViewIntent(url, fileName, headers, target.packageName)
        if (startQuietly(context, explicit)) return true
        // 某些版本换了 Activity 名：退回只带包名的隐式 VIEW 再试一次。
        if (startQuietly(context, Intent(explicit).apply { component = null })) return true
        // 最后用分享入口兜底：1DM 的分享接收器同样接受 http/https 链接。
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(target.packageName)
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startQuietly(context, share)
    }

    private fun startQuietly(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (th: Throwable) {
            AppLog.e("ExternalDownloader", th)
            false
        }

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
