package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadKind
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {

    private fun task(
        status: DownloadStatus = DownloadStatus.QUEUED,
        kind: DownloadKind = DownloadKind.PROGRESSIVE,
        totalBytes: Long = 0L,
        downloadedBytes: Long = 0L,
        segmentsDone: Int = 0,
        segmentsTotal: Int = 0,
    ) = DownloadTask(
        id = "t1",
        sourceKey = null,
        vodId = null,
        vodName = "剧名",
        playFlag = null,
        episodeIndex = 0,
        episodeName = "第1集",
        rawSeriesUrl = null,
        url = "https://a.com/v.mp4",
        headers = emptyMap(),
        kind = kind,
        fileName = "剧名 - 第1集.mp4",
        dirHint = null,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        segmentsDone = segmentsDone,
        segmentsTotal = segmentsTotal,
        status = status,
        error = null,
        errorKind = null,
        etag = null,
        lastModified = null,
        contentUri = null,
        createdAt = 0L,
        updatedAt = 0L,
        completedAt = 0L,
    )

    @Test
    fun activeStatusesAreQueuedDownloadingMerging() {
        assertTrue(task(status = DownloadStatus.QUEUED).active)
        assertTrue(task(status = DownloadStatus.DOWNLOADING).active)
        assertTrue(task(status = DownloadStatus.MERGING).active)
        listOf(
            DownloadStatus.PAUSED,
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELED,
        ).forEach { assertFalse(it.name, task(status = it).active) }
    }

    @Test
    fun completedIsAlwaysFullProgress() {
        assertEquals(100, task(status = DownloadStatus.COMPLETED).progressPercent)
    }

    @Test
    fun progressiveProgressUsesBytes() {
        assertEquals(0, task(totalBytes = 0L, downloadedBytes = 10L).progressPercent)
        assertEquals(0, task(totalBytes = 1000L, downloadedBytes = 0L).progressPercent)
        assertEquals(50, task(totalBytes = 1000L, downloadedBytes = 500L).progressPercent)
        assertEquals(100, task(totalBytes = 1000L, downloadedBytes = 2000L).progressPercent)
    }

    @Test
    fun hlsProgressPrefersSegments() {
        assertEquals(
            33,
            task(
                kind = DownloadKind.HLS,
                totalBytes = 1000L,
                downloadedBytes = 900L,
                segmentsDone = 1,
                segmentsTotal = 3,
            ).progressPercent,
        )
        assertEquals(
            50,
            task(
                kind = DownloadKind.HLS,
                segmentsDone = 2,
                segmentsTotal = 0,
                totalBytes = 1000L,
                downloadedBytes = 500L,
            ).progressPercent,
        )
    }
}
