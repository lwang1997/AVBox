package com.github.avbox.osc.ui.download

import android.content.Context
import android.content.Intent
import android.net.Uri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.download.DownloadManager
import com.github.avbox.osc.download.DownloadStorage
import com.github.avbox.osc.util.AppLog

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DownloadViewModel : ViewModel() {

    val tasks: StateFlow<List<DownloadTask>> = DownloadManager.tasks

    fun pause(id: String) = DownloadManager.pause(id)

    fun resume(id: String) = DownloadManager.resume(id)

    fun cancel(id: String) = DownloadManager.cancel(id)

    fun retry(id: String) = DownloadManager.retry(id)

    fun delete(id: String, deleteFile: Boolean) {
        viewModelScope.launch {
            runCatching { DownloadManager.delete(id, deleteFile) }
                .onFailure { AppLog.e("DownloadManager", it) }
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            tasks.value
                .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELED }
                .forEach { runCatching { DownloadManager.delete(it.id, true) } }
        }
    }

    fun open(context: Context, task: DownloadTask) {
        val uriText = task.contentUri ?: return
        val ext = task.fileName.substringAfterLast('.', "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriText), DownloadStorage.mimeForExtension(ext))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.e("DownloadManager", it) }
    }
}
