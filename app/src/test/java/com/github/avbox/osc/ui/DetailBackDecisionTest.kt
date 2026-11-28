package com.github.avbox.osc.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailBackDecisionTest {

    private fun decide(
        entering: Boolean = false,
        exiting: Boolean = false,
        fullScreen: Boolean = false,
        hasInlinePlayer: Boolean = false,
    ) = DetailBackDecision.decide(entering, exiting, fullScreen, hasInlinePlayer)

    @Test
    fun enteringCancelsEntryFirst() {
        assertEquals(
            DetailBackAction.CancelEntry,
            decide(entering = true, fullScreen = true, hasInlinePlayer = true),
        )
    }

    @Test
    fun exitingIsIdempotent() {
        assertEquals(DetailBackAction.IgnoreTransition, decide(exiting = true, fullScreen = true))
    }

    @Test
    fun fullScreenExitsFullScreen() {
        assertEquals(DetailBackAction.ExitFullscreen, decide(fullScreen = true, hasInlinePlayer = true))
    }

    @Test
    fun inlinePlayerClosesPlayer() {
        assertEquals(DetailBackAction.CloseInlinePlayer, decide(hasInlinePlayer = true))
    }

    @Test
    fun plainPageGoesBack() {
        assertEquals(DetailBackAction.PageBack, decide())
    }
}
