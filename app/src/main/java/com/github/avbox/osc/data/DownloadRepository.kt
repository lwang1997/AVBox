package com.github.avbox.osc.data

import com.github.avbox.osc.bean.DownloadTask

interface DownloadRepository {

    suspend fun getAll(): List<DownloadTask>

    suspend fun get(id: String): DownloadTask?

    suspend fun getUnfinished(): List<DownloadTask>

    suspend fun upsert(task: DownloadTask)

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

    override suspend fun delete(id: String) {
        dao().delete(id)
    }
}
