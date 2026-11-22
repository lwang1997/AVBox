package com.github.avbox.osc.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.github.avbox.osc.player.gesture.GestureVerdict
import com.github.avbox.osc.player.gesture.VERBOSE_GESTURE_LOG
import com.github.avbox.osc.player.gesture.VideoGestureHandler
import com.github.avbox.osc.player.gesture.VideoGestureSession
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.withTimeoutOrNull

fun Modifier.videoGestureLayer(
    handler: VideoGestureHandler,
    sessionProvider: (IntSize, downY: Float) -> VideoGestureSession?,
    onTapPending: () -> Unit = {},
): Modifier = composed {
    // pointerInput 的协程只在 key 变化时重启，闭包里捕获的旧 lambda 会一直生效；
    // 用 rememberUpdatedState 保证旋转/全屏切换后仍读到最新的 provider。
    val currentSessionProvider by rememberUpdatedState(sessionProvider)
    val currentOnTapPending by rememberUpdatedState(onTapPending)
    // 层尺寸直接读 PointerInputScope.size：每次手势都是当前布局尺寸，
    // 不再依赖 onSizeChanged 捕获的旧值（旧值会让下半屏被误判为底部边缘带）。
    pointerInput(handler) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val snapshot = currentSessionProvider(size, down.position.y) ?: return@awaitEachGesture
            if (handler.beginSession(snapshot, down.position.x, down.position.y) ==
                GestureVerdict.IGNORE
            ) {
                return@awaitEachGesture
            }

            var firstUp = false
            var sawMove = false
            var lastMoveAt = 0L
            val longPressWon = withTimeoutOrNull(handler.longPressTimeoutMs) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Final)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: continue
                    if (!c.pressed) {
                        firstUp = true
                        return@withTimeoutOrNull false
                    }
                    sawMove = true
                    lastMoveAt = System.currentTimeMillis()
                    if (!c.isConsumed) handler.onMove(c.position.x, c.position.y, 8f)
                    if (handler.hasMoved) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") true
            }

            if (longPressWon == null && !firstUp) {
                handler.maybeLongPress()
            }

            if (!firstUp) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Final)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: continue
                    if (!c.pressed) break
                    sawMove = true
                    lastMoveAt = System.currentTimeMillis()
                    if (!c.isConsumed) {
                        if (handler.onMove(c.position.x, c.position.y, 8f)) c.consume()
                    }
                }
            }

            val quietMs = System.currentTimeMillis() - lastMoveAt
            val cancelled = !handler.isLongPressing && (!sawMove || quietMs > CANCEL_QUIET_MS)
            if (VERBOSE_GESTURE_LOG) {
                AppLog.i(
                    "echo-gesture: sawMove=" + sawMove + " quietMs=" + quietMs +
                        " mode=" + handler.currentMode + " cancelled=" + cancelled +
                        " fromTopBand=" + snapshot.fromTopBand,
                )
            }

            val result = handler.endSession(cancelled, System.currentTimeMillis())

            if (result == VideoGestureHandler.EndResult.TAP_PENDING) {
                currentOnTapPending()
            }
        }
    }
}

private const val CANCEL_QUIET_MS = 50L
