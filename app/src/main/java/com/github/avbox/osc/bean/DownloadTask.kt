package com.github.avbox.osc.bean

enum class DownloadKind {
    PROGRESSIVE,
    HLS,
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    MERGING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
}

enum class DownloadErrorKind {
    NETWORK,
    EXPIRED,
    UNSUPPORTED,
    STORAGE,
}

data class DownloadTask(
    val id: String,
    val sourceKey: String?,
    val vodId: String?,
    val vodName: String?,
    val playFlag: String?,
    val episodeIndex: Int,
    val episodeName: String?,
    val rawSeriesUrl: String?,
    val url: String,
    val headers: Map<String, String>,
    val kind: DownloadKind,
    val fileName: String,
    val dirHint: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val segmentsDone: Int,
    val segmentsTotal: Int,
    val status: DownloadStatus,
    val error: String?,
    val errorKind: DownloadErrorKind?,
    val etag: String?,
    val lastModified: String?,
    val contentUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long,
    /** 运行期的实时速度（字节/秒），只用于界面展示，不落库。 */
    val speedBytesPerSec: Long = 0L,
) {
    val active: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.MERGING

    val progressPercent: Int
        get() {
            if (status == DownloadStatus.COMPLETED) return 100
            if (kind == DownloadKind.HLS && segmentsTotal > 0) {
                return (segmentsDone * 100 / segmentsTotal).coerceIn(0, 100)
            }
            if (totalBytes <= 0) return 0
            return (downloadedBytes * 100 / totalBytes).coerceIn(0, 100).toInt()
        }
}

data class DownloadRequest(
    val url: String,
    val headers: Map<String, String>,
    val kindHint: DownloadKind?,
    val title: String,
    val fileName: String?,
    val sourceKey: String?,
    val vodId: String?,
    val vodName: String?,
    val playFlag: String?,
    val episodeIndex: Int,
    val episodeName: String?,
    val rawSeriesUrl: String?,
)

data class DownloadResolvedUrl(
    val url: String,
    val headers: Map<String, String>,
)
