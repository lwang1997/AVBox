package com.github.avbox.osc.sync

import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** 同步结果状态 */
sealed class SyncResult {
    object Idle : SyncResult()
    object Running : SyncResult()
    data class Success(val at: Long, val remoteEmpty: Boolean) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

/**
 * 同步编排：
 *  - syncData() 自动同步"数据类"，双向 LWW 合并后写回本地并上传
 *  - uploadSettings()/downloadSettings() 手动同步设置，覆盖式
 */
object SyncManager {

    private val gson = Gson()
    private val mutex = Mutex()

    /** PBKDF2 派生密钥较重（约 0.5~2s），固定丢到 IO 线程，不堵调用方（多在 Main）。 */
    private suspend fun seal(json: String): String = withContext(Dispatchers.IO) {
        SyncCrypto.seal(json, SyncConfig.encryptPassword)
    }

    private suspend fun open(raw: String): String = withContext(Dispatchers.IO) {
        SyncCrypto.open(raw, SyncConfig.encryptPassword)
    }

    /**
     * 拉取远端 data 快照（P2 条件 GET）：
     *  - 本地有 ETag 且有正文缓存 → 带 If-None-Match；304 直接用缓存参与合并，省一次下载
     *  - 404 / 无 ETag / 无缓存 → 全量 GET，并刷新缓存与 ETag
     * 缓存统一存"服务器上的原文"（加密时为密文），与 PUT 后写入的保持一致。
     * 解不开（密码错误/缺失）直接抛出中止同步，绝不当成"远端为空"去覆盖。
     */
    private suspend fun fetchRemoteData(client: WebDavClient): SyncData? {
        val etag = SyncConfig.dataEtag
        val cache = SyncConfig.remoteDataCache
        val useConditional = etag.isNotBlank() && cache.isNotBlank()
        val raw = when (val r = client.getConditional(SyncConfig.dataFile, if (useConditional) etag else null)) {
            is WebDavClient.ConditionalGet.NotFound -> {
                SyncConfig.clearRemoteState()
                return null
            }
            // 仅 useConditional 时可能出现；缓存即上次见过的服务器原文
            is WebDavClient.ConditionalGet.NotModified -> cache
            is WebDavClient.ConditionalGet.Content -> {
                SyncConfig.remoteDataCache = r.body
                SyncConfig.dataEtag = r.etag ?: ""
                r.body
            }
        }
        return parseEnvelope(open(raw))?.data
    }

    suspend fun testConnection(): Boolean {
        if (!SyncConfig.configured) return false
        return try {
            SyncConfig.client().test()
        } catch (e: Throwable) {
            false
        }
    }

    /** 自动同步数据类 */
    suspend fun syncData(): SyncResult {
        if (!SyncConfig.configured) return SyncResult.Failure("WebDAV not configured")
        return mutex.withLock {
            try {
                val client = SyncConfig.client()
                val now = System.currentTimeMillis()

                val localRaw = SyncSnapshot.exportData()
                val local = SyncConfig.filterEnabled(localRaw)

                // P2 条件 GET：304 时复用本地缓存，不重复下载正文
                val remote = fetchRemoteData(client)

                val merged = if (remote == null) {
                    local
                } else {
                    SyncMerger.merge(local, remote.filterEnabledBy(SyncConfig), now)
                }

                // 远端没有可吸收的更新时跳过写库（merged == local 即本地导出原样）
                if (merged != local) SyncSnapshot.applyData(merged)

                // 上传时保留远端被本机关闭的同步项，避免禁用开关反而清空服务器数据
                val upload = merged.copy(
                    history = if (SyncConfig.syncHistory) merged.history else (remote?.history ?: emptyList()),
                    collect = if (SyncConfig.syncCollect) merged.collect else (remote?.collect ?: emptyList()),
                    follow = if (SyncConfig.syncFollow) merged.follow else (remote?.follow ?: emptyList()),
                    progress = if (SyncConfig.syncProgress) merged.progress else (remote?.progress ?: emptyList()),
                    percent = if (SyncConfig.syncProgress) merged.percent else (remote?.percent ?: emptyMap()),
                    totals = if (SyncConfig.syncProgress) merged.totals else (remote?.totals ?: emptyMap()),
                    watched = if (SyncConfig.syncFollow) merged.watched else (remote?.watched ?: emptyMap()),
                    tracks = if (SyncConfig.syncProgress) merged.tracks else (remote?.tracks ?: emptyMap()),
                )

                // P0：合并结果与服务器现有内容一致 → 跳过上传。
                // 只做字节级比较，保守方向是"多传一次"（顺序差异/时间戳字段不同都会传），绝不漏传。
                if (remote != null && gson.toJson(upload) == gson.toJson(remote)) {
                    SyncConfig.lastSyncAt = System.currentTimeMillis()
                    return@withLock SyncResult.Success(SyncConfig.lastSyncAt, false)
                }

                val envelope = SyncEnvelope(
                    deviceId = SyncConfig.deviceId(),
                    exportedAt = System.currentTimeMillis(),
                    data = upload,
                    settings = null,
                )
                val body = gson.toJson(envelope)
                val sealedBody = seal(body)
                // P2：PUT 后记录服务端签发的新 ETag；不回则清空，下次退回全量 GET
                SyncConfig.dataEtag = client.put(SyncConfig.dataFile, sealedBody) ?: ""
                SyncConfig.remoteDataCache = sealedBody

                SyncConfig.lastSyncAt = System.currentTimeMillis()
                SyncResult.Success(SyncConfig.lastSyncAt, remote == null)
            } catch (e: IOException) {
                AppLog.e("SyncManager syncData io failed: ${e.message}")
                SyncResult.Failure(e.message ?: "network error")
            } catch (e: Throwable) {
                AppLog.e("SyncManager syncData failed: ${e.message}")
                SyncResult.Failure(e.message ?: "unknown error")
            }
        }
    }

    /** 手动上传设置：本地覆盖远端 */
    suspend fun uploadSettings(): SyncResult {
        if (!SyncConfig.configured) return SyncResult.Failure("WebDAV not configured")
        return mutex.withLock {
            try {
                val client = SyncConfig.client()
                // 先把远端数据部分读出，避免覆盖掉数据文件（两者分文件，此步可省，保留以兼容旧结构）
                val envelope = SyncEnvelope(
                    deviceId = SyncConfig.deviceId(),
                    exportedAt = System.currentTimeMillis(),
                    data = null,
                    settings = SyncSettings(SyncSettingsWhitelist.snapshot()),
                )
                client.put(SyncConfig.settingsFile, seal(gson.toJson(envelope)))
                SyncResult.Success(System.currentTimeMillis(), false)
            } catch (e: Throwable) {
                AppLog.e("SyncManager uploadSettings failed: ${e.message}")
                SyncResult.Failure(e.message ?: "unknown error")
            }
        }
    }

    /** 手动下载设置：远端覆盖本地白名单 */
    suspend fun downloadSettings(): SyncResult {
        if (!SyncConfig.configured) return SyncResult.Failure("WebDAV not configured")
        return mutex.withLock {
            try {
                val client = SyncConfig.client()
                val json = client.get(SyncConfig.settingsFile)
                    ?: return@withLock SyncResult.Failure("No remote settings")
                // 密码错误/缺失在此抛出，中止下载，不会把解不开的快照当无效而静默失败
                val envelope = parseEnvelope(open(json))
                    ?: return@withLock SyncResult.Failure("Invalid settings snapshot")
                val settings = envelope.settings
                    ?: return@withLock SyncResult.Failure("No settings in snapshot")
                SyncSettingsWhitelist.apply(settings.values)
                SyncResult.Success(System.currentTimeMillis(), false)
            } catch (e: Throwable) {
                AppLog.e("SyncManager downloadSettings failed: ${e.message}")
                SyncResult.Failure(e.message ?: "unknown error")
            }
        }
    }

    fun parseEnvelope(json: String): SyncEnvelope? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        val schema = obj.get("schema")?.asInt ?: 1
        if (schema > SyncModels.SCHEMA_VERSION) return null
        gson.fromJson(obj, SyncEnvelope::class.java)
    } catch (e: Throwable) {
        AppLog.e("SyncManager parseEnvelope failed: ${e.message}")
        null
    }
}

private fun SyncData.filterEnabledBy(config: SyncConfig): SyncData = copy(
    history = if (config.syncHistory) history else emptyList(),
    collect = if (config.syncCollect) collect else emptyList(),
    follow = if (config.syncFollow) follow else emptyList(),
    progress = if (config.syncProgress) progress else emptyList(),
    percent = if (config.syncProgress) percent else emptyMap(),
    totals = if (config.syncProgress) totals else emptyMap(),
    watched = if (config.syncFollow) watched else emptyMap(),
    tracks = if (config.syncProgress) tracks else emptyMap(),
)
