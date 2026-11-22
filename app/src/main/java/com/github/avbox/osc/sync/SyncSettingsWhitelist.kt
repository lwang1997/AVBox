package com.github.avbox.osc.sync

import com.github.avbox.osc.util.KVKeys

/**
 * 手动同步的设置白名单。只纳入跨设备一致、且不含设备/源绑定信息的偏好。
 * 任何 api_url / 订阅 / 源历史 / 本地 SAF tree / 设备标识 / 看门狗状态 一律排除。
 */
object SyncSettingsWhitelist {

    private val KEYS: Set<String> = listOf(
        // 播放核心与画面
        KVKeys.PLAY_TYPE,
        KVKeys.PLAY_RENDER,
        KVKeys.PLAY_SCALE,
        KVKeys.PLAY_TUNNEL,
        KVKeys.PLAY_PREFER_AAC,
        KVKeys.EXO_DECODE,
        KVKeys.KERNEL_PREWARM,
        KVKeys.EXO_VIDEO_DYNAMIC_SCHEDULING,
        KVKeys.AUTO_SWITCH_LINE,
        KVKeys.M3U8_PURIFY,
        KVKeys.DETAIL_PLAY_FULLSCREEN,
        KVKeys.BUFFER_TIMES,
        KVKeys.PLAY_CACHE,
        KVKeys.EXO_CACHE_SIZE_MB,
        KVKeys.PRELOAD_NEXT_EPISODE,
        KVKeys.PRELOAD_DURATION,
        KVKeys.LONG_PRESS_SPEED,
        KVKeys.LIVE_PLAY_SCALE,
        // 画质
        KVKeys.PICTURE_PRESET,
        KVKeys.PICTURE_SATURATION,
        KVKeys.PICTURE_CONTRAST,
        KVKeys.PICTURE_BRIGHTNESS,
        KVKeys.PICTURE_GAMMA,
        KVKeys.PICTURE_HUE,
        KVKeys.PICTURE_TEMPERATURE,
        KVKeys.PICTURE_SHARPNESS,
        KVKeys.PICTURE_SHADOW_LIFT,
        KVKeys.ANIME4K_ENABLED,
        KVKeys.ANIME4K_TIER,
        KVKeys.ANIME4K_MODE,
        KVKeys.ANIME4K_AUTO,
        KVKeys.ANIME4K_AUTO_MAX_WIDTH,
        KVKeys.ANIME4K_SHARPEN,
        KVKeys.ANIME4K_DEBLUR,
        // 字幕
        KVKeys.SUBTITLE_TEXT_SIZE,
        KVKeys.SUBTITLE_TEXT_STYLE,
        KVKeys.SUBTITLE_TIME_DELAY,
        KVKeys.SUBTITLE_EXO_SCALE,
        KVKeys.SUBTITLE_EXO_POSITION,
        // 弹幕
        KVKeys.DANMU_OPEN,
        KVKeys.DANMU_MAX_LINE,
        KVKeys.DANMU_SPEED,
        KVKeys.DANMU_ALPHA,
        KVKeys.DANMU_SIZE_SCALE,
        KVKeys.DANMU_RANDOM_COLOR,
        KVKeys.DANMU_API_USE_DEFAULT,
        // 主题
        KVKeys.THEME_SOURCE,
        KVKeys.THEME_MODE,
        KVKeys.THEME_SEED,
        KVKeys.THEME_PALETTE_STYLE,
        KVKeys.THEME_PURE_BLACK,
        KVKeys.LIQUID_GLASS_NAVBAR,
        KVKeys.LIQUID_GLASS_CONTROLS,
        KVKeys.LIQUID_GLASS_BLUR,
        KVKeys.LIQUID_GLASS_DISTORTION,
        KVKeys.LIQUID_GLASS_TRANSLUCENCY,
        KVKeys.LIQUID_GLASS_DISPERSION,
        // 偏好与交互
        KVKeys.HIDE_STATUS_BAR,
        KVKeys.GESTURE_CONTROL_DISABLED,
        KVKeys.NAV_ANIMATION_DISABLED,
        KVKeys.NAV_LIVE_HIDDEN,
        KVKeys.MUSIC_AUTO_OPEN_PAGE,
        KVKeys.DEFAULT_LOAD_LIVE,
        KVKeys.COLLECT_COLUMNS,
        KVKeys.SEARCH_THREADS,
        KVKeys.INCOGNITO,
        KVKeys.DOH_URL,
        // 直播显示偏好
        KVKeys.LIVE_SHOW_TIME,
        KVKeys.LIVE_SHOW_NET_SPEED,
        KVKeys.LIVE_CHANNEL_REVERSE,
        KVKeys.LIVE_CROSS_GROUP,
        KVKeys.LIVE_CONNECT_TIMEOUT,
        // ISO 解码
        KVKeys.ISO_LPCM,
        KVKeys.ISO_TRUEHD_CORE,
        KVKeys.ISO_PGS,
        // TMDB 展示偏好（不含 API Key / 自定义 base）
        KVKeys.TMDB_ENABLE,
        KVKeys.TMDB_POSTER_STYLE,
        KVKeys.TMDB_LOGO_ENABLED,
        KVKeys.TMDB_IMAGE_QUALITY,
    ).toHashSet()

    /** 补充几个未进 KVKeys 的裸键 */
    private val EXTRA_KEYS = listOf(
        "home_layout",
        "app_language",
        "music_play_mode",
        "search_exact_match",
        "search_result_layout",
        "search_sources_empty",
    )

    fun snapshot(): Map<String, String> {
        val out = HashMap<String, String>()
        (KEYS + EXTRA_KEYS).forEach { key ->
            try {
                val raw = com.github.avbox.osc.util.KV.rawGet(key)
                if (raw != null) out[key] = raw
            } catch (ignore: Throwable) {
                // 单个键读取失败不影响整体
            }
        }
        return out
    }

    /** 写入远端设置：只接受白名单键，覆盖式 */
    fun apply(values: Map<String, String>) {
        val allowed = KEYS + EXTRA_KEYS
        values.forEach { (key, raw) ->
            if (key in allowed) {
                try {
                    com.github.avbox.osc.util.KV.rawPut(key, raw)
                } catch (ignore: Throwable) {
                }
            }
        }
    }
}
