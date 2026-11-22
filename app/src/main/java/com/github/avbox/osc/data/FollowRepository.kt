package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo
import kotlinx.coroutines.flow.Flow

interface FollowRepository {

    fun currentCid(): String

    suspend fun find(sourceKey: String?, vodId: String?): VodFollow?

    fun observeAll(): Flow<List<VodFollow>>

    suspend fun getAllRaw(): List<VodFollow>

    suspend fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int)

    /** 与 [upsert] 相同，但写入 TMDB 更新信息快照。 */
    suspend fun upsertWithTmdbInfo(
        sourceKey: String?,
        vodInfo: VodInfo,
        days: Set<Int>,
        hour: Int,
        tmdbInfo: String,
    )

    /** 更新单条追剧的 TMDB 快照，用于后台刷新。 */
    suspend fun updateTmdbInfo(id: Int, raw: String)

    suspend fun deleteSelected(ids: List<Int>)
}

internal class RoomFollowRepository(
    private val follows: () -> VodFollowDao,
) : FollowRepository {

    override fun currentCid(): String = CurrentSubscription.cid()

    override suspend fun find(sourceKey: String?, vodId: String?): VodFollow? =
        follows().getVodFollow(CurrentSubscription.cid(), sourceKey, vodId)

    override fun observeAll(): Flow<List<VodFollow>> = follows().observeAll()

    override suspend fun getAllRaw(): List<VodFollow> = follows().getAllRaw()

    override suspend fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int) {
        upsertInternal(sourceKey, vodInfo, days, hour, null)
    }

    override suspend fun upsertWithTmdbInfo(
        sourceKey: String?,
        vodInfo: VodInfo,
        days: Set<Int>,
        hour: Int,
        tmdbInfo: String,
    ) {
        upsertInternal(sourceKey, vodInfo, days, hour, tmdbInfo)
    }

    private suspend fun upsertInternal(
        sourceKey: String?,
        vodInfo: VodInfo,
        days: Set<Int>,
        hour: Int,
        tmdbInfo: String?,
    ) {
        val cid = CurrentSubscription.cid()
        val dao = follows()
        val record = dao.getVodFollow(cid, sourceKey, vodInfo.id) ?: VodFollow()
        if (record.id == 0) {
            record.cid = cid
            record.sourceKey = sourceKey
            record.vodId = vodInfo.id
            record.addedTime = System.currentTimeMillis()
        }
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        record.updateDays = FollowDays.encode(days)
        record.updateHour = hour.coerceIn(0, 23)
        if (tmdbInfo != null) record.tmdbInfo = tmdbInfo
        dao.insert(record)
    }

    override suspend fun updateTmdbInfo(id: Int, raw: String) {
        follows().updateTmdbInfo(id, raw)
    }

    override suspend fun deleteSelected(ids: List<Int>) {
        if (ids.isEmpty()) return
        val dao = follows()
        val idSet = ids.toHashSet()
        dao.getAllRaw().forEach { record ->
            if (record.id in idSet) {
                SyncTombstones.mark(SyncTombstones.KIND_FOLLOW, record.cid, record.sourceKey, record.vodId)
            }
        }
        dao.deleteSelected(ids)
    }
}
