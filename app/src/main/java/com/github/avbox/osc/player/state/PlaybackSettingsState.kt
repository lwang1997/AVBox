package com.github.avbox.osc.player.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.player.effect.anime4k.Anime4kMode
import com.github.avbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.avbox.osc.player.effect.anime4k.Anime4kTier
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV

data class PlaybackSettingsConfig(
    val playType: Int,
    val playRender: Int,
    val playScale: Int,
    val exoDecode: String,
    val anime4kTier: String,
    val anime4kMode: String,
    val anime4kAuto: Boolean,
    val anime4kAutoMaxWidth: Int,
    val kernelPrewarm: Boolean,
    val playTunnel: Boolean,
    val preferAac: Boolean,
    val autoSwitchLine: Boolean,
    val m3u8Purify: Boolean,
    val detailPlayFullscreen: Boolean,
    val pictureInPictureAuto: Boolean,
    val bufferTimes: Int,
    val playCache: Boolean,
    val exoCacheSizeMb: Int,
    val preloadNextEpisode: Boolean,
    val preloadDuration: Int,
    val anime4kDeblur: Boolean,
    val subtitleTextStyle: Int,
)

object PlaybackSettingsState {

    private var current by mutableStateOf(load())

    val config: PlaybackSettingsConfig get() = current

    private fun load(): PlaybackSettingsConfig = PlaybackSettingsConfig(
        playType = KV.get(KVKeys.PLAY_TYPE, 2),
        playRender = KV.get(KVKeys.PLAY_RENDER, 1),
        playScale = KV.get(KVKeys.PLAY_SCALE, 0),
        exoDecode = KV.get(KVKeys.EXO_DECODE, "硬解码"), // i18n: keep
        anime4kTier = Anime4kTier.byName(KV.get(KVKeys.ANIME4K_TIER, Anime4kTier.default.name)).name,
        anime4kMode = Anime4kMode.byName(KV.get(KVKeys.ANIME4K_MODE, Anime4kMode.default.name)).name,
        anime4kAuto = KV.get(KVKeys.ANIME4K_AUTO, false),
        anime4kAutoMaxWidth = KV.get(KVKeys.ANIME4K_AUTO_MAX_WIDTH, Anime4kSettings.DEFAULT_AUTO_MAX_WIDTH),
        kernelPrewarm = KV.get(KVKeys.KERNEL_PREWARM, KVKeys.KERNEL_PREWARM_DEFAULT),
        playTunnel = KV.get(KVKeys.PLAY_TUNNEL, false),
        preferAac = KV.get(KVKeys.PLAY_PREFER_AAC, false),
        autoSwitchLine = KV.get(KVKeys.AUTO_SWITCH_LINE, KVKeys.AUTO_SWITCH_LINE_DEFAULT),
        m3u8Purify = KV.get(KVKeys.M3U8_PURIFY, false),
        detailPlayFullscreen = KV.get(KVKeys.DETAIL_PLAY_FULLSCREEN, true),
        pictureInPictureAuto = KV.get(KVKeys.PICTURE_IN_PICTURE_AUTO, KVKeys.PICTURE_IN_PICTURE_AUTO_DEFAULT),
        bufferTimes = KV.get(KVKeys.BUFFER_TIMES, KVKeys.BUFFER_TIMES_DEFAULT),
        playCache = KV.get(KVKeys.PLAY_CACHE, false),
        exoCacheSizeMb = KV.get(KVKeys.EXO_CACHE_SIZE_MB, KVKeys.EXO_CACHE_SIZE_MB_DEFAULT),
        preloadNextEpisode = KV.get(KVKeys.PRELOAD_NEXT_EPISODE, false),
        preloadDuration = KV.get(KVKeys.PRELOAD_DURATION, KVKeys.PRELOAD_DURATION_DEFAULT),
        anime4kDeblur = KV.get(KVKeys.ANIME4K_DEBLUR, false),
        subtitleTextStyle = KV.get(KVKeys.SUBTITLE_TEXT_STYLE, 0),
    )

    var playType: Int
        get() = current.playType
        set(value) {
            KV.put(KVKeys.PLAY_TYPE, value)
            current = current.copy(playType = value)
        }

    var playRender: Int
        get() = current.playRender
        set(value) {
            KV.put(KVKeys.PLAY_RENDER, value)
            current = current.copy(playRender = value)
        }

    var playScale: Int
        get() = current.playScale
        set(value) {
            KV.put(KVKeys.PLAY_SCALE, value)
            current = current.copy(playScale = value)
        }

    var exoDecode: String
        get() = current.exoDecode
        set(value) {
            KV.put(KVKeys.EXO_DECODE, value)
            current = current.copy(exoDecode = value)
        }

    var anime4kTier: String
        get() = current.anime4kTier
        set(value) {
            KV.put(KVKeys.ANIME4K_TIER, value)
            current = current.copy(anime4kTier = value)
        }

    var anime4kMode: String
        get() = current.anime4kMode
        set(value) {
            KV.put(KVKeys.ANIME4K_MODE, value)
            current = current.copy(anime4kMode = value)
        }

    var anime4kAuto: Boolean
        get() = current.anime4kAuto
        set(value) {
            KV.put(KVKeys.ANIME4K_AUTO, value)
            current = current.copy(anime4kAuto = value)
        }

    var anime4kAutoMaxWidth: Int
        get() = current.anime4kAutoMaxWidth
        set(value) {
            KV.put(KVKeys.ANIME4K_AUTO_MAX_WIDTH, value)
            current = current.copy(anime4kAutoMaxWidth = value)
        }

    var kernelPrewarm: Boolean
        get() = current.kernelPrewarm
        set(value) {
            KV.put(KVKeys.KERNEL_PREWARM, value)
            current = current.copy(kernelPrewarm = value)
        }

    var playTunnel: Boolean
        get() = current.playTunnel
        set(value) {
            KV.put(KVKeys.PLAY_TUNNEL, value)
            current = current.copy(playTunnel = value)
        }

    var preferAac: Boolean
        get() = current.preferAac
        set(value) {
            KV.put(KVKeys.PLAY_PREFER_AAC, value)
            current = current.copy(preferAac = value)
        }

    var autoSwitchLine: Boolean
        get() = current.autoSwitchLine
        set(value) {
            KV.put(KVKeys.AUTO_SWITCH_LINE, value)
            current = current.copy(autoSwitchLine = value)
        }

    var m3u8Purify: Boolean
        get() = current.m3u8Purify
        set(value) {
            KV.put(KVKeys.M3U8_PURIFY, value)
            current = current.copy(m3u8Purify = value)
        }

    var detailPlayFullscreen: Boolean
        get() = current.detailPlayFullscreen
        set(value) {
            KV.put(KVKeys.DETAIL_PLAY_FULLSCREEN, value)
            current = current.copy(detailPlayFullscreen = value)
        }

    var pictureInPictureAuto: Boolean
        get() = current.pictureInPictureAuto
        set(value) {
            KV.put(KVKeys.PICTURE_IN_PICTURE_AUTO, value)
            current = current.copy(pictureInPictureAuto = value)
        }

    var bufferTimes: Int
        get() = current.bufferTimes
        set(value) {
            KV.put(KVKeys.BUFFER_TIMES, value)
            current = current.copy(bufferTimes = value)
        }

    var playCache: Boolean
        get() = current.playCache
        set(value) {
            KV.put(KVKeys.PLAY_CACHE, value)
            current = current.copy(playCache = value)
        }

    var exoCacheSizeMb: Int
        get() = current.exoCacheSizeMb
        set(value) {
            KV.put(KVKeys.EXO_CACHE_SIZE_MB, value)
            current = current.copy(exoCacheSizeMb = value)
        }

    var preloadNextEpisode: Boolean
        get() = current.preloadNextEpisode
        set(value) {
            KV.put(KVKeys.PRELOAD_NEXT_EPISODE, value)
            current = current.copy(preloadNextEpisode = value)
        }

    var preloadDuration: Int
        get() = current.preloadDuration
        set(value) {
            KV.put(KVKeys.PRELOAD_DURATION, value)
            current = current.copy(preloadDuration = value)
        }

    var anime4kDeblur: Boolean
        get() = current.anime4kDeblur
        set(value) {
            KV.put(KVKeys.ANIME4K_DEBLUR, value)
            current = current.copy(anime4kDeblur = value)
        }

    var subtitleTextStyle: Int
        get() = current.subtitleTextStyle
        set(value) {
            KV.put(KVKeys.SUBTITLE_TEXT_STYLE, value)
            current = current.copy(subtitleTextStyle = value)
        }

    fun reloadSubtitleTextStyle() {
        current = current.copy(subtitleTextStyle = KV.get(KVKeys.SUBTITLE_TEXT_STYLE, 0))
    }
}
