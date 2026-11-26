@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.bean.EpgInfo
import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.bean.LiveSettingGroup
import com.github.avbox.osc.player.KernelDecision
import com.github.avbox.osc.player.KernelReusePolicy
import com.github.avbox.osc.player.LivePlayerManager
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.PictureInPictureHelper
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.player.controller.ComposeLiveController
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.settings.LiveSettingsState
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LivePlayActivity : BaseActivity() {

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L

        /** 小窗开着时每 500ms 核对一次系统小窗状态（关小窗不保证有生命周期回调）。 */
        private const val PIP_WATCH_INTERVAL_MS = 500L

        /** 连续两次看到"不在小窗且没回前台"才判定小窗被关闭。 */
        private const val PIP_GONE_STRIKES = 2

        /** 直播小窗关闭后等系统把小窗窗口收完再释放内核：在收窗过渡里拆 SurfaceView 会闪退。 */
        private const val PIP_LIVE_RELEASE_DELAY_MS = 700L

        private val pipReleaseHandler = Handler(Looper.getMainLooper())

        /**
         * 当前真正持有直播内核的页面。小窗浮在 App 上方时，用户可能又开一个直播/视频页，
         * 内核会被新页面接手，收尾（exitLive）只能由持有者做。
         */
        private var currentLivePage: WeakReference<LivePlayActivity>? = null
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    private val vm: LivePlayViewModel by viewModels()

    private val uiState: StateFlow<LivePlayUiState> get() = vm.state

    private val fullScreen: Boolean get() = vm.state.value.frame.fullScreen
    private val currentChannelGroupIndex: Int get() = vm.state.value.channelList.playingGroupIndex
    private val currentLiveChannelIndex: Int get() = vm.state.value.channelList.playingChannelIndex
    private val currentLiveChannelItem: LiveChannelItem? get() = vm.state.value.channelList.playingChannel
    private val selectedChannelGroupIndex: Int get() = vm.state.value.channelList.tappedGroupIndex
    private var kernelView: PlayerKernelView? = null
    private var liveController: ComposeLiveController? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var sourceSwitchJob: Job? = null
    private val livePlayerManager = LivePlayerManager()
    private var exitingLivePlay = false
    private var nowday = Date()

    private val epgController = LiveEpgController(object : LiveEpgController.Host {
        override fun currentChannel(): LiveChannelItem? = currentLiveChannelItem

        override fun currentChannelHasLogo(): Boolean = !vm.state.value.epg.logoUrl.isNullOrEmpty()

        override fun onEpgListChanged(list: ArrayList<EpgInfo>) {
            vm.setEpgList(list)
        }

        override fun onEpgSettled() {
            overlay.updateChannelInfoUi()
        }
    })

    private val liveHost = object :
        LiveOverlayController.Host,
        LiveCatchupController.Host,
        LiveChannelSourceLoader.Host {

        override fun text(resId: Int, vararg args: Any): String = getString(resId, *args)

        override fun videoView(): PlayerKernelView? = kernelView

        override fun cachedEpg(channelName: String): List<EpgInfo>? = epgController.cachedEpg(channelName)

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun liveChannelHeader(): HashMap<String, String>? = this@LivePlayActivity.liveChannelHeader()

        override fun updateLogoUrl(url: String?) {
            vm.updateLogoUrl(url)
        }

        override fun loadEpgAfterChannelStarted() {
            epgController.loadAfterChannelStarted()
        }

        override fun initPlayer(view: PlayerKernelView) {
            livePlayerManager.init(view)
        }

        override fun initCatchup() {
            catchupController.initLiveObj()
        }

        override fun cancelEpgPending() {
            epgController.cancelPending()
        }

        override fun cancelSourceSwitch() = cancelSourceSwitchCountdown()

        override fun isNeedInputPassword(groupIndex: Int): Boolean = this@LivePlayActivity.isNeedInputPassword(groupIndex)

        override fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
            this@LivePlayActivity.selectChannelGroup(groupIndex, liveChannelIndex)
        }

        override fun initLiveSettingGroupList() {
            this@LivePlayActivity.initLiveSettingGroupList()
        }

        override fun onSettingsInputsChanged() {
            this@LivePlayActivity.onSettingsInputsChanged()
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    internal lateinit var overlay: LiveOverlayController

    internal lateinit var catchupController: LiveCatchupController

    internal lateinit var channelSourceLoader: LiveChannelSourceLoader

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = fullScreen

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        vm.attachSettingsSource(settingSource)
        overlay = LiveOverlayController(vm, liveHost, handler, lifecycleScope)
        catchupController = LiveCatchupController(vm, liveHost, overlay)
        channelSourceLoader = LiveChannelSourceLoader(vm, liveHost, overlay)
        syncLandscapeNow()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    vm.state.value.epg.sheetVisible -> vm.hideEpgSheet()
                    vm.state.value.settings.sheetVisible -> vm.hideSettingsSheet()
                    fullScreen -> applyFullscreen(false)
                    vm.state.value.timeshift.isBackState -> catchupController.backToLiveFromEpg()
                    else -> {
                        exitingLivePlay = true
                        finish()
                    }
                }
            }
        })
        epgController.reloadAddress()
        nowday = Date()
        epgController.epgDayPresented = FORMAT_DATE1.format(nowday)
        // 先接管播放内核（本页成为持有者），再收掉旧小窗：这样旧页收尾时不会再去拆内核
        initVideoView()
        // 打开直播页 = 新的播放页：把小窗连同它的播放一起停掉
        PictureInPictureHelper.closeHostOnNewPlayerPage()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    val state by uiState.collectAsStateWithLifecycle()
                    LiveScreen(
                        state = state,
                        actions = remember { liveActions() },
                        pictureInPicture = pipUiVisible.value,
                    )
                }
            }
        }
        channelSourceLoader.initLiveChannelList()
        initLiveSettingGroupList()
    }

    override fun onResume() {
        super.onResume()
        // 小窗被展开回页面（或普通返回前台）时本次小窗会话结束；仍在小窗里则保留标记
        if (!isInPip()) {
            pipSession = false
            pipConfirmed = false
            pipGoneStrikes = 0
        }
        applyStatusBarAppearance()
        exitingLivePlay = false
        // 回到前台的这一页就是直播内核的实际持有者（小窗里的旧页面被收尾时不能动它）
        currentLivePage = WeakReference(this)
        val takenOverByVod = PlaybackService.peek()?.enterLiveState() ?: false
        rebindLiveControllerIfNeeded()
        if (takenOverByVod) {
            replayCurrentChannelAfterTakeover()
        } else {
            kernelView?.resume()
        }
    }

    /** 正在画中画中：onPause 仍会触发，但不能暂停直播画面。 */
    private var inPictureInPicture = false

    /**
     * 本次前台周期内进过小窗。关小窗时系统不保证回调 onPictureInPictureModeChanged(false)，
     * 也不保证先让本页走 onStop（小窗浮在 App 上方时本页早就不是前台了），
     * 所以除了 onStop，还要靠 [pipWatch] 盯系统的小窗状态。
     */
    private var pipSession = false

    /** 已经确认真的进过小窗（避免把"系统还没切进小窗"误判成退出）。 */
    private var pipConfirmed = false

    /** 连续多次看到"已不在小窗且没回前台"才判定被关闭。 */
    private var pipGoneStrikes = 0

    /** 已经安排"等小窗窗口收完再释放内核"，onDestroy 就不要再立刻拆一次。 */
    private var liveReleaseDeferred = false

    /** 驱动 Compose 隐藏直播播放器上的常驻按钮。 */
    private val pipUiVisible = mutableStateOf(false)

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
                !pipConfirmed -> Unit
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                    pipSession = false
                    return
                }
                isScreenInteractive() -> {
                    pipGoneStrikes++
                    if (pipGoneStrikes >= PIP_GONE_STRIKES) {
                        AppLog.i(TAG + " watch -> pip dismissed")
                        onPipDismissed()
                        return
                    }
                }
            }
            handler.postDelayed(this, PIP_WATCH_INTERVAL_MS)
        }
    }

    private fun startPipWatch() {
        handler.removeCallbacks(pipWatch)
        handler.postDelayed(pipWatch, PIP_WATCH_INTERVAL_MS)
    }

    private fun canEnterPictureInPicture(): Boolean =
        PictureInPictureHelper.isSupported(this) && kernelView?.isPlaying == true

    private fun enterPictureInPicture() {
        if (!canEnterPictureInPicture()) {
            if (!PictureInPictureHelper.isSupported(this)) {
                Toast.makeText(this, R.string.toast_pip_unsupported, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val rect = PictureInPictureHelper.sourceRectHint(kernelView?.playerContainer())
        pipUiVisible.value = true
        if (PictureInPictureHelper.enter(this, true, rect)) {
            inPictureInPicture = true
            pipSession = true
            pipConfirmed = false
            pipGoneStrikes = 0
            startPipWatch()
            AppLog.i(TAG + " enter picture-in-picture")
        } else {
            pipUiVisible.value = false
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
        pipUiVisible.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pipSession = true
            pipConfirmed = true
            pipGoneStrikes = 0
            startPipWatch()
        } else {
            PictureInPictureHelper.unregisterHost(this)
        }
        // 退出小窗后的关闭判定交给 pipWatch（展开回页面会先 onResume，标记被清掉）
    }

    /**
     * 小窗被用户关闭（或本页被系统收走）：直播被丢在后台就会一直抽流，
     * 再回直播页还会撞上半死的内核（卡顿后闪退）。
     */
    override fun onStop() {
        super.onStop()
        if (!pipSession || isChangingConfigurations || isFinishing) return
        val screenOn = isScreenInteractive()
        AppLog.i(TAG + " stop in pip, inPip=" + isInPip() + " screenOn=" + screenOn)
        if (!screenOn) {
            // 熄屏/锁屏同样会走 onStop，但这不是关闭小窗：只暂停，小窗与页面保留
            kernelView?.pause()
            return
        }
        if (isInPip()) {
            // 小窗还在屏幕上（浮在 App 上方、用户继续操作时本页不是前台）：不是关闭
            startPipWatch()
            return
        }
        onPipDismissed()
    }

    /** 小窗已被关闭：先停画面，等小窗窗口收完再释放内核，最后结束直播页。 */
    private fun onPipDismissed() {
        if (!pipSession) return
        pipSession = false
        pipConfirmed = false
        pipGoneStrikes = 0
        inPictureInPicture = false
        exitingLivePlay = true
        pipUiVisible.value = false
        // 小窗浮在 App 上方时，用户可能已经开了别的直播/视频、内核被新页面接手：
        // 这时只收本页，绝不能把别人正在放的内容拆掉
        val ownsKernel = currentLivePage?.get() === this && PlaybackService.peek()?.isLiveMode() == true
        if (ownsKernel) {
            val view = kernelView
            if (view != null) {
                try {
                    view.pause()
                } catch (t: Throwable) {
                    AppLog.e(TAG, t)
                }
            }
            scheduleLiveReleaseAfterPip()
        } else {
            AppLog.i(TAG + " pip dismissed, kernel owned by other page")
        }
        if (!isFinishing && !isDestroyed) finish()
    }

    /**
     * 推迟释放直播内核：小窗的收窗过渡期间拆 SurfaceView 会闪退，
     * 所以先静音、把窗口收完，再去释放；期间若有新直播页接手就什么都不做。
     */
    private fun scheduleLiveReleaseAfterPip() {
        if (liveReleaseDeferred) return
        liveReleaseDeferred = true
        AppLog.i(TAG + " pip dismissed, release live kernel deferred")
        val self = this
        pipReleaseHandler.postDelayed({
            val engine = PlaybackService.peek() ?: return@postDelayed
            if (engine.isReleased()) return@postDelayed
            val current = currentLivePage?.get()
            if (current != null && current !== self) {
                AppLog.i(TAG + " deferred release skipped: kernel taken by other page")
                return@postDelayed
            }
            currentLivePage = null
            try {
                engine.exitLive()
            } catch (t: Throwable) {
                AppLog.e(TAG, t)
            }
        }, PIP_LIVE_RELEASE_DELAY_MS)
    }

    /** 屏幕是否亮着：熄屏时不能把小窗的 onStop 当成"小窗被关闭"。 */
    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    override fun onPause() {
        super.onPause()
        if (!exitingLivePlay && !inPictureInPicture && !isInPip()) kernelView?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pipWatch)
        PictureInPictureHelper.unregisterHost(this)
        overlay.hideSwitchChannelSnapshot()
        // 小窗会话没收尾就被销毁：同样推迟释放，别在收窗过渡里拆内核
        if (pipSession) {
            scheduleLiveReleaseAfterPip()
        } else if (!liveReleaseDeferred && currentLivePage?.get() === this) {
            // 只有还持有直播内核的页面才停内核；内核被新页面接手时不能动它
            try {
                PlaybackService.peek()?.exitLive()
            } catch (t: Throwable) {
                AppLog.e(TAG, t)
            }
            currentLivePage = null
        }
        super.onDestroy()
        kernelView = null
        handler.removeCallbacksAndMessages(null)
        epgController.cancelAll()
        channelSourceLoader.cancelAll()
    }

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.listener = liveControlListener
        liveController = controller
        val view = PlaybackService.engine(this).also { it.enterLive(reuseLivePlayer = true) }.player()
        // 播放器视图是全 App 共用的：上一页（可能还在小窗里）还挂着它时，
        // 先摘下来，否则下面 Compose 的 AndroidView 会抛 "child already has a parent" 直接闪退。
        (view.parent as? ViewGroup)?.removeView(view)
        view.setVideoController(controller)
        view.setProgressSink(null)
        kernelView = view
        currentLivePage = WeakReference(this)
    }

    private fun rebindLiveControllerIfNeeded() {
        val view = kernelView ?: return
        val controller = liveController ?: return
        if (view.videoController !== controller) {
            view.setVideoController(controller)
            AppLog.i("echo-p4 re-bind live controller")
        }
    }

    private fun replayCurrentChannelAfterTakeover() {
        val item = currentLiveChannelItem ?: return
        val videoView = kernelView ?: return
        vm.setLookBackIndex(-1)
        vm.setShiyi(false)
        vm.setTimeshiftBack(false)
        vm.setOverlayVisible(false)
        overlay.stopTimeshiftTicker()
        overlay.hideSwitchChannelSnapshot()
        videoView.setUrl(item.urlAt(vm.currentSourceIndex()), liveChannelHeader())
        videoView.start()
        overlay.showResolutionAfterChannelSwitch()
        catchupController.loadEpgAfterChannelStarted()
    }

    internal fun releasePlayerKernel() {
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) eng.releasePlayer()
        else kernelView?.release()
    }

    private val liveControlListener = object : ComposeLiveController.LiveControlListener {
        override fun onSingleTap(): Boolean {
            if (fullScreen) {
                if (vm.toggleOverlay()) overlay.scheduleOverlayHide()
            } else {
                applyFullscreen(true)
            }
            return true
        }

        override fun onLongPress() {
            if (vm.state.value.timeshift.isBackState) {
                vm.setOverlayVisible(true)
                overlay.scheduleOverlayHide()
            } else {
                openSettingsSheet()
            }
        }

        override fun onPlayStateChanged(playState: PlayState) {
            vm.onPlayStateChanged(playState)
            handleAutoSourceSwitch(playState)
        }

        override fun onHorizontalFling(direction: Int) {
            if (direction > 0) playNext() else playPrevious()
        }

        override fun onGesturePercent(isBrightness: Boolean, percent: Int) {
            overlay.showGestureHint(isBrightness, percent)
        }
    }

    private fun handleAutoSourceSwitch(state: PlayState) {
        cancelSourceSwitchCountdown()
        when (state) {
            PlayState.IDLE, PlayState.PAUSED -> {}
            PlayState.PREPARED, PlayState.BUFFERED, PlayState.PLAYING -> {
                overlay.onPlaybackStarted()
                vm.resetSourceSwitchAttempts()
            }
            PlayState.ERROR, PlayState.COMPLETED -> {
                overlay.hideSwitchChannelSnapshot()
                startSourceSwitchCountdown(CONNECT_TIMEOUT_SWITCH_DELAY)
            }
            PlayState.PREPARING, PlayState.BUFFERING -> {
                startSourceSwitchCountdown((KV.get(KVKeys.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L)
            }
            else -> AppLog.i("echo-Unexpected live_play state: $state")
        }
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        vm.setRotating(full != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
        vm.setFullscreen(full)
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            orientationPolicyValue()
        }
        if (full) {
            vm.setOverlayVisible(true)
            overlay.scheduleOverlayHide()
            super.hideSysBar()
        } else {
            vm.setOverlayVisible(false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.setRotating(false)
        syncLandscapeNow()
        applyStatusBarAppearance()
    }

    fun isFullBox(): Boolean = vm.state.value.frame.isFullBox

    private fun syncLandscapeNow() {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        vm.setLandscapeNow(landNow)
    }

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem?.sourceNum == 1)
        ) {
            return true
        }
        val groupChannels = channelSourceLoader.getLiveChannels(channelGroupIndex)
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && kernelView?.isPlaying == true
        val previousLivePlayerType = livePlayerManager.livePlayerType
        if (!changeSource) {
            vm.setPlayingGroupIndex(channelGroupIndex)
            vm.setPlayingChannelIndex(liveChannelIndex)
            vm.setPlayingChannel(channelSourceLoader.getLiveChannels(currentChannelGroupIndex)?.get(currentLiveChannelIndex))
            vm.updateSourceIndex(vm.consumePendingSourceIndex())
            KV.put(KVKeys.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
            requestChannelScroll()
        }
        vm.setEpgChannelName(currentLiveChannelItem?.channelName.orEmpty())
        onSettingsInputsChanged()
        vm.setLookBackIndex(-1)
        vm.setShiyi(false)
        vm.setTimeshiftBack(false)
        vm.setOverlayVisible(false)
        overlay.stopTimeshiftTicker()
        val item = currentLiveChannelItem ?: return false
        item.include_back = canCurrentChannelCatchup()
        vm.setCanCatchup(item.include_back)
        overlay.updateChannelInfoUi()
        val videoView = kernelView
        if (videoView != null) {
            val rebuildKernel = videoView.consumeKernelRebuildRequired()
            val reusePlayer = KernelReusePolicy.decide(videoView.mediaPlayer != null, rebuildKernel, false,
                    canReusePlayer(previousLivePlayerType)) == KernelDecision.REUSE
            val keepExoFrame = reusePlayer
            if (showPreviousFrame && !keepExoFrame) {
                overlay.showSwitchChannelSnapshot()
            } else {
                overlay.hideSwitchChannelSnapshot()
            }
            val liveUrl = item.urlAt(vm.currentSourceIndex())
            if (reusePlayer) {
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.replay(true)
            } else {
                releasePlayerKernel()
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.start()
            }
            overlay.showResolutionAfterChannelSwitch()
        }
        catchupController.loadEpgAfterChannelStarted()
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val videoView = kernelView ?: return false
        return videoView.playState != PlayState.IDLE &&
                previousLivePlayerType == livePlayerManager.livePlayerType
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val next = nextChannel(1)
        playChannel(next[0], next[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val next = nextChannel(-1)
        playChannel(next[0], next[1], false)
    }

    private fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        val item = currentLiveChannelItem ?: return
        val num = item.sourceNum
        val next = if (num > 0) (vm.currentSourceIndex() + 1) % num else vm.currentSourceIndex() + 1
        vm.updateSourceIndex(next)
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    private fun startSourceSwitchCountdown(delayMs: Long) {
        sourceSwitchJob?.cancel()
        sourceSwitchJob = lifecycleScope.launch {
            delay(delayMs)
            val attempts = vm.bumpSourceSwitchAttempts()
            if (currentLiveChannelItem?.sourceNum == attempts) {
                vm.resetSourceSwitchAttempts()
                val next = nextChannel(if (LiveSettingsState.config.channelReverse) -1 else 1)
                playChannel(next[0], next[1], false)
            } else {
                playNextSource()
            }
        }
    }

    private fun cancelSourceSwitchCountdown() {
        sourceSwitchJob?.cancel()
        sourceSwitchJob = null
    }

    private fun nextChannel(direction: Int): IntArray {
        return LiveChannelNavigator.nextPosition(
            groups = vm.channelGroups(),
            currentGroupIndex = currentChannelGroupIndex,
            currentChannelIndex = currentLiveChannelIndex,
            direction = direction,
            crossGroup = LiveSettingsState.config.crossGroup,
            channelsOf = { groupIndex -> channelSourceLoader.getLiveChannels(groupIndex) },
        )
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(this, getString(R.string.live_please_select_channel), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    internal fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
        vm.setTappedGroupIndex(groupIndex)
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, liveChannelIndex)
            return
        }
        if (liveChannelIndex > -1) {
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
        } else {
            expandChannelGroup(groupIndex)
        }
    }

    fun toggleChannelGroup(groupIndex: Int) {
        if (isChannelGroupExpanded(groupIndex)) {
            collapseChannelGroup(groupIndex)
            return
        }
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        expandChannelGroup(groupIndex)
    }

    internal fun isChannelGroupExpanded(groupIndex: Int): Boolean =
        vm.state.value.channelList.expandedGroups.contains(groupIndex)

    internal fun expandChannelGroup(groupIndex: Int) {
        if (isChannelGroupExpanded(groupIndex)) return
        vm.expandChannelGroup(groupIndex)
    }

    private fun collapseChannelGroup(groupIndex: Int) {
        vm.collapseChannelGroup(groupIndex)
    }

    private fun requestChannelScroll() {
        vm.requestChannelScroll()
    }

    fun selectChannel(groupIndex: Int, position: Int) {
        vm.setTappedGroupIndex(groupIndex)
        clickLiveChannel(position)
    }

    private fun clickLiveChannel(position: Int) {
        playChannel(selectedChannelGroupIndex, position, false)
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        vm.setTappedGroupIndex(groupIndex)
        expandChannelGroup(groupIndex)
        requestChannelScroll()
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        vm.updatePasswordDialogTarget(groupIndex to liveChannelIndex)
    }

    internal fun onPasswordConfirmed(password: String) {
        val target = vm.state.value.passwordDialogTarget ?: return
        vm.updatePasswordDialogTarget(null)
        val groupIndex = target.first
        if (password == vm.channelGroups().getOrNull(groupIndex)?.groupPassword) {
            vm.confirmPasswordGroup(groupIndex)
            loadChannelGroupDataAndPlay(groupIndex, target.second)
        } else {
            Toast.makeText(this, getString(R.string.live_wrong_password), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun isNeedInputPassword(groupIndex: Int): Boolean {
        val group = vm.channelGroups().getOrNull(groupIndex) ?: return false
        return group.groupPassword.orEmpty().isNotEmpty() && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean =
        vm.state.value.channelList.confirmedPasswordGroups.contains(groupIndex)

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? =
        channelSourceLoader.getLiveChannels(groupIndex)

    internal fun loadLiveConfigOnEnter() = channelSourceLoader.loadLiveConfigOnEnter()

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) =
        channelSourceLoader.setEmptyLiveChannelList(releasePlayer)

    internal fun initLiveSettingGroupList() {
        vm.updateSettingGroups(ApiConfig.get().liveSettingGroupList)
    }

    private fun loadCurrentSourceList() {
        vm.updateCurrentSourceItems(
            LiveSettingsRules.sourceItems(currentLiveChannelItem?.channelSourceNames)
        )
    }

    internal fun openSettingsSheet() {
        LiveSettingsState.reload()
        ApiConfig.get().refreshLiveApiHistoryItems()
        initLiveSettingGroupList()
        loadCurrentSourceList()
        vm.onSettingsOpened()
    }

    internal fun removeLiveConfigHistory(itemIndex: Int) {
        vm.onSettingRemoved(itemIndex, settingHost)
    }

    internal fun onSettingsInputsChanged() {
        LiveSettingsState.reload()
        vm.onSettingsInputsChanged()
    }

    private val settingSource = object : LiveSettingsSource {
        override fun settingGroups(): List<LiveSettingGroup> = vm.settingGroups()

        override fun playerScale(): Int = livePlayerManager.livePlayerScale

        override fun playerType(): Int = livePlayerManager.livePlayerType

        override fun connectTimeoutIndex(): Int = LiveSettingsState.config.connectTimeoutIndex

        override fun switchChecked(position: Int): Boolean = when (position) {
            0 -> LiveSettingsState.config.showTime
            1 -> LiveSettingsState.config.showNetSpeed
            2 -> LiveSettingsState.config.channelReverse
            3 -> LiveSettingsState.config.crossGroup
            else -> false
        }

        override fun liveGroupIndex(): Int = ApiConfig.liveGroupIndex

        override fun configIndex(): Int = LiveSettingsRules.currentConfigIndex(
            ApiConfig.isLiveFollowVod(),
            ApiConfig.get().liveConfigUrls(),
            KV.get(KVKeys.LIVE_API_URL, ""),
        )

        override fun isLiveApiLineMode(): Boolean = ApiConfig.get().isLiveApiLineMode()
    }

    internal fun clickSettingItem(groupIndex: Int, position: Int) {
        vm.onSettingClicked(groupIndex, position, settingHost)
    }

    private val settingHost = object : LivePlayViewModel.Host {
        override fun currentChannelItem(): LiveChannelItem? = currentLiveChannelItem

        override fun currentPlayerScale(): Int = livePlayerManager.livePlayerScale

        override fun currentPlayerType(): Int = livePlayerManager.livePlayerType

        override fun replayCurrentChannel() {
            playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
        }

        override fun applyPlayerScale(position: Int) {
            kernelView?.let { livePlayerManager.changeLivePlayerScale(it, position) }
        }

        override fun applyPlayerType(position: Int) {
            val videoView = kernelView ?: return
            releasePlayerKernel()
            livePlayerManager.changeLivePlayerType(videoView, position)
            currentLiveChannelItem?.let { videoView.setUrl(it.urlAt(vm.currentSourceIndex()), liveChannelHeader()) }
            videoView.start()
        }

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int) {
            channelSourceLoader.refreshLiveChannelListAndPlay(channelName, sourceIndex)
        }

        override fun setEmptyChannelList(releasePlayer: Boolean) {
            setEmptyLiveChannelList(releasePlayer)
        }

        override fun removeConfigHistory(itemIndex: Int) {
            if (ApiConfig.get().isLiveApiLineMode()) {
                Toast.makeText(
                    this@LivePlayActivity,
                    getString(R.string.live_repo_entry_not_deletable),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val history = KV.get(KVKeys.LIVE_API_HISTORY, ArrayList<String>())
            if (itemIndex < 0 || itemIndex >= history.size) return
            if (history[itemIndex] == KV.get(KVKeys.LIVE_API_URL, "")) {
                Toast.makeText(
                    this@LivePlayActivity,
                    getString(R.string.live_active_config_not_deletable),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            history.removeAt(itemIndex)
            KV.put(KVKeys.LIVE_API_HISTORY, history)
            ApiConfig.get().refreshLiveApiHistoryItems()
            initLiveSettingGroupList()
            loadCurrentSourceList()
            onSettingsInputsChanged()
            Toast.makeText(
                this@LivePlayActivity,
                getString(R.string.toast_removed_from_history),
                Toast.LENGTH_SHORT,
            ).show()
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun isFinishing(): Boolean = this@LivePlayActivity.isFinishing
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return KV.get(KVKeys.LIVE_WEB_HEADER)
    }

    internal fun liveChannelHeader(): HashMap<String, String>? {
        val item = currentLiveChannelItem ?: return liveWebHeader()
        val header = HashMap<String, String>()
        liveWebHeader()?.let { header.putAll(it) }
        item.headers?.let { header.putAll(it) }
        if (item.channelFormat.orEmpty().isNotEmpty()) {
            header[MediaSources.HEADER_FORMAT] = item.channelFormat.orEmpty()
        }
        return if (header.isEmpty()) null else header
    }

    internal fun onEpgRowClicked(position: Int): Boolean = catchupController.onEpgRowClicked(position)

    fun onTimeshiftSeek(progress: Float) {
        overlay.onTimeshiftSeek(progress)
    }

    fun onTimeshiftTogglePlay() {
        overlay.onTimeshiftTogglePlay()
    }

    internal fun canCurrentChannelCatchup(): Boolean = catchupController.canCurrentChannelCatchup()

    private fun liveActions(): LivePlayActions = LivePlayActions(
        page = LivePageActions(
            onRetryLoad = { loadLiveConfigOnEnter() },
            onPasswordConfirm = { onPasswordConfirmed(it) },
            onPasswordDismiss = {
                vm.updatePasswordDialogTarget(null)
                vm.pendingSourceIndex = 0
            },
        ),
        player = LivePlayerActions(
            videoView = { kernelView },
            onExitFullscreen = { applyFullscreen(false) },
            onPictureInPicture = { enterPictureInPicture() },
            onEpgSheetOpen = { vm.showEpgSheet() },
            onSettingsSheetOpen = { openSettingsSheet() },
            onTimeshiftSeek = { onTimeshiftSeek(it) },
            onTimeshiftTogglePlay = { onTimeshiftTogglePlay() },
        ),
        epg = LiveEpgActions(
            onDismiss = { vm.hideEpgSheet() },
            onRowClicked = { onEpgRowClicked(it) },
        ),
        list = LiveChannelListActions(
            onToggleGroup = { toggleChannelGroup(it) },
            onSelectChannel = { groupIndex, position -> selectChannel(groupIndex, position) },
        ),
        settings = LiveSettingsActions(
            onDismiss = { vm.hideSettingsSheet() },
            onItemClick = { groupIndex, itemIndex -> clickSettingItem(groupIndex, itemIndex) },
            onItemLongClick = { removeLiveConfigHistory(it) },
        ),
    )

}
