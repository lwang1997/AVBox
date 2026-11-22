package com.github.avbox.osc.sync

import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动同步触发器：应用启动 / 回到前台时调用 maybeSync()。
 * 内置节流，避免频繁触发网络请求。
 */
object AutoSync {

    private const val MIN_INTERVAL_MS = 60_000L
    private const val LAUNCH_DELAY_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var lastAttemptAt = 0L

    /** 应用启动后调用，延迟触发一次 */
    fun onAppLaunch() {
        scope.launch {
            kotlinx.coroutines.delay(LAUNCH_DELAY_MS)
            maybeSync(force = false)
        }
    }

    /** MainActivity 回到前台时调用 */
    fun onResume() {
        maybeSync(force = false)
    }

    private fun maybeSync(force: Boolean) {
        if (!SyncConfig.configured || !SyncConfig.autoEnabled) return
        if (!force && System.currentTimeMillis() - lastAttemptAt < MIN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        lastAttemptAt = System.currentTimeMillis()
        scope.launch {
            try {
                val result = SyncManager.syncData()
                if (result is SyncResult.Failure) {
                    AppLog.e("AutoSync failed: " + result.message)
                }
            } finally {
                running.set(false)
            }
        }
    }
}
