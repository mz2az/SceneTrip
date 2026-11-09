package com.mz2az.scenetrip.newcourse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XmarkIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 도시 질문은 속도를 묻지 않는다 — 보통(하루 5곳). */
internal val CITY_PACE = TripPace.normal

/**
 * 「알아서 짜 주세요」 — 도시 추천 코스 미리보기 (MZ2AZ-547 R1–R5, 웹 `proto/ProtoScreens.tsx` `PlanPreview`, iOS `CityPlanPreview`).
 *
 * 그 도시 촬영지(인기순)를 받아 **앱 안에서** 잇는다([CityPlanRules.plan], 서버 창구 없음). 카드를 꾹 눌러 그날 안에서 순서를 바꾸고,
 * 사진 위 작은 단추로 다른 곳으로 바꾸거나 뺀다. 「이 코스로 만들기」 → 서버에 저장(`POST`+`PUT`) → 정한 여행에 코스 번호 →
 * 방금 만든 코스로 표시하고 계획 화면을 연다(R5·R6).
 */
@Composable
fun CityPlanPreview(
    cityKey: String,
    days: Int,
    routes: RouteStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<List<List<PlaceSummary>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val cityName = CityPlacesStore.city(cityKey)?.name ?: cityKey
    val places = CityPlacesStore.places[cityKey]

    fun save(made: List<List<PlaceSummary>>) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val course =
                CityPlanRules.course(
                    title = NewCourseText.courseTitle(cityName, days),
                    plan = made,
                    pace = CITY_PACE,
                    madeByAI = true,
                )
            val id = routes.save(course, origin = "city")?.serverId
            busy = false
            if (id == null) {
                failed = true
                return@launch
            }
            TripMemoryStore.getInstance(context).set(TripMemo(cityKey, days, CITY_PACE, id))
            TabRouter.courseCreated(id, cityName, days)
        }
    }

    LaunchedEffect(places?.size) {
        if (plan != null) return@LaunchedEffect
        if (places == null) {
            CityPlacesStore.loadPlaces(cityKey)
            return@LaunchedEffect
        }
        // 「짜는 중」 을 잠깐 보인다 — 바로 바뀌면 무엇이 일어났는지 모른다(웹과 같은 0.9초).
        delay(PLANNING_MILLIS)
        val made = CityPlanRules.plan(places, days, CITY_PACE)
        plan = made
        // 확인용 뒷문 — 짜자마자 「이 코스로 만들기」 를 누른 것처럼 저장한다(프로세스에 한 번, 서버에 코스가 생긴다).
        if (NewCourseDebug.cityAutoSave && !NewCourseDebug.autoSaved) {
            NewCourseDebug.autoSaved = true
            save(made)
        }
    }

    val current = plan
    if (current == null) {
        NewCourseScreen(onBack = onBack) {
            NewCourseTitle(tr("코스를 짜는 중이에요…"))
            if (cityKey in CityPlacesStore.placeFailures) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("촬영지를 못 불러왔어요"), fontSize = 15.sp, color = IOS.secondaryLabel)
                    NewCourseSecondaryButton(tr("다시 시도")) { scope.launch { CityPlacesStore.loadPlaces(cityKey) } }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                    Text(tr("촬영지를 동선대로 잇고 있어요"), fontSize = 15.sp, color = IOS.secondaryLabel)
                }
            }
        }
        return
    }

    val empty = current.all { it.isEmpty() }
    NewCourseScreen(
        onBack = onBack,
        footer = {
            if (failed) {
                Text(
                    tr("저장하지 못했어요. 다시 눌러 주세요"),
                    fontSize = 13.sp,
                    color = IOS.systemRed,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            NewCoursePrimaryButton(
                title = if (busy) tr("저장하는 중…") else tr("이 코스로 만들기"),
                disabled = busy || empty,
            ) { save(current) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NewCourseTitle(NewCourseText.planTitle(cityName, days))
            Text(tr("꾹 눌러 순서를 바꾸고, 빼거나 다른 곳으로 바꾼 뒤 저장하세요"), fontSize = 14.sp, color = IOS.secondaryLabel)
        }
        if (empty) {
            Text(
                tr("이 도시는 촬영지가 적어서 코스를 못 짰어요. 뒤로 가서 「직접 고를게요」 를 눌러 주세요"),
                fontSize = 15.sp,
                color = IOS.secondaryLabel,
            )
        }
        current.forEachIndexed { day, stops ->
            PlanDayList(
                day = day,
                stops = stops,
                onMove = { from, to -> plan = CityPlanRules.moved(plan ?: current, day, from, to) },
                onSwap = { index -> plan = CityPlanRules.swapped(plan ?: current, day, index, places.orEmpty()) },
                onRemove = { index -> plan = CityPlanRules.removed(plan ?: current, day, index) },
            )
        }
    }
}

private const val PLANNING_MILLIS = 900L

/** 카드 사이 간격. */
private val CARD_GAP = 12.dp

/**
 * 하루치 카드 줄 — 꾹 0.3초 눌러 들고 끌면 그날 안에서 순서가 바뀐다 (R4, 웹 `useHoldReorder`).
 *
 * 들리기 전에 8dp 넘게 움직이면(또는 바깥 스크롤이 먼저 가져가면) 넘기기로 보고 놓아 준다. 들린 카드는 1.02배·그림자·햅틱.
 * 이웃 카드(+간격) 높이의 절반을 넘으면 자리를 바꾼다([HoldReorderRules.step]). 손가락은 **화면 기준**으로 잰다 — 카드가 자리를
 * 바꾸면 카드 안 좌표가 한꺼번에 튀기 때문이다.
 */
@Composable
private fun PlanDayList(
    day: Int,
    stops: List<PlaceSummary>,
    onMove: (Int, Int) -> Unit,
    onSwap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Long, Float>() }
    val latestStops by rememberUpdatedState(stops)
    val latestMove by rememberUpdatedState(onMove)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gap = with(density) { CARD_GAP.toPx() }
    val slop = with(density) { HoldReorderRules.SLOP_DP.dp.toPx() }
    val lift = with(density) { 10.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        ) {
            Text(tr("%d일차").format(day + 1), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = IOS.label)
            Text(tr("%d곳").format(stops.size), fontSize = 13.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(bottom = 1.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            stops.forEachIndexed { index, place ->
                key(place.id) {
                    val lifted = dragging == place.id
                    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                    val up = tr("위로 옮기기")
                    val down = tr("아래로 옮기기")
                    PlanSpotCard(
                        place = place,
                        number = index + 1,
                        onSwap = { onSwap(index) },
                        onRemove = { onRemove(index) },
                        modifier =
                            Modifier
                                .zIndex(if (lifted) 2f else 0f)
                                .onGloballyPositioned {
                                    coords[0] = it
                                    heights[place.id] = it.size.height.toFloat()
                                }.pointerInput(place.id) {
                                    awaitEachGesture {
                                        val first = awaitFirstDown(requireUnconsumed = false)
                                        // 0.3초 안에 떼거나, 8dp 넘게 움직이거나, 바깥 스크롤이 가져가면 꾹 누르기가 아니다(null 이 아니면 놓아 준다).
                                        val released =
                                            withTimeoutOrNull(HoldReorderRules.HOLD_MILLIS) {
                                                var gone = false
                                                while (!gone) {
                                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id }
                                                    gone = change == null ||
                                                        !change.pressed ||
                                                        change.isConsumed ||
                                                        (change.position - first.position).getDistance() > slop
                                                }
                                                true
                                            }
                                        if (released != null) return@awaitEachGesture
                                        val startY = coords[0]?.localToRoot(first.position)?.y ?: return@awaitEachGesture
                                        var consumed = 0f
                                        dragging = place.id
                                        offset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        drag(first.id) { change ->
                                            change.consume()
                                            val y = coords[0]?.localToRoot(change.position)?.y ?: return@drag
                                            val list = latestStops
                                            val at = list.indexOfFirst { it.id == place.id }
                                            if (at < 0) return@drag
                                            val below = list.getOrNull(at + 1)?.let { heights[it.id] }?.plus(gap)
                                            val above = list.getOrNull(at - 1)?.let { heights[it.id] }?.plus(gap)
                                            val dy = (y - startY) - consumed
                                            val step = HoldReorderRules.step(at, list.size, dy, below, above)
                                            if (step.to != at) {
                                                consumed += dy - step.offset
                                                latestMove(at, step.to)
                                            }
                                            offset = step.offset
                                        }
                                        dragging = null
                                        offset = 0f
                                    }
                                }.graphicsLayer {
                                    translationY = if (lifted) offset else 0f
                                    val scale = if (lifted) 1.02f else 1f
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = if (lifted) lift else 0f
                                    shape = RoundedCornerShape(NewCourseStyle.radiusL)
                                }.semantics {
                                    customActions =
                                        buildList {
                                            if (index > 0) add(CustomAccessibilityAction(up) { onMove(index, index - 1).let { true } })
                                            if (index < stops.size - 1) {
                                                add(CustomAccessibilityAction(down) { onMove(index, index + 1).let { true } })
                                            }
                                        }
                                },
                    )
                }
            }
        }
    }
}

/** 추천 코스의 촬영지 카드 — 사진 16:9 위, 그 아래 「어느 작품의」(보라 표) 「어떤 장소」(이름) 「어떤 장면」(설명) (R3). */
@Composable
private fun PlanSpotCard(
    place: PlaceSummary,
    number: Int,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusL)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(2.dp, shape)
                .clip(shape)
                .background(IOS.systemBackground),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NewCoursePhoto(place.imageUrl?.toString(), Modifier.fillMaxSize(), edge = 720)
            Text(
                "$number",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .heightIn(min = 26.dp)
                        .widthIn(min = 26.dp)
                        .clip(CircleShape)
                        .background(NewCourseStyle.purple)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                PhotoRoundButton(tr("다른 곳으로 바꾸기"), onSwap) { tint -> ArrowClockwiseIcon(tint, Modifier.size(14.dp)) }
                Box(Modifier.offset(x = (-6).dp)) {
                    PhotoRoundButton(tr("빼기"), onRemove) { tint -> XmarkIcon(tint, Modifier.size(12.dp)) }
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        ) {
            val work = place.contents?.firstOrNull()?.title
            if (!work.isNullOrEmpty()) WorkTag(work)
            Text(
                place.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                color = IOS.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val scene = place.sceneDescription?.trim().orEmpty()
            if (scene.isNotEmpty()) {
                Text(
                    scene,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = IOS.secondaryLabel,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (!place.address.isNullOrEmpty()) {
                Text(place.address.orEmpty(), fontSize = 13.sp, color = IOS.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
