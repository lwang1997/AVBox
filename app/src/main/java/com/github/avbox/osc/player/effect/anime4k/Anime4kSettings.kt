package com.github.avbox.osc.player.effect.anime4k

import androidx.annotation.StringRes
import com.github.avbox.osc.R
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV

internal const val ANIME4K_ASSET_DIR = "Anime4K/"

internal const val ANIME4K_CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"

internal const val ANIME4K_DEBLUR_DOG = "Anime4K_Deblur_DoG.glsl"

private const val RESTORE_CNN_S = "Anime4K_Restore_CNN_S.glsl"
private const val RESTORE_CNN_M = "Anime4K_Restore_CNN_M.glsl"
private const val RESTORE_CNN_L = "Anime4K_Restore_CNN_L.glsl"
private const val RESTORE_CNN_SOFT_S = "Anime4K_Restore_CNN_Soft_S.glsl"
private const val RESTORE_CNN_SOFT_M = "Anime4K_Restore_CNN_Soft_M.glsl"
private const val RESTORE_CNN_SOFT_L = "Anime4K_Restore_CNN_Soft_L.glsl"
private const val UPSCALE_CNN_X2_S = "Anime4K_Upscale_CNN_x2_S.glsl"
private const val UPSCALE_CNN_X2_M = "Anime4K_Upscale_CNN_x2_M.glsl"
private const val UPSCALE_CNN_X2_L = "Anime4K_Upscale_CNN_x2_L.glsl"
private const val UPSCALE_DEBLUR_DOG_X2 = "Anime4K_Upscale_Deblur_DoG_x2.glsl"

enum class Anime4kMode(@StringRes val labelRes: Int) {
    Anime(R.string.player_anime4k_mode_anime),
    RealMovie(R.string.player_anime4k_mode_real_movie),
    ;

    companion object {

        val default: Anime4kMode = Anime

        fun current(): Anime4kMode = byName(KV.get(KVKeys.ANIME4K_MODE, default.name))

        fun byName(name: String): Anime4kMode = entries.firstOrNull { it.name == name } ?: default
    }
}

enum class Anime4kTier(
    @StringRes val labelRes: Int,
    private val restoreAsset: String?,
    private val upscaleAsset: String?,
) {
    Light(R.string.player_anime4k_tier_light, RESTORE_CNN_S, null),
    Standard(R.string.player_anime4k_tier_standard, RESTORE_CNN_S, UPSCALE_CNN_X2_S),
    High(R.string.player_anime4k_tier_high, RESTORE_CNN_M, UPSCALE_CNN_X2_M),
    Ultra(R.string.player_anime4k_tier_ultra, RESTORE_CNN_L, UPSCALE_CNN_X2_L),
    UpscaleOnly(R.string.player_anime4k_tier_upscale, null, UPSCALE_CNN_X2_S),
    ;

    fun restore(mode: Anime4kMode): String? = when (mode) {
        Anime4kMode.Anime -> restoreAsset
        Anime4kMode.RealMovie -> softRestore
    }

    fun upscale(mode: Anime4kMode): String? = when (mode) {
        Anime4kMode.Anime -> upscaleAsset
        Anime4kMode.RealMovie -> realMovieUpscale
    }

    fun assetsFor(mode: Anime4kMode): List<String> = listOfNotNull(restore(mode), upscale(mode))

    private val softRestore: String?
        get() = when (this) {
            Light, Standard -> RESTORE_CNN_SOFT_S
            High -> RESTORE_CNN_SOFT_M
            Ultra -> RESTORE_CNN_SOFT_L
            UpscaleOnly -> null
        }

    private val realMovieUpscale: String?
        get() = if (this == Light) null else UPSCALE_DEBLUR_DOG_X2

    companion object {

        val default: Anime4kTier = Standard

        fun current(): Anime4kTier = byName(KV.get(KVKeys.ANIME4K_TIER, default.name))

        fun byName(name: String): Anime4kTier = entries.firstOrNull { it.name == name } ?: default
    }
}

object Anime4kSettings {

    const val DEFAULT_SHARPEN = 1.00f

    const val DEFAULT_AUTO_MAX_WIDTH = 1920

    @Volatile
    private var sharpenCached = -1f

    fun enabled(): Boolean = KV.get(KVKeys.ANIME4K_ENABLED, false)

    fun deblur(): Boolean = KV.get(KVKeys.ANIME4K_DEBLUR, false)

    fun defaultMode(): Anime4kMode = Anime4kMode.current()

    fun autoEnabled(): Boolean = KV.get(KVKeys.ANIME4K_AUTO, false)

    fun autoMaxWidth(): Int = KV.get(KVKeys.ANIME4K_AUTO_MAX_WIDTH, DEFAULT_AUTO_MAX_WIDTH)

    fun sharpen(): Float {
        if (sharpenCached < 0f) {
            sharpenCached = KV.get(KVKeys.ANIME4K_SHARPEN, DEFAULT_SHARPEN).coerceIn(0f, 1f)
        }
        return sharpenCached
    }

    fun setSharpen(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        sharpenCached = clamped
        KV.put(KVKeys.ANIME4K_SHARPEN, clamped)
    }
}

object Anime4kStatus {

    @Volatile
    private var failed = false

    /** 本次播放是否因低分辨率自动启用了 Anime4K。 */
    @Volatile
    var autoActive = false
        private set

    /** 当前效果链是否真的带着可用档位（自动未命中时为 false）。 */
    @Volatile
    var tierActive = false
        private set

    /** 用户在本次播放里手动关掉后，效果链改为直通但保留已建好的程序。 */
    @Volatile
    var sessionDisabled = false

    fun unavailable(): Boolean = failed

    fun onBuildSucceeded() {
        failed = false
    }

    fun onBuildFailed() {
        failed = true
    }

    fun onChainResolved(tierActive: Boolean, autoActive: Boolean) {
        this.tierActive = tierActive
        this.autoActive = autoActive
    }

    fun onPrepare() {
        failed = false
        autoActive = false
        tierActive = false
    }

    fun resetSession() {
        onPrepare()
        sessionDisabled = false
    }
}
