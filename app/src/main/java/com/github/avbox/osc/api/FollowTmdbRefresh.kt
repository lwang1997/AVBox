package com.github.avbox.osc.api

import com.github.avbox.osc.bean.FollowTmdbInfo
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.TmdbImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 追剧条目的 TMDB 更新信息自动刷新。
 *
 * 触发点：应用启动延迟一次 + MainActivity 回前台；同时进程内常驻一条低频循环，
 * 保证长时间停留在前台也会更新。全部走 KV 节流，避免频繁请求 TMDB。
 *
 * 无 TMDB 配置、无追剧条目、正在刷新时直接跳过；单条失败不影响其余条目。
 */
object FollowTmdbRefresh {

    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val LAUNCH_DELAY_MS = 20_000L
    private const val LOOP_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val PER_ITEM_DELAY_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var loopStarted = false

    /** 应用启动后调用，延迟触发一次并启动常驻低频循环。 */
    fun onAppLaunch() {
        startLoop()
        scope.launch {
            delay(LAUNCH_DELAY_MS)
            maybeRefresh(force = false)
        }
    }

    /** MainActivity 回到前台时调用。 */
    fun onResume() {
        startLoop()
        maybeRefresh(force = false)
    }

    /** 手动刷新入口；[onComplete] 在本次刷新结束（或跳过）时回调一次。 */
    fun refreshNow(onComplete: (() -> Unit)? = null) {
        maybeRefresh(force = true, onComplete = onComplete)
    }

    @Synchronized
    private fun startLoop() {
        if (loopStarted) return
        loopStarted = true
        scope.launch {
            while (true) {
                delay(LOOP_INTERVAL_MS)
                maybeRefresh(force = false)
            }
        }
    }

    private fun maybeRefresh(force: Boolean, onComplete: (() -> Unit)? = null) {
        if (!TmdbImages.isActive()) {
            onComplete?.invoke()
            return
        }
        val now = System.currentTimeMillis()
        val last = KV.get(KVKeys.FOLLOW_TMDB_LAST_REFRESH_AT, 0L)
        if (!force && now - last < MIN_INTERVAL_MS) {
            onComplete?.invoke()
            return
        }
        if (!running.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }
        KV.put(KVKeys.FOLLOW_TMDB_LAST_REFRESH_AT, now)
        scope.launch {
            try {
                val follows = AppGraph.followRepository.getAllRaw()
                for (follow in follows) {
                    if (follow.name.isNullOrBlank()) continue
                    val old = FollowTmdbInfo.decode(follow.tmdbInfo)
                    val detail = TmdbPoster.refreshDetail(
                        follow.name,
                        old?.year ?: 0,
                        old?.altName,
                    ) ?: continue
                    val fresh = FollowTmdbInfo.fromDetail(
                        detail,
                        year = old?.year ?: 0,
                        altName = old?.altName.orEmpty(),
                    ) ?: continue
                    // 保留用户手设的追剧计划，只覆盖 TMDB 快照
                    AppGraph.followRepository.updateTmdbInfo(follow.id, FollowTmdbInfo.encode(fresh))
                    delay(PER_ITEM_DELAY_MS)
                }
            } catch (e: Throwable) {
                AppLog.e("FollowTmdbRefresh failed: " + e.message)
            } finally {
                running.set(false)
                // 下拉刷新需要在此收起指示器；回调可能抛出异常，不能影响状态复位。
                try {
                    onComplete?.invoke()
                } catch (e: Throwable) {
                    AppLog.e("FollowTmdbRefresh callback failed: " + e.message)
                }
            }
        }
    }

}
