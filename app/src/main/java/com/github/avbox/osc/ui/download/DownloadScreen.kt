package com.github.avbox.osc.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import com.github.avbox.osc.R
import com.github.avbox.osc.bean.DownloadStatus
import com.github.avbox.osc.bean.DownloadTask
import com.github.avbox.osc.download.DownloadStorage
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.TopBarActionBox

@Composable
fun DownloadScreen(vm: DownloadViewModel, onNavigateBack: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val context = LocalContext.current
    val active = tasks.filter { it.active }
    val finished = tasks.filter { !it.active }
    val freeBytes = remember(tasks.size) { DownloadStorage.freeBytes() }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.download_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
        actions = {
            TextButton(onClick = { vm.clearCompleted() }) {
                Text(stringResource(R.string.download_clear_completed))
            }
        },
    ) { topPad, bottomPad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = topPad + 8.dp,
                bottom = bottomPad + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.download_storage_summary),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatBytes(freeBytes) + " " + stringResource(R.string.download_free_space),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.download_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.download_group_active),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(active, key = { it.id }) { task ->
                        DownloadItemCard(
                            task = task,
                            onPause = { vm.pause(task.id) },
                            onResume = { vm.resume(task.id) },
                            onCancel = { vm.cancel(task.id) },
                            onRetry = { vm.retry(task.id) },
                            onDelete = { vm.delete(task.id, true) },
                            onOpen = { vm.open(context, task) },
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.download_group_finished),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(finished, key = { it.id }) { task ->
                        DownloadItemCard(
                            task = task,
                            onPause = { vm.pause(task.id) },
                            onResume = { vm.resume(task.id) },
                            onCancel = { vm.cancel(task.id) },
                            onRetry = { vm.retry(task.id) },
                            onDelete = { vm.delete(task.id, true) },
                            onOpen = { vm.open(context, task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = task.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!task.episodeName.isNullOrEmpty()) {
                Text(
                    text = task.episodeName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                if (task.totalBytes > 0) {
                    Text(
                        text = formatBytes(task.downloadedBytes) + " / " + formatBytes(task.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.MERGING, DownloadStatus.QUEUED -> {
                        TextButton(onClick = onPause) { Text(stringResource(R.string.download_pause)) }
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.download_cancel)) }
                    }
                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = onResume) { Text(stringResource(R.string.download_resume)) }
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete)) }
                    }
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete)) }
                    }
                    DownloadStatus.COMPLETED -> {
                        TextButton(onClick = onOpen) { Text(stringResource(R.string.download_open)) }
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete)) }
                    }
                    DownloadStatus.CANCELED -> {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.download_status_downloading)
    DownloadStatus.MERGING -> stringResource(R.string.download_status_merging)
    DownloadStatus.PAUSED -> stringResource(R.string.download_status_paused)
    DownloadStatus.COMPLETED -> stringResource(R.string.download_status_completed)
    DownloadStatus.FAILED -> task.error ?: stringResource(R.string.download_status_failed)
    DownloadStatus.CANCELED -> stringResource(R.string.download_status_canceled)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        value.toLong().toString() + " " + units[index]
    } else {
        String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
    }
}
