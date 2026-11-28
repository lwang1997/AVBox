@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.ui.player.CastSheet
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.HomeBackdrop
import com.github.avbox.osc.ui.components.ImagePalette
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LocalTopBarGlassBackdrop
import com.github.avbox.osc.ui.components.PosterBackdrop
import com.github.avbox.osc.ui.components.VodCardMenu
import com.github.avbox.osc.ui.components.rememberVodCardMenuState
import com.github.avbox.osc.ui.download.DownloadMethodDialog
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.avbox.osc.ui.page.jumpToSearch

private const val EntrySlideDurationMs = 300

private const val FlatScrollScrimAlpha = 0.45f

private val FlatPageScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.05f),
    0.45f to Color.Black.copy(alpha = 0.28f),
    0.75f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.62f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val enteringFullscreen by vm.enteringFullscreen.collectAsState()
    val exitingFullscreen by vm.exitingFullscreen.collectAsState()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroFadePx = with(density) { HeroScrollFadeDistance.roundToPx() }
    val heroProgress by remember(listState, heroFadePx) {
        derivedStateOf {
            heroScrollProgress(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                heroFadePx,
            )
        }
    }
    val heroGone by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val configuration = LocalConfiguration.current
    val flatHero = flatHeroLayout(
        configuration.smallestScreenWidthDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    LaunchedEffect(heroGone, flatHero) { activity.setStatusBarIconsLight(heroGone && !flatHero) }

    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = DetailFullScreenFrame.fullBox(rotating, full, isLandscapeNow)
    val inTransition = enteringFullscreen || exitingFullscreen
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    val container = activity.playContainer
    // 系统小窗里只该看到画面：整页内容（Hero 的播放按钮之类）不能被缩进小窗
    val pipActive = container?.uiState()?.pictureInPicture == true
    val scope = rememberCoroutineScope()
    var backdropPic by remember { mutableStateOf("") }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    val pendingPic = vm.pendingPicture
    val backdropSource = backdropPic.ifEmpty { pendingPic }
    val backdropSeedArgb = backdropSeed ?: HomeBackdrop.seedOf(pendingPic)
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme()) || flatHero
    val schemeSeed = backdropSeedArgb ?: if (flatHero) AppThemeState.config.seedArgb else null
    val colorScheme = schemeSeed?.let { argb ->
        remember(argb, darkTheme) {
            AppThemeState.customScheme(argb, darkTheme, AppThemeState.config.style)
        }
    } ?: MaterialTheme.colorScheme

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Loading) {
            backdropPic = ""
            backdropSeed = null
        }
    }

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Ready) {
            withFrameNanos { }
            withFrameNanos { }
            activity.preheatWebView()
        }
    }

    LaunchedEffect(full, playSignal) {
        if (activity.playContainer == null && (full || playSignal > 0)) activity.ensurePlayContainer()
    }

    LaunchedEffect(vm) {
        vm.playbackCommands.collect { command ->
            val c = activity.playContainer ?: return@collect
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> c.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> c.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> c.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> c.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> c.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (musicArmed) {
            snapshotFlow {
                activity.playContainer?.uiState()?.playState to UiSettingsState.config.musicAutoOpenPage
            }
                .filter { (_, enabled) -> enabled }
                .first { activity.musicPlaybackDetected() }
            if (activity.handOffToMusicPlayer()) {
                musicArmed = false
            } else {
                delay(300)
            }
        }
    }

    val slideAnim = remember { Animatable(0f) }

    fun slideProgress(): Float = when {
        pipActive -> 1f
        inTransition -> slideAnim.value
        full -> 1f
        else -> 0f
    }

    LaunchedEffect(enteringFullscreen, exitingFullscreen) {
        when {
            exitingFullscreen -> {
                // 收尾必须无条件执行：animateTo 被取消时若跳过 onExitSlideFinished，
                // exitingFullscreen 会永久为 true，返回键从此只剩 IgnoreTransition
                try {
                    slideAnim.animateTo(0f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                } finally {
                    vm.onExitSlideFinished()
                    activity.settleRotationAfterExit()
                }
            }

            enteringFullscreen -> {
                activity.settleRotationAfterExit()
                slideAnim.animateTo(1f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                vm.onEntrySlideFinished()
            }
        }
    }

    LaunchedEffect(inTransition, container) {
        activity.setPlayerTouchBlocked(inTransition)
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { message ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.finishEvent.collect {
            activity.finish()
        }
    }

    val pageBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    MaterialTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (flatHero) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                        val info = vm.vodInfo
                        if (info != null) {
                            DetailHeroPoster(
                                vm = vm,
                                title = info.name ?: "TVBox",
                                altTitle = info.en,
                                picture = info.pic,
                                year = info.year,
                                onImage = { image ->
                                    scope.launch {
                                        val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                                        if (seed != null && backdropSeed == null) backdropSeed = seed
                                    }
                                },
                                onBackdropPic = { backdropPic = it },
                                paused = full,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(*FlatPageScrimStops)),
                        )
                    }
                    FlatScrollScrim(progress = { heroProgress })
                }
            } else {
                PosterBackdrop(
                    pic = backdropSource,
                    onImage = { image ->
                        scope.launch {
                            val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                            if (seed != null && backdropSeed == null) backdropSeed = seed
                        }
                    },
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                if ((!fullBox && !pipActive) || exitingFullscreen) {
                    when (val state = pageState) {
                        is DetailViewModel.PageState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ContainedLoadingIndicator(Modifier.size(64.dp))
                            }
                        }

                        is DetailViewModel.PageState.Empty -> {
                            LoadStateBox(
                                state = LoadState.Empty,
                                emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                                errorText = "",
                                retryText = "",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        is DetailViewModel.PageState.Ready -> {
                            CompositionLocalProvider(LocalTopBarGlassBackdrop provides pageBackdrop) {
                                DetailContent(
                                    activity = activity,
                                    vm = vm,
                                    listState = listState,
                                    onPosterPic = { backdropPic = it },
                                    onSeed = { argb -> if (argb != null) backdropSeed = argb },
                                )
                            }
                        }
                    }
                }
                if ((!fullBox && !pipActive) || exitingFullscreen) {
                    DetailTopScrim(
                        modifier = Modifier.align(Alignment.TopCenter),
                        alpha = if (flatHero) 1f else 1f - heroProgress,
                    )
                }
            }

            val playerContainer = container
            if (playerContainer != null && !activity.playContainerHidden) {
                // 小窗：窗口按视频画面宽高比收进 Hero 约束框（不裁剪、无黑边）；全屏=铺满，progress 插值
                val progress = slideProgress()
                val heroConstraintPx = if (flatHero) {
                    rootSize.height * 0.5f
                } else {
                    with(density) { heroImageHeight(configuration.screenHeightDp).toPx() }
                }
                val rect = playerContainer.uiState().videoRect
                val baseW: Float
                val baseH: Float
                if (!rect.empty && rect.width > 0 && rect.height > 0 && rootSize.width > 0) {
                    val fitScale = minOf(
                        rootSize.width / rect.width.toFloat(),
                        heroConstraintPx / rect.height.toFloat(),
                    )
                    baseW = rect.width * fitScale
                    baseH = rect.height * fitScale
                } else {
                    baseW = rootSize.width.toFloat()
                    baseH = heroConstraintPx
                }
                val playerWidthPx = baseW + (rootSize.width - baseW) * progress
                val playerHeightPx = baseH + (rootSize.height - baseH) * progress

                // 默认顶边 = 状态栏 + 顶部按钮行下方（红框区域）；小窗内可拖动，位置记忆
                val topActionsBottomPx = with(density) {
                    (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp).toPx()
                }
                var dragX by remember { mutableStateOf(Float.NaN) }
                var dragY by remember { mutableStateOf(Float.NaN) }
                val maxPosX = (rootSize.width - playerWidthPx).coerceAtLeast(0f)
                val maxPosY = (rootSize.height - playerHeightPx).coerceAtLeast(0f)
                val posX = (if (dragX.isNaN()) maxPosX / 2f else dragX).coerceIn(0f, maxPosX)
                val posY = (if (dragY.isNaN()) topActionsBottomPx else dragY).coerceIn(0f, maxPosY)
                val draggable = !full && !inTransition && !pipActive

                Box(
                    modifier = Modifier
                        .width(with(density) { playerWidthPx.toDp() })
                        .height(with(density) { playerHeightPx.toDp() })
                        .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                        .then(
                            if (draggable) {
                                Modifier.pointerInput(playerWidthPx, playerHeightPx, rootSize) {
                                    detectDragGestures(
                                        onDragStart = {
                                            if (dragX.isNaN()) {
                                                dragX = posX
                                                dragY = posY
                                            }
                                        },
                                        onDrag = { change, drag ->
                                            dragX = (dragX + drag.x).coerceIn(0f, maxPosX)
                                            dragY = (dragY + drag.y).coerceIn(0f, maxPosY)
                                            change.consume()
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    AndroidView(
                        factory = { playerContainer },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            container?.uiState()?.let { PlayerCastOverlay(it) }

            if (inTransition) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }

        EpisodeSheet(
            vm,
            slideFromEnd = fullBox && isLandscapeNow,
            showQuality = full,
            onDownloadSelected = { indices -> activity.startEpisodeDownload(indices) },
        )
        activity.downloadMethodPrompt?.let { prompt ->
            DownloadMethodDialog(
                idmLabel = prompt.idmLabel,
                onBuiltin = { prompt.pickBuiltin() },
                onIdm = { prompt.pickIdm() },
                onDismiss = { prompt.cancel() },
            )
        }
        activity.downloadBatch?.let { batch ->
            AVBoxAlertDialog(
                onDismissRequest = {},
                dismissible = false,
                title = { Text(stringResource(R.string.download_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.download_batch_progress,
                            batch.current,
                            batch.total,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = batch.onCancel) {
                        Text(stringResource(R.string.download_batch_cancel))
                    }
                },
            )
        }
        VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
    }
}

@Composable
private fun PlayerCastOverlay(state: PlayerUiState) {
    state.castSheet?.let { sheet ->
        CastSheet(sheet) { state.dismissCastSheet() }
    }
}

@Composable
private fun FlatScrollScrim(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = FlatScrollScrimAlpha * progress().coerceIn(0f, 1f),
                ),
            ),
    )
}
