package com.github.avbox.osc.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.components.SettingsTestButton
import com.github.avbox.osc.ui.components.SettingsTextFieldRow
import com.github.avbox.osc.ui.components.TopBarActionBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SectionSpacing = 28.dp
private val CardContentPadding = 20.dp
private val FieldVerticalPadding = 4.dp
private val FieldShape = RoundedCornerShape(10.dp)
private val FieldHeight = 44.dp

@Composable
fun SyncSettingsScreen(
    onNavigateBack: () -> Unit,
    vm: SyncSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_sync_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            // WebDAV 服务器
            SettingsGroup(title = stringResource(R.string.settings_group_webdav)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.settings_sync_url),
                        value = state.url,
                        onValueChange = vm::setUrl,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        placeholder = stringResource(R.string.settings_sync_url_hint),
                        fieldShape = FieldShape,
                        fieldHeight = FieldHeight,
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.settings_sync_user),
                        value = state.user,
                        onValueChange = vm::setUser,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        clearable = true,
                        fieldShape = FieldShape,
                        fieldHeight = FieldHeight,
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.settings_sync_password),
                        value = state.password,
                        onValueChange = vm::setPassword,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        masked = true,
                        fieldShape = FieldShape,
                        fieldHeight = FieldHeight,
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    Column(modifier = Modifier.padding(CardContentPadding)) {
                        SettingsTestButton(
                            text = stringResource(R.string.settings_sync_test),
                            onClick = { vm.testConnection(context) },
                            loading = state.testing,
                        )
                    }
                }
            }

            // 数据加密（快照压缩 + AES-GCM；留空不加密）
            SettingsGroup(title = stringResource(R.string.settings_group_encrypt)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsTextFieldRow(
                        label = stringResource(R.string.settings_sync_encrypt_password),
                        value = state.encryptPassword,
                        onValueChange = vm::setEncryptPassword,
                        modifier = Modifier.padding(horizontal = CardContentPadding, vertical = FieldVerticalPadding),
                        placeholder = stringResource(R.string.settings_sync_encrypt_hint),
                        masked = true,
                        fieldShape = FieldShape,
                        fieldHeight = FieldHeight,
                    )
                }
            }

            // 自动同步
            SettingsGroup(title = stringResource(R.string.settings_group_auto)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sync_auto_enable),
                        subtitle = stringResource(R.string.settings_sync_auto_subtitle),
                        checked = state.autoEnabled,
                        onCheckedChange = vm::setAuto,
                    )
                }
            }

            // 同步项
            SettingsGroup(title = stringResource(R.string.settings_group_items)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sync_item_history),
                        checked = state.syncHistory,
                        onCheckedChange = vm::setItemHistory,
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sync_item_collect),
                        checked = state.syncCollect,
                        onCheckedChange = vm::setItemCollect,
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sync_item_follow),
                        checked = state.syncFollow,
                        onCheckedChange = vm::setItemFollow,
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sync_item_progress),
                        checked = state.syncProgress,
                        onCheckedChange = vm::setItemProgress,
                    )
                }
            }

            // 手动同步
            SettingsGroup(title = stringResource(R.string.settings_group_manual)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_sync_now),
                        subtitle = stringResource(R.string.settings_sync_now_subtitle),
                        enabled = !state.busy,
                        onClick = { vm.syncNow(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_sync_upload_settings),
                        subtitle = stringResource(R.string.settings_sync_settings_subtitle),
                        enabled = !state.busy,
                        onClick = { vm.uploadSettings(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_sync_download_settings),
                        subtitle = stringResource(R.string.settings_sync_settings_subtitle),
                        enabled = !state.busy,
                        onClick = { vm.downloadSettings(context) },
                    )
                }
            }

            // 状态
            SettingsGroup(title = stringResource(R.string.settings_group_status)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_sync_last),
                        valueText = if (state.lastSyncAt > 0) formatTime(state.lastSyncAt)
                        else stringResource(R.string.settings_sync_never),
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ms))
}
