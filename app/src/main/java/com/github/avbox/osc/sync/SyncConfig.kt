package com.github.avbox.osc.sync

import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import java.util.UUID

object SyncConfig {

    private const val FILE_DATA = "avbox-sync-data.json"
    private const val FILE_SETTINGS = "avbox-sync-settings.json"
    /** 应用专用子目录名：URL 里不用手填，由 normalize 自动补、WebDavClient 自动创建。 */
    private const val SUB_DIR = "AVBox"
    /**
     * 坚果云 WebDAV 禁止在 /dav/ 根目录直接写文件（PUT 返回 404 ObjectNotFound），
     * 必须在子目录内。默认指向 /dav/，AVBox 子目录自动追加并 MKCOL 创建。
     */
    const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/"

    /**
     * 输入框显示值：只反映用户输入，**不带** AVBox 尾段 ——
     * 读取时剥掉（含历史版本存进去的），[effectiveUrl] 在真正发请求时才补回。
     */
    var url: String
        get() {
            val stored = KV.get(KVKeys.SYNC_WEBDAV_URL, "").trim()
            return stripSubDir(stored.ifBlank { DEFAULT_URL })
        }
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_URL, value.trim()) }

    /** 实际请求地址：显示值（或默认地址）+ 自动补 AVBox 子目录，目录由 WebDavClient 逐级 MKCOL 创建。 */
    val effectiveUrl: String
        get() = normalize(url)

    /** 去掉末段的 /AVBox（仅一层，用于展示；normalize 会按需补回，实际路径不变）。 */
    private fun stripSubDir(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (!v.substringAfterLast('/').equals(SUB_DIR, ignoreCase = true)) return v
        val stripped = v.substringBeforeLast('/')
        return if (stripped.isBlank()) v else stripped
    }

    /**
     * 把显示地址规整成"根地址 + /AVBox"：
     *  - 坚果云裸域名补到 /dav（该服务根目录禁写）
     *  - 末段已是 AVBox 的原样保留
     *  - 其余一律追加 /AVBox —— 用户不用手动加
     */
    private fun normalize(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return normalize(DEFAULT_URL)
        val rooted = if (
            v.equals("https://dav.jianguoyun.com", ignoreCase = true) ||
            v.equals("https://dav.jianguoyun.com/dav", ignoreCase = true)
        ) {
            "https://dav.jianguoyun.com/dav"
        } else {
            v
        }
        return if (rooted.substringAfterLast('/').equals(SUB_DIR, ignoreCase = true)) {
            rooted
        } else {
            "$rooted/$SUB_DIR"
        }
    }

    var user: String
        get() = KV.get(KVKeys.SYNC_WEBDAV_USER, "")
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_USER, value) }

    var password: String
        get() = KV.get(KVKeys.SYNC_WEBDAV_PASSWORD, "")
        set(value) { KV.put(KVKeys.SYNC_WEBDAV_PASSWORD, value) }

    /**
     * 快照加密密码：留空不加密。与 WebDAV 密码相互独立且不进同步白名单 ——
     * 它是解密远端文件的密钥本身，同步出去等于没加密。
     */
    var encryptPassword: String
        get() = KV.get(KVKeys.SYNC_ENCRYPT_PASSWORD, "")
        set(value) { KV.put(KVKeys.SYNC_ENCRYPT_PASSWORD, value) }

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

    /** data 文件的 ETag：条件 GET（P2）用，304 时跳过下载。 */
    var dataEtag: String
        get() = KV.get(KVKeys.SYNC_DATA_ETAG, "")
        set(value) { KV.put(KVKeys.SYNC_DATA_ETAG, value) }

    /**
     * 服务器 data 文件最近一次的明文 SyncData JSON 缓存：
     * ETag 命中 304 时用它参与 LWW 合并，避免重复下载。只存本机（与 Room 数据同级）。
     */
    var remoteDataCache: String
        get() = KV.get(KVKeys.SYNC_DATA_CACHE, "")
        set(value) { KV.put(KVKeys.SYNC_DATA_CACHE, value) }

    /** 远端状态失效（404 / 正文损坏）：同时清 ETag 与缓存，下次全量拉取。 */
    fun clearRemoteState() {
        dataEtag = ""
        remoteDataCache = ""
    }

    fun client(): WebDavClient = WebDavClient(effectiveUrl, user, password)

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
