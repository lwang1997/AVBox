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
private const val UPSCALE_DTD_X2 = "Anime4K_Upscale_DTD_x2.glsl"
private const val UPSCALE_DENOISE_CNN_X2_L = "Anime4K_Upscale_Denoise_CNN_x2_L.glsl"

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

    // 真人素材所有档位原先共用 DoG_x2，切档只有 1:1 Soft Restore 变化、超分无差异。
    // 按档位区分超分资产：High 走 DTD（感知锐度更好、无 CNN 棋盘格），Ultra 走降噪 CNN
    // （顺带补上真人链缺失的降噪）。Light 无超分，Standard/UpscaleOnly 保留原 DoG 行为。
    private val realMovieUpscale: String?
        get() = when (this) {
            Light -> null
            Standard, UpscaleOnly -> UPSCALE_DEBLUR_DOG_X2
            High -> UPSCALE_DTD_X2
            Ultra -> UPSCALE_DENOISE_CNN_X2_L
        }

    companion object {

        val default: Anime4kTier = Standard

        fun current(): Anime4kTier = byName(KV.get(KVKeys.ANIME4K_TIER, default.name))

        fun byName(name: String): Anime4kTier = entries.firstOrNull { it.name == name } ?: default
    }
}

object Anime4kSettings {

    const val DEFAULT_SHARPEN = 1.00f

    const val DEFAULT_AUTO_MAX_WIDTH = 1920

    /** 真人超分资产（DoG/DTD/Denoise CNN）已自带锐化，present 锐化打折避免三重叠加。 */
    const val REAL_MOVIE_SHARPEN_SCALE = 0.35f

    @Volatile
    private var sharpenCached = -1f

    /**
     * 是否手动开启。只剩播放器面板的「本次播放」开关（[Anime4kStatus.sessionForced]）：
     * 换片即清，不会留下任何全局常开状态。旧的 `ANIME4K_ENABLED` 键不再读取
     * （设置页没有总开关了，之前误开的值一律忽略）。
     *
     * 手动开启表示用户明确要求这个视频走增强，因此跳过 [Anime4kAutoPolicy] 的自动上限
     * ——「自动开启上限」只约束自动开启。
     */
    fun manualActive(): Boolean = Anime4kStatus.sessionForced

    fun deblur(): Boolean = KV.get(KVKeys.ANIME4K_DEBLUR, false)

    /**
     * 去模糊开关是否对该模式有意义。真人模式的超分资产（DoG/DTD/Denoise CNN）
     * 本身已含去模糊或降噪，再叠一层 1:1 Deblur_DoG 容易 ringing。
     * 链与 UI 必须用同一判据，避免"链里忽略、UI 仍显示"的错位。
     */
    fun deblurApplicable(mode: Anime4kMode): Boolean = mode == Anime4kMode.Anime

    /** 链里实际插入的独立去模糊开关。 */
    fun effectiveDeblur(mode: Anime4kMode, wanted: Boolean = deblur()): Boolean =
        deblurApplicable(mode) && wanted

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

    /** present pass 实际用的锐化强度：真人模式打折，避免与超分资产自带锐化叠加过冲。 */
    fun effectiveSharpen(mode: Anime4kMode, value: Float = sharpen()): Float =
        if (mode == Anime4kMode.RealMovie) value * REAL_MOVIE_SHARPEN_SCALE else value
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

    /**
     * 播放器面板的「本次播放」开启。不写全局总开关，换片（[resetSession]）即清，
     * 因此不会像旧实现那样把设置页的「自动开启上限」永久废掉。
     */
    @Volatile
    var sessionForced = false

    /**
     * 面板改了「本次播放」开关 / 档位 / 模式 / 去模糊：由链在下一帧按当前设置自建，
     * **不重建内核**。重建内核要重新请求一次数据源（直链 m3u8 带时效 token，
     * 过期后整段播放失败），所以这类纯着色器参数只能热更新。
     */
    @Volatile
    private var chainSyncRequested = false

    fun requestChainSync() {
        chainSyncRequested = true
    }

    fun takeChainSync(): Boolean {
        if (!chainSyncRequested) return false
        chainSyncRequested = false
        return true
    }

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
        chainSyncRequested = false
    }

    fun resetSession() {
        onPrepare()
        sessionDisabled = false
        sessionForced = false
    }
}
