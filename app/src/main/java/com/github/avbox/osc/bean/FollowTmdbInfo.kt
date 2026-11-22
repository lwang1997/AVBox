package com.github.avbox.osc.bean

import com.google.gson.Gson
import java.io.Serializable

/**
 * 订阅条目上缓存的 TMDB 更新信息快照。
 *
 * 与 VodFollow.tmdbInfo 对应，整条以 JSON 存一列，多端同步时按原子字段搬运。
 * 字段全部为可空/默认值，旧缓存或 TMDB 返回不全时安全降级。
 */
data class FollowTmdbInfo(
    val status: String = "",
    val totalEpisodes: Int = 0,
    val lastSeason: Int = 0,
    val lastEpisode: Int = 0,
    val lastAirDate: String = "",
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0,
    val nextAirDate: String = "",
    val fetchedAt: Long = 0L,
    /** 标题查找用年份 / 别名，后台刷新时复用，避免二次猜测。 */
    val year: Int = 0,
    val altName: String = "",
) : Serializable {

    companion object {
        private val gson = Gson()

        /** 从 TMDB 详情构造快照；无有效更新信息时返回 null。 */
        fun fromDetail(
            detail: TmdbDetail?,
            now: Long = System.currentTimeMillis(),
            year: Int = 0,
            altName: String = "",
        ): FollowTmdbInfo? {
            if (detail == null) return null
            val last = detail.lastEpisode
            val next = detail.nextEpisode
            val hasInfo = !detail.status.isNullOrBlank() ||
                detail.totalEpisodes > 0 ||
                (last != null && (last.season > 0 || last.episode > 0)) ||
                (next != null && (next.season > 0 || next.episode > 0))
            if (!hasInfo) return null
            return FollowTmdbInfo(
                status = detail.status.orEmpty(),
                totalEpisodes = detail.totalEpisodes.coerceAtLeast(0),
                lastSeason = (last?.season ?: 0).coerceAtLeast(0),
                lastEpisode = (last?.episode ?: 0).coerceAtLeast(0),
                lastAirDate = last?.airDate.orEmpty(),
                nextSeason = (next?.season ?: 0).coerceAtLeast(0),
                nextEpisode = (next?.episode ?: 0).coerceAtLeast(0),
                nextAirDate = next?.airDate.orEmpty(),
                fetchedAt = now,
                year = year,
                altName = altName,
            )
        }

        fun encode(info: FollowTmdbInfo): String = gson.toJson(info)

        fun decode(raw: String?): FollowTmdbInfo? {
            if (raw.isNullOrBlank()) return null
            return try {
                gson.fromJson(raw, FollowTmdbInfo::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
