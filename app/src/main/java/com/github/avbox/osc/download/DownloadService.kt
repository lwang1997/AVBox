package com.github.avbox.osc.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

import androidx.core.app.NotificationCompat

import com.github.avbox.osc.R
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.util.AppLog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        const val ACTION_STOP = "download.stop"
        const val ACTION_PAUSE_ALL = "download.pause_all"
        const val ACTION_RESUME_ALL = "download.resume_all"
        const val ACTION_PAUSE = "download.pause"
        const val ACTION_RESUME = "download.resume"
        const val ACTION_CANCEL = "download.cancel"
        const val EXTRA_ID = "download_id"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 0x0D1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        scope.launch {
            DownloadManager.tasks.collect { tasks -> updateNotification(tasks) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            foregroundStarted = true
            startForeground(NOTIFICATION_ID, buildNotification(DownloadManager.tasks.value))
        }
        when (intent?.action) {
            ACTION_PAUSE_ALL -> DownloadManager.pauseAll()
            ACTION_RESUME_ALL -> DownloadManager.resumeAll()
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let(DownloadManager::pause)
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let(DownloadManager::resume)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(DownloadManager::cancel)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        foregroundStarted = false
        super.onDestroy()
    }

    private fun updateNotification(tasks: List<DownloadTask>) {
        val active = tasks.filter { it.active }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(tasks))
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.active }
        val task = active.firstOrNull() ?: tasks.firstOrNull()
        val running = active.filter { it.status == DownloadStatus.DOWNLOADING }.size
        val title = if (active.size > 1) {
            getString(R.string.download_notification_count, active.size)
        } else {
            task?.fileName ?: getString(R.string.download_title)
        }
        val progress = task?.progressPercent ?: 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(getString(R.string.download_notification_progress, progress))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent())
        if (active.isNotEmpty()) {
            builder.setProgress(100, progress, progress <= 0)
        }
        if (running > 0) {
            builder.addAction(
                R.drawable.ic_download,
                getString(R.string.download_notification_pause),
                actionIntent(ACTION_PAUSE_ALL),
            )
        } else {
            builder.addAction(
                R.drawable.ic_download,
                getString(R.string.download_notification_resume),
                actionIntent(ACTION_RESUME_ALL),
            )
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("avbox://downloads")).apply {
            setPackage(packageName)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_service_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("WAKELOCK_TIMEOUT")
    private fun acquireLocks() {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avbox:download").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        } catch (th: Throwable) {
            AppLog.e("DownloadService", th)
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "avbox:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (th: Throwable) {
            AppLog.e("DownloadService", th)
        }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
        wifiLock = null
    }
}
