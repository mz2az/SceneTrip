package com.mz2az.scenetrip.newcourse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.hometab.CityCovers
import com.mz2az.scenetrip.hometab.CitySlideImages
import com.mz2az.scenetrip.hometab.CitySlideRules
import com.mz2az.scenetrip.hometab.HomeCityRules
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.pressClickable
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 확인용 뒷문 (MZ2AZ-547 P3) — `MainActivity` 가 인텐트 값으로 채운다. iOS `-cityDays 2 -cityHow plan|pick`·`-worksStep works`·`-cityAutoSave 1`.
 * 에뮬레이터에 합성 탭을 덜 쓰고 화면을 바로 보려는 것이다. 프로세스에 한 번만 쓴다.
 */
object NewCourseDebug {
    var cityDays: Int = 0
    var cityHow: String? = null
    var worksStep: String? = null
    var cityAutoSave: Boolean = false
    internal var cityUsed = false
    internal var autoSaved = false
}

/**
 * 도시로 여행 짜기 — 도시 → 며칠 → 어떻게 → 추천 코스 | 직접 고르기 (MZ2AZ-547 Q1–Q3, 웹 `proto/ProtoScreens.tsx` `QuestionFlow`,
 * iOS `CityQuestionView`).
 *
 * 단계 이동은 [CityQuestionFlow] 가 정한다. 홈 카드의 도시 칩으로 들어왔으면 「며칠?」 부터고 그때 뒤로는 닫기다.
 */
@Composable
fun CityQuestionView(
    startCity: String?,
    routes: RouteStore,
    onClose: () -> Unit,
) {
    var flow by remember { mutableStateOf(applyBackdoor(CityQuestionFlow.start(startCity))) }
    val scope = rememberCoroutineScope()

    fun back() {
        val previous = flow.back()
        if (previous == null) onClose() else flow = previous
    }
    BackHandler { back() }

    LaunchedEffect(Unit) { CityPlacesStore.loadCities() }
    // 고른 도시의 촬영지는 미리 받아 둔다 — 「며칠?」·「어떻게?」 에 답하는 동안이면 넉넉하다.
    LaunchedEffect(flow.city) { flow.city?.let { CityPlacesStore.loadPlaces(it) } }

    val dots = flow.stepNumber?.let { it to 3 }
    val cityName = CityPlacesStore.city(flow.city)?.name ?: flow.city.orEmpty()
    val days = flow.days ?: 1

    when (flow.step) {
        CityStep.CITY -> {
            NewCourseScreen(onBack = ::back, step = dots, hero = { CityHero() }) {
                if (CityPlacesStore.status == CityPlacesStore.Status.FAILED) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("도시 목록을 못 불러왔어요"), fontSize = 15.sp, color = IOS.secondaryLabel)
                        NewCourseSecondaryButton(tr("다시 시도")) { scope.launch { CityPlacesStore.retry() } }
                    }
                } else {
                    val status = CityPlacesStore.status
                    CityGrid(
                        cities = CityPlacesStore.shownCities,
                        loading = status == CityPlacesStore.Status.IDLE || status == CityPlacesStore.Status.LOADING,
                    ) { flow = flow.pickCity(it) }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        tr("아직 몰라요. 그냥 둘러볼게요"),
                        fontSize = 15.sp,
                        color = IOS.secondaryLabel,
                        modifier =
                            Modifier
                                .heightIn(min = 44.dp)
                                .clickable(onClick = onClose)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        CityStep.DAYS -> {
            NewCourseScreen(onBack = ::back, step = dots) {
                NewCourseTitle(tr("%s에서 며칠 머무세요?").format(cityName))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CityQuestionFlow.DAY_OPTIONS.forEach { count ->
                        DayChoice(NewCourseText.days(count), flow.days == count) { flow = flow.pickDays(count) }
                    }
                }
            }
        }

        CityStep.HOW -> {
            val count = CityPlacesStore.city(flow.city)?.placeCount
            NewCourseScreen(onBack = ::back, step = dots) {
                NewCourseTitle(tr("코스를 어떻게 짤까요?"))
                HowCard(
                    primary = true,
                    title = tr("알아서 짜 주세요"),
                    detail =
                        tr("%s 인기 촬영지를 동선대로 이어 %s 코스를 바로 만들어요. 빼고 바꾸는 건 그다음에.")
                            // 「1박 / 2일」 로 끊기지 않게 기간 안 띄어쓰기는 붙인다(에뮬레이터 실측).
                            .format(cityName, NewCourseText.days(days).replace(' ', '\u00A0')),
                    icon = { SparklesIcon(Color.White, Modifier.size(24.dp)) },
                ) { flow = flow.planForMe() }
                HowCard(
                    primary = false,
                    title = tr("직접 고를게요"),
                    detail = tr("%s 촬영지 %s곳을 보고 가고 싶은 곳만 담아요.").format(cityName, count?.toString().orEmpty()),
                    icon = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(NewCourseStyle.purple),
                            contentAlignment = Alignment.Center,
                        ) { CheckmarkIcon(Color.White, Modifier.size(12.dp)) }
                    },
                ) { flow = flow.pickMyself() }
            }
        }

        CityStep.PLAN -> {
            CityPlanPreview(cityKey = flow.city.orEmpty(), days = days, routes = routes, onBack = ::back)
        }

        CityStep.PICK -> {
            CityPickView(cityKey = flow.city.orEmpty(), days = days, routes = routes, onBack = ::back)
        }
    }
}

/** 확인용 뒷문 — `cityDays`·`cityHow` 가 있으면 추천 코스·직접 고르기까지 바로 간다(프로세스에 한 번, 도시가 주어졌을 때만). */
private fun applyBackdoor(start: CityQuestionFlow): CityQuestionFlow {
    if (NewCourseDebug.cityUsed || start.startCity == null || NewCourseDebug.cityDays <= 0) return start
    NewCourseDebug.cityUsed = true
    val withDays = start.pickDays(NewCourseDebug.cityDays)
    return when (NewCourseDebug.cityHow) {
        "plan" -> withDays.planForMe()
        "pick" -> withDays.pickMyself()
        else -> withDays
    }
}

/** 「며칠?」 의 큰 줄 하나. */
@Composable
private fun DayChoice(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusM)
    Text(
        label,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (on) Color.White else IOS.label,
        modifier =
            Modifier
                .pressClickable(onClick = onClick)
                .fillMaxWidth()
                .shadow(2.dp, shape)
                .clip(shape)
                .background(if (on) NewCourseStyle.purple else IOS.systemBackground)
                .semantics {
                    role = Role.Button
                    selected = on
                }.padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

/** 「어떻게 짤까요?」 의 두 카드 — 같은 크기, 「알아서」 가 위에 채운 색(웹 결정 3). */
@Composable
private fun HowCard(
    primary: Boolean,
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusL)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .pressClickable(onClick = onClick)
                .fillMaxWidth()
                .shadow(2.dp, shape)
                .clip(shape)
                .background(if (primary) NewCourseStyle.purple else IOS.systemBackground)
                .then(if (primary) Modifier else Modifier.border(1.5.dp, NewCourseStyle.purple, shape))
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(18.dp),
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = if (primary) Color.White else IOS.label)
            Text(
                detail,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = if (primary) Color.White.copy(alpha = 0.92f) else IOS.secondaryLabel,
            )
        }
        ChevronRightIcon(
            if (primary) Color.White else NewCourseStyle.purple,
            Modifier.padding(top = 4.dp).size(width = 9.dp, height = 16.dp),
        )
    }
}

/** 첫 질문의 사진 머리 — 홈 카드와 같은 고정 8장이 4초마다 겹쳐 바뀌고, 아래쪽은 화면 바탕색으로 녹아든다 (웹 `CityHero`). */
@Composable
fun CityHero() {
    val reduceMotion = rememberReduceMotion()
    val slides = CityCovers.all
    var at by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduceMotion) {
        if (!CitySlideRules.advances(slides.size, reduceMotion)) {
            at = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(CitySlideRules.INTERVAL_MILLIS)
            at = CitySlideRules.next(at, slides.size)
        }
    }
    // 높이 min(화면 46%, 390), 최소 300(웹 `min(46dvh, 390px)`).
    val screen = LocalConfiguration.current.screenHeightDp
    val height = maxOf(300f, minOf(screen * 0.46f, 390f)).dp
    Box(modifier = Modifier.fillMaxWidth().height(height).background(NewCourseStyle.brandFill)) {
        CitySlideImages(slides = slides, at = at, zooms = !reduceMotion)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.28f to Color.Transparent,
                    0.52f to Color.Black.copy(alpha = 0.15f),
                    0.82f to Color.Black.copy(alpha = 0.7f),
                    1f to NewCourseStyle.grouped,
                ),
            ),
        )
        // 오른쪽 위 작은 글 — 지금 사진의 「도시 · 촬영지 · 작품」.
        Crossfade(
            targetState = at,
            animationSpec = tween(CitySlideRules.CROSSFADE_MILLIS),
            label = "heroCaption",
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp, end = 14.dp),
        ) { index ->
            slides.getOrNull(index)?.let { cover ->
                val lang = AppLanguage.current
                val work = if (lang == Lang.ko) cover.workKo else cover.workEn
                Text(
                    "${HomeCityRules.caption(cover, lang)} · $work",
                    style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), blurRadius = 3f)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painterResource(R.drawable.haetae_face),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    tr("드라마·영화 촬영지로 짜는 한국 여행"),
                    style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), blurRadius = 4f)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
            Text(
                tr("한국 어디로 여행 가세요?"),
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), blurRadius = 10f)),
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** 도시 칸 2열 — 사진, 이름 17, 「촬영지 N곳」. 받는 중이면 회색 칸 (웹 `CityGrid`). */
@Composable
private fun CityGrid(
    cities: List<PlaceCity>,
    loading: Boolean,
    onPick: (String) -> Unit,
) {
    // 바깥이 세로 스크롤이라 지연 격자 대신 줄을 직접 짠다(지연 격자를 세로 스크롤 안에 두면 무한 높이 제약으로 죽는다).
    val cells: List<PlaceCity?> = if (loading && cities.isEmpty()) List(CityListRules.SHOWN_CITIES) { null } else cities
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { city ->
                    Box(Modifier.weight(1f)) {
                        if (city == null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(NewCourseStyle.radiusM))
                                    .background(IOS.systemGray5.copy(alpha = 0.6f)),
                            )
                        } else {
                            CityCell(city) { onPick(city.key) }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CityCell(
    city: PlaceCity,
    onClick: () -> Unit,
) {
    val label = tr("%s, 촬영지 %d곳").format(city.name, city.placeCount)
    Box(
        modifier =
            Modifier
                .pressClickable(onClick = onClick)
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(NewCourseStyle.radiusM))
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    role = Role.Button
                },
    ) {
        CityPhoto(CityPlacesStore.photo(city.key), Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.65f))),
            ),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp)) {
            Text(city.name, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            Text(tr("촬영지 %d곳").format(city.placeCount), fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
        }
    }
}
