package com.github.avbox.osc.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.avbox.osc.R
import com.github.avbox.osc.util.restartApp

/**
 * 设置写入（手动下载设置 / 恢复备份）后提示重启。
 *
 * 主题、语言、接口源等状态对象都是"进程启动时读一次 KV"的快照，下载/恢复只是直接写 KV，
 * 不会刷新这些内存状态；只重建 Activity 也不够（语言在 attachBaseContext 里就固定了），
 * 因此给用户一个明确的重启入口，而不是让改动静默地"看起来没生效"。
 */
@Composable
fun RestartPromptDialog(messageRes: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_restart_title)) },
        text = { Text(stringResource(messageRes)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_restart_later))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                restartApp(context.applicationContext)
            }) {
                Text(stringResource(R.string.settings_restart_now))
            }
        },
    )
}
