package com.github.avbox.osc.util.update

import com.github.avbox.osc.BuildConfig
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.net.Http
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 读取 GitHub Releases 并做镜像测速。
 * 只依赖 net/util，不引用 UI，供 [UpdateManager] 调用。
 */
object UpdateChecker {

    const val REPO = "lwang1997/AVBox"

    private const val API_PATH = "repos/$REPO/releases/latest"

    /** Release API 候选：直连 + 可用加速前缀，并发请求取最快成功的结果。 */
    private val apiUrls = listOf(
        "https://api.github.com/$API_PATH",
        "https://ghfast.top/https://api.github.com/$API_PATH",
        "https://gh-proxy.com/https://api.github.com/$API_PATH",
        "https://ghproxy.net/https://api.github.com/$API_PATH",
    )

    /** APK 下载候选镜像；空前缀代表直连。 */
    private val mirrorPrefixes = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://hub.gitmirror.com/",
    )

    private const val PROBE_BYTES = 128 * 1024
    private const val PROBE_TIMEOUT_SECONDS = 6L

    private val versionCodeRegex = Regex("<!--\\s*avbox-version-code\\s*:\\s*(\\d+)\\s*-->")

    @Volatile
    private var probeClient: OkHttpClient? = null

    fun releasesPage(): String = "https://github.com/$REPO/releases"

    /** 并发拉取 Release，第一个成功解析的结果即采用。 */
    suspend fun fetchLatestRelease(): UpdateInfo? = coroutineScope {
        val results = Channel<UpdateInfo?>(apiUrls.size)
        val jobs = apiUrls.map { url ->
            launch(Dispatchers.IO) {
                val value = runCatching { fetchOne(url) }
                    .onFailure {
                        if (it !is CancellationException) {
                            AppLog.e("UpdateChecker", "fetch failed: $url", it)
                        }
                    }
                    .getOrNull()
                results.trySend(value)
            }
        }
        var remaining = apiUrls.size
        while (remaining > 0) {
            val value = results.receive()
            remaining--
            if (value != null) {
                jobs.forEach { it.cancel() }
                results.close()
                return@coroutineScope value
            }
        }
        null
    }

    /** 当前安装包是否低于 Release 版本。优先用 versionCode，旧 Release 回退语义化版本。 */
    fun isNewer(info: UpdateInfo): Boolean {
        info.versionCode?.let { return it > BuildConfig.VERSION_CODE }
        return compareSemver(info.versionName, BuildConfig.VERSION_NAME) > 0
    }

    /**
     * 对直链和所有镜像做小范围 Range 探测，按耗时升序返回；
     * 探测失败的地址排在最后作为兜底，保证至少还有重试机会。
     */
    suspend fun rankedApkUrls(directUrl: String): List<String> = coroutineScope {
        val candidates = mirrorPrefixes.map { it + directUrl }.distinct()
        val probed = candidates.map { url ->
            async(Dispatchers.IO) { url to probe(url) }
        }.awaitAll()
        val reachable = probed.filter { it.second != null }.sortedBy { it.second }
        reachable.map { it.first } + probed.filter { it.second == null }.map { it.first }
    }

    private suspend fun fetchOne(url: String): UpdateInfo? {
        val body = Http.get(url) {
            headers("Accept", "application/vnd.github+json")
            headers("User-Agent", "AVBox-Update/1.0")
        }
        return parseRelease(body)
    }

    private fun parseRelease(json: String): UpdateInfo? {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj.get("draft")?.asBoolean == true) return null
        if (obj.get("prerelease")?.asBoolean == true) return null

        val tag = obj.get("tag_name")?.asString?.trim().orEmpty()
        if (tag.isEmpty()) return null

        val body = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val pageUrl = obj.get("html_url")?.asString ?: releasesPage()
        val assets = obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        var apkAsset: JsonObject? = null
        for (element in assets) {
            if (!element.isJsonObject) continue
            val asset = element.asJsonObject
            val name = asset.get("name")?.asString ?: continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            // 优先 arm64 包；没有就退回任意 apk。
            if (apkAsset == null || name.contains("arm64", ignoreCase = true)) apkAsset = asset
        }
        val asset = apkAsset ?: return null
        val downloadUrl = asset.get("browser_download_url")?.asString ?: return null
        val apkName = asset.get("name")?.asString ?: return null
        val sizeBytes = asset.get("size")?.asLong ?: 0L
        val versionCode = versionCodeRegex.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()

        return UpdateInfo(
            versionName = tag.removePrefix("v").removePrefix("V"),
            versionCode = versionCode,
            notes = cleanNotes(body),
            apkUrl = downloadUrl,
            apkName = apkName,
            sizeBytes = sizeBytes,
            pageUrl = pageUrl,
        )
    }

    private fun cleanNotes(body: String): String =
        versionCodeRegex.replace(body, "").replace("\r\n", "\n").trim()

    private fun compareSemver(left: String, right: String): Int {
        val a = semverParts(left)
        val b = semverParts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun semverParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** 探测单个地址：读取最多 128KB，返回首包耗时（毫秒）；失败返回 null。 */
    private fun probe(url: String): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .header("Accept-Encoding", "identity")
                .build()
            val start = System.nanoTime()
            client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val input = response.body.byteStream()
                val buffer = ByteArray(16 * 1024)
                var read = 0
                while (read < PROBE_BYTES) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    read += count
                }
                if (read <= 0) return null
            }
            (System.nanoTime() - start) / 1_000_000L
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    private fun client(): OkHttpClient {
        probeClient?.let { return it }
        val base = HttpClients.defaultClient ?: OkHttpClient()
        return base.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .also { probeClient = it }
    }
}
