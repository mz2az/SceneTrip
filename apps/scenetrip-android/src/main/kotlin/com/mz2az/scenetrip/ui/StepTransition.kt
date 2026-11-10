package com.mz2az.scenetrip.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 질문 단계 넘김 (MZ2AZ-547 계획 motion-works-drawer-parity §5) — 「도시로 시작」·「작품으로」 의 단계가 바뀔 때.
 *
 * 앞으로면 새 단계가 오른쪽 24dp 에서 밀려 들어오며 나타나고 이전 단계는 왼쪽으로 조금 밀리며 사라진다(0.28초, [ProtoMotion.EaseOutSheet]).
 * 뒤로면 반대. 동작 줄이기면 밀림 없이 0.2초 서서히만.
 *
 * 화면 전체는 제자리에서 서서히 바뀌고, **밀리는 것은 [stepSlide] 를 붙인 부분뿐**이다 — 새 코스 화면 틀(`NewCourseScreen`)은 본문과
 * 아래 단추에 붙이고 위 줄(‹ 뒤로 · 단계 점)에는 붙이지 않는다. 그래서 단계 점은 제자리에 있고 [LocalStepFrom] 에서 길이가 자란다.
 */
object StepMotion {
    /** 단계가 넘어가는 시간(ms). 단계 점도 같은 시간에 늘거나 준다. */
    const val MILLIS = 280

    /** 동작 줄이기일 때 서서히 바뀌는 시간(ms). */
    const val REDUCED_MILLIS = 200

    /** 밀리는 거리(dp). */
    const val SHIFT_DP = 24
}

/** 지금 그리는 단계 칸의 넘김 — [StepTransition] 안에서만 있다. */
class StepSlot internal constructor(
    internal val scope: AnimatedVisibilityScope,
    internal val forward: Boolean,
    internal val reduceMotion: Boolean,
    internal val shiftPx: Int,
)

val LocalStepSlot = compositionLocalOf<StepSlot?> { null }

/** 이 단계 칸이 나타나기 직전 단계 번호(단계 점이 여기서부터 자란다). 처음 칸이거나 번호가 없으면 null. */
val LocalStepFrom = compositionLocalOf<Int?> { null }

/**
 * [state] 가 바뀌면 단계를 넘긴다. 방향은 [order] 로 정한다(커지면 앞으로). [contentKey] 가 같은 값끼리는 넘기지 않고 그대로 바꾼다.
 * [stepNumber] 는 단계 점 번호 — 새 칸의 단계 점이 이전 번호에서 자라게 한다.
 * [modifier] 에는 화면 바탕색을 준다 — 두 칸이 함께 반쯤 투명한 동안 그 아래 화면(홈 등)이 비치지 않게.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun <S> StepTransition(
    state: S,
    order: (S) -> Int,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    stepNumber: (S) -> Int? = { null },
    content: @Composable (S) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val shiftPx = with(LocalDensity.current) { StepMotion.SHIFT_DP.dp.roundToPx() }
    // 지난 단계와 방향 — 구성 중에 바로 정해 같은 판에서 칸들이 읽는다(스냅샷 상태가 아니어서 되돌아 쓰기가 아니다).
    val memo = remember { StepMemo(state) }
    if (contentKey(state) != contentKey(memo.last)) {
        memo.forward = order(state) >= order(memo.last)
        memo.from = stepNumber(memo.last)
        memo.last = state
    }
    // 칸 함수가 방향을 붙잡아 두어, 방향이 바뀌면 나가는 칸도 다시 그려져 새 방향으로 나간다.
    val forward = memo.forward
    val from = memo.from
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            val millis = if (reduceMotion) StepMotion.REDUCED_MILLIS else StepMotion.MILLIS
            val easing = if (reduceMotion) LinearEasing else ProtoMotion.EaseOutSheet
            fadeIn(tween(millis, easing = easing)) togetherWith fadeOut(tween(millis, easing = easing))
        },
        label = "step",
    ) { shown ->
        val entryFrom = remember { from }
        CompositionLocalProvider(
            LocalStepSlot provides StepSlot(this, forward, reduceMotion, shiftPx),
            LocalStepFrom provides entryFrom,
        ) {
            content(shown)
        }
    }
}

private class StepMemo<S>(
    var last: S,
) {
    var forward = true
    var from: Int? = null
}

/** 단계 넘김에서 옆으로 밀리는 부분. [StepTransition] 밖이거나 동작 줄이기면 아무것도 하지 않는다. */
@Composable
fun Modifier.stepSlide(): Modifier {
    val slot = LocalStepSlot.current ?: return this
    if (slot.reduceMotion) return this
    val spec = tween<IntOffset>(StepMotion.MILLIS, easing = ProtoMotion.EaseOutSheet)
    val shift = slot.shiftPx
    val enter: EnterTransition = slideInHorizontally(spec) { if (slot.forward) shift else -shift }
    val exit: ExitTransition = slideOutHorizontally(spec) { if (slot.forward) -shift else shift }
    return with(slot.scope) { this@stepSlide.animateEnterExit(enter = enter, exit = exit, label = "stepSlide") }
}
