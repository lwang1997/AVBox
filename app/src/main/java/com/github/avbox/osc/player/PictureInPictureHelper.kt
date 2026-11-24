package com.github.avbox.osc.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppLog

/** 系统画中画（Picture-in-Picture）能力封装。所有 PiP API 调用集中在 [enter]，调用方需先做版本判断。 */
object PictureInPictureHelper {

    private const val TAG = "echo-pip"

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** 取视频画面在屏幕上的可见矩形，作为 PiP 进出动画的起点，避免从整个页面缩放。 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun sourceRectHint(view: View?): Rect? {
        if (view == null) return null
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return null
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    /**
     * 进入画中画。失败时返回 false，由调用方决定降级（例如提示用户）。
     * [playing] 决定小窗里展示的是「暂停」还是「播放」动作。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun enter(activity: android.app.Activity, playing: Boolean, sourceRect: Rect?): Boolean {
        val context: Context = activity
        if (!isSupported(context)) return false
        return try {
            val builder = PictureInPictureParams.Builder()
            if (sourceRect != null) builder.setSourceRectHint(sourceRect)
            builder.setActions(
                listOf(
                    pipAction(context, PlaybackService.ACTION_PREVIOUS, R.drawable.player_ic_prev, R.string.player_prev_episode),
                    if (playing) {
                        pipAction(context, PlaybackService.ACTION_PAUSE, R.drawable.player_ic_pause, R.string.common_pause)
                    } else {
                        pipAction(context, PlaybackService.ACTION_PLAY, R.drawable.player_ic_play, R.string.common_play)
                    },
                    pipAction(context, PlaybackService.ACTION_NEXT, R.drawable.player_ic_next, R.string.player_next_episode),
                ),
            )
            activity.enterPictureInPictureMode(builder.build())
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, t)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(context: Context, action: String, iconRes: Int, labelRes: Int): RemoteAction {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getService(context, action.hashCode(), intent, flags)
        val label = context.getString(labelRes)
        return RemoteAction(Icon.createWithResource(context, iconRes), label, label, pendingIntent)
    }
}
