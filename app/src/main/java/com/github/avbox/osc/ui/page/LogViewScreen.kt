package com.github.avbox.osc.ui.page

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionMenuRow
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.PermissionHelper
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogViewScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(KV.get(KVKeys.LOG_LEVEL, 0)) }
    var lines by remember { mutableStateOf(AppLog.snapshot()) }
    var fileEnabled by remember { mutableStateOf(AppLog.isFileLogEnabled()) }
    var logFilePath by remember(fileEnabled) { mutableStateOf(AppLog.fileLogPath()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            lines = AppLog.snapshot()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_logs_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = onNavigateBack,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = topPad, bottom = 8.dp),
        ) {
            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.log_level),
                        iconRes = R.drawable.ic_settings_log,
                        valueText = stringResource(
                            when (level) {
                                1 -> R.string.log_level_error
                                2 -> R.string.log_level_none
                                else -> R.string.log_level_debug
                            },
                        ),
                        options = listOf(
                            stringResource(R.string.log_level_debug),
                            stringResource(R.string.log_level_error),
                            stringResource(R.string.log_level_none),
                        ),
                        selectedIndex = level.coerceIn(0, 2),
                        onSelect = { idx ->
                            level = idx
                            KV.put(KVKeys.LOG_LEVEL, idx)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.log_file_save),
                        subtitle = logFilePath.takeIf { it.isNotEmpty() }
                            ?.let { stringResource(R.string.log_file_path, it) },
                        leadingIconRes = R.drawable.ic_settings_log,
                        checked = fileEnabled,
                        onCheckedChange = { enabled ->
                            fileEnabled = enabled
                            AppLog.setFileLogEnabled(enabled)
                            if (enabled) {
                                // 写存储根目录需要"所有文件访问"；未授权时先申请，拒绝则退回应用外部目录
                                val activity = context as? Activity
                                if (activity != null && !PermissionHelper.isStorageGranted(activity)) {
                                    PermissionHelper.requestStorage(activity) { _, _ ->
                                        logFilePath = AppLog.fileLogPath()
                                    }
                                }
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.log_clear),
                        leadingIconRes = R.drawable.ic_delete,
                        onClick = {
                            AppLog.clearAll()
                            lines = AppLog.snapshot()
                            Toast.makeText(context, context.getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = stringResource(R.string.log_export),
                        leadingIconRes = R.drawable.ic_export,
                        onClick = { exportLogs(context) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (lines.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(lines) { line ->
                        Text(
                            text = "${fmt.format(Date(line.time))} ${line.level} ${line.text}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun exportLogs(context: Context) {
    try {
        val text = AppLog.exportText()
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "AVBox_log_$stamp.log")
        file.writeText(text)
        Toast.makeText(
            context,
            context.getString(R.string.log_export_done) + ": " + file.absolutePath,
            Toast.LENGTH_LONG,
        ).show()
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AVBox log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    } catch (t: Throwable) {
        AppLog.e("LogView", "export failed", t)
        Toast.makeText(
            context,
            context.getString(R.string.log_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
