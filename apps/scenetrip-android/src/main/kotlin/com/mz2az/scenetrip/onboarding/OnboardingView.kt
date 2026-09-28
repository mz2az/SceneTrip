package com.mz2az.scenetrip.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.RoutePoiGroup
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 첫 실행에 한 번 보여 주는 사용법 넉 장. iOS `Onboarding/OnboardingView.swift`를 옮긴 것이다.
 *
 * **영어가 본문이다** — 이 앱은 외국인이 쓰는 앱이다. 본체 UI 의 영문화는 여기서
 * 하지 않는다(별도 일감). 한국어는 팀 검수용 흐린 보조줄로만 남긴다.
 *
 * 앱이 실제로 하는 일이 넷이라 넉 장이다 — 검색 / AI 코스 / 길찾기 / 반경 POI·챗봇.
 * 다섯 장을 넘으면 사람이 「Skip」을 누른다.
 */
private data class Lesson(
    val pose: PinoPose,
    val title: String,
    val body: String,
    val korean: String,
)

private val LESSONS =
    listOf(
        Lesson(
            pose = PinoPose.MAGNIFIER,
            title = "Where the scene\nwas filmed",
            body = "Search by drama, movie, or the scene itself.\nReal locations, straight onto the map.",
            korean = "드라마 이름으로도, 장면 설명으로도 찾는다",
        ),
        Lesson(
            pose = PinoPose.SPARKLE,
            title = "Set your pace.\nJINDO plans the days.",
            // 7과 3은 지어낸 수가 아니라 계약 GuidePlanRequest.pace 의 값이다(빡빡 7 · 여유 3).
            // 그쪽을 고치면 이 문장도 함께 고쳐야 한다(MZ2AZ-321).
            body = "Packed fits 7 stops a day, Easy fits 3.\nNearby spots get grouped, day by day.",
            korean = "빡빡하게 하루 7곳 · 널널하게 3곳",
        ),
        Lesson(
            pose = PinoPose.PAW,
            title = "Tap Directions\nwhen you feel like going",
            body = "From wherever you are standing — subway,\nbus, and every turn of the walk.",
            korean = "지금 서 있는 자리에서 지하철·버스·골목까지",
        ),
        Lesson(
            pose = PinoPose.SPEECH,
            title = "Eat on the way.\nAsk when you are stuck.",
            body = "Restaurants, sights, transit and stays\naround you. JINDO handles the Korean.",
            korean = "반경 안의 음식점·명소·교통·숙소, 그리고 챗봇",
        ),
    )

@Composable
fun OnboardingView(
    onboardingFlag: OnboardingFlag,
    onDone: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { LESSONS.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == LESSONS.lastIndex

    fun finish() {
        onboardingFlag.markSeen()
        onDone()
    }

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
                    Text("Skip", fontSize = 17.sp, color = IOS.secondaryLabel)
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
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp).fillMaxWidth(),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // iOS 는 안전 영역(홈 인디케이터) **위로** 40 띄운다 — 제스처 막대를 빼지 않았더니
                    // 버튼이 iOS 보다 바닥에 붙었다(2026-09-28 대조: 아래 여백 40 대 74).
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 40.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IOS.accent)
                    .clickable {
                        if (isLast) {
                            finish()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1,
                                    animationSpec = tween(280),
                                )
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isLast) "Get started" else "Next",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            Backdrop(index)
            PinoMascot(
                pose = lesson.pose,
                width = mascotWidth,
                modifier = Modifier.offset(x = mascotShift.x.dp, y = mascotShift.y.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = lesson.title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            // 큰 글자는 iOS 줄 간격이 약 1.16em 이다 — 앱 기본 1.3em 을 그대로 두면 두 줄 제목이 벌어졌다.
            lineHeight = 1.16.em,
            textAlign = TextAlign.Center,
            color = IOS.label,
        )
        Text(
            text = lesson.body,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(top = 12.dp),
        )
        // 한국어는 팀 검수용 보조줄이다. 영어보다 확실히 흐려야 "본문이 둘"로 보이지 않는다.
        Text(
            text = lesson.korean,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = IOS.tertiaryLabel,
            modifier = Modifier.padding(top = 14.dp),
        )

        Spacer(Modifier.weight(1f))
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
                        .alpha(day.fade)
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(IOS.systemBackground)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(day.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = IOS.pinDeep)
                day.places.forEach { place -> Text(place, fontSize = 12.sp, color = IOS.label) }
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

        Caption("현재 위치", x = 30.dp, y = 261.dp)
        Caption("도보 4분", x = 100.dp, y = 213.dp)
        Caption("2호선 · 6개 역", x = 156.dp, y = 147.dp)
        Caption("도보 6분", x = 216.dp, y = 71.dp)
    }
}

@Composable
private fun Caption(
    text: String,
    x: Dp,
    y: Dp,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = IOS.secondaryLabel,
        modifier = Modifier.offset(x = x, y = y),
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
            Chip(RoutePoiGroup.FOOD, "Food", Offset(-116f, -100f)),
            Chip(RoutePoiGroup.SIGHT, "Sights", Offset(122f, 4f)),
            Chip(RoutePoiGroup.TRANSIT, "Transit", Offset(-112f, 66f)),
            Chip(RoutePoiGroup.STAY, "Stays", Offset(104f, 92f)),
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
                        .shadow(3.dp, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .background(IOS.systemBackground)
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(RoutePoiTone.of(chip.group)))
                Text(chip.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
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
