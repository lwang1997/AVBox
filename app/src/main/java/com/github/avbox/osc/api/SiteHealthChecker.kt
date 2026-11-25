package com.github.avbox.osc.api

import com.github.avbox.osc.bean.SiteHealth
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.data.PlaybackPorts
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import com.github.catvod.crawler.SpiderReaper
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 站点健康状态。状态只来自三类真实采样：
 *  1. 站点被实际使用时（首页 / 列表 / 搜索 / 详情 / API 源请求）记录成功与耗时；
 *  2. 播放结果（起播耗时、取流失败、播放超时）；
 *  3. 闲时对最近没被用过的站点做一次低优先级 `homeContent(true)` 补查。
 *
 * 移除静态检查：本地文件在不在，不能代表站点可用。
 * 结果用 Gson JSON 存 KV，UI 通过 [results] 只读观察，不提供任何手动检测入口。
 */
object SiteHealthChecker {

    /** 成功且 <=8s 为良好，>8s 为偏慢。慢不再等于异常，避免慢站点被误标红。 */
    const val GOOD_MAX_MS = 8_000L
    /** 连续失败达到该次数才判异常；单次失败（搜索抖动/偶发空数据）只降偏慢。 */
    private const val BAD_AFTER_FAILS = 2

    private const val PROBE_TIMEOUT_MS = 20_000L
    private const val IDLE_START_DELAY_MS = 10_000L
    private const val IDLE_ROUND_INTERVAL_MS = 3L * 60 * 1000
    private const val IDLE_RECHECK_MS = 60_000L
    private const val IDLE_FRESH_MS = 60L * 60 * 1000
    private const val IDLE_QUIET_MS = 5_000L
    private const val IDLE_GAP_MS = 1_000L
    private const val IDLE_BATCH_SIZE = 10

    // 旧缓存里的 OK/DEEP_OK/STATIC_OK 归入良好，WARN/NEED_DEEP/CHECKING 归入偏慢。
    private val gson = GsonBuilder()
        .registerTypeAdapter(SiteHealth.State::class.java, JsonDeserializer<SiteHealth.State> { json, _, _ ->
            if (json.isJsonNull) SiteHealth.State.UNKNOWN else when (json.asString) {
                "OK", "DEEP_OK", "STATIC_OK" -> SiteHealth.State.GOOD
                "WARN", "NEED_DEEP", "CHECKING" -> SiteHealth.State.SLOW
                "BAD" -> SiteHealth.State.BAD
                else -> SiteHealth.State.UNKNOWN
            }
        })
        .create()

    private val _results: MutableStateFlow<Map<String, SiteHealth>> by lazy {
        MutableStateFlow(loadRaw())
    }
    val results: StateFlow<Map<String, SiteHealth>> by lazy { _results.asStateFlow() }

    private val generation = AtomicInteger()
    private val idleScheduled = AtomicBoolean(false)
    private val idleRunning = AtomicBoolean(false)
    private val idleExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "site-health-idle").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val probeExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "site-health-probe").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var lastUseAt = 0L

    /** 连续失败计数：成功清零。只在连续失败时判异常，避免偶发抖动误报。 */
    private val failCounts = ConcurrentHashMap<String, Int>()

    fun bumpGeneration() {
        generation.incrementAndGet()
    }

    /** 应用启动后调度一次闲时补查；常驻节流由 KV 时间戳控制。 */
    fun onAppLaunch() {
        if (!idleScheduled.compareAndSet(false, true)) return
        idleExecutor.execute {
            try {
                Thread.sleep(IDLE_START_DELAY_MS)
                // 常驻低频心跳：没到轮次间隔或当前不闲时会快速跳过，不产生请求
                while (true) {
                    runIdleRound()
                    Thread.sleep(IDLE_RECHECK_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                AppLog.e("echo-site-health idle failed: " + t.message)
            }
        }
    }

    /** 切换站点时调用：让闲时补查让位给马上发生的真实使用。 */
    fun noteUse() {
        lastUseAt = System.currentTimeMillis()
    }

    /**
     * 切换到一个站点时，若状态已过期则安排一次低优先级补查。
     * 真实首页加载会先记录结果，执行时再判一次新鲜度即可避免重复请求。
     */
    fun ensureFresh(bean: SourceBean) {
        noteUse()
        val key = bean.key ?: return
        if (key.isEmpty()) return
        idleExecutor.execute {
            try {
                Thread.sleep(1_000L)
                if (isFresh(key)) return@execute
                probe(bean)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                AppLog.e("echo-site-health switch probe failed: " + t.message)
            }
        }
    }

    /** 包住一次 spider 调用，用真实结果更新站点状态；不改变原有异常语义。 */
    fun <T> track(
        key: String?,
        spider: Spider?,
        emptyIsBad: Boolean = true,
        authoritative: Boolean = false,
        block: () -> T,
    ): T {
        if (key.isNullOrEmpty()) return SpiderReaper.track(spider, block)
        noteUse()
        val start = System.nanoTime()
        return try {
            val value = SpiderReaper.track(spider, block)
            record(key, !emptyIsBad || !isBlank(value), elapsed(start), SiteHealth.VIA_USE, authoritative)
            value
        } catch (e: CancellationException) {
            // 用户取消（例如取消搜索）不代表站点异常
            throw e
        } catch (t: Throwable) {
            record(key, ok = false, elapsed(start), SiteHealth.VIA_USE)
            throw t
        }
    }

    /**
     * 包住一次挂起调用（HTTP 类站点），语义同 [track]。
     * [validate] 用于判定业务结果是否有效：默认非空即可，分类加载等场景可传入更严格的条件
     * （例如必须解析出有效分类），避免「接口通了但分类是空的」被误判为良好。
     */
    suspend fun <T> observe(
        key: String?,
        emptyIsBad: Boolean = true,
        validate: (T) -> Boolean = { !isBlank(it) },
        authoritative: Boolean = false,
        block: suspend () -> T,
    ): T {
        if (key.isNullOrEmpty()) return block()
        noteUse()
        val start = System.nanoTime()
        return try {
            val value = block()
            val ok = if (emptyIsBad) validate(value) else true
            record(key, ok, elapsed(start), SiteHealth.VIA_USE, authoritative)
            value
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            record(key, ok = false, elapsed(start), SiteHealth.VIA_USE)
            throw t
        }
    }

    /**
     * 播放侧采样。起播成功按耗时分档，播放失败/超时降为偏慢：
     * 单部影片的问题不足以把整个站点标为异常，所以这里最低只到 SLOW。
     * 另外不允许一次播放成功把已被接口判为异常的站点洗回良好。
     */
    fun recordPlayback(key: String?, ok: Boolean, latencyMs: Long) {
        if (key.isNullOrEmpty()) return
        val state = when {
            !ok -> SiteHealth.State.SLOW
            latencyMs <= GOOD_MAX_MS -> SiteHealth.State.GOOD
            else -> SiteHealth.State.SLOW
        }
        // 分类是硬指标：接口已判异常/偏慢（含空分类）的站点，不因一次播放成功被洗回良好。
        val current = _results.value[key]?.state
        if (state == SiteHealth.State.GOOD &&
            (current == SiteHealth.State.BAD || current == SiteHealth.State.SLOW)) return
        recordState(key, state, latencyMs.coerceAtLeast(0L), SiteHealth.VIA_PLAY)
    }

    fun clearResults() {
        KV.delete(KVKeys.SITE_HEALTH_RESULTS)
        KV.delete(KVKeys.SITE_HEALTH_CHECKED_AT)
        KV.delete(KVKeys.SITE_HEALTH_IDLE_LAST_AT)
        failCounts.clear()
        _results.value = emptyMap()
    }

    private fun runIdleRound() {
        if (!idleRunning.compareAndSet(false, true)) return
        val gen = generation.get()
        try {
            val lastRound = KV.get(KVKeys.SITE_HEALTH_IDLE_LAST_AT, 0L)
            if (System.currentTimeMillis() - lastRound < IDLE_ROUND_INTERVAL_MS) return
            if (!isIdle()) return
            val now = System.currentTimeMillis()
            val beans = ApiConfig.get().getSourceBeanList()
                .filter { !it.key.isNullOrEmpty() && !isControl(it) }
                .filter { isStale(it.key.orEmpty(), now) }
                .sortedBy { _results.value[it.key]?.checkedAt ?: 0L }
                .take(IDLE_BATCH_SIZE)
            if (beans.isEmpty()) {
                KV.put(KVKeys.SITE_HEALTH_IDLE_LAST_AT, now)
                return
            }
            for (bean in beans) {
                if (gen != generation.get()) return
                // 每站之间只让位给真实使用；播放页附着只在轮次开始时判断一次，避免中途停摆
                if (System.currentTimeMillis() - lastUseAt >= IDLE_QUIET_MS) probe(bean)
                Thread.sleep(IDLE_GAP_MS)
            }
            KV.put(KVKeys.SITE_HEALTH_IDLE_LAST_AT, System.currentTimeMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            AppLog.e("echo-site-health idle round failed: " + t.message)
        } finally {
            idleRunning.set(false)
        }
    }

    /** 闲时判定：正在播放或有页面附着时不打扰；最近有真实使用则让路。 */
    private fun isIdle(): Boolean {
        if (PlaybackPorts.ports?.hasAttachedPage() == true) return false
        return System.currentTimeMillis() - lastUseAt >= IDLE_QUIET_MS
    }

    private fun isStale(key: String, now: Long): Boolean {
        val health = _results.value[key] ?: return true
        // 异常/偏慢的站点更快复检，让偶发误判能早点被纠正
        val freshMs = when (health.state) {
            SiteHealth.State.BAD, SiteHealth.State.SLOW -> 10L * 60 * 1000
            else -> IDLE_FRESH_MS
        }
        return health.checkedAt <= 0L || now - health.checkedAt >= freshMs
    }

    private fun isFresh(key: String): Boolean = !isStale(key, System.currentTimeMillis())

    /** 一次真实补查；Future 超时隔离，避免坏站点长期占住闲时线程。 */
    private fun probe(bean: SourceBean) {
        val key = bean.key ?: return
        val gen = generation.get()
        val task: Future<ProbeResult?> = probeExecutor.submit(Callable {
            val start = System.nanoTime()
            val spider = ApiConfig.get().getCSP(bean)
            if (spider is SpiderNull) {
                // HTTP 类站点（type 0/1/4）没有 spider，不能用 homeContent 判定，
                // 否则会被误判为失败；这类站点交给真实使用时的采样即可。
                null
            } else {
                // 与真实首页加载保持一致：分类是硬指标，必须解析出有效分类才算可用
                val body = SpiderReaper.track(spider) { spider.homeContent(true) }
                ProbeResult(hasClasses(body), elapsed(start))
            }
        })
        try {
            val result = task.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (result != null && gen == generation.get()) {
                record(key, result.ok, result.latencyMs, SiteHealth.VIA_IDLE, authoritative = true)
            }
        } catch (e: TimeoutException) {
            task.cancel(true)
            if (gen == generation.get()) record(key, false, PROBE_TIMEOUT_MS, SiteHealth.VIA_IDLE)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (gen == generation.get()) record(key, false, PROBE_TIMEOUT_MS, SiteHealth.VIA_IDLE)
        }
    }

    private data class ProbeResult(val ok: Boolean, val latencyMs: Long)

    /**
     * [authoritative] 表示这次采样的是站点硬指标（首页分类 / 闲时补查）。
     * 只有权威来源能把已被判异常的站点恢复为良好；搜索、详情等旁路成功不得洗白。
     */
    private fun record(
        key: String,
        ok: Boolean,
        latencyMs: Long,
        via: String,
        authoritative: Boolean = false,
    ) {
        if (!ok) {
            val fails = (failCounts[key] ?: 0) + 1
            failCounts[key] = fails
            // 单次失败只降偏慢；异常要连续失败才判，避免搜索偶发失败/空数据误报
            recordState(
                key,
                if (fails >= BAD_AFTER_FAILS) SiteHealth.State.BAD else SiteHealth.State.SLOW,
                latencyMs,
                via,
            )
        } else {
            failCounts[key] = 0
            val current = _results.value[key]?.state
            val state = when {
                latencyMs > GOOD_MAX_MS -> SiteHealth.State.SLOW
                !authoritative && (current == SiteHealth.State.BAD || current == SiteHealth.State.SLOW) -> SiteHealth.State.SLOW
                else -> SiteHealth.State.GOOD
            }
            recordState(key, state, latencyMs, via)
        }
    }

    private fun recordState(key: String, state: SiteHealth.State, latencyMs: Long, via: String) {
        val now = System.currentTimeMillis()
        val next = HashMap(_results.value)
        next[key] = SiteHealth(key, state, latencyMs.coerceAtLeast(0L), now, via)
        _results.value = next
        KV.put(KVKeys.SITE_HEALTH_RESULTS, gson.toJson(next))
        KV.put(KVKeys.SITE_HEALTH_CHECKED_AT, now)
    }

    /**
     * 分类是站点的硬指标：接口返回非空但解析不出有效分类（class 里含 type_id/type_name）
     * 时同样视为失败。与 SourceResultParser.sortJson 对分类的判定保持一致。
     */
    private fun hasClasses(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            val arr = obj.getAsJsonArray("class") ?: return false
            arr.any { el ->
                el.isJsonObject && el.asJsonObject.let { it.has("type_id") && it.has("type_name") }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun isBlank(value: Any?): Boolean {
        if (value == null) return true
        return value is CharSequence && value.isBlank()
    }

    private fun elapsed(startNano: Long): Long =
        ((System.nanoTime() - startNano) / 1_000_000L).coerceAtLeast(0L)

    private fun isControl(bean: SourceBean): Boolean =
        bean.api.orEmpty().trim().startsWith("custom_")

    private fun loadRaw(): Map<String, SiteHealth> {
        val raw = KV.get(KVKeys.SITE_HEALTH_RESULTS, "")
        if (raw.isBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, SiteHealth>>() {}.type
            val decoded: Map<String, SiteHealth> = gson.fromJson(raw, type) ?: emptyMap()
            // 旧缓存是静态快扫产物（via 为空），不是真实测过的结果，一律丢弃
            decoded.filterValues {
                it.via == SiteHealth.VIA_USE || it.via == SiteHealth.VIA_PLAY || it.via == SiteHealth.VIA_IDLE
            }
        } catch (t: Throwable) {
            AppLog.e("echo-site-health cache decode failed: " + t.message)
            emptyMap()
        }
    }
}
