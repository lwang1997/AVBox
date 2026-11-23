package com.github.avbox.osc.data

import com.github.avbox.osc.bean.FollowTmdbInfo
import com.github.avbox.osc.bean.TmdbAirEpisode
import com.github.avbox.osc.bean.TmdbDetail
import com.github.avbox.osc.bean.TmdbSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowTmdbInfoTest {

    @Test
    fun fromDetail_readsStatusAndEpisodes() {
        val detail = TmdbDetail(
            overview = "x",
            rating = 0.0,
            genres = emptyList(),
            cast = emptyList(),
            seasons = emptyList(),
            status = "Returning Series",
            totalEpisodes = 36,
            lastEpisode = TmdbAirEpisode(2, 5, "第五集", "2026-11-20"),
            nextEpisode = TmdbAirEpisode(2, 6, "第六集", "2026-11-27"),
        )
        val info = FollowTmdbInfo.fromDetail(detail, now = 1234L)!!
        assertEquals("Returning Series", info.status)
        assertEquals(36, info.totalEpisodes)
        assertEquals(2, info.lastSeason)
        assertEquals(5, info.lastEpisode)
        assertEquals("2026-11-20", info.lastAirDate)
        assertEquals(6, info.nextEpisode)
        assertEquals("2026-11-27", info.nextAirDate)
        assertEquals(1234L, info.fetchedAt)
    }

    @Test
    fun fromDetail_absoluteEpisodeSumsPriorSeasonsAndSkipsSpecials() {
        val detail = TmdbDetail(
            overview = "x",
            rating = 0.0,
            genres = emptyList(),
            cast = emptyList(),
            seasons = listOf(
                TmdbSeason(0, 3),   // 特别篇，不计入
                TmdbSeason(1, 10),
                TmdbSeason(2, 12),
                TmdbSeason(3, 8),   // 目标季之后，不计入
            ),
            status = "Returning Series",
            totalEpisodes = 30,
            lastEpisode = TmdbAirEpisode(2, 5, "第五集", "2026-11-20"),
        )
        val info = FollowTmdbInfo.fromDetail(detail, now = 1234L)!!
        // 第1季10集 + 第2季第5集 = 15
        assertEquals(15, info.lastAbsoluteEpisode)
        assertEquals(2, info.lastSeason)
        assertEquals(5, info.lastEpisode)
    }

    @Test
    fun fromDetail_absoluteEpisodeZeroWhenNoAirEpisode() {
        val detail = TmdbDetail(
            overview = "x",
            rating = 0.0,
            genres = emptyList(),
            cast = emptyList(),
            seasons = listOf(TmdbSeason(1, 10)),
            status = "Ended",
            totalEpisodes = 10,
            lastEpisode = null,
        )
        val info = FollowTmdbInfo.fromDetail(detail)!!
        assertEquals(0, info.lastAbsoluteEpisode)
    }

    @Test
    fun fromDetail_returnsNullWhenNoUpdateInfo() {
        val detail = TmdbDetail("x", 0.0, emptyList(), emptyList(), emptyList())
        assertNull(FollowTmdbInfo.fromDetail(detail))
        assertNull(FollowTmdbInfo.fromDetail(null))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val info = FollowTmdbInfo(
            status = "Ended",
            totalEpisodes = 24,
            lastSeason = 2,
            lastEpisode = 24,
            lastAirDate = "2025-01-01",
            fetchedAt = 99L,
            year = 2024,
            altName = "Alt Title",
        )
        val decoded = FollowTmdbInfo.decode(FollowTmdbInfo.encode(info))!!
        assertEquals(info, decoded)
    }

    @Test
    fun decode_toleratesEmptyAndMalformed() {
        assertNull(FollowTmdbInfo.decode(""))
        assertNull(FollowTmdbInfo.decode(null))
        assertNull(FollowTmdbInfo.decode("not-json"))
    }
}
