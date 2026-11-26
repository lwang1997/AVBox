package com.github.avbox.osc.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.DownloadSheetState
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AVBoxBottomSheet

@Composable
internal fun DownloadSheet(
    state: DownloadSheetState,
    onDismiss: () -> Unit,
) {
    var showHeaderWarning by remember { mutableStateOf(false) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.download_sheet_title),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = playerDim(R.dimen.vs_30),
                    end = playerDim(R.dimen.vs_30),
                    bottom = playerDim(R.dimen.vs_30),
                ),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.episodeName.isNotEmpty()) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Text(
                    text = state.episodeName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = state.kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
            Text(
                text = stringResource(R.string.download_save_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.headers.isNotEmpty()) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Text(
                    text = stringResource(R.string.download_header_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.warning.isNullOrEmpty()) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Text(
                    text = state.warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            SheetButton(
                text = stringResource(R.string.download_method_builtin),
                iconRes = R.drawable.ic_download,
                onClick = {
                    onDismiss()
                    state.onStartBuiltin()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.idmAvailable) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                SheetButton(
                    text = stringResource(R.string.download_method_idm),
                    iconRes = R.drawable.ic_download,
                    onClick = {
                        if (state.headers.isNotEmpty()) {
                            showHeaderWarning = true
                        } else {
                            onDismiss()
                            state.onStartIdm()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
            SheetButton(
                text = stringResource(R.string.download_copy_link),
                onClick = {
                    state.onCopy()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.download_batch_cancel))
            }
        }
    }
    if (showHeaderWarning) {
        AVBoxAlertDialog(
            onDismissRequest = { showHeaderWarning = false },
            title = { Text(stringResource(R.string.download_header_dialog_title)) },
            text = { Text(stringResource(R.string.download_header_dialog_message)) },
            dismissButton = {
                TextButton(onClick = {
                    showHeaderWarning = false
                    onDismiss()
                    state.onStartBuiltin()
                }) {
                    Text(stringResource(R.string.download_method_builtin))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showHeaderWarning = false
                    onDismiss()
                    state.onStartIdm()
                }) {
                    Text(stringResource(R.string.download_still_idm))
                }
            },
        )
    }
}
