package com.mz2az.scenetrip.newcourse

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XmarkIcon
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.launch

/** 도시 질문은 속도를 묻지 않는다 — 보통(하루 5곳). */
internal val CITY_PACE = TripPace.normal

/**
 * 「알아서 짜 주세요」 — 도시 추천 코스 미리보기 (MZ2AZ-547 R1–R5, 웹 `proto/ProtoScreens.tsx` `PlanPreview`, iOS `CityPlanPreview`).
 *
 * 서버 AI 일정(`POST /guide/plan {city}`, MZ2AZ-548 — [RouteStore.planCity])을 응답만으로 카드로 그린다. 도시 촬영지 목록은 받지 않고,
 * 못 받으면 앱 계산으로 대신하지 않고 「다시 시도」 를 보인다(2026-11-10 사용자 결정, 계획 city-ai-course.md §5).
 * 사진 위 작은 단추로 다른 곳으로 바꾸거나 뺀다(꾹 눌러 순서 바꾸기는 뺐다). 사진을 누르면 뒤집혀 장면 그림([SceneFlipPhoto], §6).
 * 「이 코스로 만들기」 → 서버에 저장(`POST`+`PUT`) → 정한 여행에 코스 번호 → 방금 만든 코스로 표시하고 계획 화면을 연다(R5·R6).
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
    val reduceMotion = rememberReduceMotion()
    var plan by remember { mutableStateOf<List<List<PlaceSummary>>?>(null) }
    // AI 일정을 못 받았다 — 「다시 시도」. 누를 때마다 [attempt] 가 올라 다시 묻는다.
    var planFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val cityName = CityPlacesStore.city(cityKey)?.name ?: cityKey

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

    /** 「다른 곳으로 바꾸기」 — 그 카드 자리에서 가까운 그 도시 촬영지를 물어 아직 코스에 없는 첫 곳으로. 못 받으면 그대로. */
    fun swap(
        day: Int,
        index: Int,
    ) {
        val spot = plan?.getOrNull(day)?.getOrNull(index) ?: return
        scope.launch {
            val nearby = routes.nearbyCityPlaces(cityKey, spot.latitude, spot.longitude) ?: return@launch
            val latest = plan ?: return@launch
            plan = CityPlanRules.swapped(latest, day, index, nearby)
        }
    }

    LaunchedEffect(attempt) {
        if (plan != null) return@LaunchedEffect
        planFailed = false
        val made = routes.planCity(cityKey, days, CITY_PACE)
        if (made == null) {
            planFailed = true
            return@LaunchedEffect
        }
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
            if (planFailed) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("코스를 못 짰어요"), fontSize = 15.sp, color = IOS.secondaryLabel)
                    NewCourseSecondaryButton(tr("다시 시도")) { attempt += 1 }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                    Text(tr("AI 가 동선을 짜고 있어요"), fontSize = 15.sp, color = IOS.secondaryLabel)
                }
            }
        }
        return
    }

    // 처음 한 번 살짝 돌 카드 — 첫 카드(모든 카드가 뒤집힌다, §6 표, [SceneFlipRules.peekIndex]).
    val cards = current.flatten()
    val peekId =
        SceneFlipRules
            .peekIndex(
                flippable = cards.map { true },
                hasFlipped = SceneFlipStore.hasFlipped(context),
                reduceMotion = reduceMotion,
            )?.let { cards[it].id }
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
                disabled = busy || current.all { it.isEmpty() },
            ) { save(current) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NewCourseTitle(NewCourseText.planTitle(cityName, days))
            Text(tr("빼거나 다른 곳으로 바꾼 뒤 저장하세요"), fontSize = 14.sp, color = IOS.secondaryLabel)
        }
        current.forEachIndexed { day, stops ->
            PlanDayList(
                day = day,
                stops = stops,
                peekId = peekId,
                onSwap = { index -> swap(day, index) },
                onRemove = { index -> plan = CityPlanRules.removed(plan ?: current, day, index) },
            )
        }
    }
}

/** 하루치 카드 줄 (R4). 꾹 눌러 순서 바꾸기는 뺐다(2026-11-10 사용자 결정 — 스크롤과 엉켜 동작하지 않았다, city-ai-course.md §5). */
@Composable
private fun PlanDayList(
    day: Int,
    stops: List<PlaceSummary>,
    peekId: Long?,
    onSwap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        ) {
            // 날 사이가 카드에 묻히지 않게 크게(2026-11-10 사용자 요청, iOS 17 → 24).
            Text(tr("%d일차").format(day + 1), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = IOS.label)
            Text(tr("%d곳").format(stops.size), fontSize = 15.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(bottom = 3.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stops.forEachIndexed { index, place ->
                // 장소마다 열쇠를 둔다 — 「다른 곳으로 바꾸기」 로 카드가 바뀌면 사진 칸이 앞면으로 돌아간다(§6).
                key(place.id) {
                    PlanSpotCard(
                        place = place,
                        number = index + 1,
                        peek = place.id == peekId,
                        onSwap = { onSwap(index) },
                        onRemove = { onRemove(index) },
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
    peek: Boolean,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusL)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(2.dp, shape)
                .clip(shape)
                .background(IOS.systemBackground),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SceneFlipPhoto(place, peek, Modifier.fillMaxSize())
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
