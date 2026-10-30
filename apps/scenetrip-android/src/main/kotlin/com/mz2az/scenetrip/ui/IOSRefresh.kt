package com.mz2az.scenetrip.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS `.refreshable` 의 모습 — 내용이 아래로 밀려 내려가며 그 빈자리 가운데에 회색 12갈래 활동 표시(`UIActivityIndicatorView`)가
 * 돈다. Material 의 둥근 판 + 화살표 아이콘은 내용 위에 떠서 글자를 가렸다(2026-10-30 재감사 R15).
 *
 * 당기는 동안에는 당긴 만큼 갈래가 하나씩 나타나고, 놓으면 [HOLD] 만큼 내려온 채 도는 동안 머문다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOSPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val holdPx = with(LocalDensity.current) { HOLD.toPx() }
    val held by animateFloatAsState(if (isRefreshing) 1f else 0f, tween(220), label = "refreshHold")

    // 내용이 내려간 정도(0..1.3, 1 = 머무는 자리).
    fun shift(): Float = maxOf(state.distanceFraction.coerceIn(0f, 1.3f), held)
    // 8차 PR1: 홈처럼 상태줄 밑까지 깔린 화면에서는 활동 표시가 상태줄 안(y 36dp)에 떴다 — iOS 는 안전 영역 윗변에서 30 아래.
    // 상자 윗변이 상태줄보다 위면 그만큼 내려 그린다.
    val statusTopPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    var boxTopPx by remember { mutableFloatStateOf(statusTopPx) }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.onGloballyPositioned { boxTopPx = it.positionInWindow().y },
        indicator = {
            IOSRefreshSpinner(state, isRefreshing, holdPx, shift = { shift() }, extraTop = { (statusTopPx - boxTopPx).coerceAtLeast(0f) })
        },
    ) {
        Box(modifier = Modifier.graphicsLayer { translationY = shift() * holdPx }, content = content)
    }
}

private val HOLD = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.IOSRefreshSpinner(
    state: PullToRefreshState,
    refreshing: Boolean,
    holdPx: Float,
    shift: () -> Float,
    extraTop: () -> Float,
) {
    val turn =
        rememberInfiniteTransition(label = "spin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 12f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "spinStep",
            )
    Canvas(
        Modifier
            .align(Alignment.TopCenter)
            .size(27.9.dp) // 9차 PR1': iOS 활동 표시 지름 29.7 (Android 21.7 → 실측 29.0 → 27.9 로 보정)
            .graphicsLayer {
                translationY = shift() * holdPx / 2f - size.height / 2f + extraTop()
                alpha = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
            },
    ) {
        val c = Offset(size.width / 2, size.height / 2)
        val inner = size.minDimension * 0.28f
        val outer = size.minDimension * 0.5f
        val lit = if (refreshing) 12 else (state.distanceFraction.coerceIn(0f, 1f) * 12).toInt()
        // 돌 때는 12 단계로 끊겨 돈다(iOS 와 같다) — 가장 진한 갈래가 한 칸씩 시계 방향으로 간다.
        val head = turn.value.toInt() % 12
        for (i in 0 until 12) {
            if (i >= lit) continue
            val a = (i * 30 - 90) * PI / 180.0
            val age = if (refreshing) (head - i + 12) % 12 else i
            val alpha = if (refreshing) (1f - age / 12f).coerceAtLeast(0.2f) else 0.7f
            drawLine(
                Color(0xFF8E8E93).copy(alpha = alpha),
                Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
                Offset(c.x + (outer * cos(a)).toFloat(), c.y + (outer * sin(a)).toFloat()),
                strokeWidth = size.minDimension * 0.09f,
                cap = StrokeCap.Round,
            )
        }
    }
}
