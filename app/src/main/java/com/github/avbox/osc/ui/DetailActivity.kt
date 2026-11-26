package com.github.avbox.osc.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.DownloadKind
import com.github.avbox.osc.bean.DownloadRequest
import com.github.avbox.osc.download.DownloadManager
import com.github.avbox.osc.download.DownloadSettings
import com.github.avbox.osc.download.DownloadStorage
import com.github.avbox.osc.download.DownloaderTarget
import com.github.avbox.osc.download.ExternalDownloader
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.download.DownloadMethodPrompt
import com.github.avbox.osc.ui.download.IdmHeaderConfirmPrompt
import com.github.avbox.osc.player.PageHost
import com.github.avbox.osc.player.PictureInPictureHelper
import com.github.avbox.osc.player.PlaybackController
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.ui.player.PlayContainer
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PermissionHelper
import com.github.avbox.osc.util.SpiderStorageNotice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

/** 小窗开着时每 500ms 核对一次系统小窗状态（关小窗不保证有生命周期回调）。 */
private const val PIP_WATCH_INTERVAL_MS = 500L

/** 连续两次看到"不在小窗且没回前台"才判定小窗被关闭。 */
private const val PIP_GONE_STRIKES = 2

/** 小窗进出前后这段时间内不做"横屏即全屏"的自动切换（小窗窗口本身是横的）。 */
private const val PIP_ROTATION_GRACE_MS = 1500L

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set
    private var appliedFullscreen = false
    private var pendingEpisodeSync = false
    private var videoSizeWatchArmed = false

    /** 详情页批量下载只借用播放内核解析地址，解析期间隐藏小窗播放器。 */
    var playContainerHidden by mutableStateOf(false)
        private set

    /** 详情页下载方式选择弹窗；null 表示无待选择项。 */
    internal var downloadMethodPrompt by mutableStateOf<DownloadMethodPrompt?>(null)
        private set

    /** 已选外部下载器但链接需要请求头时的二次确认。 */
    internal var idmHeaderConfirmPrompt by mutableStateOf<IdmHeaderConfirmPrompt?>(null)
        private set

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (vm.fullScreen.value) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = vm.fullScreen.value

    private var statusBarIconsLight = false

    fun setStatusBarIconsLight(light: Boolean) {
        if (statusBarIconsLight == light) return
        statusBarIconsLight = light
        applyStatusBarAppearance()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = statusBarIconsLight
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (vm.enteringFullscreen.value) {
                    vm.cancelEntrySlide()
                    container?.stopForExitFullscreen()
                    return
                }
                if (vm.exitingFullscreen.value) return
                if (vm.fullScreen.value) {
                    if (container != null && container.onBackPressed()) return
                    // 退出全屏回小窗：视频继续播放，不调用 stopForExitFullscreen（那会暂停并释放播放）
                    vm.onFullScreenToggleRequested(false, playbackFacts())
                    container?.hideTipOnUiThread()
                } else {
                    // 悬浮（小窗）播放器开着时，X/返回只关闭播放器，留在详情页
                    if (container != null) {
                        closeInlinePlayer()
                        return
                    }
                    if (vm.backToPreviousTarget()) {
                        pendingEpisodeSync = false
                        return
                    }
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        // 打开新的详情页 = 新的播放页：把小窗连同它的播放一起停掉，避免两个页面抢同一个内核
        PictureInPictureHelper.closeHostOnNewPlayerPage()
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    DetailScreen(activity = this, vm = vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEpisodeSync = false
        vm.onFullScreenToggleRequested(false, playbackFacts())
        vm.dismissEpisodeSheet()
        playContainer?.cancelCastWait()
        playContainer?.endCastPrepare()
        playContainer?.uiState()?.clearOverlays()
        playContainer?.hideTipOnUiThread()
        vm.pushTargetFromIntent(intent)
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.pageHostRef = this
                it.setPreviewMode(true)
                it.qualitySelectedListener = PlayContainer.OnQualitySelectedListener { position -> vm.onQualitySelectionAccepted(position) }
            }
        }
        return requireNotNull(playContainer) { "play container not created" }
    }

    fun preheatWebView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { preheatWebView() }
            return
        }
        if (!webViewPreheated.compareAndSet(false, true)) return
        val startedAt = System.nanoTime()
        try {
            WebView(this).destroy()
            AppLog.i("echo-webview-prewarm: " + (System.nanoTime() - startedAt) / 1_000_000L + "ms")
        } catch (th: Throwable) {
            AppLog.e("DetailActivity", th)
        }
    }

    fun playbackFacts(): DetailPlaybackFacts = DetailPlaybackFacts(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    /** 关闭悬浮（小窗）播放器：停止并释放容器，留在详情页。 */
    fun closeInlinePlayer() {
        val container = playContainer ?: return
        container.cancelCastWait()
        container.endCastPrepare()
        if (container.hasClaimedPlayback()) container.stopForExitFullscreen()
        releasePlayContainer()
        AppLog.i("echo-detail: inline player closed")
    }

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    override fun onPlayerFullscreenRequest() {
        if (vm.fullScreen.value || vm.enteringFullscreen.value || vm.exitingFullscreen.value) return
        vm.onFullScreenToggleRequested(true, playbackFacts())
    }

    /** 正在画中画中：此时 Activity 仍会走 onPause，但不能暂停视频。 */
    private var inPictureInPicture = false

    /**
     * 本次前台周期内进过小窗。关小窗时系统不保证回调 onPictureInPictureModeChanged(false)，
     * 也不保证先让本页走 onStop（小窗浮在 App 上方时本页早就不是前台了），
     * 所以除了 onStop，还要靠 [pipWatch] 盯系统的小窗状态。展开回页面时由 onResume 清掉。
     */
    private var pipSession = false

    /** 已经确认真的进过小窗（避免把"系统还没切进小窗"误判成退出）。 */
    private var pipConfirmed = false

    /** 连续多次看到"已不在小窗且没回到前台"才判定被关闭，避开展开过程的瞬时状态。 */
    private var pipGoneStrikes = 0

    /** 进小窗前是否全屏：从小窗回来要恢复成同一种状态，别把人卡在全屏里。 */
    private var pipWasFullscreen = false

    /** 已退出小窗、等页面回到前台时再恢复窗口状态（避免在没显示的页面上跑动画卡住）。 */
    private var pipRestorePending = false

    /** 退出小窗的时刻，用来给"横屏即全屏"的自动切换设一段豁免期。 */
    private var pipExitedAt = 0L

    private fun isInPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    /**
     * 小窗开着时的核对循环：确认进过小窗、后来又不在小窗里、也没有回到前台，
     * 才算小窗被用户关掉（小窗浮在 App 上方时，用户继续切换界面/点视频都不受影响）。
     */
    private val pipWatch = object : Runnable {
        override fun run() {
            if (!pipSession || isFinishing || isDestroyed) return
            when {
                isInPip() -> {
                    pipConfirmed = true
                    pipGoneStrikes = 0
                }
                // 刚请求进小窗、系统还没落地：先不判
                !pipConfirmed -> Unit
                // 回到页面了（小窗被展开）：本次小窗会话结束
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                    pipSession = false
                    return
                }
                // 熄屏/锁屏时会走 onStop，这时状态也可能是"不在小窗"，不能当成被关闭
                isScreenInteractive() -> {
                    pipGoneStrikes++
                    if (pipGoneStrikes >= PIP_GONE_STRIKES) {
                        AppLog.i("echo-pip: watch -> dismissed")
                        onPipDismissed()
                        return
                    }
                }
            }
            window.decorView.postDelayed(this, PIP_WATCH_INTERVAL_MS)
        }
    }

    private fun startPipWatch() {
        window.decorView.removeCallbacks(pipWatch)
        window.decorView.postDelayed(pipWatch, PIP_WATCH_INTERVAL_MS)
    }

    private fun canEnterPictureInPicture(): Boolean {
        if (!PictureInPictureHelper.isSupported(this)) return false
        val container = playContainer ?: return false
        return container.isPlaybackActive() && !isAudioContent()
    }

    override fun onPictureInPictureRequest() {
        enterPictureInPicture()
    }

    private fun enterPictureInPicture() {
        if (!canEnterPictureInPicture()) {
            if (!PictureInPictureHelper.isSupported(this)) {
                Toast.makeText(this, R.string.toast_pip_unsupported, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val wasFullscreen = vm.fullScreen.value
        val container = requireNotNull(playContainer)
        val rect = PictureInPictureHelper.sourceRectHint(container.kernelView?.playerContainer())
        val playing = container.kernelView?.isPlaying == true
        val ui = container.uiState()
        ui.updatePictureInPicture(true)
        if (PictureInPictureHelper.enter(this, playing, rect)) {
            inPictureInPicture = true
            pipSession = true
            pipConfirmed = false
            pipGoneStrikes = 0
            pipWasFullscreen = wasFullscreen
            pipRestorePending = false
            startPipWatch()
            AppLog.i("echo-pip: enter from detail fullscreen=" + wasFullscreen)
        } else {
            ui.updatePictureInPicture(false)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!PlaybackSettingsState.config.pictureInPictureAuto) return
        if (inPictureInPicture || isInPip()) return
        enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        playContainer?.uiState()?.updatePictureInPicture(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            pipSession = true
            pipConfirmed = true
            pipGoneStrikes = 0
            startPipWatch()
            playContainer?.uiState()?.clearOverlays()
        } else {
            PictureInPictureHelper.unregisterHost(this)
            // 记录退出时刻：小窗带来的那次横屏配置不能把页面顶进全屏
            pipExitedAt = SystemClock.uptimeMillis()
            pipRestorePending = true
        }
        // 退出小窗后的窗口状态恢复放在 onResume（页面真的回到前台时再切，避免动画卡住）
    }

    /**
     * 小窗被用户关闭（或本页被系统收走）：不在这里收尾就会留下
     * "小窗没了、声音还在、又回不到播放页"的幽灵播放。
     */
    override fun onStop() {
        super.onStop()
        if (!pipSession || isChangingConfigurations || isFinishing) return
        val screenOn = isScreenInteractive()
        AppLog.i("echo-pip: stop in pip, inPip=" + isInPip() + " screenOn=" + screenOn)
        if (!screenOn) {
            // 熄屏/锁屏同样会走 onStop，但这不是关闭小窗：只暂停，页面和小窗保留
            playContainer?.hostPause()
            return
        }
        if (isInPip()) {
            // 小窗还在屏幕上（浮在 App 上方、用户继续操作时本页不是前台）：不是关闭，
            // 交给核对循环，真被关掉时会自己收尾
            startPipWatch()
            return
        }
        onPipDismissed()
    }

    /** 小窗已被关闭：停播并结束本页，别让播放器和页面留在后台当幽灵。 */
    private fun onPipDismissed() {
        if (!pipSession) return
        pipSession = false
        pipConfirmed = false
        pipGoneStrikes = 0
        pipRestorePending = false
        pipWasFullscreen = false
        inPictureInPicture = false
        val container = playContainer
        // 小窗浮在 App 上方时，用户可能已经开了别的视频、内核被新页面接手：
        // 只有内核还挂在本页时才去停播，否则只收本页，不能停别人正在放的内容
        val ownedByThisPage = container != null && PlaybackService.peek()?.attachedPage() === container
        if (!ownedByThisPage) {
            AppLog.i("echo-pip: dismissed, engine owned by other page")
            container?.cancelCastWait()
            releasePlayContainer()
        } else {
            closeInlinePlayer()
        }
        if (!isFinishing) finish()
    }

    /** 屏幕是否亮着：熄屏时不能把小窗的 onStop 当成"小窗被关闭"。 */
    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
        applyInlineOrientationFreedom()
    }

    /** 小窗模式：播放期间放开方向锁，允许横屏触发全屏（见 onConfigurationChanged）。 */
    private fun applyInlineOrientationFreedom() {
        if (PlaybackSettingsState.config.detailPlayFullscreen) return
        if (vm.fullScreen.value || vm.enteringFullscreen.value) return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun ensurePlaying(): PlayContainer {
        val container = ensurePlayContainer()
        if (!engineOwnsDetailContent()) {
            playCurrent()
        } else {
            container.ensurePlaybackActive()
        }
        return container
    }

    /**
     * 引擎里已经装着内容、但装的是别的影片时（典型场景：在影片 A 的音乐页返回首页，
     * 再打开全新影片 B），不能直接复用，否则音乐页拿到的是 A 的海报与内容。
     */
    private fun engineOwnsDetailContent(): Boolean {
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return false
        val info = vm.vodInfo ?: return false
        return contentMatches(playing, info)
    }

    private fun contentMatches(playing: VodInfo, info: VodInfo): Boolean {
        if (playing.id != info.id) return false
        // 首页复用同一播放器实例时 controller 的 sourceKey 可能取自上次会话，
        // 因此只在本页确实用过该源时才要求源一致，避免把同一影片误判成新内容。
        val expectedSource = vm.firstsourceKey
        return expectedSource.isEmpty()
            || playing.sourceKey == expectedSource
            || vm.sourceKey == expectedSource
    }

    fun openCast() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.beginCastPrepare()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.endCastPrepare()
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        container.setData(session)
        container.beginCastWait()
    }

    fun startEpisodeDownload(indices: List<Int>) {
        if (indices.isEmpty()) return
        val engine = PlaybackService.peek()
        // 只在“真的正在播放”时拦截；暂停/已结束/仅解析会话都允许下载。
        if (engine != null && !engine.isReleased() && engine.headlessBridge().isPlaying()) {
            Toast.makeText(this, getString(R.string.download_requires_stop_playback), Toast.LENGTH_SHORT).show()
            return
        }
        val targets = ExternalDownloader.installedTargets(this)
        val idmAvailable = indices.size == 1 && targets.isNotEmpty()
        when {
            idmAvailable && DownloadSettings.method == DownloadSettings.METHOD_ASK -> {
                downloadMethodPrompt = DownloadMethodPrompt(targets.first().label) { choice ->
                    downloadMethodPrompt = null
                    if (choice != DownloadMethodPrompt.METHOD_CANCEL) {
                        beginEpisodeDownload(indices, choice, targets)
                    }
                }
            }
            idmAvailable && DownloadSettings.method == DownloadSettings.METHOD_IDM ->
                beginEpisodeDownload(indices, DownloadSettings.METHOD_IDM, targets)
            else -> beginEpisodeDownload(indices, DownloadSettings.METHOD_BUILTIN, targets)
        }
    }

    private fun beginEpisodeDownload(
        indices: List<Int>,
        method: Int,
        targets: List<DownloaderTarget>,
    ) {
        PermissionHelper.requestNotificationIfNeeded(this)
        playContainerHidden = true
        val container = ensurePlayContainer()
        lifecycleScope.launch {
            try {
                runDownloadBatch(container, indices, method, targets)
            } finally {
                if (playContainer === container && !container.hasClaimedPlayback()) {
                    closeInlinePlayer()
                }
                playContainerHidden = false
            }
        }
    }

    private suspend fun askIdmWithHeaders(): Boolean = suspendCancellableCoroutine { cont ->
        idmHeaderConfirmPrompt = IdmHeaderConfirmPrompt { keepIdm ->
            idmHeaderConfirmPrompt = null
            if (cont.isActive) cont.resume(keepIdm)
        }
        cont.invokeOnCancellation { idmHeaderConfirmPrompt = null }
    }

    private suspend fun runDownloadBatch(
        container: PlayContainer,
        indices: List<Int>,
        method: Int,
        targets: List<DownloaderTarget>,
    ) {
        var added = 0
        var failed = 0
        indices.forEachIndexed { position, index ->
            val session = vm.selectEpisodeForDownload(index)
            val resolved = if (session == null) null else container.resolveForDownload(session)
            if (session == null || resolved == null) {
                failed++
            } else {
                val vod = session.vod()
                val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
                var useIdm = method == DownloadSettings.METHOD_IDM
                if (useIdm && resolved.headers.isNotEmpty()) {
                    useIdm = askIdmWithHeaders()
                }
                var handled = false
                if (useIdm) {
                    val ext = DownloadStorage.extensionFromUrl(resolved.url) ?: "mp4"
                    val fileName = DownloadStorage.buildFileName(vod.name, series?.name, vod.name, ext)
                    handled = ExternalDownloader.launch(
                        this@DetailActivity,
                        resolved.url,
                        fileName,
                        targets.first(),
                    )
                }
                if (!handled) {
                    runCatching {
                        DownloadManager.enqueue(
                            DownloadRequest(
                                url = resolved.url,
                                headers = resolved.headers,
                                kindHint = if (resolved.url.substringBefore('?')
                                        .substringBefore('#')
                                        .endsWith(".m3u8", ignoreCase = true)
                                ) {
                                    DownloadKind.HLS
                                } else {
                                    null
                                },
                                title = vod.name ?: "",
                                fileName = null,
                                sourceKey = session.sourceKey(),
                                vodId = vod.id,
                                vodName = vod.name,
                                playFlag = vod.playFlag,
                                episodeIndex = vod.playIndex,
                                episodeName = series?.name,
                                rawSeriesUrl = series?.url,
                            ),
                        )
                        added++
                    }.onFailure {
                        AppLog.e("DownloadManager", it)
                        failed++
                    }
                } else {
                    added++
                }
            }
            if (position < indices.lastIndex) delay(1200)
        }
        if (added > 0) {
            Toast.makeText(
                this@DetailActivity,
                getString(R.string.download_added_n, added),
                Toast.LENGTH_SHORT,
            ).show()
        }
        if (failed > 0) {
            Toast.makeText(
                this@DetailActivity,
                getString(R.string.download_resolve_failed, failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun musicPlaybackDetected(): Boolean {
        if (!UiSettingsState.config.musicAutoOpenPage) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().playState
        val accepted = state == PlayState.PREPARING ||
            state == PlayState.PREPARED ||
            state == PlayState.BUFFERING ||
            state == PlayState.BUFFERED ||
            state == PlayState.PLAYING
        return accepted && isAudioContent()
    }

    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isAudioOnlyContent()
    }

    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (isIsoPlayback()) {
            Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        // 先按纯音频启动，避免详情页这一帧就把视频解码器建起来（交接后无法回收）。
        prepareAudioOnlyForMusicPage()
        ensurePlaying()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIsoPlayback(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        if (!engineOwnsDetailContent()) return false
        return MediaSources.isoHeaders(controller.webHeaderMap()) || controller.isIsoEscalated()
    }

    private fun prepareAudioOnlyForMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(true)
    }

    private fun restoreVideoAfterMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(false)
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: run {
            restoreVideoAfterMusicPage()
            return false
        }
        val playing = PlaybackService.peek()?.controller()?.vod()
        if (playing == null) {
            restoreVideoAfterMusicPage()
            return false
        }
        // 引擎里若还装着别的影片，先切到本页内容，避免音乐页显示上一部影片。
        val info = vm.vodInfo
        if (info != null && !contentMatches(playing, info)) {
            AppLog.i("echo-music handoff switch content: " + playing.id + " -> " + info.id)
            restoreVideoAfterMusicPage()
            playCurrent()
        }
        prepareAudioOnlyForMusicPage()
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        vm.syncEpisodeAfterMusicReturn()
    }

    fun applyFullscreen(full: Boolean) {
        if (appliedFullscreen == full) return
        appliedFullscreen = full
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 横屏状态下进入全屏保持横屏，避免被竖屏策略拉回去再翻转一次
        requestedOrientation = if (full && landNow) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            orientationPolicyValue()
        }
        if (full) {
            armVideoSizeWatch()
            hideSysBar()
        } else {
            videoSizeWatchArmed = false
            playContainer?.videoSizeReadyListener = null
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    private fun armVideoSizeWatch() {
        val container = playContainer ?: return
        if (container.hasVideoSize()) {
            applyVideoOrientation(container.isPortraitVideo())
            return
        }
        if (videoSizeWatchArmed) return
        videoSizeWatchArmed = true
        container.videoSizeReadyListener = { portraitVideo -> onVideoSizeReady(portraitVideo) }
    }

    private fun onVideoSizeReady(portraitVideo: Boolean) {
        if (!videoSizeWatchArmed) return
        videoSizeWatchArmed = false
        playContainer?.videoSizeReadyListener = null
        applyVideoOrientation(portraitVideo)
    }

    private fun applyVideoOrientation(portraitVideo: Boolean) {
        if (!vm.fullScreen.value || portraitVideo) return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        settleRotationAfterExit()
        // 小窗进出本身就会换来一次横屏配置（小窗窗口是横的），那不是用户把手机横过来，
        // 不能因此把页面顶进全屏，否则从小窗回来会被卡在全屏里退不出去。
        if (pipRotationGraceActive()) return
        // 小窗播放中横屏 → 切到全屏播放
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            !vm.fullScreen.value && !vm.enteringFullscreen.value &&
            playContainer?.isPlaybackActive() == true
        ) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            vm.onFullScreenToggleRequested(true, playbackFacts())
        }
    }

    /** 小窗期间、以及刚退出小窗的一小段时间内不做"横屏即全屏"的自动切换。 */
    private fun pipRotationGraceActive(): Boolean {
        if (pipSession) return true
        return pipExitedAt > 0 && SystemClock.uptimeMillis() - pipExitedAt < PIP_ROTATION_GRACE_MS
    }

    fun settleRotationAfterExit() {
        if (!vm.rotating.value) return
        vm.settleRotation()
        syncFullBoxSideEffects()
    }

    fun setPlayerTouchBlocked(blocked: Boolean) {
        playContainer?.touchBlocked = blocked
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DetailFullScreenFrame.playerFullScreen(vm.rotating.value, vm.fullScreen.value, landNow)
    }

    private fun syncFullBoxSideEffects() {
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        // 小窗被展开回页面（或普通返回前台）时本次小窗会话结束；仍在小窗里则保留标记，
        // 熄屏后再亮屏也走这里，不能把还没被关闭的小窗当成已结束。
        if (!isInPip()) {
            pipSession = false
            pipConfirmed = false
            pipGoneStrikes = 0
            // 小窗已经不在：保证页面回到正常布局（个别机型退出小窗不一定回调模式切换）
            playContainer?.uiState()?.updatePictureInPicture(false)
        }
        applyStatusBarAppearance()
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
        restoreAfterPipState()
        val container = playContainer ?: return
        vm.applyPlaybackEntry(
            DetailPlaybackEntry.MusicReturn,
            resumable = container.hasClaimedPlayback() && !container.ownsEngineContent() && container.isPlaybackActive(),
        )
    }

    /**
     * 从小窗回来时恢复进小窗前的大小窗状态：进小窗前是页内小窗，回来就别停在全屏
     * （小窗窗口本身是横的，切回来会被"横屏即全屏"顶成全屏，人就卡在那了）。
     */
    private fun restoreAfterPipState() {
        if (!pipRestorePending) return
        pipRestorePending = false
        val wasFullscreen = pipWasFullscreen
        pipWasFullscreen = false
        if (isFinishing || isDestroyed) return
        AppLog.i(
            "echo-pip: back from pip wasFullscreen=" + wasFullscreen
                + " full=" + vm.fullScreen.value
                + " orientation=" + resources.configuration.orientation,
        )
        if (wasFullscreen) return
        if (!vm.fullScreen.value || vm.exitingFullscreen.value || vm.enteringFullscreen.value) return
        AppLog.i("echo-pip: restore page state after pip (exit fullscreen)")
        vm.onFullScreenToggleRequested(false, playbackFacts())
        playContainer?.hideTipOnUiThread()
    }

    override fun onPause() {
        // 进入画中画时 Activity 仍会走 onPause，此时不能暂停视频，否则小窗是静止画面
        if (!inPictureInPicture && !isInPip()) {
            playContainer?.hostPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(pipWatch)
        PictureInPictureHelper.unregisterHost(this)
        playContainer?.cancelCastWait()
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }

    companion object {
        private val webViewPreheated = AtomicBoolean(false)
    }
}
