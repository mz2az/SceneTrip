package com.mz2az.scenetrip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 웹 시안(`proto.css`)의 움직임을 Compose 로 옮긴 공용 부품 (MZ2AZ-547 계획 motion-works-drawer-parity §4).
 * 시간·곡선은 웹 값 그대로다. 시스템 「애니메이션 제거」 이면 애니메이터 배율 0 이라 Compose 가 바로 끝 값으로 간다.
 */
object ProtoMotion {
    /** 웹 `cubic-bezier(.32,.72,0,1)` — 서랍·검색칸이 열릴 때. */
    val EaseOutSheet = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 웹 `cubic-bezier(.4,0,1,1)` — 검색칸이 닫힐 때. */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 웹 `cubic-bezier(.34,1.56,.64,1)` — 살짝 튀는 좋아요. */
    val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
}

/**
 * 덮는 화면(코스 편집·새 코스·인기 작품·프로필·코스 목록) — 아래에서 0.3초에 올라오고, 닫으면 0.3초에 내려간다(MZ2AZ-547 M3, iOS 기본 덮개).
 * [value] 가 null 이 되어도 내려가는 동안은 마지막 값을 그린다. 값이 다른 값으로 바뀌면 움직임 없이 갈아 끼운다.
 */
@Composable
fun <T : Any> CoverHost(
    value: T?,
    content: @Composable (T) -> Unit,
) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(
        visible = value != null,
        enter = slideInVertically(tween(COVER_MILLIS, easing = ProtoMotion.EaseOutSheet)) { it },
        exit = slideOutVertically(tween(COVER_MILLIS, easing = FastOutLinearInEasing)) { it },
    ) {
        @Suppress("UNCHECKED_CAST")
        val shown = (value ?: last[0]) as T? ?: return@AnimatedVisibility
        content(shown)
    }
}

/** 덮는 화면이 오르내리는 시간(ms). */
const val COVER_MILLIS = 300

/**
 * 둥근 알약 모양으로 잘라 보이는 범위만 넓힌다(웹 `clip-path: inset(... round 999px)`) — 폭은 그대로라 레이아웃을 다시 재지 않는다.
 * [progress] 0 이면 [collapsed] 폭의 동그라미, 1 이면 전체. [fromStart] 면 왼쪽에 붙어 오른쪽으로 길어지고, 아니면 오른쪽에 붙어 왼쪽으로.
 */
fun Modifier.reveal(
    progress: () -> Float,
    collapsed: Dp,
    fromStart: Boolean,
): Modifier =
    graphicsLayer {
        val p = progress()
        if (p >= 1f) {
            clip = false
            return@graphicsLayer
        }
        val c = collapsed.toPx()
        clip = true
        shape = RevealShape(p, c, fromStart)
    }

private class RevealShape(
    private val progress: Float,
    private val collapsedPx: Float,
    private val fromStart: Boolean,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val start = minOf(collapsedPx, size.width)
        val width = start + (size.width - start) * progress.coerceIn(0f, 1f)
        val left = if (fromStart) 0f else size.width - width
        val r = size.height / 2f
        return Outline.Rounded(RoundRect(left, 0f, left + width, size.height, CornerRadius(r, r)))
    }
}

/**
 * 처음 그려질 때 [delayMillis] 뒤 [distance] 만큼 비켜 있다가 [millis] 에 제자리로 오며 나타난다.
 * [distance] 가 음수면 위에서 내려오고(웹 `proto-drop-in` −6px), 양수면 아래에서 떠오른다(`proto-coach-in` +6px).
 * [key] 가 바뀌면 다시 한다.
 */
fun Modifier.appearIn(
    distance: Dp = (-6).dp,
    millis: Int = 220,
    delayMillis: Int = 80,
    key: Any? = Unit,
): Modifier =
    composed {
        val p = remember(key) { Animatable(0f) }
        LaunchedEffect(key) {
            if (delayMillis > 0) delay(delayMillis.toLong())
            p.animateTo(1f, tween(millis, easing = LinearOutSlowInEasing))
        }
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * distance.toPx()
        }
    }
