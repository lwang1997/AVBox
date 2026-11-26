package com.github.avbox.osc.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadKind
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "download_task")
class DownloadTaskEntity {
    @PrimaryKey
    @JvmField
    var id: String = ""

    @JvmField
    var sourceKey: String? = null

    @JvmField
    var vodId: String? = null

    @JvmField
    var vodName: String? = null

    @JvmField
    var playFlag: String? = null

    @JvmField
    var episodeIndex: Int = 0

    @JvmField
    var episodeName: String? = null

    @JvmField
    var rawSeriesUrl: String? = null

    @JvmField
    var url: String = ""

    @JvmField
    var headersJson: String = "{}"

    @JvmField
    var kind: String = DownloadKind.PROGRESSIVE.name

    @JvmField
    var fileName: String = ""

    @JvmField
    var dirHint: String? = null

    @JvmField
    var totalBytes: Long = 0L

    @JvmField
    var downloadedBytes: Long = 0L

    @JvmField
    var segmentsDone: Int = 0

    @JvmField
    var segmentsTotal: Int = 0

    @JvmField
    var status: String = DownloadStatus.QUEUED.name

    @JvmField
    var error: String? = null

    @JvmField
    var errorKind: String? = null

    @JvmField
    var etag: String? = null

    @JvmField
    var lastModified: String? = null

    @JvmField
    var contentUri: String? = null

    @JvmField
    var createdAt: Long = 0L

    @JvmField
    var updatedAt: Long = 0L

    @JvmField
    var completedAt: Long = 0L
}

private val downloadGson = Gson()

private val headersType = object : TypeToken<Map<String, String>>() {}.type

fun DownloadTaskEntity.toDownloadTask(): DownloadTask {
    val headers: Map<String, String> = try {
        downloadGson.fromJson<Map<String, String>>(headersJson, headersType) ?: emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }
    return DownloadTask(
        id = id,
        sourceKey = sourceKey,
        vodId = vodId,
        vodName = vodName,
        playFlag = playFlag,
        episodeIndex = episodeIndex,
        episodeName = episodeName,
        rawSeriesUrl = rawSeriesUrl,
        url = url,
        headers = headers,
        kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.PROGRESSIVE),
        fileName = fileName,
        dirHint = dirHint,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        segmentsDone = segmentsDone,
        segmentsTotal = segmentsTotal,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        error = error,
        errorKind = errorKind?.let { runCatching { DownloadErrorKind.valueOf(it) }.getOrNull() },
        etag = etag,
        lastModified = lastModified,
        contentUri = contentUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}

fun DownloadTask.toEntity(): DownloadTaskEntity {
    val entity = DownloadTaskEntity()
    entity.id = id
    entity.sourceKey = sourceKey
    entity.vodId = vodId
    entity.vodName = vodName
    entity.playFlag = playFlag
    entity.episodeIndex = episodeIndex
    entity.episodeName = episodeName
    entity.rawSeriesUrl = rawSeriesUrl
    entity.url = url
    entity.headersJson = downloadGson.toJson(headers)
    entity.kind = kind.name
    entity.fileName = fileName
    entity.dirHint = dirHint
    entity.totalBytes = totalBytes
    entity.downloadedBytes = downloadedBytes
    entity.segmentsDone = segmentsDone
    entity.segmentsTotal = segmentsTotal
    entity.status = status.name
    entity.error = error
    entity.errorKind = errorKind?.name
    entity.etag = etag
    entity.lastModified = lastModified
    entity.contentUri = contentUri
    entity.createdAt = createdAt
    entity.updatedAt = updatedAt
    entity.completedAt = completedAt
    return entity
}
