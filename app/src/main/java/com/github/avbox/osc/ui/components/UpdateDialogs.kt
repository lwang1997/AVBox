package com.github.avbox.osc.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.util.update.DownloadState
import com.github.avbox.osc.util.update.UpdateInfo
import java.io.File
import kotlin.math.roundToInt

/**
 * 新版本信息弹窗：启动提示与设置页手动检查共用。
 * 下载中不可点外部关闭，只能显式取消。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    download: DownloadState,
    checking: Boolean,
    onUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = download is DownloadState.Preparing || download is DownloadState.Downloading
    val dismissButton: (@Composable () -> Unit)? = if (busy) {
        null
    } else {
        {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    }
    AVBoxAlertDialog(
        onDismissRequest = { if (busy) onCancelDownload() else onDismiss() },
        dismissible = !busy,
        title = { Text(stringResource(R.string.update_available_title, info.versionName)) },
        text = {
            UpdateDialogBody(info = info, download = download, checking = checking)
        },
        dismissButton = dismissButton,
        confirmButton = {
            when (download) {
                is DownloadState.Preparing, is DownloadState.Downloading -> {
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }

                is DownloadState.Ready -> {
                    TextButton(onClick = { onInstall(download.file) }) {
                        Text(stringResource(R.string.update_install))
                    }
                }

                is DownloadState.Failed -> {
                    TextButton(onClick = onUpdate) {
                        Text(stringResource(R.string.update_retry))
                    }
                }

                else -> {
                    TextButton(onClick = onUpdate, enabled = !checking) {
                        Text(stringResource(R.string.update_now))
                    }
                }
            }
        },
    )
}

@Composable
private fun UpdateDialogBody(
    info: UpdateInfo,
    download: DownloadState,
    checking: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.update_size, FileUtils.formatCacheSize(info.sizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = info.notes.ifBlank { stringResource(R.string.update_notes_empty) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (download) {
            is DownloadState.Preparing -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.update_preparing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DownloadState.Downloading -> {
                Spacer(Modifier.height(16.dp))
                val fraction = if (download.total > 0) {
                    (download.bytes.toFloat() / download.total).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.update_downloading,
                        (fraction * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DownloadState.Failed -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.update_download_failed, download.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> {
                if (checking) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
