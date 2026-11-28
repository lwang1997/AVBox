@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.avbox.osc.ui.page

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.avbox.osc.R
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.sync.SyncBackup
import com.github.avbox.osc.ui.ConfigManageActivity
import com.github.avbox.osc.ui.LogViewActivity
import com.github.avbox.osc.ui.PlaySettingsActivity
import com.github.avbox.osc.ui.PreferenceSettingsActivity
import com.github.avbox.osc.ui.SyncSettingsActivity
import com.github.avbox.osc.ui.ThemeSettingsActivity
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.RestartPromptDialog
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionMenuRow
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.UpdateAvailableDialog
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.PendingRestart
import com.github.avbox.osc.util.PermissionHelper
import com.github.avbox.osc.util.SearchSettingsState
import com.github.avbox.osc.util.SpiderStorageNotice
import com.github.avbox.osc.util.restartApp
import com.github.avbox.osc.util.update.CheckResult
import com.github.avbox.osc.util.update.InstallResult
import com.github.avbox.osc.util.update.UpdateChecker
import com.github.avbox.osc.util.update.UpdateManager
import com.github.avbox.osc.api.TmdbPoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CACHE_SIZE_REFRESH_MIN_INTERVAL_MS = 60_000L

/** 版本号连点 5 次切换日志总开关：release 默认关闭且隐藏「日志记录」入口，连点才出现 */
private const val VERSION_TAP_COUNT = 5

/** 两次点击间隔超过这个值就不算「连续点击」，重新从 1 计数 */
private const val VERSION_TAP_GAP_MS = 1_500L

/** 单次点击等这么久没等到下一次点击，才当作「检查更新」，避免连点过程中弹更新对话框 */
private const val VERSION_TAP_DEBOUNCE_MS = 500L

/** 备份目录：手机存储/AVBox/backup（与 AVBox 其它产物分开，用户可在文件管理器直达） */
private const val AVBOX_DIR_NAME = "AVBox"
private const val BACKUP_DIR_NAME = "backup"

private fun avboxDir(): File = File(Environment.getExternalStorageDirectory(), AVBOX_DIR_NAME)

private fun backupDir(): File = File(avboxDir(), BACKUP_DIR_NAME)

/** 列表/弹层的卡片圆角位置 */
private fun pickCardPosition(index: Int, size: Int): SettingsCardPosition = when {
    size <= 1 -> SettingsCardPosition.SINGLE
    index == 0 -> SettingsCardPosition.FIRST
    index == size - 1 -> SettingsCardPosition.LAST
    else -> SettingsCardPosition.MIDDLE
}

data class SettingsState(
    val cacheSizeText: String = "",
)

/** 备份列表项：file 原始文件，name 文件名，detail 修改时间 + 大小（弹层副标题） */
data class BackupEntry(
    val file: File,
    val name: String,
    val detail: String,
)

class SettingsViewModel : ViewModel() {
    private var cacheSizeText: String = ""
    private var cacheSizeRefreshedAt = 0L

    private val _state = mutableStateOf(loadState())

    init {
        refresh()
    }

    val state: androidx.compose.runtime.State<SettingsState> = _state

    fun refresh() {
        UiSettingsState.reload()
        _state.value = loadState()
        refreshCacheSize()
    }

    fun refreshState() {
        UiSettingsState.reload()
        _state.value = loadState()
    }

    fun refreshCacheSize() {
        cacheSizeRefreshedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { cacheSizeText() }
            applyCacheSizeText(text)
        }
    }

    fun refreshCacheSizeIfStale() {
        if (SystemClock.elapsedRealtime() - cacheSizeRefreshedAt < CACHE_SIZE_REFRESH_MIN_INTERVAL_MS) return
        refreshCacheSize()
    }

    fun checkUpdate(onResult: (CheckResult) -> Unit) = UpdateManager.checkNow(onResult)

    fun clearCache(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                FileUtils.clearCache()
                TmdbPoster.clearCachedPosters()
                cacheSizeText()
            }
            applyCacheSizeText(text)
            onCleared()
        }
    }

    /** 导出本地备份：zip → 自动写入 手机存储/AVBox/backup/<时间戳>.zip（不覆盖已有文件），返回实际路径 */
    fun exportBackup(context: Context, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val path = try {
                val bytes = withContext(Dispatchers.IO) { SyncBackup.exportZip() }
                withContext(Dispatchers.IO) { writeBackupFile(bytes) }
            } catch (t: Throwable) {
                AppLog.e("SettingsVM", "backup export failed", t)
                null
            }
            onDone(path)
        }
    }

    /**
     * 写入 手机存储/AVBox/backup/（用户可见、文件管理器直达）。
     * Android 11+ 写根目录需要"所有文件访问"权限，导出入口处已检查并申请；
     * 未授权时 writeBytes 会抛 IOException，由 exportBackup 兜底转失败 toast。
     */
    private fun writeBackupFile(bytes: ByteArray): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "avbox-backup_$stamp"
        val dir = backupDir().apply { mkdirs() }
        var name = "$base.zip"
        var seq = 1
        while (File(dir, name).exists()) {
            name = "${base}_$seq.zip"
            seq++
        }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * 扫描 手机存储/AVBox/backup（含旧版留在 AVBox/ 根下的）里的 zip 备份，按修改时间倒序。
     * 读该目录需要"所有文件访问"；没授权时返回空列表，UI 走系统选择器兜底。
     */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        listOf(backupDir(), avboxDir()).distinct()
            .asSequence()
            .flatMap { dir -> (dir.listFiles() ?: emptyArray()).asSequence() }
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                BackupEntry(
                    file = file,
                    name = file.name,
                    detail = stamp.format(Date(file.lastModified())) + " · " +
                            FileUtils.formatCacheSize(file.length()),
                )
            }
            .toList()
    }

    /** 恢复本地备份目录里的文件（与系统选择器同一条恢复流程） */
    fun importBackupFile(file: File, onDone: (SyncBackup.ImportResult) -> Unit) {
        viewModelScope.launch {
            finishImport(readImport { file.readBytes() }, onDone)
        }
    }

    /** 恢复本地备份：读文件字节（zip 或旧版裸文件）→ 数据整体替换 + 设置覆盖，结果交 UI 转 toast */
    fun importBackup(context: Context, uri: Uri, onDone: (SyncBackup.ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = readImport {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("openInputStream returned null")
                input.use { it.readBytes() }
            }
            finishImport(result, onDone)
        }
    }

    private suspend fun readImport(read: () -> ByteArray): SyncBackup.ImportResult = try {
        SyncBackup.importBytes(withContext(Dispatchers.IO) { read() })
    } catch (t: Throwable) {
        AppLog.e("SettingsVM", "backup import failed", t)
        SyncBackup.ImportResult.Failed(t.message ?: "read failed")
    }

    private fun finishImport(result: SyncBackup.ImportResult, onDone: (SyncBackup.ImportResult) -> Unit) {
        if (result is SyncBackup.ImportResult.Success) refreshState()
        onDone(result)
    }

    private fun cacheSizeText(): String =
        FileUtils.formatCacheSize(FileUtils.cacheSize())

    private fun applyCacheSizeText(text: String) {
        if (text != cacheSizeText) {
            cacheSizeText = text
            _state.value = _state.value.copy(cacheSizeText = text)
        }
    }

    private fun loadState(): SettingsState = SettingsState(
        cacheSizeText = cacheSizeText,
    )
}

@Composable
fun SettingsPage(
    vm: SettingsViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val state by vm.state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshState()
        vm.refreshCacheSizeIfStale()
    }
    val context = LocalContext.current
    val versionName = remember { DefaultConfig.appVersionName(context) ?: "" }
    var aboutSheet by remember { mutableStateOf(false) }

    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    val manualCheck: () -> Unit = {
        showUpdateDialog = true
        vm.checkUpdate { result ->
            when (result) {
                is CheckResult.UpToDate -> {
                    showUpdateDialog = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_up_to_date),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is CheckResult.Failed -> {
                    showUpdateDialog = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_check_failed, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }

                is CheckResult.Available -> Unit
            }
        }
    }

    // 日志总开关：release 默认关闭且不显示「日志记录」入口；连续点版本号 5 次打开（再点 5 次关闭）
    var logEntryVisible by remember { mutableStateOf(AppLog.isLogEnabled()) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var versionTapAt by remember { mutableLongStateOf(0L) }
    val versionTapScope = rememberCoroutineScope()
    val onVersionTap: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        versionTapCount = if (now - versionTapAt > VERSION_TAP_GAP_MS) 1 else versionTapCount + 1
        versionTapAt = now
        if (versionTapCount >= VERSION_TAP_COUNT) {
            versionTapCount = 0
            val enabled = !AppLog.isLogEnabled()
            AppLog.setLogEnabled(enabled)
            logEntryVisible = enabled
            Toast.makeText(
                context,
                context.getString(
                    if (enabled) R.string.settings_logs_hidden_enabled
                    else R.string.settings_logs_hidden_disabled,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            // 手停下才当作普通点击（检查更新），连点时不弹更新对话框
            versionTapScope.launch {
                delay(VERSION_TAP_DEBOUNCE_MS)
                if (versionTapCount < VERSION_TAP_COUNT &&
                    SystemClock.elapsedRealtime() - versionTapAt >= VERSION_TAP_DEBOUNCE_MS
                ) {
                    versionTapCount = 0
                    manualCheck()
                }
            }
        }
    }

    // 本地备份：导出自动写入 手机存储/AVBox/backup；恢复优先列该目录里的备份，系统选择器只作「其它位置」兜底
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportFile by remember { mutableStateOf<File?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var showBackupPicker by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportFile = null
            pendingImportUri = uri
            showImportConfirm = true
        }
    }
    LaunchedEffect(showBackupPicker) {
        if (showBackupPicker) backups = vm.listBackups()
    }

    val listState = rememberScrollState()

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {
            Text(
                text = stringResource(R.string.tab_mine),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(start = navStart, bottom = 8.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            AppInfoHeaderCard(
                versionName = versionName,
                checking = updateState.checking,
                availableVersion = updateState.available?.versionName,
                onVersionClick = onVersionTap,
            )

            if (PendingRestart.pending) {
                SettingsGroup(title = null) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        SettingsRow(
                            title = stringResource(R.string.settings_restart_pending_title),
                            subtitle = stringResource(R.string.settings_restart_pending_subtitle),
                            onClick = { restartApp(context.applicationContext) },
                        )
                    }
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_config_manage),
                        subtitle = stringResource(R.string.settings_config_manage_subtitle),
                        iconRes = R.drawable.ic_settings_api,
                        onClick = { ConfigManageActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        subtitle = stringResource(R.string.settings_theme_subtitle),
                        iconRes = R.drawable.ic_settings_theme,
                        onClick = { ThemeSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_play),
                        subtitle = stringResource(R.string.settings_play_subtitle),
                        iconRes = R.drawable.ic_settings_play,
                        onClick = { PlaySettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_preference_title),
                        subtitle = stringResource(R.string.settings_preference_subtitle),
                        iconRes = R.drawable.ic_settings_preference,
                        onClick = { PreferenceSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_sync_title),
                        subtitle = stringResource(R.string.settings_sync_subtitle),
                        iconRes = R.drawable.ic_settings_sync,
                        onClick = { SyncSettingsActivity.start(context) },
                    )
                }
            }


            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    val uiConfig = UiSettingsState.config
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_default_page),
                        subtitle = stringResource(R.string.settings_default_page_subtitle),
                        iconRes = R.drawable.ic_settings_start,
                        valueText = stringResource(if (uiConfig.defaultLoadLive) R.string.common_live else R.string.common_vod),
                        options = listOf(
                            stringResource(R.string.common_vod),
                            stringResource(R.string.common_live),
                        ),
                        selectedIndex = if (uiConfig.defaultLoadLive) 1 else 0,
                        onSelect = { idx -> UiSettingsState.defaultLoadLive = idx == 1 },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_history_limit),
                        subtitle = stringResource(R.string.settings_history_limit_subtitle),
                        iconRes = R.drawable.ic_settings_history,
                        valueText = stringResource(
                            R.string.settings_history_limit_value,
                            HistoryHelper.getHisNum(SearchSettingsState.config.historyNumIndex),
                        ),
                        options = listOf(0, 1, 2).map {
                            stringResource(R.string.settings_history_limit_value, HistoryHelper.getHisNum(it))
                        },
                        selectedIndex = SearchSettingsState.config.historyNumIndex,
                        onSelect = { idx -> SearchSettingsState.historyNumIndex = idx },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
                    SettingsRow(
                        title = stringResource(R.string.settings_clear_cache),
                        subtitle = stringResource(R.string.settings_clear_cache_subtitle),
                        iconRes = R.drawable.ic_delete,
                        valueText = state.cacheSizeText,
                        onClick = { showClearCacheDialog = true },
                    )
                    if (showClearCacheDialog) {
                        val dismissThen = LocalSheetDismissThen.current
                        AVBoxAlertDialog(
                            onDismissRequest = { showClearCacheDialog = false },
                            title = { Text(stringResource(R.string.settings_clear_cache)) },
                            text = { Text(stringResource(R.string.settings_clear_cache_confirm_text)) },
                            dismissButton = {
                                TextButton(onClick = { showClearCacheDialog = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showClearCacheDialog = false
                                    dismissThen {
                                        vm.clearCache {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_cache_cleared),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.common_confirm))
                                }
                            },
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    val uiConfig = UiSettingsState.config
                    SettingsOptionMenuRow(
                        title = "DOH",
                        subtitle = stringResource(R.string.settings_doh_subtitle),
                        iconRes = R.drawable.ic_settings_doh,
                        valueText = if (uiConfig.dohIndex == 0) stringResource(R.string.common_off)
                        else HttpClients.dnsHttpsList.getOrNull(uiConfig.dohIndex)
                            ?: stringResource(R.string.common_off),
                        options = HttpClients.dnsHttpsList.mapIndexed { index, name ->
                            if (index == 0) context.getString(R.string.common_off) else name
                        },
                        selectedIndex = uiConfig.dohIndex,
                        onSelect = { idx -> UiSettingsState.dohIndex = idx },
                    )
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_backup_export),
                        subtitle = stringResource(R.string.settings_backup_export_subtitle),
                        iconRes = R.drawable.ic_export,
                        onClick = {
                            val doExport = {
                                vm.exportBackup(context) { path ->
                                    val msg = if (path != null) {
                                        context.getString(R.string.toast_backup_exported) + ": " + path
                                    } else {
                                        context.getString(R.string.toast_backup_export_failed)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                            // 写存储根目录：Android 11+ 需"所有文件访问"，未授权先申请
                            if (PermissionHelper.isStorageGranted(context)) {
                                doExport()
                            } else {
                                val activity = context as? Activity
                                if (activity == null) {
                                    doExport()
                                } else {
                                    PermissionHelper.requestStorage(activity) { granted, _ ->
                                        if (!granted.isNullOrEmpty()) {
                                            doExport()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_backup_no_storage),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_backup_import),
                        subtitle = stringResource(R.string.settings_backup_import_subtitle),
                        iconRes = R.drawable.ic_file_choose,
                        // 系统选择器的 EXTRA_INITIAL_URI 在部分机型上不生效，改为自己列 AVBox/backup；
                        // 读该目录要"所有文件访问"，没授权先申请，仍拒绝就用系统选择器从任意位置选
                        onClick = {
                            if (PermissionHelper.isStorageGranted(context)) {
                                showBackupPicker = true
                            } else {
                                val activity = context as? Activity
                                if (activity == null) {
                                    showBackupPicker = true
                                } else {
                                    PermissionHelper.requestStorage(activity) { granted, _ ->
                                        if (!granted.isNullOrEmpty()) {
                                            showBackupPicker = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_backup_no_storage_read),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                            importLauncher.launch(arrayOf("*/*"))
                                        }
                                    }
                                }
                            }
                        },
                    )
                    if (showImportConfirm) {
                        AVBoxAlertDialog(
                            onDismissRequest = { showImportConfirm = false },
                            title = { Text(stringResource(R.string.settings_backup_confirm_title)) },
                            text = { Text(stringResource(R.string.settings_backup_confirm_text)) },
                            dismissButton = {
                                TextButton(onClick = { showImportConfirm = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showImportConfirm = false
                                    val uri = pendingImportUri
                                    val file = pendingImportFile
                                    pendingImportUri = null
                                    pendingImportFile = null
                                    when {
                                        file != null -> vm.importBackupFile(file) { result ->
                                            if (result is SyncBackup.ImportResult.Success) {
                                                PendingRestart.mark()
                                                showRestartPrompt = true
                                            } else {
                                                toastImportResult(context, result)
                                            }
                                        }
                                        uri != null -> vm.importBackup(context, uri) { result ->
                                            if (result is SyncBackup.ImportResult.Success) {
                                                PendingRestart.mark()
                                                showRestartPrompt = true
                                            } else {
                                                toastImportResult(context, result)
                                            }
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.common_confirm))
                                }
                            },
                        )
                    }
                }

                if (logEntryVisible) {
                    SettingsCard(SettingsCardPosition.MIDDLE) {
                        SettingsRow(
                            title = stringResource(R.string.settings_logs_title),
                            subtitle = stringResource(R.string.settings_logs_subtitle),
                            iconRes = R.drawable.ic_settings_log,
                            onClick = { LogViewActivity.start(context) },
                        )
                    }
                }

                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about),
                        subtitle = stringResource(R.string.settings_about_subtitle),
                        iconRes = R.drawable.ic_settings_about,
                        onClick = { aboutSheet = true },
                    )
                }

                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_github),
                        subtitle = stringResource(R.string.settings_github_subtitle),
                        iconRes = R.drawable.ic_settings_github,
                        onClick = { openExternalUrl(context, GITHUB_REPO_URL) },
                    )
                }
            }
        }
    }

    if (showBackupPicker) {
        BackupPickerSheet(
            backups = backups,
            onDismiss = { showBackupPicker = false },
            onPick = { file ->
                showBackupPicker = false
                pendingImportFile = file
                pendingImportUri = null
                showImportConfirm = true
            },
            onBrowseOther = {
                showBackupPicker = false
                importLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    if (aboutSheet) {
        AboutSheet(versionName = versionName, onDismiss = { aboutSheet = false })
    }

    if (showRestartPrompt) {
        RestartPromptDialog(R.string.settings_restart_after_backup) { showRestartPrompt = false }
    }

    val availableUpdate = updateState.available
    if (showUpdateDialog && availableUpdate != null) {
        UpdateAvailableDialog(
            info = availableUpdate,
            download = updateState.download,
            checking = updateState.checking,
            onUpdate = { UpdateManager.startDownload(context.applicationContext, availableUpdate) },
            onCancelDownload = { UpdateManager.cancelDownload() },
            onInstall = { file ->
                when (UpdateManager.install(context.applicationContext, file)) {
                    InstallResult.NeedPermission -> Toast.makeText(
                        context,
                        R.string.update_permission_needed,
                        Toast.LENGTH_LONG,
                    ).show()

                    InstallResult.Failed -> Toast.makeText(
                        context,
                        R.string.update_install_failed,
                        Toast.LENGTH_LONG,
                    ).show()

                    InstallResult.Started -> Unit
                }
            },
            onDismiss = { showUpdateDialog = false },
        )
    }
}

@Composable
private fun AboutSheet(versionName: String, onDismiss: () -> Unit) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_about),
        modifier = Modifier.fillMaxHeight(AboutSheetHeightFraction),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            if (versionName.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_about_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 恢复备份的选目录弹层：列出 AVBox/backup 里的 zip（旧版 AVBox/ 根下的也列出来），
 * 末端给一条「其它位置…」走系统选择器。不走 SAF 的 initial URI —— 部分机型忽略它。
 */
@Composable
private fun BackupPickerSheet(
    backups: List<BackupEntry>,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
    onBrowseOther: () -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_pick_title),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (backups.isEmpty()) {
            Text(
                text = stringResource(R.string.backup_pick_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            backups.forEachIndexed { index, entry ->
                SettingsCard(pickCardPosition(index, backups.size + 1)) {
                    SettingsRow(
                        title = entry.name,
                        subtitle = entry.detail,
                        onClick = { onPick(entry.file) },
                    )
                }
            }
            SettingsCard(pickCardPosition(backups.size, backups.size + 1)) {
                SettingsRow(
                    title = stringResource(R.string.backup_pick_other),
                    iconRes = R.drawable.ic_file_choose,
                    onClick = onBrowseOther,
                )
            }
        }
    }
}

private fun toastImportResult(context: Context, result: SyncBackup.ImportResult) {
    val msg = when (result) {
        is SyncBackup.ImportResult.Success -> context.getString(R.string.toast_backup_imported)
        is SyncBackup.ImportResult.Empty -> context.getString(R.string.toast_backup_empty)
        is SyncBackup.ImportResult.NeedPassword -> context.getString(R.string.toast_backup_need_password)
        is SyncBackup.ImportResult.Failed -> context.getString(R.string.toast_backup_import_failed, result.message)
    }
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

private const val AboutSheetHeightFraction = 0.6f

private val GITHUB_REPO_URL = "https://github.com/${UpdateChecker.REPO}"

private fun openExternalUrl(context: Context, url: String) {
    try {
        SpiderStorageNotice.suppress()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TextEditDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.dialog_api_url_hint)) },
            )
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onConfirm(text.trim()) } }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}
