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

    /** 更换追剧绑定的站点：就地更新条目，其余设置保留。 */
    suspend fun rebind(id: Int, sourceKey: String?, vodInfo: VodInfo)

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

    override suspend fun rebind(id: Int, sourceKey: String?, vodInfo: VodInfo) {
        val dao = follows()
        val record = dao.getAllRaw().firstOrNull { it.id == id } ?: return
        val vodId = vodInfo.id
        if (record.sourceKey == sourceKey && record.vodId == vodId) return
        val cid = record.cid ?: CurrentSubscription.cid()
        // 旧身份写墓碑，避免同步合并后把原来的站点条目复活成重复项。
        SyncTombstones.mark(SyncTombstones.KIND_FOLLOW, cid, record.sourceKey, record.vodId)
        dao.getVodFollow(cid, sourceKey, vodId)?.let { duplicate ->
            if (duplicate.id != record.id) dao.deleteSelected(listOf(duplicate.id))
        }
        FollowWatched.migrate(
            FollowWatched.ownerOf(record.sourceKey, record.vodId),
            FollowWatched.ownerOf(sourceKey, vodId),
        )
        record.cid = cid
        record.sourceKey = sourceKey
        record.vodId = vodId
        record.name = vodInfo.name
        if (!vodInfo.pic.isNullOrEmpty()) record.pic = vodInfo.pic
        // 刷新时间戳：新身份在同步合并中按 LWW 生效；重新绑回旧站点时也能越过旧墓碑。
        record.addedTime = System.currentTimeMillis()
        dao.insert(record)
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
