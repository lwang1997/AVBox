package com.github.avbox.osc.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "设置已写盘、内存快照还没换"的进程内标记。
 *
 * 下载设置 / 恢复备份会直接写 KV，而主题、语言、接口源等状态对象只在进程启动时读一次；
 * 用户选择"稍后"后，设置页顶部据此给出重启入口，避免改动静默地不生效。
 * 冷启动时清除 —— 新进程本来就会重新读 KV。
 */
object PendingRestart {

    private const val KEY = "pending_restart_at"

    private var pendingState by mutableStateOf(read())

    val pending: Boolean get() = pendingState

    fun mark() {
        KV.put(KEY, System.currentTimeMillis())
        pendingState = true
    }

    fun clear() {
        if (KV.contains(KEY)) KV.delete(KEY)
        pendingState = false
    }

    private fun read(): Boolean = KV.get(KEY, 0L) > 0L
}
