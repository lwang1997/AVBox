package com.github.avbox.osc.util.update

import java.io.File

/** GitHub Releases 中最新的可用版本信息。 */
data class UpdateInfo(
    /** tag_name 去掉前缀 v，例如 "1.4.0" */
    val versionName: String,
    /** Release 正文注入的 versionCode；旧 Release 没有该标记时为 null */
    val versionCode: Long?,
    /** Release 正文（已去掉 versionCode 标记），即更新说明 */
    val notes: String,
    /** APK 直链 browser_download_url */
    val apkUrl: String,
    /** APK 文件名，例如 AVBox_1.4.0_arm64-v8a.apk */
    val apkName: String,
    /** APK 字节数，未知时为 0 */
    val sizeBytes: Long,
    /** Release 页面地址 */
    val pageUrl: String,
)

sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val info: UpdateInfo) : CheckResult
    data class Failed(val message: String) : CheckResult
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Preparing : DownloadState
    data class Downloading(val bytes: Long, val total: Long) : DownloadState
    data class Ready(val file: File, val info: UpdateInfo) : DownloadState
    data class Failed(val message: String) : DownloadState
    data object Canceled : DownloadState
}

data class UpdateUiState(
    val checking: Boolean = false,
    /** 最近一次检查发现的新版本，设置页展示用 */
    val available: UpdateInfo? = null,
    /** 启动弹窗待展示的新版本 */
    val prompt: UpdateInfo? = null,
    val download: DownloadState = DownloadState.Idle,
)

enum class InstallResult { Started, NeedPermission, Failed }
