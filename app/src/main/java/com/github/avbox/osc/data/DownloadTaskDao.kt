package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity): Long

    @Query("select * from download_task order by createdAt desc")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query("select * from download_task where id=:id")
    suspend fun get(id: String): DownloadTaskEntity?

    @Query("select * from download_task where status in ('QUEUED','DOWNLOADING','MERGING')")
    suspend fun getUnfinished(): List<DownloadTaskEntity>

    @Query("delete from download_task where id=:id")
    suspend fun delete(id: String)

    @Query(
        "update download_task set status=:status, error=:error, errorKind=:errorKind, " +
            "updatedAt=:updatedAt where id=:id",
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        error: String?,
        errorKind: String?,
        updatedAt: Long,
    )

    @Query("update download_task set kind=:kind where id=:id")
    suspend fun updateKind(id: String, kind: String)

    @Query(
        "update download_task set downloadedBytes=:downloadedBytes, totalBytes=:totalBytes, " +
            "segmentsDone=:segmentsDone, segmentsTotal=:segmentsTotal, updatedAt=:updatedAt " +
            "where id=:id and status='DOWNLOADING'",
    )
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        segmentsDone: Int,
        segmentsTotal: Int,
        updatedAt: Long,
    )
}
