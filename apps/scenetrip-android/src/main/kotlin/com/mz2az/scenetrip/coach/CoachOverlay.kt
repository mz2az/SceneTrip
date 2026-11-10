package com.mz2az.scenetrip.coach

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.appearIn
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlin.math.roundToInt

/** 지금 그리는 창의 층 번호 — 앱 창은 0, 시트 안은 그 시트의 층([CoachLayer]). */
val LocalCoachLayer = staticCompositionLocalOf { 0 }

/**
 * 시트처럼 **안내가 가리킬 단추를 품는** 대화창의 내용을 감싼다 — 층을 하나 올리고, 그 창 맨 위에 덮개 자리를 둔다.
 * 창 전체(상태줄까지)를 채우는 대화창 안에서 불러야 한다(`IOSSheet`).
 */
@Composable
fun CoachLayer(content: @Composable () -> Unit) {
    val layer = rememberCoachLayer()
    CompositionLocalProvider(LocalCoachLayer provides layer) {
        Box(Modifier.fillMaxSize()) {
            content()
            CoachOverlayHost(layer)
        }
    }
}

/** 단추를 품지 않는 대화창(확인창·사진 보기 등) — 층만 올려, 그 밑의 단추가 「가려짐」으로 보이게 한다. */
@Composable
fun CoachWindowLayer() {
    rememberCoachLayer()
}

@Composable
private fun rememberCoachLayer(): Int {
    val layer = remember { CoachCenter.pushLayer() }
    DisposableEffect(layer) { onDispose { CoachCenter.popLayer(layer) } }
    return layer
}

/** 안내 덮개의 어두운 색 — 웹 `rgba(10, 8, 25, 0.62)`. */
private val DIM = Color(red = 10, green = 8, blue = 25).copy(alpha = 0.62f)

/**
 * 안내의 그림 — 어두운 덮개에 단추 자리만 뚫고, 그 위나 아래에 말풍선 (MZ2AZ-547 P6 C1, iOS `CoachOverlay.swift`).
 *
 * 말풍선 폭 280, 제목 16 · 본문 14 · 「1 / N」 · [건너뛰기](마지막 단계엔 없음) · [다음]/[알겠어요]. 덮개를 눌러도 다음으로.
 * 자리는 [CoachRules.layout] 이 화면 가장자리·안전영역 안으로 정한다. 애니메이션 제거(움직임 줄이기)면 나타남·옮김을 움직이지 않는다.
 * 뒤로 가기는 「건너뛰기」 와 같다 — 안드로이드에서 뒤로는 「이것 닫기」 다.
 *
 * 이 층([layer])의 단추를 가리킬 때만 그린다. 그 밖에는 아무것도 없어 손짓이 그대로 아래로 간다.
 */
@Composable
fun CoachOverlayHost(layer: Int) {
    val run = CoachCenter.running ?: return
    val shown = CoachCenter.shown?.takeIf { it.layer == layer && it.run.tour.id == run.tour.id } ?: return
    OverlayContent(run, shown)
}

@Composable
private fun OverlayContent(
    run: CoachCenter.Running,
    shown: CoachCenter.Shown,
) {
    val tourId = run.tour.id
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val view = LocalView.current
    // 나타날 때 0.2초 흐리게 → 또렷하게(iOS `.transition(.opacity)`).
    val appear = remember(tourId) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(appear) { appear.animateTo(1f, tween(200)) }
    // 잰 말풍선 높이(dp). 재기 전에는 웹처럼 150 으로 어림한다.
    var bubbleHeight by remember { mutableFloatStateOf(150f) }
    BackHandler { CoachCenter.perform(CoachAction.SKIP, tourId) }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        val d = density.density
        val r = shown.screenRect
        val target =
            CoachRect(
                x = (r.left - origin[0]) / d,
                y = (r.top - origin[1]) / d,
                width = r.width / d,
                height = r.height / d,
            )
        val safeTop = WindowInsets.statusBars.getTop(density) / d
        val safeBottom = WindowInsets.navigationBars.getBottom(density) / d
        val layout =
            CoachRules.layout(target, CoachSize(maxWidth.value, maxHeight.value), safeTop, safeBottom, bubbleHeight)
        val spec = tween<Float>(if (reduceMotion) 0 else 220, easing = FastOutSlowInEasing)
        // 밝은 구멍은 0.2초 ease-out 으로 옮겨 간다(웹 `.proto-coach-hole`, MZ2AZ-547 M10).
        val holeSpec = tween<Float>(if (reduceMotion) 0 else 200, easing = LinearOutSlowInEasing)
        val holeX by animateFloatAsState(layout.hole.x, holeSpec, label = "coachHoleX")
        val holeY by animateFloatAsState(layout.hole.y, holeSpec, label = "coachHoleY")
        val holeW by animateFloatAsState(layout.hole.width, holeSpec, label = "coachHoleW")
        val holeH by animateFloatAsState(layout.hole.height, holeSpec, label = "coachHoleH")
        val bubbleX by animateFloatAsState(layout.bubble.x, spec, label = "coachBubbleX")
        val bubbleY by animateFloatAsState(layout.bubble.y, spec, label = "coachBubbleY")

        // 덮개 — 구멍 밖을 어둡게. 구멍까지 어디를 눌러도 다음으로(iOS 의 덮개도 구멍을 누르면 다음이다).
        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .pointerInput(tourId) { detectTapGestures { CoachCenter.perform(CoachAction.TAP_SCRIM, tourId) } },
        ) {
            val path =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(
                        androidx.compose.ui.geometry
                            .Rect(Offset.Zero, size),
                    )
                    addRoundRect(
                        RoundRect(
                            left = holeX.dp.toPx(),
                            top = holeY.dp.toPx(),
                            right = (holeX + holeW).dp.toPx(),
                            bottom = (holeY + holeH).dp.toPx(),
                            cornerRadius = CornerRadius(minOf(22f, holeH / 2).dp.toPx()),
                        ),
                    )
                }
            drawPath(path, DIM)
        }
        Box(
            Modifier
                .offset { IntOffset(bubbleX.dp.roundToPx(), bubbleY.dp.roundToPx()) }
                // 말풍선은 단계마다 6dp 아래에서 떠오르며 0.22초에 나타난다(웹 `.proto-coach-bubble`, MZ2AZ-547 M10).
                .then(if (reduceMotion) Modifier else Modifier.appearIn(distance = 6.dp, millis = 220, delayMillis = 0, key = run.index))
                .width(layout.bubble.width.dp),
        ) {
            if (layout.showsArrow) {
                // 꼭지 — 말풍선 밑에 깔린 45° 돌린 네모. 위쪽이면 말풍선 윗변, 아래쪽이면 아랫변에서 반쯤 나온다.
                Box(
                    Modifier
                        .offset { IntOffset(layout.arrowX.dp.roundToPx(), (if (layout.below) -7f else bubbleHeight - 9f).dp.roundToPx()) }
                        .size(16.dp)
                        .rotate(45f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(IOS.systemBackground),
                )
            }
            Bubble(
                run = run,
                modifier =
                    Modifier
                        .onSizeChanged { bubbleHeight = it.height / d }
                        .shadow(15.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.25f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(IOS.systemBackground),
            )
        }
    }
}

@Composable
private fun Bubble(
    run: CoachCenter.Running,
    modifier: Modifier,
) {
    val step = run.step
    val count = run.tour.steps.size
    val tourId = run.tour.id
    val title = tr(step.title)
    Column(
        modifier
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Polite
            }.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = IOS.label,
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        Text(tr(step.text), fontSize = 14.sp, lineHeight = 20.sp, color = IOS.secondaryLabel)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                CoachRules.counter(run.index, count),
                style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                color = IOS.tertiaryLabel,
            )
            Spacer(Modifier.weight(1f))
            if (CoachRules.showsSkip(run.index, count)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { CoachCenter.perform(CoachAction.SKIP, tourId) }
                            .padding(horizontal = 6.dp),
                ) {
                    Text(tr("건너뛰기"), fontSize = 14.sp, color = IOS.secondaryLabel)
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { CoachCenter.perform(CoachAction.NEXT, tourId) },
            ) {
                Text(
                    tr(CoachRules.primaryTitle(run.index, count)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IOS.homePurple)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
