package com.github.avbox.osc.sync

import com.github.avbox.osc.data.AppDataManager
import com.github.avbox.osc.data.CacheEntry
import com.github.avbox.osc.data.PlaybackProgress
import com.github.avbox.osc.data.SyncTombstones
import com.github.avbox.osc.data.VodCollect
import com.github.avbox.osc.data.VodFollow
import com.github.avbox.osc.data.VodRecord
import com.github.avbox.osc.data.WatchProgressIndex
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.MD5

/**
 * 负责本地状态 <-> 快照 DTO 的转换。
 * 只做数据搬运，不含网络、不含合并策略。
 */
object SyncSnapshot {

    private const val KEY_PERCENT = "playback_progress"
    private const val KEY_TOTALS = "episode_totals"
    private const val KEY_WATCHED = "follow_watched"
    private const val TRACK_PREFIX = "track_mem_"

    /** 导出"数据类"快照（自动同步部分） */
    suspend fun exportData(): SyncData {
        val db = AppDataManager.get()
        val history = db.getVodRecordDao().getAllRaw().map {
            HistoryEntry(it.cid, it.sourceKey, it.vodId, it.updateTime, it.dataJson)
        }
        val collect = db.getVodCollectDao().getAll().map {
            CollectEntry(it.cid, it.sourceKey, it.vodId, it.updateTime, it.name, it.pic)
        }
        val follow = db.getVodFollowDao().getAllRaw().map {
            FollowEntry(it.cid, it.sourceKey, it.vodId, it.addedTime, it.name, it.pic, it.updateDays, it.updateHour, it.tmdbInfo)
        }

        val progress = ArrayList<ProgressEntry>()
        val indexKeys = KV.keys(WatchProgressIndex.KEY_PREFIX)
        for (indexKey in indexKeys) {
            val owner = WatchProgressIndex.ownerOf(indexKey)
            val entry = WatchProgressIndex.decode(KV.get(indexKey, "")) ?: continue
            for (ep in entry.eps) {
                val hashed = MD5.string2MD5(ep) ?: continue
                val cached = db.getCacheDao().getCache(hashed)?.data ?: continue
                val position = toLong(cached) ?: continue
                progress.add(ProgressEntry(owner, ep, position, entry.at))
            }
        }

        val percent = readMapRaw(KEY_PERCENT)
        val totals = readMapRaw(KEY_TOTALS)
        val watched = readMapRaw(KEY_WATCHED)
        val tracks = HashMap<String, String>()
        KV.keys(TRACK_PREFIX).forEach { key ->
            val raw = KV.rawGet(key)
            if (raw != null) tracks[key] = raw
        }

        return SyncData(
            history = history,
            collect = collect,
            follow = follow,
            progress = progress,
            percent = percent,
            totals = totals,
            watched = watched,
            tracks = tracks,
            tombstones = SyncTombstones.read(),
        )
    }

    /** 应用合并后的"数据类"快照：按业务键 upsert，不改动 id */
    suspend fun applyData(data: SyncData) {
        val db = AppDataManager.get()

        // 历史：按 (cid, sourceKey, vodId) 覆盖
        val historyDao = db.getVodRecordDao()
        data.history.forEach { e ->
            if (e.vodId.isNullOrEmpty()) return@forEach
            val existing = historyDao.getVodRecord(e.cid, e.sourceKey, e.vodId)
            val record = existing ?: VodRecord()
            record.cid = e.cid
            record.sourceKey = e.sourceKey
            record.vodId = e.vodId
            record.updateTime = e.updateTime
            record.dataJson = e.dataJson
            historyDao.insert(record)
        }

        // 收藏
        val collectDao = db.getVodCollectDao()
        data.collect.forEach { e ->
            if (e.vodId.isNullOrEmpty()) return@forEach
            val existing = collectDao.getVodCollect(e.cid, e.sourceKey, e.vodId)
            val record = existing ?: VodCollect()
            record.cid = e.cid
            record.sourceKey = e.sourceKey
            record.vodId = e.vodId
            record.updateTime = e.updateTime
            record.name = e.name
            record.pic = e.pic
            collectDao.insert(record)
        }

        // 追剧
        val followDao = db.getVodFollowDao()
        data.follow.forEach { e ->
            if (e.vodId.isNullOrEmpty()) return@forEach
            val existing = followDao.getVodFollow(e.cid, e.sourceKey, e.vodId)
            val record = existing ?: VodFollow()
            if (record.id == 0) {
                record.cid = e.cid
                record.sourceKey = e.sourceKey
                record.vodId = e.vodId
                record.addedTime = e.addedTime
            }
            record.name = e.name
            record.pic = e.pic
            record.updateDays = e.updateDays
            record.updateHour = e.updateHour
            record.tmdbInfo = e.tmdbInfo
            followDao.insert(record)
        }

        // 进度：写 cache 表 + 重建索引
        val cacheDao = db.getCacheDao()
        data.progress.forEach { e ->
            if (e.ep.isEmpty() || e.positionMs <= 0) return@forEach
            val hashed = MD5.string2MD5(e.ep) ?: return@forEach
            val existing = cacheDao.getCache(hashed)
            val entry = existing ?: CacheEntry()
            entry.key = hashed
            entry.data = toBytes(e.positionMs)
            cacheDao.save(entry)
            if (e.owner.isNotEmpty()) {
                val indexKey = WatchProgressIndex.keyOf(e.owner)
                val merged = WatchProgressIndex.withEp(KV.get(indexKey, ""), e.ep, e.at)
                KV.put(indexKey, merged)
            }
        }

        // 百分比 / 集数 / 追剧打卡 / 轨道记忆
        applyMapRaw(KEY_PERCENT, data.percent)
        applyMapRaw(KEY_TOTALS, data.totals)
        applyMapRaw(KEY_WATCHED, data.watched)
        data.tracks.forEach { (key, raw) ->
            if (key.startsWith(TRACK_PREFIX)) KV.rawPut(key, raw)
        }

        // 墓碑取并集（时间戳大者）
        val localTombs = SyncTombstones.read()
        data.tombstones.forEach { (key, at) ->
            val old = localTombs[key] ?: 0L
            if (at > old) localTombs[key] = at
        }
        SyncTombstones.write(localTombs)

        // 刷新进度条 UI
        PlaybackProgress.reload()
    }

    private val gson = com.google.gson.Gson()

    private fun readMapRaw(key: String): Map<String, String> {
        val raw = KV.rawGet(key) ?: return emptyMap()
        val json = if (raw.startsWith("\u0001json:")) raw.substring("\u0001json:".length) else raw
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private fun applyMapRaw(key: String, incoming: Map<String, String>) {
        if (incoming.isEmpty()) return
        val current = LinkedHashMap<String, String>(readMapRaw(key))
        current.putAll(incoming)
        KV.rawPut(key, "\u0001json:" + gson.toJson(current))
    }

    private fun toLong(bytes: ByteArray): Long? = try {
        java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readObject() as? Long }
    } catch (e: Throwable) {
        null
    }

    private fun toBytes(value: Long): ByteArray = try {
        java.io.ByteArrayOutputStream().use { baos ->
            java.io.ObjectOutputStream(baos).use { it.writeObject(value) }
            baos.toByteArray()
        }
    } catch (e: Throwable) {
        ByteArray(0)
    }
}
