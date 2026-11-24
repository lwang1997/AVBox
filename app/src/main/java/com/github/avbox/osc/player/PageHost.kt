package com.github.avbox.osc.player

import android.content.Context

interface PageHost {

    fun context(): Context

    fun isPageAlive(): Boolean

    fun runOnUi(action: Runnable)

    fun toast(text: CharSequence)

    fun launchLocalSubtitlePicker()

    fun requestNotificationPermission()

    fun showEpisodeSheet()

    fun onPlaybackLinesExhausted(): Boolean

    /** 小窗播放器点「全屏」时由页面接管（详情页进全屏）。 */
    fun onPlayerFullscreenRequest() {}

    /** 播放器请求进入系统画中画，由持有窗口的 Activity 执行。 */
    fun onPictureInPictureRequest() {}
}
