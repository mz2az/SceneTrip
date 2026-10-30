package com.mz2az.scenetrip.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.RoutePoiGroup
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.iosLineBox
import com.mz2az.scenetrip.ui.iosShadow
import kotlinx.coroutines.launch

/**
 * 첫 실행에 한 번 보여 주는 사용법 넉 장. iOS `Onboarding/OnboardingView.swift`를 옮긴 것이다.
 *
 * 언어 선택에서 고른 언어로 한 벌만 표시한다. 큰 글자는 세로로 스크롤할 수 있다.
 *
 * 앱이 실제로 하는 일이 넷이라 넉 장이다 — 검색 / AI 코스 / 길찾기 / 반경 POI·챗봇.
 * 다섯 장을 넘으면 사람이 「Skip」을 누른다.
 */
private data class Lesson(
    val pose: PinoPose,
    val title: String,
    val body: String,
)

private val LESSONS =
    listOf(
        Lesson(
            pose = PinoPose.MAGNIFIER,
            title = "그 장면,\n어디서 찍었을까",
            body = "드라마·영화 제목으로도, 장면 설명으로도 찾아요.\n실제 촬영지가 바로 지도에 뜹니다.",
        ),
        Lesson(
            pose = PinoPose.SPARKLE,
            title = "속도만 정하세요.\n일정은 해태가 짭니다.",
            body = "원하는 속도에 맞춰 가까운 촬영지로 일정을 짭니다.\n완성된 초안은 원하는 대로 바꿀 수 있어요.",
        ),
        Lesson(
            pose = PinoPose.PAW,
            title = "가고 싶어지면\n길찾기를 누르세요",
            body = "코스를 따라 다음 장소로 가는 길을 확인해요.\n필요하면 지도 앱으로 길찾기를 이어갈 수 있어요.",
        ),
        Lesson(
            pose = PinoPose.SPEECH,
            title = "가는 길에 먹고,\n막히면 물어보세요",
            body = "주변의 음식점·명소·교통·숙소를 보여 줘요.\n촬영지와 여행 계획을 해태에게 물어보세요.",
        ),
    )

@Composable
fun OnboardingView(
    onboardingFlag: OnboardingFlag,
    onDone: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { LESSONS.size })
    val scope = rememberCoroutineScope()
    val progress = remember { OnboardingProgress(LESSONS.size) }
    val isLast = pagerState.currentPage == LESSONS.lastIndex

    fun finish() {
        if (!progress.finish()) return
        onboardingFlag.markSeen()
        AppAnalytics.log(AppEvent.TutorialComplete)
        onDone()
    }

    LaunchedEffect(Unit) { AppAnalytics.log(AppEvent.TutorialBegin) }
    LaunchedEffect(pagerState.currentPage) { progress.select(pagerState.currentPage) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(IOS.systemBackground),
    ) {
        // 마지막 장에는 두지 않는다 — 거기 버튼이 이미 「Get started」다.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!isLast) {
                TextButton(onClick = { finish() }) {
                    Text(tr("건너뛰기"), fontSize = 17.sp, color = IOS.secondaryLabel)
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            LessonPage(lesson = LESSONS[page], index = page)
        }

        PageDots(
            count = LESSONS.size,
            current = pagerState.currentPage,
            modifier =
                Modifier.padding(top = 8.dp, bottom = 12.dp).fillMaxWidth().semantics {
                    contentDescription = tr("사용법 %d / %d").format(pagerState.currentPage + 1, LESSONS.size)
                },
        )
        OnboardingNavigation(
            page = pagerState.currentPage,
            isLast = isLast,
            previous = {
                scope.launch {
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage - 1).coerceAtLeast(0),
                        animationSpec = tween(280),
                    )
                }
            },
            next = {
                if (isLast) {
                    finish()
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1).coerceAtMost(LESSONS.lastIndex),
                            animationSpec = tween(280),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun OnboardingNavigation(
    page: Int,
    isLast: Boolean,
    previous: () -> Unit,
    next: () -> Unit,
) {
    val large = LocalDensity.current.fontScale >= 1.6f
    val previousButton: @Composable () -> Unit = {
        TextButton(onClick = previous, enabled = page > 0, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(
                tr("이전"),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (page > 0) IOS.accent else IOS.tertiaryLabel,
            )
        }
    }
    val nextButton: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.accent)
                .clickable(onClick = next)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr(if (isLast) "시작하기" else "다음"),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
    val modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
    if (large) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            previousButton()
            nextButton(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            previousButton()
            nextButton(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LessonPage(
    lesson: Lesson,
    index: Int,
) {
    // 곁들인 그림이 한쪽에 몰린 장에서는 피노를 반대쪽으로 비켜 세운다.
    // ③은 특히 작다 — 경로선이 화면을 가로지르는 장이라 경로가 주인공이다. 처음에
    // 다른 장과 같은 크기로 두면 앞발이 경로 위에 얹혀 "2호선 · 6개 역"을 가린다.
    val mascotWidth = if (index == 2) 128.dp else 186.dp
    val mascotShift =
        when (index) {
            1 -> Offset(62f, 0f)
            2 -> Offset(-100f, -74f)
            else -> Offset.Zero
        }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawingSize = minOf(300.dp, (maxWidth - 32.dp).coerceAtLeast(1.dp))
        val scale = drawingSize / 300.dp
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(modifier = Modifier.size(drawingSize), contentAlignment = Alignment.Center) {
                Box(
                    modifier =
                        Modifier.requiredSize(300.dp).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Backdrop(index)
                    PinoMascot(
                        pose = lesson.pose,
                        width = mascotWidth,
                        modifier = Modifier.offset(x = mascotShift.x.dp, y = mascotShift.y.dp),
                    )
                }
            }

            Text(
                text = tr(lesson.title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                // 큰 글자는 iOS 줄 간격이 약 1.16em 이다 — 앱 기본 1.3em 을 그대로 두면 두 줄 제목이 벌어졌다.
                lineHeight = 1.16.em,
                textAlign = TextAlign.Center,
                color = IOS.label,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = tr(lesson.body),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                lineHeight = 23.sp,
                color = IOS.secondaryLabel,
            )
        }
    }
}

@Composable
private fun Backdrop(index: Int) {
    when (index) {
        0 -> MapFragment()
        1 -> DayCards()
        2 -> LegTrace()
        else -> RadiusChips()
    }
}

/** ① 지도 조각과 다른 촬영지 핀들. */
@Composable
private fun MapFragment() {
    val spots = listOf(Offset(-104f, -78f), Offset(112f, 98f), Offset(108f, -108f))
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(268.dp, 236.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(IOS.systemGray6),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridColor = IOS.systemGray4
                val stroke = 7.dp.toPx()
                drawLine(gridColor, Offset(0f, h * 0.39f), Offset(w, h * 0.39f), stroke)
                drawLine(gridColor, Offset(0f, h * 0.746f), Offset(w, h * 0.746f), stroke)
                drawLine(gridColor, Offset(w * 0.291f, 0f), Offset(w * 0.291f, h), stroke)
                drawLine(gridColor, Offset(w * 0.731f, 0f), Offset(w * 0.731f, h), stroke)
            }
        }

        spots.forEach { spot ->
            MiniPin(tint = IOS.systemGray3, modifier = Modifier.offset(x = spot.x.dp, y = spot.y.dp))
        }
    }
}

/** ② 일차 카드 세 장. */
@Composable
private fun DayCards() {
    data class Day(
        val title: String,
        val places: List<String>,
        val fade: Float,
    )
    val days =
        listOf(
            Day("1일차", listOf("덕수궁 돌담길", "정동길 · 서울시청"), 1f),
            Day("2일차", listOf("북촌한옥마을", "삼청동길 · 경복궁"), 1f),
            // 셋째 장은 흐리게 — "더 있다"를 잘린 카드 없이 말한다.
            Day("3일차", listOf("주문진 방파제"), 0.55f),
        )

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEach { day ->
            Column(
                modifier =
                    Modifier
                        .width(132.dp)
                        // iOS `.shadow(.black.opacity(0.10), radius: 5, y: 2)` — 고도 그림자는 투명도에 플랫폼 상수가 또 곱해져
                        // 흰 화면 위에서 카드 가장자리가 안 보였다(2026-10-29 감사 #12). `iosShadow` 가 iOS 값 그대로 그린다.
                        .iosShadow(Color.Black.copy(alpha = 0.10f * day.fade), radius = 5.dp, y = 2.dp, cornerRadius = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // 흐림은 `alpha` 층이 아니라 그림자·글자 색에 곱한다 — 층은 카드 크기로 잘려 아래 그림자가 평평하게 끊겼다(5차 감사).
                        // 바탕은 흰색 그대로 둔다(흰 화면 위라 같은 색이고, 반투명이면 밑의 그림자가 비쳐 회색이 된다).
                        .background(IOS.systemBackground)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // 줄 칸을 iOS 글자 높이(11→13.2 · 12→14.4)로 고정한다 — 한글 글꼴 제 높이(1.44em)로 카드가 65 → 74 로 커져
                // 셋째 카드가 그림 칸 아래에서 잘렸다(5차 감사 lesson-2).
                Text(
                    tr(day.title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = IOS.pinDeep.copy(alpha = day.fade),
                    modifier = Modifier.iosLineBox(13.2.dp),
                )
                day.places.forEach { place ->
                    Text(tr(place), fontSize = 12.sp, color = IOS.label.copy(alpha = day.fade), modifier = Modifier.iosLineBox(14.4.dp))
                }
            }
        }
    }
}

/** ③ 도보(점선) → 대중교통(실선) → 도보. 길찾기 결과 화면이 실제로 그리는 모양이다. */
@Composable
private fun LegTrace() {
    Box(modifier = Modifier.size(300.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // iOS 소스의 좌표는 pt(≈dp) 값이다 — Canvas 는 실제 픽셀 단위라 dp.toPx()로
            // 바꾸지 않으면 밀도가 높은 기기에서 그림 전체가 왼쪽 위 구석으로 쪼그라든다.
            fun px(v: Float) = v.dp.toPx()

            fun point(
                x: Float,
                y: Float,
            ) = Offset(px(x), px(y))

            val dash = PathEffect.dashPathEffect(floatArrayOf(px(1f), px(9f)))

            val walk1 =
                Path().apply {
                    moveTo(px(42f), px(246f))
                    cubicTo(px(62f), px(232f), px(70f), px(216f), px(90f), px(208f))
                }
            drawPath(
                walk1,
                color = IOS.accent,
                style = Stroke(width = px(5f), cap = StrokeCap.Round, pathEffect = dash),
            )

            val transit =
                Path().apply {
                    moveTo(px(90f), px(208f))
                    cubicTo(px(136f), px(186f), px(162f), px(120f), px(200f), px(92f))
                }
            drawPath(transit, color = IOS.pinDeep, style = Stroke(width = px(6f), cap = StrokeCap.Round))

            val walk2 =
                Path().apply {
                    moveTo(px(200f), px(92f))
                    cubicTo(px(220f), px(78f), px(230f), px(62f), px(244f), px(54f))
                }
            drawPath(
                walk2,
                color = IOS.accent,
                style = Stroke(width = px(5f), cap = StrokeCap.Round, pathEffect = dash),
            )

            drawCircle(IOS.systemBackground, radius = px(9f), center = point(42f, 246f))
            drawCircle(
                IOS.accent,
                radius = px(9f),
                center = point(42f, 246f),
                style = Stroke(width = px(5f)),
            )
            drawCircle(IOS.pinDeep, radius = px(6f), center = point(90f, 208f))
            drawCircle(IOS.pinDeep, radius = px(6f), center = point(200f, 92f))
        }

        MiniPin(tint = IOS.pinDeep, modifier = Modifier.offset(x = 231.dp, y = 27.dp))

        // iOS `.position(point)` 은 글자 **가운데**를 그 점에 놓는다 — 전에는 왼쪽 위 모서리를 놓아 라벨이 점선과 겹쳤다(7차 감사 O3).
        Caption("현재 위치", cx = 60.dp, cy = 268.dp)
        Caption("도보 4분", cx = 130.dp, cy = 220.dp)
        Caption("2호선 · 6개 역", cx = 196.dp, cy = 154.dp)
        Caption("도보 6분", cx = 246.dp, cy = 78.dp)
    }
}

@Composable
private fun Caption(
    text: String,
    cx: Dp,
    cy: Dp,
) {
    Text(
        text = tr(text),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = IOS.secondaryLabel,
        modifier =
            Modifier.layout { measurable, _ ->
                val placeable =
                    measurable.measure(
                        androidx.compose.ui.unit
                            .Constraints(),
                    )
                layout(0, 0) { placeable.place(cx.roundToPx() - placeable.width / 2, cy.roundToPx() - placeable.height / 2) }
            },
    )
}

/**
 * ④ 반경과 갈래 넷. 색은 [RoutePoiTone.of]를 그대로 부른다 — 두 벌로 적으면 튜토리얼에서
 * 본 색과 실제 화면 색이 갈린다.
 */
@Composable
private fun RadiusChips() {
    data class Chip(
        val group: RoutePoiGroup,
        val label: String,
        val place: Offset,
    )
    val chips =
        listOf(
            Chip(RoutePoiGroup.FOOD, "음식점", Offset(-116f, -100f)),
            Chip(RoutePoiGroup.SIGHT, "명소", Offset(122f, 4f)),
            Chip(RoutePoiGroup.TRANSIT, "교통", Offset(-112f, 66f)),
            Chip(RoutePoiGroup.STAY, "숙소", Offset(104f, 92f)),
        )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(262.dp)
                    .clip(CircleShape)
                    .background(IOS.accent.copy(alpha = 0.06f)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = IOS.accent.copy(alpha = 0.45f),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style =
                        Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                        ),
                )
            }
        }

        chips.forEach { chip ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .offset(x = chip.place.x.dp, y = chip.place.y.dp)
                        .shadow(
                            4.dp,
                            RoundedCornerShape(50),
                            ambientColor = Color.Black.copy(alpha = 0.10f),
                            spotColor = Color.Black.copy(alpha = 0.10f),
                        ).clip(RoundedCornerShape(50))
                        .background(IOS.systemBackground)
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(RoutePoiTone.of(chip.group)))
                Text(tr(chip.label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            }
        }
    }
}

/**
 * 곁들인 그림에 쓰는 작은 핀. 지도의 실제 핀은 `NaverMap.kt`가 그리는 것과 별개로,
 * 삽화용은 원 + 삼각 꼬리로 근사한다(그림자·테두리는 뺀다 — iOS와 동일하게 장식용).
 */
@Composable
private fun MiniPin(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(26.dp, 34.dp)) {
        val r = size.width / 2f
        drawCircle(tint, radius = r, center = Offset(r, r))
        val tip =
            Path().apply {
                moveTo(r * 0.35f, r * 1.55f)
                lineTo(r, size.height)
                lineTo(r * 1.65f, r * 1.55f)
                close()
            }
        drawPath(tip, color = tint)
    }
}

@Composable
private fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val active = index == current
            val width by animateFloatAsState(if (active) 20f else 7f, tween(220), label = "dot")
            Box(
                modifier =
                    Modifier
                        .size(width = width.dp, height = 7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) IOS.accent else IOS.systemGray4),
            )
        }
    }
}
