package com.github.avbox.osc.sync

import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import java.util.UUID

object SyncConfig {

    private const val FILE_DATA = "avbox-sync-data.json"
    private const val FILE_SETTINGS = "avbox-sync-settings.json"
    /**
     * 坚果云 WebDAV 禁止在 /dav/ 根目录直接写文件（PUT 返回 404 ObjectNotFound），
     * 必须在子目录内。默认指向一个专用子目录，客户端会在同步前自动 MKCOL 创建它。
     */
    const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/AVBox/"

    var url: String
        get() {
            val stored = KV.get(KVKeys.SYNC_WEBDAV_URL, "")
            return normalize(stored.ifBlank { DEFAULT_URL })
        }
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_URL, normalize(value)) }

    /**
     * 坚果云 WebDAV 端点必须带 /dav/ 且写到子目录内，直接在根目录 PUT 会返回 404 ObjectNotFound。
     * 对坚果云域名：裸域名 / 只到 /dav 的，一律补全到默认子目录；已填更深子目录的保持原样。
     * 其他服务原样返回。
     */
    private fun normalize(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (v.equals("https://dav.jianguoyun.com", ignoreCase = true) ||
            v.equals("https://dav.jianguoyun.com/dav", ignoreCase = true)
        ) {
            return DEFAULT_URL
        }
        return v
    }

    var user: String
        get() = KV.get(KVKeys.SYNC_WEBDAV_USER, "")
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_USER, value) }

    var password: String
        get() = KV.get(KVKeys.SYNC_WEBDAV_PASSWORD, "")
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_PASSWORD, value) }

    var autoEnabled: Boolean
        get() = KV.get(KVKeys.SYNC_AUTO_ENABLED, false)
        set(value) { KV.put(KVKeys.SYNC_AUTO_ENABLED, value) }

    var syncHistory: Boolean
        get() = KV.get(KVKeys.SYNC_ITEM_HISTORY, true)
        set(value) { KV.put(KVKeys.SYNC_ITEM_HISTORY, value) }

    var syncCollect: Boolean
        get() = KV.get(KVKeys.SYNC_ITEM_COLLECT, true)
        set(value) { KV.put(KVKeys.SYNC_ITEM_COLLECT, value) }

    var syncFollow: Boolean
        get() = KV.get(KVKeys.SYNC_ITEM_FOLLOW, true)
        set(value) { KV.put(KVKeys.SYNC_ITEM_FOLLOW, value) }

    var syncProgress: Boolean
        get() = KV.get(KVKeys.SYNC_ITEM_PROGRESS, true)
        set(value) { KV.put(KVKeys.SYNC_ITEM_PROGRESS, value) }

    val dataFile: String get() = FILE_DATA
    val settingsFile: String get() = FILE_SETTINGS

    /** URL 有默认值，故以是否填了用户名作为「已配置」判据，避免空凭证触发同步 */
    val configured: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    fun deviceId(): String {
        var id = KV.get(KVKeys.SYNC_DEVICE_ID, "")
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            KV.put(KVKeys.SYNC_DEVICE_ID, id)
        }
        return id
    }

    var lastSyncAt: Long
        get() = KV.get(KVKeys.SYNC_LAST_SYNC_AT, 0L)
        set(value) { KV.put(KVKeys.SYNC_LAST_SYNC_AT, value) }

    fun client(): WebDavClient = WebDavClient(url, user, password)

    /** 按开关裁剪要同步的数据项 */
    fun filterEnabled(data: SyncData): SyncData = data.copy(
        history = if (syncHistory) data.history else emptyList(),
        collect = if (syncCollect) data.collect else emptyList(),
        follow = if (syncFollow) data.follow else emptyList(),
        progress = if (syncProgress) data.progress else emptyList(),
        percent = if (syncProgress) data.percent else emptyMap(),
        totals = if (syncProgress) data.totals else emptyMap(),
        watched = if (syncFollow) data.watched else emptyMap(),
        tracks = if (syncProgress) data.tracks else emptyMap(),
    )
}
