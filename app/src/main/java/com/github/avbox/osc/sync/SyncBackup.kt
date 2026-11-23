package com.github.avbox.osc.sync

import com.github.avbox.osc.BuildConfig
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份：一次性全量导出/恢复，**不走 WebDAV 同步的设置白名单** ——
 * 白名单是为"多设备反复互相覆盖"设计的保守策略，会漏掉未登记的新设置项；
 * 备份语义是"完整带走"，改用小黑名单，只排除"恢复了就是错的"：
 *  - 设备身份（thunder_imei/mac、sync_device_id）：换机恢复会身份错乱
 *  - SAF 目录授权（local_source_trees）：不跨设备不跨重装，恢复出来是死链
 *  - 同步运行态（etag/缓存/上次同步时间）：属于服务器状态，恢复无意义
 *  - 运行时缓存与看门狗状态（cache_ / jsRuntime_ / boot_ 前缀）
 * 凭据（WebDAV 账号、加密密码）按产品决定保留在备份内。
 *
 * 导出：信封 → 设了加密密码则 gzip+AES（否则明文交由 zip 压缩）→ **zip 压缩包**，
 * 由调用方按时间戳命名写入固定目录、不覆盖已有文件。
 * 恢复：**整体替换**（清空本地后写入备份，不做 LWW 合并）—— 备份按时间戳命名、
 * 用户主动挑选，语义就是"回到备份时点"；设置仍为覆盖式写入。
 * 封装层复用 SyncEnvelope（带 schema 版本）+ SyncCrypto（兼容旧裸文件）。
 */
object SyncBackup {

    private val gson = Gson()

    private const val ZIP_ENTRY_PLAIN = "avbox-backup.json"
    private const val ZIP_ENTRY_SEALED = "avbox-backup.enc"

    /** 恢复结果：Success 正常 / Empty 备份里没有可恢复内容 / Failed 凭据或文件问题 */
    sealed class ImportResult {
        object Success : ImportResult()
        object Empty : ImportResult()
        data class Failed(val message: String) : ImportResult()
        /** 本地没设密码却读到加密备份，或密码不符 —— UI 提示去设置页处理 */
        object NeedPassword : ImportResult()
    }

    private val EXCLUDE_KEYS: Set<String> = setOf(
        KVKeys.THUNDER_IMEI,
        KVKeys.THUNDER_MAC,
        KVKeys.SYNC_DEVICE_ID,
        KVKeys.LOCAL_SOURCE_TREES,
        KVKeys.SYNC_DATA_ETAG,
        KVKeys.SYNC_DATA_CACHE,
        KVKeys.SYNC_LAST_SYNC_AT,
    )

    private val EXCLUDE_PREFIXES = listOf("cache_", "jsRuntime_", "boot_")

    private fun isExcluded(key: String): Boolean =
        key in EXCLUDE_KEYS || EXCLUDE_PREFIXES.any { key.startsWith(it) }

    /** 导出：全量观看数据 + 全量设置（减黑名单）→ 信封 → （设密码则加密）→ zip 字节 */
    suspend fun exportZip(): ByteArray = withContext(Dispatchers.IO) {
        val envelope = SyncEnvelope(
            deviceId = SyncConfig.deviceId(),
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            data = SyncSnapshot.exportData(),
            settings = SyncSettings(snapshotSettings()),
        )
        val json = gson.toJson(envelope)
        val password = SyncConfig.encryptPassword
        // 未设密码：明文 JSON 交给 zip 的 deflate 压缩（可读且够小）；
        // 设了密码：gzip+AES 加密载荷（密文交给 zip 只做容器）
        val content: String
        val entryName: String
        if (password.isEmpty()) {
            content = json
            entryName = ZIP_ENTRY_PLAIN
        } else {
            content = SyncCrypto.seal(json, password)
            entryName = ZIP_ENTRY_SEALED
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        bos.toByteArray()
    }

    private fun snapshotSettings(): Map<String, String> {
        val out = HashMap<String, String>()
        KV.keys("").forEach { key ->
            if (isExcluded(key)) return@forEach
            try {
                KV.rawGet(key)?.let { out[key] = it }
            } catch (ignore: Throwable) {
                // 单键读取失败不影响整体
            }
        }
        return out
    }

    /**
     * 恢复：zip 或旧版裸文件 → 解出载荷 → **整体替换**本地数据 + 设置覆盖式写入（减黑名单）。
     * 密码错误/缺失、文件非法不抛出，统一转 Failed 由调用方转 toast。
     */
    suspend fun importBytes(bytes: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = if (isZip(bytes)) unzipFirstEntry(bytes) else bytes.toString(Charsets.UTF_8)
            val json = SyncCrypto.open(text, SyncConfig.encryptPassword)
            val envelope = SyncManager.parseEnvelope(json)
                ?: return@withContext ImportResult.Failed("invalid backup file")
            val data = envelope.data
            val settings = envelope.settings?.values
            if (data == null && settings.isNullOrEmpty()) {
                return@withContext ImportResult.Empty
            }
            if (data != null) {
                SyncSnapshot.replaceData(data)
            }
            var applied = 0
            settings?.forEach { (key, raw) ->
                if (isExcluded(key)) return@forEach
                try {
                    KV.rawPut(key, raw)
                    applied++
                } catch (ignore: Throwable) {
                    // 单键写入失败不影响整体
                }
            }
            if (data == null && applied == 0) ImportResult.Empty else ImportResult.Success
        } catch (e: IllegalArgumentException) {
            // SyncCrypto 对密码问题抛 IAE（"sync password required" / "wrong sync password"）
            val msg = e.message ?: ""
            if (msg.contains("password")) ImportResult.NeedPassword
            else ImportResult.Failed(msg.ifBlank { "invalid backup file" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ImportResult.Failed(e.message ?: "import failed")
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() // "PK"

    /** 解出 zip 里第一个文本条目（本备份固定单条目） */
    private fun unzipFirstEntry(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry ?: throw IOException("empty zip backup")
            while (entry.isDirectory) entry = zip.nextEntry ?: throw IOException("empty zip backup")
            val text = zip.readBytes().toString(Charsets.UTF_8)
            if (text.isEmpty()) throw IOException("empty zip backup")
            return text
        }
    }
}
