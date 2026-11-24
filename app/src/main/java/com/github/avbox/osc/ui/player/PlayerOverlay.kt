package com.github.avbox.osc.ui.player

import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.ParamsChoice
import com.github.avbox.osc.player.state.PlayerActions
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.player.gesture.VideoGestureHandler
import com.github.avbox.osc.player.gesture.VideoGestureSession
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    actions: PlayerActions,
    decodeChoice: ParamsChoice,
    gestureHandler: VideoGestureHandler,
    gestureSession: (width: Int, height: Int, screenWidth: Int, downY: Float) -> VideoGestureSession,
    onTapPending: () -> Unit,
) {
    val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val pictureInPicture = state.pictureInPicture
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                if (pictureInPicture) {
                    Modifier
                } else {
                    Modifier.videoGestureLayer(
                        handler = gestureHandler,
                        sessionProvider = { size, downY ->
                            val sizeW = size.width
                            val sizeH = size.height
                            if (state.overlayPanelOpen || sizeW <= 0 || sizeH <= 0) {
                                null
                            } else {
                                gestureSession(sizeW, sizeH, screenWidthPx, downY)
                            }
                        },
                        onTapPending = onTapPending,
                    )
                },
            ),
    ) {
        // 系统画中画里只保留画面，播放器按键/提示由系统 PiP 控制项接管
        if (pictureInPicture) return@BoxWithConstraints
        val iconBox = playerIconBox(maxWidth - playerEdgePadding() * 2)
        PlayerTipLayer(state)
        PlayerSlideVisibility(
            visible = state.topLeftVisible || (state.topRightVisible && !state.previewMode),
            fromTop = true,
        ) {
            PlayerTopBar(state, actions)
        }
        PlayerSlideVisibility(
            visible = state.controlsVisible,
            fromTop = false,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(state, actions)
        }
        PlayerSlideHint(state)
        PlayerSeekHint(state)
        PlayerFadeVisibility(
            visible = state.loadingVisible && !state.tipVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = if (state.tipVisible) 0 else PLAYER_OSD_OUT_MS,
        ) {
            PlayerLoadingLayer(state)
        }
        PlayerNetSpeedCenter(state)
        PlayerSideButtons(state, actions, iconBox)
        PlayerFadeVisibility(
            visible = state.infoOsdVisible,
            enterMs = PLAYER_OSD_IN_MS,
            exitMs = PLAYER_OSD_OUT_MS,
        ) {
            PlayerInfoOsd(state, actions, maxWidth)
        }
        PlayerSpeedBoostHint(state)

        if (state.comparing) {
            CompareBadge(
                text = stringResource(R.string.player_picture_compare_left),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = playerEdgePadding(), top = playerDim(R.dimen.vs_12)),
            )
            CompareBadge(
                text = stringResource(R.string.player_picture_compare_right),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = playerEdgePadding(), top = playerDim(R.dimen.vs_12)),
            )
        }

        state.selectDialog?.let { dialogState ->
            PlayerSelectDialog(
                dialogState = dialogState,
                onDismiss = state::dismissSelectDialog,
            )
        }

        state.paramsSheet?.let { sheet ->
            PlayerParamsSheet(
                sheet = sheet,
                decodeChoice = decodeChoice,
                tab = state.paramsTab,
                onTabSelected = state::selectParamsTab,
                osdVisible = state.infoOsdVisible,
                onToggleOsd = actions::onInfoOsdClicked,
                slideFromEnd = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                onDismiss = state::dismissParamsSheet,
            )
        }

        state.danmuSettingSheet?.let { sheet ->
            DanmuSettingSheet(sheet) { state.dismissDanmuSettingSheet() }
        }
        state.danmuSearchSheet?.let { sheet ->
            DanmuSearchSheet(sheet) { state.dismissDanmuSearchSheet() }
        }
        state.subtitleSheet?.let { sheet ->
            SubtitleSheet(sheet) { state.dismissSubtitleSheet() }
        }
        state.subtitleSearchSheet?.let { sheet ->
            SubtitleSearchSheet(sheet) { state.dismissSubtitleSearchSheet() }
        }
    }

    LaunchedEffect(state.seekHintVisible, state.seekHintText) {
        if (state.seekHintVisible) {
            delay(1000)
            actions.hideSeekHint()
        }
    }
    LaunchedEffect(state.slideHintVisible, state.slideHintText) {
        if (state.slideHintVisible) {
            delay(1000)
            actions.hideSlideHint()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { actions.refreshSystemInfo() }
                .onFailure { AppLog.e("echo-osd-refresh-failed: $it") }
            delay(1000)
        }
    }
}

private const val PLAYER_DESIGN_WIDTH = 1280f

@Composable
private fun playerMmScale(): Float {
    val container = LocalWindowInfo.current.containerSize
    val longEdgePx = if (container.width > 0 && container.height > 0) {
        maxOf(container.width, container.height).toFloat()
    } else {
        val conf = LocalConfiguration.current
        maxOf(conf.screenWidthDp, conf.screenHeightDp) * LocalDensity.current.density
    }
    return if (longEdgePx > 0f) longEdgePx / PLAYER_DESIGN_WIDTH else 1f
}

private fun rawMm(resources: Resources, @DimenRes id: Int): Float? {
    val tv = TypedValue()
    try {
        resources.getValue(id, tv, true)
    } catch (e: Resources.NotFoundException) {
        return null
    }
    if (tv.type != TypedValue.TYPE_DIMENSION) return null
    if ((tv.data and TypedValue.COMPLEX_UNIT_MASK) != TypedValue.COMPLEX_UNIT_MM) return null
    return TypedValue.complexToFloat(tv.data)
}

@Composable
internal fun playerDim(@DimenRes id: Int): Dp {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val pxInt = if (px == 0f) 0 else px.roundToInt().coerceAtLeast(1)
    return with(LocalDensity.current) { pxInt.toFloat().toDp() }
}

@Composable
internal fun playerTextSize(@DimenRes id: Int): TextUnit {
    val res = LocalContext.current.resources
    val raw = rawMm(res, id)
    val px = if (raw != null) raw * playerMmScale() else res.getDimension(id)
    val safePx = if (px.isFinite()) px.coerceAtLeast(0f) else 0f
    return with(LocalDensity.current) { safePx.toSp() }
}

@Composable
internal fun playerEdgePadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp >= 600) 48.dp else 16.dp

@Composable
private fun CompareBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_18),
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.55f),
                RoundedCornerShape(playerDim(R.dimen.vs_5)),
            )
            .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_5)),
    )
}

@Composable
internal fun PlayerMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    textColor: Color = Color.White,
    @DimenRes textSizeId: Int = R.dimen.ts_20,
) {
    var pressed by remember { mutableStateOf(false) }
    val buttonModifier = modifier
        .pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = onLongClick?.let { cb -> { cb() } },
            )
        }
        .background(
            if (pressed) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            RoundedCornerShape(playerDim(R.dimen.vs_5)),
        )
        .padding(horizontal = playerDim(R.dimen.vs_10), vertical = playerDim(R.dimen.vs_5))
    Text(
        text = text,
        color = textColor,
        fontSize = playerTextSize(textSizeId),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (pressed) FontWeight.Bold else FontWeight.Medium,
        modifier = buttonModifier,
    )
}

internal const val ICON_TO_BOX_RATIO = 0.55f

private const val PILL_MAX_ICONS = 8

@Composable
internal fun playerIconBox(availableWidth: Dp): Dp {
    val gap = playerDim(R.dimen.vs_8)
    return minOf(
        playerDim(R.dimen.vs_70),
        (availableWidth - gap * (PILL_MAX_ICONS + 1)) / PILL_MAX_ICONS,
    ).coerceAtLeast(1.dp)
}
