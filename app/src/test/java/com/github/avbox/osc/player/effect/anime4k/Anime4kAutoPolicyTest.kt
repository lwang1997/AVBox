package com.github.avbox.osc.player.effect.anime4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4kAutoPolicyTest {

    private val autoLimit = Anime4kSettings.DEFAULT_AUTO_MAX_WIDTH

    private fun auto(w: Int, h: Int, limit: Int = autoLimit) =
        Anime4kAutoPolicy.outputScale(w, h, false, true, limit)

    private fun manual(w: Int, h: Int) =
        Anime4kAutoPolicy.outputScale(w, h, true, false, 0)

    @Test
    fun disabled_keeps_native_size() {
        assertEquals(1, Anime4kAutoPolicy.outputScale(854, 480, false, false, autoLimit))
        assertEquals(1, Anime4kAutoPolicy.outputScale(854, 480, false, false, 0))
    }

    @Test
    fun auto_scales_480p_to_four() {
        assertEquals(4, auto(854, 480))
    }

    @Test
    fun auto_scales_576p_and_720p_to_two() {
        assertEquals(2, auto(1024, 576))
        assertEquals(2, auto(1280, 720))
    }

    @Test
    fun auto_scales_1080p_to_two() {
        assertEquals(2, auto(1920, 1080))
    }

    @Test
    fun auto_beyond_limit_keeps_native_size() {
        assertEquals(1, auto(2560, 1440))
        assertEquals(1, auto(3840, 2160))
    }

    @Test
    fun auto_with_zero_limit_keeps_native_size() {
        assertEquals(1, auto(854, 480, 0))
    }

    @Test
    fun manual_bypasses_auto_limit_but_respects_output_cap() {
        assertEquals(4, manual(854, 480))
        assertEquals(2, manual(1920, 1080))
        assertEquals(1, manual(3840, 2160))
    }

    @Test
    fun manual_takes_priority_when_both_enabled() {
        // 自动阈值收紧到 1280 时 1080p 不命中，但手动开启仍可放大到 2x。
        assertEquals(
            2,
            Anime4kAutoPolicy.outputScale(1920, 1080, true, true, 1280),
        )
    }

    @Test
    fun invalid_dimensions_keep_native_size() {
        assertEquals(1, Anime4kAutoPolicy.outputScale(0, 480, true, false, 0))
        assertEquals(1, Anime4kAutoPolicy.outputScale(854, -1, true, false, 0))
    }

    @Test
    fun output_cap_boundary() {
        assertEquals(4, Anime4kAutoPolicy.outputScale(960, 540, true, false, 0))
        assertEquals(2, Anime4kAutoPolicy.outputScale(961, 540, true, false, 0))
        assertEquals(2, Anime4kAutoPolicy.outputScale(1920, 1080, true, false, 0))
        assertEquals(1, Anime4kAutoPolicy.outputScale(1921, 1080, true, false, 0))
    }

    @Test
    fun non_standard_aspect_ratios_get_a_scale() {
        assertEquals(4, auto(852, 480))
        assertEquals(2, auto(1280, 544))
        assertEquals(2, auto(1279, 719))
        assertEquals(2, auto(1281, 721))
        assertEquals(4, auto(640, 360))
        assertEquals(4, auto(320, 240))
        assertEquals(1, auto(2560, 1080))
    }

    @Test
    fun portrait_video_scales_by_long_edge() {
        assertEquals(2, auto(1080, 1920))
        assertEquals(2, auto(720, 1280))
    }

    @Test
    fun pixel_budget_caps_near_square_sources() {
        assertEquals(4, auto(960, 720))
        assertEquals(2, auto(960, 960))
        assertEquals(2, auto(867, 867))
        assertEquals(4, auto(866, 866))
        assertEquals(2, auto(1920, 1081))
    }

    @Test
    fun pixel_budget_applies_in_manual_mode_too() {
        assertEquals(4, manual(960, 720))
        assertEquals(2, manual(960, 960))
        assertEquals(2, manual(1920, 1080))
    }

    @Test
    fun is_hit_matches_scale_for_osd_realtime_judgement() {
        // OSD 不能读 configure 时才写的瞬态 tierActive；用 isHit 实时判定，
        // 480p 必然命中，与倍率结果严格一致。
        assertTrue(Anime4kAutoPolicy.isHit(640, 480, false, true, autoLimit))
        assertTrue(Anime4kAutoPolicy.isHit(854, 480, false, true, autoLimit))
        assertTrue(Anime4kAutoPolicy.isHit(1920, 1080, true, false, 0))
        assertFalse(Anime4kAutoPolicy.isHit(2560, 1440, false, true, autoLimit))
        assertFalse(Anime4kAutoPolicy.isHit(854, 480, false, false, autoLimit))
        assertFalse(Anime4kAutoPolicy.isHit(0, 0, true, false, 0))
        // 每一个用例：isHit 必须等价于 outputScale > 1
        for ((w, h, m, a, limit) in listOf(
            intArrayOf(640, 480, 0, 1, autoLimit),
            intArrayOf(1280, 720, 0, 1, autoLimit),
            intArrayOf(1920, 1080, 1, 0, 0),
            intArrayOf(960, 960, 0, 1, autoLimit),
            intArrayOf(3840, 2160, 1, 0, 0),
        )) {
            val scale = Anime4kAutoPolicy.outputScale(w, h, m == 1, a == 1, limit)
            assertEquals(scale > 1, Anime4kAutoPolicy.isHit(w, h, m == 1, a == 1, limit))
        }
    }
}
