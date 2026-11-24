package com.github.avbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.sync.SyncConfig
import com.github.avbox.osc.sync.SyncManager
import com.github.avbox.osc.sync.SyncResult
import com.github.avbox.osc.util.PendingRestart
import kotlinx.coroutines.launch

data class SyncSettingsState(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val encryptPassword: String = "",
    val autoEnabled: Boolean = false,
    val syncHistory: Boolean = true,
    val syncCollect: Boolean = true,
    val syncFollow: Boolean = true,
    val syncProgress: Boolean = true,
    val busy: Boolean = false,
    val testing: Boolean = false,
    val lastSyncAt: Long = 0L,
)

/** 同步页状态容器。网络操作在 ViewModel，UI 只读 state。 */
class SyncSettingsViewModel : ViewModel() {

    private val _state = mutableStateOf(load())
    val state: State<SyncSettingsState> = _state

    private fun load(): SyncSettingsState = SyncSettingsState(
        url = SyncConfig.url,
        user = SyncConfig.user,
        password = SyncConfig.password,
        encryptPassword = SyncConfig.encryptPassword,
        autoEnabled = SyncConfig.autoEnabled,
        syncHistory = SyncConfig.syncHistory,
        syncCollect = SyncConfig.syncCollect,
        syncFollow = SyncConfig.syncFollow,
        syncProgress = SyncConfig.syncProgress,
        lastSyncAt = SyncConfig.lastSyncAt,
    )

    fun refresh() {
        _state.value = load()
    }

    fun setUrl(value: String) {
        SyncConfig.url = value
        // 直接回显用户输入（getter 会剥 /AVBox 尾段，逐字输入到 "AVBox" 时会被吞掉）
        _state.value = _state.value.copy(url = value)
    }

    fun setUser(value: String) {
        SyncConfig.user = value
        _state.value = _state.value.copy(user = SyncConfig.user)
    }

    fun setPassword(value: String) {
        SyncConfig.password = value
        _state.value = _state.value.copy(password = SyncConfig.password)
    }

    fun setEncryptPassword(value: String) {
        SyncConfig.encryptPassword = value
        _state.value = _state.value.copy(encryptPassword = SyncConfig.encryptPassword)
    }

    fun setAuto(enabled: Boolean) {
        SyncConfig.autoEnabled = enabled
        _state.value = _state.value.copy(autoEnabled = enabled)
    }

    fun setItemHistory(value: Boolean) {
        SyncConfig.syncHistory = value
        _state.value = _state.value.copy(syncHistory = value)
    }

    fun setItemCollect(value: Boolean) {
        SyncConfig.syncCollect = value
        _state.value = _state.value.copy(syncCollect = value)
    }

    fun setItemFollow(value: Boolean) {
        SyncConfig.syncFollow = value
        _state.value = _state.value.copy(syncFollow = value)
    }

    fun setItemProgress(value: Boolean) {
        SyncConfig.syncProgress = value
        _state.value = _state.value.copy(syncProgress = value)
    }

    fun testConnection(context: Context) {
        if (!SyncConfig.configured) {
            toast(context, R.string.toast_sync_no_config)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true)
            val ok = SyncManager.testConnection()
            _state.value = _state.value.copy(testing = false)
            toast(context, if (ok) R.string.toast_sync_test_ok else R.string.toast_sync_test_fail)
        }
    }

    fun syncNow(context: Context) {
        if (!SyncConfig.configured) {
            toast(context, R.string.toast_sync_no_config)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = SyncManager.syncData()) {
                is SyncResult.Success -> {
                    _state.value = _state.value.copy(busy = false, lastSyncAt = result.at)
                    toast(context, R.string.toast_sync_success)
                }
                is SyncResult.Failure -> {
                    _state.value = _state.value.copy(busy = false)
                    toast(context, context.getString(R.string.toast_sync_failed, result.message), long = false)
                }
                else -> _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun uploadSettings(context: Context) {
        if (!SyncConfig.configured) {
            toast(context, R.string.toast_sync_no_config)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = SyncManager.uploadSettings()) {
                is SyncResult.Success -> {
                    _state.value = _state.value.copy(busy = false)
                    toast(context, R.string.toast_settings_uploaded)
                }
                is SyncResult.Failure -> {
                    _state.value = _state.value.copy(busy = false)
                    toast(context, context.getString(R.string.toast_sync_failed, result.message), long = false)
                }
                else -> _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun downloadSettings(context: Context, onApplied: () -> Unit = {}) {
        if (!SyncConfig.configured) {
            toast(context, R.string.toast_sync_no_config)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = SyncManager.downloadSettings()) {
                is SyncResult.Success -> {
                    _state.value = _state.value.copy(busy = false)
                    PendingRestart.mark()
                    onApplied()
                }
                is SyncResult.Failure -> {
                    _state.value = _state.value.copy(busy = false)
                    if (result.message == "No remote settings") {
                        toast(context, R.string.toast_settings_none_remote)
                    } else {
                        toast(context, context.getString(R.string.toast_sync_failed, result.message), long = false)
                    }
                }
                else -> _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun toast(context: Context, res: Int, long: Boolean = true) {
        Toast.makeText(context, context.getString(res), if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun toast(context: Context, text: String, long: Boolean = true) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
