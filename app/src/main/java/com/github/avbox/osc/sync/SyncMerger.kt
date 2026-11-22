package com.github.avbox.osc.sync

import com.github.avbox.osc.data.SyncTombstones

/**
 * 合并策略：按业务键做 Last-Write-Wins（时间戳大者胜），叠加删除墓碑。
 * 纯逻辑、无副作用，便于单测。
 */
object SyncMerger {

    fun merge(local: SyncData, remote: SyncData, now: Long): SyncData {
        val history = mergeByKey(
            local.history, remote.history,
            key = { businessKey(it.cid, it.sourceKey, it.vodId) },
            merge = { a, b -> if (a.updateTime >= b.updateTime) a else b },
        )

        val collect = mergeByKey(
            local.collect, remote.collect,
            key = { businessKey(it.cid, it.sourceKey, it.vodId) },
            merge = { a, b -> if (a.updateTime >= b.updateTime) a else b },
        )

        val follow = mergeByKey(
            local.follow, remote.follow,
            key = { businessKey(it.cid, it.sourceKey, it.vodId) },
            merge = { a, b -> if (a.addedTime >= b.addedTime) a else b },
        )

        val progress = mergeProgress(local.progress, remote.progress)

        val tombs = HashMap<String, Long>()
        listOf(local.tombstones, remote.tombstones).forEach { map ->
            map.forEach { (k, v) -> if (v > (tombs[k] ?: 0L)) tombs[k] = v }
        }

        // 应用墓碑：单条删除
        val filteredHistory = history.filterNot { tombs.isDeleted(SyncTombstones.historyKey(it.cid, it.sourceKey, it.vodId), it.updateTime, now) }
        val filteredCollect = collect.filterNot { tombs.isDeleted(SyncTombstones.collectKey(it.cid, it.sourceKey, it.vodId), it.updateTime, now) }
        val filteredFollow = follow.filterNot { tombs.isDeleted(SyncTombstones.followKey(it.cid, it.sourceKey, it.vodId), it.addedTime, now) }
        val filteredProgress = progress.filterNot { tombs.isDeleted(SyncTombstones.progressKey(it.ep), it.at, now) }

        // 应用墓碑：按 cid 清空
        val historyCleared = applyClear(filteredHistory, tombs, SyncTombstones.KIND_HISTORY, now) { it.cid.orEmpty() }
        val collectCleared = applyClear(filteredCollect, tombs, SyncTombstones.KIND_COLLECT, now) { it.cid.orEmpty() }
        val followCleared = applyClear(filteredFollow, tombs, SyncTombstones.KIND_FOLLOW, now) { it.cid.orEmpty() }

        // KV 附加数据：按 key 做时间戳比较，无法比较时取远端（对端更新的值）
        val percent = mergeKVMap(local.percent, remote.percent) { rawTime(it) }
        val totals = mergeKVMap(local.totals, remote.totals) { 0L }
        val watched = mergeKVMap(local.watched, remote.watched) { it.toLongOrNull() ?: 0L }
        val tracks = mergeKVMap(local.tracks, remote.tracks) { 0L }

        return SyncData(
            history = historyCleared,
            collect = collectCleared,
            follow = followCleared,
            progress = filteredProgress,
            percent = percent,
            totals = totals,
            watched = watched,
            tracks = tracks,
            tombstones = tombs,
        )
    }

    private fun businessKey(cid: String?, sourceKey: String?, vodId: String?): String =
        listOf(cid.orEmpty(), sourceKey.orEmpty(), vodId.orEmpty()).joinToString("\u0001")

    private fun <T> mergeByKey(
        local: List<T>,
        remote: List<T>,
        key: (T) -> String,
        merge: (T, T) -> T,
    ): List<T> {
        val best = LinkedHashMap<String, T>()
        local.forEach { best[key(it)] = it }
        remote.forEach { item ->
            val k = key(item)
            val cur = best[k]
            best[k] = if (cur == null) item else merge(cur, item)
        }
        return best.values.toList()
    }

    private fun mergeProgress(local: List<ProgressEntry>, remote: List<ProgressEntry>): List<ProgressEntry> {
        val best = LinkedHashMap<String, ProgressEntry>()
        local.forEach { best[it.ep] = it }
        remote.forEach { item ->
            val cur = best[item.ep]
            if (cur == null || item.at >= cur.at) best[item.ep] = item
        }
        // 同一 owner 的 eps 聚合时间取最大值，避免索引 at 回退
        val ownerAt = HashMap<String, Long>()
        best.values.forEach { ownerAt[it.owner] = maxOf(ownerAt[it.owner] ?: 0L, it.at) }
        return best.values.map { it.copy(at = ownerAt[it.owner] ?: it.at) }
    }

    private fun mergeKVMap(
        local: Map<String, String>,
        remote: Map<String, String>,
        timeOf: (String) -> Long,
    ): Map<String, String> {
        val out = LinkedHashMap(local)
        remote.forEach { (k, v) ->
            val cur = out[k]
            if (cur == null || timeOf(v) >= timeOf(cur)) out[k] = v
        }
        return out
    }

    /** 从 playback_progress 编码串里解析 t（保存时间） */
    private fun rawTime(raw: String): Long {
        val json = if (raw.startsWith("\u0001json:")) raw.substring("\u0001json:".length) else raw
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            obj.get("t")?.asLong ?: 0L
        } catch (e: Throwable) {
            0L
        }
    }

    private fun Map<String, Long>.isDeleted(key: String, recordTime: Long, now: Long): Boolean {
        val at = this[key] ?: return false
        if (at > now) return false // 未来时间戳（时钟异常）忽略
        return at >= recordTime
    }

    private fun <T> applyClear(
        list: List<T>,
        tombs: Map<String, Long>,
        kind: String,
        now: Long,
        cidOf: (T) -> String,
    ): List<T> {
        return list.filterNot { item ->
            val at = tombs[SyncTombstones.clearKey(kind, cidOf(item))] ?: return@filterNot false
            at <= now && at >= 0L
        }
    }
}
