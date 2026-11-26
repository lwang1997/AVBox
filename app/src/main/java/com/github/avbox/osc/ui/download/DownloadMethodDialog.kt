package com.github.avbox.osc.ui.download

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.avbox.osc.R
import com.github.avbox.osc.download.DownloadSettings
import com.github.avbox.osc.ui.components.AVBoxAlertDialog

/**
 * 详情页点“下载”且默认方式为“询问”时弹出的下载方式选择。
 * 选择结果通过 [onPick] 回传：内置下载 / 外部下载器 / 取消。
 */
internal class DownloadMethodPrompt(
    val idmLabel: String,
    private val onPick: (Int) -> Unit,
) {
    fun pickBuiltin() = onPick(DownloadSettings.METHOD_BUILTIN)

    fun pickIdm() = onPick(DownloadSettings.METHOD_IDM)

    fun cancel() = onPick(METHOD_CANCEL)

    companion object {
        const val METHOD_CANCEL = -1
    }
}

/**
 * 用户明确选择外部下载器、但链接需要请求头时的二次确认。
 */
internal class IdmHeaderConfirmPrompt(
    private val onPick: (Boolean) -> Unit,
) {
    fun useIdm() = onPick(true)

    fun useBuiltin() = onPick(false)
}

@Composable
internal fun IdmHeaderConfirmDialog(
    onContinueIdm: () -> Unit,
    onUseBuiltin: () -> Unit,
) {
    AVBoxAlertDialog(
        onDismissRequest = onUseBuiltin,
        title = { Text(stringResource(R.string.download_header_dialog_title)) },
        text = { Text(stringResource(R.string.download_header_dialog_message)) },
        dismissButton = {
            TextButton(onClick = onUseBuiltin) {
                Text(stringResource(R.string.download_method_builtin))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinueIdm) {
                Text(stringResource(R.string.download_still_idm))
            }
        },
    )
}

@Composable
internal fun DownloadMethodDialog(
    idmLabel: String,
    onBuiltin: () -> Unit,
    onIdm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_method_title)) },
        text = { Text(stringResource(R.string.download_method_message)) },
        dismissButton = {
            TextButton(onClick = onIdm) {
                Text(
                    text = stringResource(R.string.download_method_use_external, idmLabel),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onBuiltin) {
                Text(stringResource(R.string.download_method_builtin))
            }
        },
    )
}
