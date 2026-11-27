package com.github.avbox.osc.data

import com.github.avbox.osc.bean.DownloadErrorKind
import com.github.avbox.osc.bean.DownloadKind
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask

interface DownloadRepository {

    suspend fun getAll(): List<DownloadTask>

    suspend fun get(id: String): DownloadTask?

    suspend fun getUnfinished(): List<DownloadTask>

    suspend fun upsert(task: DownloadTask)

    /** 只改状态相关列，避免用整行快照覆盖别人的进度写入。 */
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        error: String?,
        errorKind: DownloadErrorKind?,
        updatedAt: Long,
    )

    /** 下载地址后缀和实际内容不一致时（无 .m3u8 后缀的 HLS），修正任务类型。 */
    suspend fun updateKind(id: String, kind: DownloadKind)

    /** 只改进度列；任务已不在下载中时由 SQL 自身丢弃，不会复活状态。 */
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        segmentsDone: Int,
        segmentsTotal: Int,
        updatedAt: Long,
    )

    suspend fun delete(id: String)
}

internal class RoomDownloadRepository(
    private val dao: () -> DownloadTaskDao,
) : DownloadRepository {

    override suspend fun getAll(): List<DownloadTask> =
        dao().getAll().map { it.toDownloadTask() }

    override suspend fun get(id: String): DownloadTask? =
        dao().get(id)?.toDownloadTask()

    override suspend fun getUnfinished(): List<DownloadTask> =
        dao().getUnfinished().map { it.toDownloadTask() }

    override suspend fun upsert(task: DownloadTask) {
        dao().upsert(task.toEntity())
    }

    override suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        error: String?,
        errorKind: DownloadErrorKind?,
        updatedAt: Long,
    ) {
        dao().updateStatus(id, status.name, error, errorKind?.name, updatedAt)
    }

    override suspend fun updateKind(id: String, kind: DownloadKind) {
        dao().updateKind(id, kind.name)
    }

    override suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        segmentsDone: Int,
        segmentsTotal: Int,
        updatedAt: Long,
    ) {
        dao().updateProgress(id, downloadedBytes, totalBytes, segmentsDone, segmentsTotal, updatedAt)
    }

    override suspend fun delete(id: String) {
        dao().delete(id)
    }
}
