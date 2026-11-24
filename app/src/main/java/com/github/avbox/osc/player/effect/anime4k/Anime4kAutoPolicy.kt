package com.github.avbox.osc.player.effect.anime4k

import kotlin.math.max

/**
 * 按视频分辨率决定 Anime4K 的输出缓冲倍率。
 *
 * 输出面尺寸由 App 通过 MSG_SET_VIDEO_OUTPUT_RESOLUTION 推送（见 ui-spec §6.17），
 * 效果链内部算出的尺寸不会改变输出面，所以倍率必须在推送层决策。
 * 放大后的尺寸同时被 EffectCanvas 读取，compose/planPasses 会据此自动匹配
 * CNN 次数与 present 目标，chain 侧无需改动。
 */
object Anime4kAutoPolicy {

    /** 输出缓冲单边上限：避开 GL_MAX_TEXTURE_SIZE = 4096 的临界设备。 */
    const val MAX_OUTPUT_EDGE = 3840

    /**
     * 输出缓冲总像素上限。单边上限只约束最长边，4:3 / 方形这类接近正方形的
     * 非标准比例会在长边相同的情况下多出 33%~78% 的像素，链累计纹理可达 140MB。
     * 取 1200 万像素：4:3 960x720 的 4x（11.06M）放行，方形 960x960 的 4x
     * （14.75M）降到 2x，正好切在显存危险线之前。
     */
    const val MAX_OUTPUT_PIXELS = 12_000_000L

    /**
     * 返回输出缓冲相对视频原生尺寸的倍率（1 / 2 / 4）。
     * 关闭、自动未命中、或放大后超单边/总像素上限时返回 1。
     */
    fun outputScale(
        width: Int,
        height: Int,
        manualEnabled: Boolean,
        autoEnabled: Boolean,
        autoMaxWidth: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        val edge = max(width, height)
        val eligible = manualEnabled ||
            (autoEnabled && autoMaxWidth > 0 && edge <= autoMaxWidth)
        if (!eligible) return 1
        val sourcePixels = width.toLong() * height.toLong()
        fun fits(scale: Int): Boolean =
            edge.toLong() * scale <= MAX_OUTPUT_EDGE &&
                sourcePixels * scale.toLong() * scale <= MAX_OUTPUT_PIXELS
        if (fits(4)) return 4
        if (fits(2)) return 2
        return 1
    }

    /** 当前 KV 与视频尺寸下增强是否会命中（倍率 > 1）。供 OSD 等实时判定，避免读瞬态状态。 */
    fun isHit(
        width: Int,
        height: Int,
        manualEnabled: Boolean,
        autoEnabled: Boolean,
        autoMaxWidth: Int,
    ): Boolean = outputScale(width, height, manualEnabled, autoEnabled, autoMaxWidth) > 1
}
