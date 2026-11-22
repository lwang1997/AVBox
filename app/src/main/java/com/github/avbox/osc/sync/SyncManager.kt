package com.github.avbox.osc.sync

import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

                val remoteJson = client.get(SyncConfig.dataFile)
                val remote = remoteJson?.let { parseEnvelope(it)?.data }

                val merged = if (remote == null) {
                    local
                } else {
                    SyncMerger.merge(local, remote.filterEnabledBy(SyncConfig), now)
                }

                SyncSnapshot.applyData(merged)

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
                val envelope = SyncEnvelope(
                    deviceId = SyncConfig.deviceId(),
                    exportedAt = System.currentTimeMillis(),
                    data = upload,
                    settings = null,
                )
                client.put(SyncConfig.dataFile, gson.toJson(envelope))

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
                client.put(SyncConfig.settingsFile, gson.toJson(envelope))
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
                val envelope = parseEnvelope(json)
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
