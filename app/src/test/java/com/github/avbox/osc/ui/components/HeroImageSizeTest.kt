package com.github.avbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroImageSizeTest {

    @Test
    fun posterStaysLargeUpToThreshold() {
        assertEquals(PosterSize.LARGE, heroPosterSize(540))
        assertEquals(PosterSize.LARGE, heroPosterSize(720))
        assertEquals(PosterSize.LARGE, heroPosterSize(800))
    }

    @Test
    fun posterSwitchesToFullAboveThreshold() {
        assertEquals(PosterSize.FULL, heroPosterSize(801))
        assertEquals(PosterSize.FULL, heroPosterSize(1080))
        assertEquals(PosterSize.FULL, heroPosterSize(2800))
    }

    @Test
    fun backdropNeverUsesOriginal() {
        assertEquals(PosterSize.LARGE, heroBackdropSize(780))
        assertEquals(PosterSize.LARGE, heroBackdropSize(800))
        assertEquals(PosterSize.WIDE, heroBackdropSize(801))
        assertEquals(PosterSize.WIDE, heroBackdropSize(1280))
        assertEquals(PosterSize.WIDE, heroBackdropSize(1400))
        assertEquals(PosterSize.WIDE, heroBackdropSize(1401))
        assertEquals(PosterSize.WIDE, heroBackdropSize(2800))
    }
}
