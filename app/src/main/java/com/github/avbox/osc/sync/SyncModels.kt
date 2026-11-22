package com.github.avbox.osc.sync

/**
 * 多端同步快照的数据传输对象。
 *
 * 快照分两部分：
 *  - data   : 历史 / 收藏 / 追剧 / 播放进度 —— 自动同步、自动合并（LWW + 墓碑）
 *  - settings: 偏好设置白名单 —— 仅手动同步，覆盖式写入
 */
data class SyncEnvelope(
    val schema: Int = SyncModels.SCHEMA_VERSION,
    val deviceId: String = "",
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val data: SyncData? = null,
    val settings: SyncSettings? = null,
)

data class SyncData(
    val history: List<HistoryEntry> = emptyList(),
    val collect: List<CollectEntry> = emptyList(),
    val follow: List<FollowEntry> = emptyList(),
    val progress: List<ProgressEntry> = emptyList(),
    /** KV "playback_progress" 的原始值：owner -> 编码串 */
    val percent: Map<String, String> = emptyMap(),
    /** KV "episode_totals" 原始值 */
    val totals: Map<String, String> = emptyMap(),
    /** KV "follow_watched" 原始值 */
    val watched: Map<String, String> = emptyMap(),
    /** KV "track_mem_*" 原始值：完整 key -> 编码串 */
    val tracks: Map<String, String> = emptyMap(),
    /** 删除墓碑：key -> 删除时间戳 */
    val tombstones: Map<String, Long> = emptyMap(),
)

data class HistoryEntry(
    val cid: String? = null,
    val sourceKey: String? = null,
    val vodId: String? = null,
    val updateTime: Long = 0L,
    val dataJson: String? = null,
)

data class CollectEntry(
    val cid: String? = null,
    val sourceKey: String? = null,
    val vodId: String? = null,
    val updateTime: Long = 0L,
    val name: String? = null,
    val pic: String? = null,
)

data class FollowEntry(
    val cid: String? = null,
    val sourceKey: String? = null,
    val vodId: String? = null,
    val addedTime: Long = 0L,
    val name: String? = null,
    val pic: String? = null,
    val updateDays: String = "",
    val updateHour: Int = 21,
    /** JSON snapshot of FollowTmdbInfo */
    val tmdbInfo: String = "",
)

data class ProgressEntry(
    /** owner = sourceKey|vodId */
    val owner: String = "",
    /** 原始 progressKey（非 MD5），用于跨端重建索引 */
    val ep: String = "",
    val positionMs: Long = 0L,
    /** 时间戳（来自 progress_index 的 at），用于 LWW */
    val at: Long = 0L,
)

data class SyncSettings(
    /** KV key -> 未经解码的原始字符串（KV.rawGet 的值） */
    val values: Map<String, String> = emptyMap(),
)

object SyncModels {
    const val SCHEMA_VERSION = 1
}
