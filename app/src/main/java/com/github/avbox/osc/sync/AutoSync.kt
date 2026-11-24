package com.github.avbox.osc.sync

import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动同步触发器：
 *  - 冷启动：立即同步一次（原来延迟 5 秒且受 5 分钟节流，刚打开时可能一次都不跑）
 *  - 进程回到前台：同步一次，带 [MIN_INTERVAL_MS] 节流，避免在多个 Activity 间来回切时反复请求
 *  - 进程切到后台：立即同步一次，把本机最后的观看进度 / 收藏推上去
 * 进程被强杀、被系统回收时收不到任何回调，后台那次只能是尽力而为。
 */
object AutoSync {

    /** 回到前台时的最小同步间隔 */
    private const val MIN_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var lastAttemptAt = 0L

    /** Application.onCreate 调用：冷启动立即同步 */
    fun onAppLaunch() {
        startSync(force = true)
    }

    /** 进程回到前台（ProcessLifecycleOwner.ON_START） */
    fun onForeground() {
        startSync(force = false)
    }

    /** 进程切到后台（ProcessLifecycleOwner.ON_STOP）：把本机改动推上去 */
    fun onBackground() {
        startSync(force = true)
    }

    @Synchronized
    private fun startSync(force: Boolean) {
        if (!SyncConfig.configured || !SyncConfig.autoEnabled) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < MIN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        lastAttemptAt = now
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
