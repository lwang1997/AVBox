package com.github.avbox.osc.util

import android.os.Environment
import android.util.Log

import com.github.avbox.osc.BuildConfig

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppLog {
    private var TAG = "TVBox-runtime"
    private const val MAX_LOG_LENGTH = 3000

    /** 应用内日志查看器的环形缓冲（级别过滤在采集端，KV LOG_LEVEL：0=Debug 1=Error 2=None）。 */
    class LogLine(val time: Long, val level: Char, val text: String)

    private const val RING_MAX = 1500
    private val ring = ArrayDeque<LogLine>()

    private fun captureEnabled(level: Char): Boolean {
        if (!isLogEnabled()) return false
        return try {
            when (KV.get(KVKeys.LOG_LEVEL, 0)) {
                1 -> level == 'E'
                2 -> false
                else -> true
            }
        } catch (t: Throwable) {
            level == 'E'
        }
    }

    // ------------------------------------------------------------------
    // 日志总开关：默认跟随构建类型（debug 开、release 关）。
    // release 下设置页不显示「日志记录」入口，连续点版本号 5 次才打开（见 SettingsPage）。
    // 注意：proguard-rules.pro 里不能再对 AppLog 用 -assumenosideeffects，否则调用点被 R8 删掉，开关失效。
    // ------------------------------------------------------------------
    @Volatile
    private var logEnabledOverride: Boolean? = null

    @JvmStatic
    fun isLogEnabled(): Boolean {
        logEnabledOverride?.let { return it }
        return try {
            KV.get(KVKeys.LOG_ENABLED, BuildConfig.DEBUG)
        } catch (t: Throwable) {
            BuildConfig.DEBUG
        }
    }

    @JvmStatic
    fun setLogEnabled(enabled: Boolean) {
        logEnabledOverride = enabled
        try {
            KV.put(KVKeys.LOG_ENABLED, enabled)
        } catch (ignored: Throwable) {
        }
        if (!enabled) {
            synchronized(ring) { ring.clear() }
            executeFileTask { closeUserFile() }
        } else if (isFileLogEnabled()) {
            executeFileTask { ensureUserFile() }
        }
    }

    private fun capture(level: Char, msg: String?) {
        if (msg == null) return
        if (!captureEnabled(level)) return
        val line = LogLine(System.currentTimeMillis(), level, msg)
        synchronized(ring) {
            if (ring.size >= RING_MAX) ring.removeFirst()
            ring.addLast(line)
        }
        writeUserFile(line)
    }

    @JvmStatic
    fun snapshot(): List<LogLine> = synchronized(ring) { ring.toList() }

    @JvmStatic
    fun exportText(): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        for (line in snapshot()) {
            sb.append(fmt.format(Date(line.time))).append(' ')
                .append(line.level).append(' ').append(line.text).append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 用户可见的日志文件：默认保存到存储根目录 AVBox/log/avbox.log（文件管理器直达），
    // 没有"所有文件访问"权限时退回应用外部目录。日志页可开关、可清空。
    // ------------------------------------------------------------------
    private const val USER_LOG_DIR = "AVBox/log"
    private const val USER_LOG_NAME = "avbox.log"
    private const val USER_LOG_BACKUP = "avbox.old.log"
    internal const val USER_LOG_MAX_BYTES = 4L * 1024 * 1024

    @Volatile
    private var userFileEnabled: Boolean? = null

    @Volatile
    private var userFileFlushAt: Long = 0L

    private val userFileExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "applog-file").apply { isDaemon = true }
        }

    private var userFileWriter: BufferedWriter? = null
    private var userFileTarget: File? = null
    private val userLineFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val userHeaderFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @JvmStatic
    fun isFileLogEnabled(): Boolean {
        val cached = userFileEnabled
        if (cached != null) return cached
        val value = try {
            KV.get(KVKeys.LOG_TO_FILE, true)
        } catch (th: Throwable) {
            true
        }
        userFileEnabled = value
        return value
    }

    @JvmStatic
    fun setFileLogEnabled(enabled: Boolean) {
        userFileEnabled = enabled
        try {
            KV.put(KVKeys.LOG_TO_FILE, enabled)
        } catch (ignored: Throwable) {
        }
        executeFileTask {
            if (enabled) ensureUserFile() else closeUserFile()
        }
    }

    /** 当前日志文件路径；没有任何可写目录时返回空串。 */
    @JvmStatic
    fun fileLogPath(): String {
        val dir = resolveUserLogDir() ?: return ""
        return File(dir, USER_LOG_NAME).absolutePath
    }

    /** 清空应用内日志与已保存的日志文件。 */
    @JvmStatic
    fun clearAll() {
        synchronized(ring) { ring.clear() }
        executeFileTask {
            closeUserFile()
            val dir = resolveUserLogDir() ?: return@executeFileTask
            File(dir, USER_LOG_NAME).delete()
            File(dir, USER_LOG_BACKUP).delete()
        }
    }

    internal fun shouldRotateUserLog(sizeBytes: Long): Boolean = sizeBytes >= USER_LOG_MAX_BYTES

    private fun executeFileTask(task: () -> Unit) {
        try {
            userFileExecutor.execute { try { task() } catch (ignored: Throwable) {} }
        } catch (ignored: Throwable) {
        }
    }

    private fun writeUserFile(line: LogLine) {
        if (!isFileLogEnabled()) return
        executeFileTask {
            val writer = ensureUserFile() ?: return@executeFileTask
            writer.write(userLineFormat.format(Date(line.time)))
            writer.write(" ")
            writer.write(line.level.toString())
            writer.write(" ")
            writer.write(line.text)
            writer.newLine()
            val now = System.currentTimeMillis()
            if (line.level == 'E' || now - userFileFlushAt >= 1000L) {
                writer.flush()
                userFileFlushAt = now
            }
        }
    }

    private fun ensureUserFile(): BufferedWriter? {
        val dir = resolveUserLogDir() ?: return null
        val file = File(dir, USER_LOG_NAME)
        val current = userFileWriter
        if (current != null && userFileTarget?.absolutePath == file.absolutePath) return current
        closeUserFile()
        return try {
            if (file.exists() && shouldRotateUserLog(file.length())) {
                val backup = File(dir, USER_LOG_BACKUP)
                backup.delete()
                file.renameTo(backup)
            }
            val writer = BufferedWriter(FileWriter(file, true))
            writer.write("==== " + userHeaderFormat.format(Date()) + " ====\n")
            writer.flush()
            userFileWriter = writer
            userFileTarget = file
            writer
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun closeUserFile() {
        try {
            userFileWriter?.flush()
        } catch (ignored: Throwable) {
        }
        try {
            userFileWriter?.close()
        } catch (ignored: Throwable) {
        }
        userFileWriter = null
        userFileTarget = null
        userFileFlushAt = 0L
    }

    private fun resolveUserLogDir(): File? {
        val context = try {
            AppContextHolder.context()
        } catch (th: Throwable) {
            null
        }
        try {
            val dir = File(Environment.getExternalStorageDirectory(), USER_LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            if (dir.isDirectory && dir.canWrite()) return dir
        } catch (ignored: Throwable) {
        }
        if (context == null) return null
        try {
            val dir = File(context.getExternalFilesDir(null), "log")
            if (!dir.exists()) dir.mkdirs()
            if (dir.isDirectory && dir.canWrite()) return dir
        } catch (ignored: Throwable) {
        }
        return null
    }


    private val FILE_LOG = BuildConfig.DEBUG
    private val FILE_LOG_PREFIXES = arrayOf("echo-preload", "echo-setDataSource", "echo-play-cache", "echo-kv", "echo-progress", "echo-bar-draw", "echo-exo", "echo-music", "echo-lyric", "echo-sub", "echo-danmu", "echo-p2", "echo-p3", "echo-p4", "echo-p5", "clearCache", "echo--jar", "echo-local-src", "echo-setTrack", "echo-autoRetry", "echo-player", "echo-switch", "echo-goPlayUrl", "echo-history", "echo-iso", "echo-render", "echo-surface", "echo-output-size", "echo-picture", "echo-anime4k", "echo--list", "echo--getList", "echo--parse", "echo--getSort", "echo--sort", "echo-detail", "echo-thunder", "echo-proxy", "echo-home-backdrop", "echo-cast", "echo-resolvePlayUrl", "echo-playM3u8", "echo-tmdb", "echo-live", "echo-load", "echo-zero-start", "echo-storage", "echo-config-refresh", "echo-boot")
    private const val FILE_LOG_NAME = "preload_debug.log"
    private const val FILE_LOG_BACKUP = ".1"
    internal const val FILE_LOG_MAX_BYTES = 8L * 1024 * 1024
    private var fileLogExecutor: ExecutorService? = null

    internal fun shouldRotateLog(sizeBytes: Long): Boolean = sizeBytes >= FILE_LOG_MAX_BYTES

    private fun fileLog(level: String, msg: String?) {
        if (!FILE_LOG || msg == null) return
        var match = false
        for (prefix in FILE_LOG_PREFIXES) {
            if (msg.startsWith(prefix)) {
                match = true
                break
            }
        }
        if (!match) return
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " " + level + " " + msg
        synchronized(AppLog::class.java) {
            if (fileLogExecutor == null) fileLogExecutor = Executors.newSingleThreadExecutor()
        }
        val executor = fileLogExecutor
        try {
            executor?.execute {
                try {
                    val dir = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.filesDir
                    val file = File(dir, FILE_LOG_NAME)
                    if (shouldRotateLog(file.length())) {
                        val backup = File(dir, FILE_LOG_NAME + FILE_LOG_BACKUP)
                        if (backup.exists()) backup.delete()
                        file.renameTo(backup)
                    }
                    FileWriter(file, true).use { writer ->
                        writer.write(line + "\n")
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun e(msg: String?) {
        capture('E', msg)
        Log.e(TAG, "" + msg)
        fileLog("E", msg.toString())
    }

    @JvmStatic
    fun i(msg: String?) {
        capture('I', msg)
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "" + msg)
        fileLog("I", msg.toString())
    }

    @JvmStatic
    fun debugI(msg: String?) {
        if (!BuildConfig.DEBUG) return
        i(msg)
    }

    @JvmStatic
    fun d(tag: String?, msg: String?) {
        capture('D', if (tag == null) msg else "$tag: $msg")
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, tag + ": " + msg)
        fileLog("D", msg.toString())
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?) {
        capture('E', if (tr == null) "$tag: $msg" else "$tag: $msg | $tr")
        Log.e(TAG, tag + ": " + msg, tr)
        fileLog("E", msg.toString())
        if (tr != null && isLogEnabled()) tr.printStackTrace()
    }

    @JvmStatic
    fun e(tag: String?, tr: Throwable?) {
        e(tag, tr?.toString() ?: "null", tr)
    }

    @JvmStatic
    fun longI(prefix: String?, msg: String?) {
        capture('I', (prefix ?: "") + msg)
        if (!BuildConfig.DEBUG) return
        longLog(Log.INFO, prefix, msg)
    }

    @JvmStatic
    fun longE(prefix: String?, msg: String?) {
        capture('E', (prefix ?: "") + msg)
        longLog(Log.ERROR, prefix, msg)
    }

    private fun longLog(priority: Int, prefix: String?, msg: String?) {
        val text = msg ?: "null"
        val title = prefix ?: ""
        val length = text.length
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text)
            return
        }
        val count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH
        for (i in 0 until count) {
            val start = i * MAX_LOG_LENGTH
            val end = Math.min(start + MAX_LOG_LENGTH, length)
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end))
        }
    }
}
