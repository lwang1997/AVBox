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
    /** 最近一集的绝对集数（前面各季集数之和 + 本季集数），与观看进度同口径展示。 */
    val lastAbsoluteEpisode: Int = 0,
    val lastAirDate: String = "",
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0,
    val nextAirDate: String = "",
    val fetchedAt: Long = 0L,
    /** 标题查找用年份 / 别名，后台刷新时复用，避免二次猜测。 */
    val year: Int = 0,
    val altName: String = "",
) : Serializable {

    /**
     * 展示用「更新至第几集」：优先用绝对集数；旧缓存没有该字段时回退到季内集号，
     * 保证旧追剧条目在刷新前也能显示更新集数（单季剧两者相同，多季剧刷新后修正）。
     */
    val displayUpdateEpisode: Int
        get() = if (lastAbsoluteEpisode > 0) lastAbsoluteEpisode else lastEpisode.coerceAtLeast(0)

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
                lastAbsoluteEpisode = absoluteEpisode(detail),
                lastAirDate = last?.airDate.orEmpty(),
                nextSeason = (next?.season ?: 0).coerceAtLeast(0),
                nextEpisode = (next?.episode ?: 0).coerceAtLeast(0),
                nextAirDate = next?.airDate.orEmpty(),
                fetchedAt = now,
                year = year,
                altName = altName,
            )
        }

        /**
         * 最近一集的绝对集数 = 之前各季集数之和 + 本季集号。
         * 只累加季号在 (0, lastSeason) 区间的正片季，忽略特别篇(0)与同季之后的季，
         * 避免 TMDB 季数据缺失/乱序时把结果算错。
         */
        private fun absoluteEpisode(detail: TmdbDetail): Int {
            val last = detail.lastEpisode ?: return 0
            if (last.season <= 0 || last.episode <= 0) return 0
            val prior = detail.seasons
                .filter { it.seasonNumber in 1 until last.season }
                .sumOf { it.episodeCount.coerceAtLeast(0) }
            return (prior + last.episode).coerceAtLeast(0)
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
