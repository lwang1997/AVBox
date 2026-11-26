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
        // 链已挂时下一帧按新模式自建；未挂时由起播的 onPrepare 带进去。
        Anime4kStatus.requestChainSync()
    }

    /**
     * 播放器面板的 Anime4K 开关：只作用于本次播放，不落库。
     * 下一次起播回到「低分辨率自动开启 + 上限」的自动判断；「关」只让本次播放直通。
     *
     * 只改会话状态 + 让链在下一帧自建，**不重建内核**：重建内核会重新请求数据源，
     * 直链 m3u8 带时效 token，重开一次就整段播放失败（真机：nby 直链点开启后
     * `ERROR_CODE_PARSING_MANIFEST_MALFORMED`，自动重解析才恢复）。
     */
    fun setAnime4kSessionEnabled(enabled: Boolean) {
        Anime4kStatus.sessionForced = enabled
        syncAnime4kEffect()
        Anime4kStatus.requestChainSync()
        Anime4kStatus.sessionDisabled = !enabled
        colorTone.compareSplit = comparing
        detail.compareSplit = comparing
        anime4k.compareSplit = comparing
        redrawPictureFrame()
    }

    /**
     * 把当前设置同步到已挂的 Anime4K 效果实例上（链在下一帧读这些字段自建）。
     * 关闭时保留原档位，重新打开不需要重建内核。
     */
    private fun syncAnime4kEffect() {
        val manual = Anime4kSettings.manualActive()
        val auto = Anime4kSettings.autoEnabled()
        anime4k.manualEnabled = manual
        anime4k.autoMaxWidth = if (auto) Anime4kSettings.autoMaxWidth() else 0
        anime4k.mode = sessionMode
        if (manual || auto) anime4k.tier = Anime4kTier.current()
        Anime4kStatus.requestChainSync()
    }

    private fun redrawPictureFrame() {
        val player = current?.get() ?: return
        if (player.isPictureHdrSource()) return
        if (RedrawPolicy.shouldRedrawOnParams(player.isPlaying, player.isPictureEffectsActive())) {
            player.redrawVideoFrame()
        }
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
        if (anime4kOpened) {
            // 链已挂：面板参数（开关/档位/模式/去模糊）就地热更新，链在下一帧按
            // 当前设置自建。**不能**为这类纯着色器参数重建内核——重建要重开数据源，
            // 直链 m3u8 的时效 token 一过期就会整段播放失败（真机：nby 直链点开启后
            // ERROR_CODE_PARSING_MANIFEST_MALFORMED，靠自动重解析才恢复）。
            syncAnime4kEffect()
        } else if (Anime4kSettings.manualActive() || Anime4kSettings.autoEnabled()) {
            // 效果链根本没挂（自动关 + 无预置）：只能重建内核把效果带上去。
            return true
        }
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
