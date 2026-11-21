package com.github.avbox.osc.player.effect.anime4k

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.max

internal class Anime4kEffect : GlEffect {

    @Volatile
    var tier: Anime4kTier? = null

    @Volatile
    var mode: Anime4kMode = Anime4kMode.default

    @Volatile
    var manualEnabled: Boolean = false

    @Volatile
    var autoMaxWidth: Int = 0

    @Volatile
    var compareSplit: Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Anime4kChainProgram(context, this, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        if (tier == null) return true
        if (manualEnabled) return false
        val limit = autoMaxWidth
        return limit <= 0 || max(inputWidth, inputHeight) > limit
    }
}
