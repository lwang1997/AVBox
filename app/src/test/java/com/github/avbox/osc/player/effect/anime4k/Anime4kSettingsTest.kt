package com.github.avbox.osc.player.effect.anime4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4kSettingsTest {

    @Test
    fun real_movie_upscales_differ_across_quality_tiers() {
        val standard = Anime4kTier.Standard.upscale(Anime4kMode.RealMovie)
        val high = Anime4kTier.High.upscale(Anime4kMode.RealMovie)
        val ultra = Anime4kTier.Ultra.upscale(Anime4kMode.RealMovie)
        assertEquals("Anime4K_Upscale_Deblur_DoG_x2.glsl", standard)
        assertEquals("Anime4K_Upscale_DTD_x2.glsl", high)
        assertEquals("Anime4K_Upscale_Denoise_CNN_x2_L.glsl", ultra)
        assertNotEquals(standard, high)
        assertNotEquals(high, ultra)
    }

    @Test
    fun real_movie_light_still_has_no_upscale() {
        assertNull(Anime4kTier.Light.upscale(Anime4kMode.RealMovie))
        assertNull(Anime4kTier.Light.upscale(Anime4kMode.Anime))
    }

    @Test
    fun anime_tiers_keep_their_original_upscales() {
        assertEquals("Anime4K_Upscale_CNN_x2_S.glsl", Anime4kTier.Standard.upscale(Anime4kMode.Anime))
        assertEquals("Anime4K_Upscale_CNN_x2_M.glsl", Anime4kTier.High.upscale(Anime4kMode.Anime))
        assertEquals("Anime4K_Upscale_CNN_x2_L.glsl", Anime4kTier.Ultra.upscale(Anime4kMode.Anime))
    }

    @Test
    fun real_movie_restore_still_scales_with_tier() {
        assertEquals("Anime4K_Restore_CNN_Soft_S.glsl", Anime4kTier.Standard.restore(Anime4kMode.RealMovie))
        assertEquals("Anime4K_Restore_CNN_Soft_M.glsl", Anime4kTier.High.restore(Anime4kMode.RealMovie))
        assertEquals("Anime4K_Restore_CNN_Soft_L.glsl", Anime4kTier.Ultra.restore(Anime4kMode.RealMovie))
    }

    @Test
    fun effective_deblur_is_ignored_for_real_movie() {
        assertTrue(Anime4kSettings.effectiveDeblur(Anime4kMode.Anime, true))
        assertFalse(Anime4kSettings.effectiveDeblur(Anime4kMode.Anime, false))
        assertFalse(Anime4kSettings.effectiveDeblur(Anime4kMode.RealMovie, true))
        assertFalse(Anime4kSettings.effectiveDeblur(Anime4kMode.RealMovie, false))
    }

    @Test
    fun deblur_applicability_matches_chain_so_ui_can_hide_the_switch() {
        assertTrue(Anime4kSettings.deblurApplicable(Anime4kMode.Anime))
        assertFalse(Anime4kSettings.deblurApplicable(Anime4kMode.RealMovie))
        // 两种模式下的 Chain 判据必须与 UI 可见性判据完全一致。
        listOf(true, false).forEach { wanted ->
            assertEquals(
                Anime4kSettings.deblurApplicable(Anime4kMode.Anime) && wanted,
                Anime4kSettings.effectiveDeblur(Anime4kMode.Anime, wanted),
            )
            assertEquals(
                Anime4kSettings.deblurApplicable(Anime4kMode.RealMovie) && wanted,
                Anime4kSettings.effectiveDeblur(Anime4kMode.RealMovie, wanted),
            )
        }
    }

    @Test
    fun effective_sharpen_is_discounted_for_real_movie_only() {
        val value = Anime4kSettings.DEFAULT_SHARPEN
        assertEquals(value, Anime4kSettings.effectiveSharpen(Anime4kMode.Anime, value), 0.0001f)
        assertEquals(
            value * Anime4kSettings.REAL_MOVIE_SHARPEN_SCALE,
            Anime4kSettings.effectiveSharpen(Anime4kMode.RealMovie, value),
            0.0001f,
        )
        assertTrue(Anime4kSettings.effectiveSharpen(Anime4kMode.RealMovie, value) < value)
    }
}
