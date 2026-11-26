package com.github.avbox.osc.ui.download

import android.content.Context
import android.content.Intent

import com.github.avbox.osc.R
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge

import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider

class DownloadActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java))
        }
    }

    private val vm: DownloadViewModel by lazy {
        ViewModelProvider(this)[DownloadViewModel::class.java]
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                DownloadScreen(vm = vm, onNavigateBack = { finish() })
            }
        }
    }
}
