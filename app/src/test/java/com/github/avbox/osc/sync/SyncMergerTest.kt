package com.github.avbox.osc.sync

import com.github.avbox.osc.data.SyncTombstones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun history_latestWins() {
        val local = SyncData(
            history = listOf(h("cid", "src", "v1", 100L, "local")),
        )
        val remote = SyncData(
            history = listOf(h("cid", "src", "v1", 200L, "remote")),
        )
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals(1, merged.history.size)
        assertEquals("remote", merged.history[0].dataJson)
    }

    @Test
    fun history_localNewerWins() {
        val local = SyncData(history = listOf(h("cid", "src", "v1", 300L, "local")))
        val remote = SyncData(history = listOf(h("cid", "src", "v1", 200L, "remote")))
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals("local", merged.history[0].dataJson)
    }

    @Test
    fun history_differentVideosBothKept() {
        val local = SyncData(history = listOf(h("cid", "src", "v1", 100L, "a")))
        val remote = SyncData(history = listOf(h("cid", "src", "v2", 100L, "b")))
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals(2, merged.history.size)
    }

    @Test
    fun history_cidIsolation() {
        val local = SyncData(history = listOf(h("cidA", "src", "v1", 100L, "a")))
        val remote = SyncData(history = listOf(h("cidB", "src", "v1", 500L, "b")))
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals(2, merged.history.size)
    }

    @Test
    fun tombstone_removesOlderRecord() {
        val key = SyncTombstones.historyKey("cid", "src", "v1")
        val local = SyncData(history = listOf(h("cid", "src", "v1", 100L, "old")))
        val remote = SyncData(tombstones = mapOf(key to 200L))
        val merged = SyncMerger.merge(local, remote, now)
        assertTrue(merged.history.isEmpty())
    }

    @Test
    fun tombstone_newerRecordSurvives() {
        val key = SyncTombstones.historyKey("cid", "src", "v1")
        val local = SyncData(history = listOf(h("cid", "src", "v1", 300L, "new")))
        val remote = SyncData(tombstones = mapOf(key to 200L))
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals(1, merged.history.size)
    }

    @Test
    fun clearTombstone_removesWholeCid() {
        val key = SyncTombstones.clearKey(SyncTombstones.KIND_HISTORY, "cid")
        val local = SyncData(history = listOf(h("cid", "src", "v1", 100L, "a"), h("cid", "src", "v2", 100L, "b")))
        val remote = SyncData(tombstones = mapOf(key to 200L))
        val merged = SyncMerger.merge(local, remote, now)
        assertTrue(merged.history.isEmpty())
    }

    @Test
    fun progress_latestWinsAndOwnerTimeAggregated() {
        val local = SyncData(progress = listOf(p("o", "ep1", 100L, 100L)))
        val remote = SyncData(
            progress = listOf(
                p("o", "ep1", 200L, 200L),
                p("o", "ep2", 50L, 50L),
            ),
        )
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals(2, merged.progress.size)
        val ep1 = merged.progress.first { it.ep == "ep1" }
        val ep2 = merged.progress.first { it.ep == "ep2" }
        assertEquals(200L, ep1.positionMs)
        assertEquals(200L, ep2.at) // owner 时间取最大
    }

    @Test
    fun kvMaps_mergeByKey() {
        val local = SyncData(percent = mapOf("a" to "100", "b" to "50"))
        val remote = SyncData(percent = mapOf("b" to "80", "c" to "30"))
        val merged = SyncMerger.merge(local, remote, now)
        assertEquals("100", merged.percent["a"])
        assertEquals("80", merged.percent["b"])
        assertEquals("30", merged.percent["c"])
    }

    private fun h(cid: String, sourceKey: String, vodId: String, time: Long, json: String) =
        HistoryEntry(cid, sourceKey, vodId, time, json)

    private fun p(owner: String, ep: String, pos: Long, at: Long) =
        ProgressEntry(owner, ep, pos, at)
}
