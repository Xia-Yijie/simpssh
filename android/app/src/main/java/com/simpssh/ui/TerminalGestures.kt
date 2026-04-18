package com.simpssh.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.withTimeoutOrNull

internal object MouseBtn {
    const val LEFT: Int = 0
    const val MIDDLE: Int = 1
    const val RIGHT: Int = 2
    const val WHEEL_UP: Int = 64
    const val WHEEL_DOWN: Int = 65
}

internal fun sgrMouseBytes(button: Int, col: Int, row: Int, press: Boolean): ByteArray {
    val terminator = if (press) 'M' else 'm'
    return "\u001B[<$button;$col;$row$terminator".toByteArray(StandardCharsets.US_ASCII)
}

internal fun sgrMouseClick(button: Int, col: Int, row: Int): ByteArray =
    sgrMouseBytes(button, col, row, press = true) +
        sgrMouseBytes(button, col, row, press = false)

internal val TAB_BYTES: ByteArray = byteArrayOf(0x09)
internal val SHIFT_TAB_BYTES: ByteArray = "\u001B[Z".toByteArray(StandardCharsets.US_ASCII)
internal val BRACKETED_PASTE_START: ByteArray = "\u001B[200~".toByteArray(StandardCharsets.US_ASCII)
internal val BRACKETED_PASTE_END: ByteArray = "\u001B[201~".toByteArray(StandardCharsets.US_ASCII)

@Suppress("LongParameterList")
internal fun Modifier.terminalGestures(
    charW: () -> Float,
    charH: () -> Float,
    onLeftClick: (col: Int, row: Int) -> Unit,
    onRightClick: (col: Int, row: Int) -> Unit,
    onWheelStep: (col: Int, row: Int, steps: Int) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    onPinch: (zoom: Float) -> Unit,
    onLongPress: (col: Int, row: Int) -> Unit,
    onSelectionExtend: (col: Int, row: Int) -> Unit,
    onSelectionCommit: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTransformGestures(panZoomLock = false) { _, _, zoom, _ ->
            if (zoom != 1f) onPinch(zoom)
        }
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val w = charW(); val h = charH()
            if (w <= 0f || h <= 0f) return@awaitEachGesture
            handleNonPinchGestures(
                w, h,
                onLeftClick, onRightClick, onWheelStep,
                onSwipeRight, onSwipeLeft, onLongPress,
                onSelectionExtend, onSelectionCommit,
            )
        }
    }

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private suspend fun AwaitPointerEventScope.handleNonPinchGestures(
    charW: Float,
    charH: Float,
    onLeftClick: (col: Int, row: Int) -> Unit,
    onRightClick: (col: Int, row: Int) -> Unit,
    onWheelStep: (col: Int, row: Int, steps: Int) -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    onLongPress: (col: Int, row: Int) -> Unit,
    onSelectionExtend: (col: Int, row: Int) -> Unit,
    onSelectionCommit: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val swipeThreshold = slop * 2f
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

    // requireUnconsumed = true:让选择手柄拖动(拥有自己 pointerInput、会独吞消费事件)
    // 不会同时触发这里的滚动 / 点击逻辑。
    val firstDown = awaitFirstDown(requireUnconsumed = true)
    val downTime = firstDown.uptimeMillis
    val downPos = firstDown.position

    var totalDrag = Offset.Zero
    var sawMultiPointer = false
    var initialMultiDist = 0f
    var multiMotionSeen = false
    var gesture = Decided.UNDECIDED
    var verticalAccum = 0f
    var lastSelectionCell = Pair(-1, -1)

    fun cellOf(offset: Offset): Pair<Int, Int> {
        val col = (offset.x / charW).toInt().coerceAtLeast(0) + 1
        val row = (offset.y / charH).toInt().coerceAtLeast(0) + 1
        return col to row
    }

    fun maybeFireLongPress(elapsed: Long) {
        if (gesture != Decided.UNDECIDED) return
        if (sawMultiPointer) return
        if (totalDrag.magnitude() >= slop) return
        if (elapsed < longPressTimeoutMs) return
        val (col, row) = cellOf(downPos)
        gesture = Decided.LONG_PRESS
        lastSelectionCell = col to row
        onLongPress(col, row)
    }

    while (true) {
        // 两端都必须用 SystemClock.uptimeMillis():firstDown.uptimeMillis 是 uptime(开机以来毫秒),
        // 不是挂钟时间;若用 System.currentTimeMillis() 相减,会拿挂钟减 uptime(相差约 1.7e12 ms),
        // 导致每次轻触都立刻触发长按。
        val elapsed = SystemClock.uptimeMillis() - downTime
        // 按时间阈值主动触发:部分设备在手指静止按住时仍持续上报 pointer 事件,
        // 只靠 withTimeoutOrNull 的超时分支会一直拿到事件而漏掉长按。
        maybeFireLongPress(elapsed)

        val remaining = longPressTimeoutMs - elapsed
        val event = if (gesture == Decided.UNDECIDED && remaining > 0L) {
            withTimeoutOrNull(remaining) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        } ?: continue

        val pressed = event.changes.filter { it.pressed }

        if (pressed.size >= 2) {
            // 不变量:多指手势归 pinch detector 所有。撤回第二根手指落下前
            // 单指 drag 可能已经提前 commit 的方向判定。
            if (gesture == Decided.HORIZONTAL || gesture == Decided.VERTICAL) {
                gesture = Decided.UNDECIDED
            }
            val dist = (pressed[0].position - pressed[1].position).magnitude()
            if (!sawMultiPointer) {
                sawMultiPointer = true
                initialMultiDist = dist
            } else if (abs(dist - initialMultiDist) > slop * 2f) {
                // 位移没达到 pinch 的缩放阈值,但两指确实动了 —— 抬起时抑制双指点击(右键)。
                multiMotionSeen = true
            }
        } else if (pressed.size == 1 && !sawMultiPointer) {
            val dy = pressed[0].positionChange().y
            totalDrag += pressed[0].positionChange()
            if (gesture == Decided.UNDECIDED && totalDrag.magnitude() > swipeThreshold) {
                gesture = if (abs(totalDrag.x) > abs(totalDrag.y)) {
                    Decided.HORIZONTAL
                } else {
                    verticalAccum = totalDrag.y
                    Decided.VERTICAL
                }
            }
            when (gesture) {
                Decided.VERTICAL -> {
                    verticalAccum += dy
                    val steps = (verticalAccum / charH).toInt()
                    if (steps != 0) {
                        verticalAccum -= steps * charH
                        val (col, row) = cellOf(downPos)
                        onWheelStep(col, row, steps)
                    }
                }
                Decided.LONG_PRESS -> {
                    val pos = pressed[0].position
                    val newCol = cellWithHysteresis(pos.x / charW, lastSelectionCell.first - 1) + 1
                    val newRow = cellWithHysteresis(
                        pos.y / charH,
                        lastSelectionCell.second - 1,
                        ROW_HYSTERESIS_THRESHOLD,
                    ) + 1
                    val cell = newCol to newRow
                    if (cell != lastSelectionCell) {
                        lastSelectionCell = cell
                        onSelectionExtend(cell.first, cell.second)
                    }
                }
                else -> Unit
            }
        }

        val allUp = pressed.isEmpty() && event.changes.any { it.changedToUp() }
        if (allUp) {
            when (gesture) {
                Decided.HORIZONTAL -> if (!sawMultiPointer) {
                    if (totalDrag.x > 0) onSwipeRight() else onSwipeLeft()
                }
                Decided.VERTICAL -> Unit
                Decided.UNDECIDED -> {
                    if (sawMultiPointer) {
                        if (!multiMotionSeen) {
                            val (col, row) = cellOf(downPos)
                            onRightClick(col, row)
                        }
                    } else {
                        val (col, row) = cellOf(downPos)
                        onLeftClick(col, row)
                    }
                }
                Decided.LONG_PRESS -> onSelectionCommit()
            }
            return
        }
    }
}

private fun Offset.magnitude(): Float = sqrt(x * x + y * y)

// Schmitt trigger 式的 cell 判定:光标恰好落在 cell 边界时,手指的微小抖动
// 会让 raw.toInt() 在两个 cell 之间反复跳;用 last cell 中心 ± threshold 的滞回区
// 吞掉这种抖动。threshold = 0.5 等价于原始边界(无滞回),越大越需要手指
// 明确越过边界才切换 —— 与 AOSP TextView 文本选择的防抖手法一致。
internal fun cellWithHysteresis(raw: Float, last: Int, threshold: Float = 0.8f): Int {
    val clamped = raw.toInt().coerceAtLeast(0)
    if (last < 0) return clamped
    val diff = raw - (last + 0.5f)
    return if (diff > threshold || diff < -threshold) clamped else last
}

// 行阈值比列大:行的像素高度大于列的像素宽度,但手指在 x/y 两轴的物理抖动
// (毫米尺度)基本相当,于是同样的抖动对一个 cell 的占比,列比行大。列只用
// 0.8 就能保持灵敏;行要求 ~1.1 cell 的刻意位移才切换,才能避免选择时跨行乱跳。
internal const val ROW_HYSTERESIS_THRESHOLD = 1.1f

private enum class Decided { UNDECIDED, HORIZONTAL, VERTICAL, LONG_PRESS }

// 按住自动重复用 deadline(绝对时间点)而非每轮 sleep 固定间隔:
// 两次 fire 之间可能穿插无关的 pointer 事件(移动等),若用 sleep 方案
// 每次事件都会重置计时,节奏会被打乱。
internal fun Modifier.repeatOnHold(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        action()
        var nextFireAt = System.currentTimeMillis() + INITIAL_DELAY_MS
        while (true) {
            val waitFor = (nextFireAt - System.currentTimeMillis()).coerceAtLeast(1L)
            val event = withTimeoutOrNull(waitFor) { awaitPointerEvent() }
            if (event == null) {
                action()
                nextFireAt = System.currentTimeMillis() + REPEAT_INTERVAL_MS
            } else if (event.changes.all { !it.pressed }) {
                return@awaitEachGesture
            }
        }
    }
}

private const val INITIAL_DELAY_MS: Long = 400L
private const val REPEAT_INTERVAL_MS: Long = 60L
