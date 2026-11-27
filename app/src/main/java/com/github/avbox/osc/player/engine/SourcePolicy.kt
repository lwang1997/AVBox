package com.github.avbox.osc.player.engine

object SourcePolicy {

    enum class CacheMode {
        NONE,

        PLAY_CACHE,

        PRELOAD_TARGET,
    }

    @JvmStatic
    fun applyRtmpLiveFlag(path: String, isLive: Boolean): String {
        if (path.startsWith(RTMP_SCHEME) && isLive && !path.contains(LIVE_FLAG)) {
            return path + " " + LIVE_FLAG
        }
        return path
    }

    @JvmStatic
    fun isRtmp(path: String?): Boolean = path != null && path.startsWith(RTMP_SCHEME)

    @JvmStatic
    fun isLocalProxyUrl(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1") ||
            url.startsWith("http://localhost") || url.startsWith("https://localhost")
    }

    @JvmStatic
    fun resolveCacheMode(
        isLocalProxyUrl: Boolean,
        isRtmp: Boolean,
        isLive: Boolean,
        preloadTarget: Boolean,
        playCacheWanted: Boolean,
    ): CacheMode = when {
        // 直播分片是滚动窗口、播放列表还会反复刷新，和磁盘缓存天然冲突，一律不缓存。
        isLocalProxyUrl || isRtmp || isLive -> CacheMode.NONE
        preloadTarget -> CacheMode.PRELOAD_TARGET
        playCacheWanted -> CacheMode.PLAY_CACHE
        else -> CacheMode.NONE
    }

    private const val RTMP_SCHEME = "rtmp://"
    private const val LIVE_FLAG = "live=1"
}
