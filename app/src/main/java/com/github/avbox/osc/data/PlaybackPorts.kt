package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo

interface PlaybackDataPorts {

    fun currentVod(): VodInfo?

    fun isLiveMode(): Boolean

    fun hasAttachedPage(): Boolean

    fun discardStartedContentOf(owners: List<String>)

    fun onProgressKeysCleared(keys: List<String>)

    /**
     * 当前播放信息 JSON（duration/position/title/artist/url/state），供 spider.jar 的 /media 端点读取。
     * 没有在播放时返回 null。
     */
    fun mediaJson(): String? = null
}

object PlaybackPorts {

    @Volatile
    var ports: PlaybackDataPorts? = null
}
