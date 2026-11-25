package com.github.avbox.osc.player.effect

import androidx.media3.common.Effect
import com.github.avbox.osc.player.core.RedrawPolicy
import com.github.avbox.osc.player.effect.anime4k.Anime4kEffect
import com.github.avbox.osc.player.effect.anime4k.Anime4kMode
import com.github.avbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.avbox.osc.player.effect.anime4k.Anime4kStatus
import com.github.avbox.osc.player.effect.anime4k.Anime4kTier
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import java.lang.ref.WeakReference

enum class PictureEffectUnavailableReason {
    None, Tunneling, Hdr, DecoderUnsupported;

    companion object {
        fun of(
            tunneling: Boolean,
            hdr: Boolean,
            pipeOpen: Boolean,
            effectsActive: Boolean,
            hasLook: Boolean,
        ): PictureEffectUnavailableReason = when {
            tunneling -> Tunneling
            hdr && hasLook -> Hdr
            pipeOpen && !effectsActive && hasLook -> DecoderUnsupported
            else -> None
        }
    }
}

object PictureEffects {

    private val colorTone = ColorToneAdjustEffect()
    private val detail = DetailAdjustEffect()
    private val anime4k = Anime4kEffect()

    private val listWithoutAnime4k: List<Effect> = listOf(colorTone, detail)
    private val listWithAnime4k: List<Effect> = listOf(colorTone, detail, anime4k)

    internal fun effectsFor(anime4kEnabled: Boolean, autoAnime4kEnabled: Boolean = false): List<Effect> =
        if (anime4kEnabled || autoAnime4kEnabled) listWithAnime4k else listWithoutAnime4k

    private var current: WeakReference<PictureEffectHost>? = null

    private var tunneling = false

    private var openedThisSession = false

    private var comparing = false

    private var anime4kOpened = false
    private var anime4kForcedOpened = false
    private var anime4kTierOpened: Anime4kTier = Anime4kTier.default
    private var anime4kModeOpened: Anime4kMode = Anime4kMode.default
    private var anime4kDeblurOpened = false
    private var sessionMode: Anime4kMode = Anime4kMode.default
    private var sessionLoaded = false
    private var contentKey: String? = null

    fun preset(): PicturePreset {
        val name = KV.get(KVKeys.PICTURE_PRESET, PicturePreset.Original.name)
        return PicturePreset.entries.firstOrNull { it.name == name } ?: PicturePreset.Original
    }

    fun anime4kMode(): Anime4kMode = sessionMode

    fun anime4kActive(): Boolean =
        (Anime4kSettings.manualActive() || Anime4kStatus.autoActive) && !Anime4kStatus.sessionDisabled

    fun comparing(): Boolean = comparing

    var custom: PictureProfile
        get() = PictureProfile(
            saturation = KV.get(KVKeys.PICTURE_SATURATION, 1f),
            contrast = KV.get(KVKeys.PICTURE_CONTRAST, 1f),
            brightness = KV.get(KVKeys.PICTURE_BRIGHTNESS, 0f),
            gamma = KV.get(KVKeys.PICTURE_GAMMA, 1f),
            hue = KV.get(KVKeys.PICTURE_HUE, 0f),
            temperature = KV.get(KVKeys.PICTURE_TEMPERATURE, 0f),
            sharpness = KV.get(KVKeys.PICTURE_SHARPNESS, 0f),
            shadowLift = KV.get(KVKeys.PICTURE_SHADOW_LIFT, 0f),
        ).clamped()
        set(value) {
            KV.put(KVKeys.PICTURE_SATURATION, value.saturation)
            KV.put(KVKeys.PICTURE_CONTRAST, value.contrast)
            KV.put(KVKeys.PICTURE_BRIGHTNESS, value.brightness)
            KV.put(KVKeys.PICTURE_GAMMA, value.gamma)
            KV.put(KVKeys.PICTURE_HUE, value.hue)
            KV.put(KVKeys.PICTURE_TEMPERATURE, value.temperature)
            KV.put(KVKeys.PICTURE_SHARPNESS, value.sharpness)
            KV.put(KVKeys.PICTURE_SHADOW_LIFT, value.shadowLift)
            push()
        }

    fun applied(): PictureProfile {
        val preset = preset()
        return if (preset.adjustable) custom else PictureProfile.of(preset)
    }

    private fun wanted(): Boolean =
        !applied().isNoOp || Anime4kSettings.manualActive() || Anime4kSettings.autoEnabled()

    fun selectPreset(preset: PicturePreset) {
        KV.put(KVKeys.PICTURE_PRESET, preset.name)
        push()
    }

    fun reset() {
        KV.put(KVKeys.PICTURE_PRESET, PicturePreset.Original.name)
        custom = PictureProfile.OFF
    }

    fun compare(split: Boolean) {
        if (comparing == split) return
        comparing = split
        AppLog.i(
            "echo-compare: split=$split opened=$openedThisSession" +
                " anime4k=${anime4kActive()} preset=${preset().name}",
        )
        push()
    }

    fun setAnime4kMode(mode: Anime4kMode) {
        if (sessionMode == mode) return
        sessionMode = mode
        anime4k.mode = mode
    }

    fun setAnime4kSessionDisabled(disabled: Boolean) {
        if (Anime4kStatus.sessionDisabled == disabled) return
        Anime4kStatus.sessionDisabled = disabled
        colorTone.compareSplit = comparing
        detail.compareSplit = comparing
        anime4k.compareSplit = comparing
        val player = current?.get() ?: return
        if (player.isPictureHdrSource()) return
        if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
            player.redrawVideoFrame()
        }
    }

    /**
     * 播放器面板的 Anime4K 开关：只作用于本次播放，不落库。
     * 下一次起播回到「低分辨率自动开启 + 上限」的自动判断；「关」只让本次播放直通。
     */
    fun setAnime4kSessionEnabled(enabled: Boolean) {
        Anime4kStatus.sessionForced = enabled
        // 本次链路已带档位在跑（自动命中）：无需重建，直接记下已生效，
        // 否则之后每次面板操作都会再触发一次内核重建。
        if (Anime4kStatus.sessionForced && Anime4kStatus.tierActive) anime4kForcedOpened = true
        setAnime4kSessionDisabled(!enabled)
    }

    fun onContentReplaced(url: String?) {
        if (url == contentKey) return
        contentKey = url
        sessionMode = Anime4kSettings.defaultMode()
        sessionLoaded = true
        Anime4kStatus.resetSession()
        comparing = false
        colorTone.compareSplit = false
        detail.compareSplit = false
        anime4k.compareSplit = false
    }

    fun onPrepare(player: PictureEffectHost, tunnelingBlocked: Boolean) {
        comparing = false
        Anime4kStatus.onPrepare()
        colorTone.compareSplit = false
        detail.compareSplit = false
        anime4k.compareSplit = false
        if (KV.get(KVKeys.PLAYER_IS_LIVE, false)) {
            current = null
            AppLog.i("echo-picture-effects skip: live")
            return
        }
        current = WeakReference(player)
        tunneling = tunnelingBlocked
        if (tunnelingBlocked) {
            AppLog.i("echo-picture-effects skip: tunneling enabled")
            return
        }
        val profile = applied()
        val tier = Anime4kTier.current()
        val manual = Anime4kSettings.manualActive()
        val auto = Anime4kSettings.autoEnabled()
        val anime4kWanted = manual || auto
        if (!sessionLoaded) {
            sessionMode = Anime4kSettings.defaultMode()
            sessionLoaded = true
        }
        anime4k.mode = sessionMode
        anime4k.manualEnabled = manual
        anime4k.autoMaxWidth = if (auto) Anime4kSettings.autoMaxWidth() else 0
        anime4k.tier = if (anime4kWanted) tier else null
        anime4kOpened = anime4kWanted
        anime4kForcedOpened = Anime4kStatus.sessionForced
        anime4kTierOpened = tier
        anime4kModeOpened = sessionMode
        anime4kDeblurOpened = Anime4kSettings.deblur()
        colorTone.setProfile(profile)
        detail.profile = profile
        val enabled = !profile.isNoOp || anime4kWanted
        openedThisSession = enabled
        if (enabled) player.applyVideoEffects(effectsFor(manual, auto))
    }

    /**
     * 内核释放：只解绑引用，**不重置本次播放的会话状态**（[contentKey] / [sessionLoaded] /
     * `Anime4kStatus.sessionForced` 等）。
     *
     * 画面参数、渲染方式、解码方式变更走的都是「释放内核 + 同地址重播」
     * （`releaseKernel` → `ExoPlayer.release()` → 新建内核 → `setDataSource` 同一 URL），
     * 内容没变却会经过这里。旧实现在这里把 contentKey 清空，重建后的
     * `onContentReplaced(同 URL)` 便当成换片 → `Anime4kStatus.resetSession()`，
     * 面板「本次播放」开关和本次模式当场被打回默认；而 1080p 这类高于
     * `ANIME4K_AUTO_MAX_WIDTH` 的片又必须靠这次重建才能把 manualEnabled 带进链路，
     * 于是点「开启」后链路仍是纯拷贝（真机日志：rebuild 后 `tier=off passes=0`）。
     * 换片仍由 [onContentReplaced] 按 URL 变化判定重置。
     */
    fun onPlayerReleased(player: PictureEffectHost) {
        if (current?.get() === player) current = null
    }

    fun anime4kUnavailable(): Boolean {
        val player = current?.get() ?: return false
        return anime4kActive() && !tunneling && !player.isPictureHdrSource() &&
            Anime4kStatus.unavailable()
    }

    fun setAnime4kSharpen(value: Float) {
        Anime4kSettings.setSharpen(value)
        val player = current?.get() ?: return
        if (player.isPictureHdrSource()) return
        if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
            player.redrawVideoFrame()
        }
    }

    fun unavailableReason(): PictureEffectUnavailableReason {
        val player = current?.get() ?: return PictureEffectUnavailableReason.None
        return PictureEffectUnavailableReason.of(
            tunneling = tunneling,
            hdr = player.isPictureHdrSource(),
            pipeOpen = openedThisSession,
            effectsActive = player.isPictureEffectsActive(),
            hasLook = wanted(),
        )
    }

    fun consumeRestartNeeded(): Boolean {
        val player = current?.get() ?: return false
        if (tunneling || player.isPictureHdrSource()) return false
        val anime4kWanted = Anime4kSettings.manualActive() || Anime4kSettings.autoEnabled()
        if (anime4kWanted != anime4kOpened) return true
        // 面板「本次播放」开在自动未命中的视频上（2K/4K）：链路当时是被上限裁掉的，
        // 必须重建一次才能把 manualEnabled 带进去，否则点了没反应。
        if (Anime4kStatus.sessionForced && !anime4kForcedOpened) return true
        if (anime4kWanted && Anime4kTier.current() != anime4kTierOpened) return true
        if (anime4kWanted && sessionMode != anime4kModeOpened) return true
        if (anime4kWanted && Anime4kSettings.deblur() != anime4kDeblurOpened) return true
        if (Anime4kSettings.manualActive() && !Anime4kStatus.tierActive) return true
        return restartNeeded(wanted(), openedThisSession, preset() == PicturePreset.Original)
    }

    internal fun restartNeeded(wantEffects: Boolean, opened: Boolean, presetOriginal: Boolean): Boolean =
        if (wantEffects) !opened else (opened && presetOriginal)

    private fun push() {
        val player = current?.get() ?: return
        val profile = applied()
        colorTone.setProfile(profile)
        detail.profile = profile
        colorTone.compareSplit = comparing
        detail.compareSplit = comparing
        anime4k.compareSplit = comparing
        if (tunneling) return
        if (player.isPictureHdrSource()) return
        if (openedThisSession) {
            if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
                player.redrawVideoFrame()
            }
            return
        }
        if (!wanted()) return
        player.applyVideoEffects(effectsFor(anime4kOpened))
    }
}
