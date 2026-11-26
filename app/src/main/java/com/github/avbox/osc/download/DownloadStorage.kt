package com.github.avbox.osc.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore

import androidx.core.content.ContextCompat

import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog

import java.io.File
import java.util.Locale

object DownloadStorage {

    private const val ROOT_DIR = "AVBox"
    private const val DOWNLOAD_ROOT = "Download"

    fun tempDir(taskId: String): File {
        val ctx = AppContextHolder.context()
        val base = ctx?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(File(base, "download"), taskId)
    }

    fun sanitizeName(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "download"
        val cleaned = text
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
        return cleaned.take(120).ifBlank { "download" }
    }

    fun buildFileName(vodName: String?, episodeName: String?, title: String?, ext: String): String {
        val vod = sanitizeName(vodName)
        val ep = sanitizeName(episodeName)
        val fallback = sanitizeName(title)
        val base = when {
            vod.isNotEmpty() && vod != "download" && ep.isNotEmpty() && ep != "download" -> "$vod - $ep"
            vod.isNotEmpty() && vod != "download" -> vod
            ep.isNotEmpty() && ep != "download" -> ep
            else -> fallback
        }
        val suffix = if (ext.isBlank()) "" else "." + ext.trimStart('.')
        return sanitizeName(base) + suffix
    }

    fun extensionFromUrl(url: String?): String? {
        val path = url?.substringBefore('?')?.substringBefore('#') ?: return null
        val name = path.substringAfterLast('/', "")
        if (!name.contains('.')) return null
        val ext = name.substringAfterLast('.').lowercase(Locale.ROOT)
        if (ext.length !in 2..5) return null
        if (!ext.all { it.isLetterOrDigit() }) return null
        return ext
    }

    fun extensionFromMime(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)?.substringBefore(';')) {
        "video/mp4" -> "mp4"
        "video/x-flv" -> "flv"
        "video/mp2t" -> "ts"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "audio/mpeg" -> "mp3"
        "audio/aac" -> "aac"
        "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "m3u8"
        else -> null
    }

    fun mimeForExtension(ext: String?): String = when (ext?.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "flv" -> "video/x-flv"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m3u8" -> "application/vnd.apple.mpegurl"
        else -> "application/octet-stream"
    }

    fun publish(tempFile: File, task: DownloadTask, fileName: String): String? {
        val ctx = AppContextHolder.context() ?: return null
        if (!tempFile.exists()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(ctx, tempFile, task, fileName)
        } else {
            publishToFileSystem(ctx, tempFile, task, fileName)
        }
    }

    private fun publishToMediaStore(
        ctx: Context,
        tempFile: File,
        task: DownloadTask,
        fileName: String,
    ): String? {
        val resolver = ctx.contentResolver
        val relative = DOWNLOAD_ROOT + "/" + ROOT_DIR + "/" + sanitizeName(task.dirHint ?: task.vodName)
        val displayName = uniqueMediaStoreName(resolver, relative, fileName)
        val mime = mimeForExtension(fileName.substringAfterLast('.', ""))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri: Uri? = try {
            resolver.insert(collection, values)
        } catch (th: Throwable) {
            AppLog.e("DownloadStorage", th)
            null
        }
        if (uri == null) {
            return publishToFileSystem(ctx, tempFile, task, fileName)
        }
        var ok = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                out.flush()
            } ?: throw IllegalStateException("openOutputStream returned null")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            ok = true
            return uri.toString()
        } catch (th: Throwable) {
            AppLog.e("DownloadStorage", th)
            return null
        } finally {
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun publishToFileSystem(
        ctx: Context,
        tempFile: File,
        task: DownloadTask,
        fileName: String,
    ): String? {
        val dir = targetDir(ctx, task)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = uniqueFile(dir, fileName)
        return try {
            tempFile.inputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
            target.absolutePath
        } catch (th: Throwable) {
            AppLog.e("DownloadStorage", th)
            runCatching { target.delete() }
            null
        }
    }

    private fun targetDir(ctx: Context, task: DownloadTask): File {
        val sub = sanitizeName(task.dirHint ?: task.vodName)
        val writeGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (writeGranted) {
            @Suppress("DEPRECATION")
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$ROOT_DIR/$sub",
            )
        } else {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            File(base, "$ROOT_DIR/$sub")
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var index = 1
        while (candidate.exists() && index < 1000) {
            val name = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            candidate = File(dir, name)
            index++
        }
        return candidate
    }

    private fun uniqueMediaStoreName(
        resolver: android.content.ContentResolver,
        relative: String,
        fileName: String,
    ): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = fileName
        var index = 1
        while (index < 1000 && existsInMediaStore(resolver, relative, candidate)) {
            candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            index++
        }
        return candidate
    }

    private fun existsInMediaStore(
        resolver: android.content.ContentResolver,
        relative: String,
        displayName: String,
    ): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
            MediaStore.MediaColumns.DISPLAY_NAME + "=?"
        return try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(relative, displayName),
                null,
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun deletePublished(task: DownloadTask): Boolean {
        val ctx = AppContextHolder.context() ?: return false
        val uri = task.contentUri
        if (!uri.isNullOrEmpty() && uri.startsWith("content://")) {
            return try {
                ctx.contentResolver.delete(Uri.parse(uri), null, null) > 0
            } catch (th: Throwable) {
                AppLog.e("DownloadStorage", th)
                false
            }
        }
        if (!uri.isNullOrEmpty()) {
            return runCatching { File(uri).delete() }.getOrDefault(false)
        }
        return false
    }

    fun clearTemp(taskId: String) {
        runCatching { tempDir(taskId).deleteRecursively() }
    }

    fun freeBytes(): Long {
        return try {
            val ctx = AppContextHolder.context()
            val dir = ctx?.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx?.filesDir
            val stat = StatFs(dir?.absolutePath ?: "/")
            stat.availableBytes
        } catch (_: Throwable) {
            0L
        }
    }
}
