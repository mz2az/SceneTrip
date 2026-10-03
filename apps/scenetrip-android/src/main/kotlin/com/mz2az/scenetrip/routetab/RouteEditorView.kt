package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.searchtab.BottomSheet
import com.mz2az.scenetrip.searchtab.Detent
import com.mz2az.scenetrip.searchtab.MapPins
import com.mz2az.scenetrip.searchtab.NaverMapCanvas
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.searchtab.centerOn
import com.mz2az.scenetrip.searchtab.fit
import com.mz2az.scenetrip.searchtab.rememberLocate
import com.mz2az.scenetrip.ui.BagIcon
import com.mz2az.scenetrip.ui.BusIcon
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSearchField
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetMaterial
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.StairsIcon
import com.mz2az.scenetrip.ui.SubwayIcon
import com.mz2az.scenetrip.ui.SwapArrowsIcon
import com.mz2az.scenetrip.ui.TransitIcon
import com.mz2az.scenetrip.ui.WalkIcon
import com.mz2az.scenetrip.ui.sheetListBottom
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.NaverMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 코스 편집. iOS `RouteTab/RouteEditorView.swift`(+Controls/+Parts, 지도 중심 화면)를
 * 옮긴 것이다 — **처음 이 화면을 목록으로만 만들었다가, iOS 시뮬레이터와 나란히 대조해
 * 보고서야 지도가 주인공인 화면이라는 것을 알았다.** 검색 탭의 지도·바텀시트를 그대로
 * 재사용한다.
 *
 * 발자취(황금 점선)·데모 주행은 아직 없다. 코스를 지도로 보며 순서를 바꾸고, 동선을
 * 최적화하고, 검색·장바구니·핀 찍기로 담고, 저장·삭제·여행 시작/종료·실시간 안내
 * (길찾기·도착 판정)·챗봇(RouteGuide)·주변 편의시설 칩은 된다.
 */
private data class MapViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val lat: Double,
    val lng: Double,
    val zoom: Double,
)

@Composable
fun RouteEditorView(
    store: RouteStore,
    initial: RouteCourse,
    isNew: Boolean = false,
    onClose: (RouteCourse?) -> Unit,
    // 마법사 시트 안에서 열릴 때 — 시트가 이미 상태바 아래에 있으니 상태바 여백을 또 두지 않는다.
    inSheet: Boolean = false,
) {
    var course by remember { mutableStateOf(initial) }
    var dayIndex by remember { mutableStateOf(0) }
    var map by remember { mutableStateOf<NaverMap?>(null) }
    var detent by remember { mutableStateOf(Detent.MEDIUM) }
    var panelHeight by remember { mutableStateOf(0.dp) }
    var focusedStopId by remember { mutableStateOf<java.util.UUID?>(null) }
    var pinning by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<RoutePin?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var stayTarget by remember { mutableStateOf<RouteStop?>(null) }
    var saving by remember { mutableStateOf(false) }
    var blockedDayRemoval by remember { mutableStateOf(false) }
    var pinStart by remember { mutableStateOf(false) }
    var pinEnd by remember { mutableStateOf(false) }
    var myLocation by remember { mutableStateOf<PlaceSummary?>(null) }
    // 켜짐이 기본이다(2026-08-28 iOS 결정) — 코스를 보는 사람은 대개 자기 위치와
    // 견주고 싶어서 본다. 끄는 것은 선택으로 남는다.
    var showingMe by remember { mutableStateOf(true) }
    var fitToken by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var ambientPois by remember { mutableStateOf<List<PoiSummary>>(emptyList()) }
    var poiGroupsOn by remember { mutableStateOf(PoiCategoryGroup.entries.toSet()) }
    var aiPlacesOn by remember { mutableStateOf(true) }
    var showDraftNotes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cart = remember { CartStore(context) }
    val guideSession = remember { RouteGuideSession(context) }
    val trip = remember { TripSession(context) }
    val footprints = remember { FootprintStore.getInstance(context) }
    val poisApi = remember { PoisApi(API_BASE) }
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(Unit) { cart.refresh() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { trip.end() } }
    val requestLocation =
        rememberLocate(
            onLocated = { found ->
                myLocation = PlaceSummary(id = 0, name = "여기", latitude = found.latitude, longitude = found.longitude)
            },
            onFailure = {},
        )
    // 못 받아도 코스는 만들어진다 — 실패는 조용히 넘긴다(동선 최적화가 못 쓸 뿐).
    LaunchedEffect(Unit) { requestLocation() }

    fun updateDay(
        index: Int,
        transform: (RouteDay) -> RouteDay,
    ) {
        val days = course.days.toMutableList()
        if (index in days.indices) {
            days[index] = transform(days[index])
            course = course.copy(days = days)
        }
    }

    fun markVisited(stop: RouteStop) {
        course =
            course.copy(
                days =
                    course.days.map { day ->
                        if (day.stops.any { it.id == stop.id }) {
                            day.copy(stops = day.stops.map { if (it.id == stop.id) it.copy(visited = true) else it })
                        } else {
                            day
                        }
                    },
            )
        // 서버에도 남긴다 — iOS `RouteEditorTrip.markVisited`. 로컬만 바꾸면 코스를
        // 다시 열었을 때 방문 표시가 없던 일이 된다.
        val courseId = course.serverId
        val itemId = stop.serverItemId
        if (courseId != null && itemId != null) {
            scope.launch { store.markVisited(courseId, itemId) }
        }
    }

    fun saveAndClose() {
        // 제목을 비운 채 저장하면 목록에 이름 없는 코스가 생긴다 — iOS `saveAndClose`와 같이 기본 이름으로.
        course = course.copy(title = course.title.trim().ifEmpty { "내 코스" })
        saving = true
        scope.launch {
            val saved = store.save(course)
            saving = false
            onClose(saved ?: course)
        }
    }

    // 정지점 줄의 "어느 작품에 나온 곳인가" — iOS `RouteEditorView.workTitles(for:)`.
    // 계약의 `CourseItem`엔 작품이 없어(`placeId`·`name`·`address`만 온다)
    // `RouteStore.places`(촬영지 전체)로 되짚는다. 직접 찍은 핀(id가 음수)은
    // 되짚을 것이 없다.
    fun workTitles(stop: RouteStop): String {
        val placeId = stop.place.id
        if (placeId <= 0) return ""
        val found = store.places.firstOrNull { it.id == placeId } ?: return ""
        return found.contents
            .orEmpty()
            .map { it.title }
            .take(2)
            .joinToString(" · ")
    }
    LaunchedEffect(Unit) { store.loadPlaces() }

    LaunchedEffect(Unit) { trip.onArrived = { stop -> markVisited(stop) } }

    // 홈 「이어서 길찾기」 — 코스가 열리면 첫 미방문 성지로 안내를 켠다. 표시는 한 번
    // 읽고 끈다(iOS `RouteEditorView.runPendingTripStart`).
    LaunchedEffect(course) {
        if (!TabRouter.pendingTripStart) return@LaunchedEffect
        val allStops = course.days.flatMap { it.stops }
        if (allStops.isEmpty()) return@LaunchedEffect
        TabRouter.pendingTripStart = false
        val next = allStops.firstOrNull { !it.visited } ?: allStops.first()
        val serverId = course.serverId ?: return@LaunchedEffect
        store.setRunning(course, true, dayNo = 1)
        course = course.copy(isRunning = true)
        trip.start(serverId, next, scope)
    }

    fun addStops(
        newStops: List<RouteStop>,
        asNext: Boolean = false,
    ) {
        if (newStops.isEmpty()) return
        updateDay(dayIndex) { day ->
            val existing = day.stops.map { RouteDedupe.key(it.place) }.toSet()
            val toAdd = newStops.filterNot { RouteDedupe.key(it.place) in existing }
            if (toAdd.isEmpty()) return@updateDay day
            day.copy(stops = if (asNext) toAdd + day.stops else day.stops + toAdd)
        }
        fitToken += 1
    }

    val day = course.days.getOrNull(dayIndex)
    val stops = day?.stops ?: emptyList()
    val takenIds =
        course.days
            .flatMap { it.stops }
            .mapNotNull { it.savablePlaceId }
            .toSet()
    // 코스에 이미 담긴 곳은 뺀다 — 같은 가게가 두 겹으로 찍히면 어느 쪽을 누른
    // 것인지 모른다(iOS `visibleAmbientPois`·`aiChip`).
    val takenPlaceKeys =
        course.days
            .flatMap { it.stops }
            .map { RouteDedupe.key(it.place) }
            .toSet()
    val poisForChips = ambientPois.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val visibleAmbientPois = poisForChips.filter { poiGroupsOn.contains(it.categoryGroup) }
    val poiCounts = poisForChips.groupingBy { it.categoryGroup }.eachCount()
    val aiPlacesForChip = guideSession.places.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val visibleAiPlaces = if (aiPlacesOn) aiPlacesForChip else emptyList()

    // 챗봇이 장소를 찾아 오면 지도엔 그것만 남긴다(iOS `applyGuideAnswer`) — 주변 점
    // 서른 개 사이에서는 방금 추천받은 곳을 못 찾는다. 갈래는 칩으로 다시 켤 수 있다.
    LaunchedEffect(guideSession.places) {
        if (guideSession.places.isNotEmpty()) {
            poiGroupsOn = emptySet()
            aiPlacesOn = true
        }
    }

    // 안내 중에는 편의시설 점을 다 끈다(2026-09-04 사용자 요청) — 경로선이 주인공인데
    // 음식점·명소 점이 그 위를 덮었다. 안내가 끝나면 다시 전부 켠다. 안내 중에 칩으로
    // 켜는 것은 그대로 된다(iOS `RouteEditorView.onChange(of: trip.isActive)`).
    LaunchedEffect(trip.isActive) {
        poiGroupsOn = if (trip.isActive) emptySet() else PoiCategoryGroup.entries.toSet()
    }

    LaunchedEffect(map, dayIndex, fitToken, showingMe, myLocation) {
        val target = map ?: return@LaunchedEffect
        // 「내 위치」 켜져 있으면 나와 촬영지가 같이 보이게 — 그래야 동선 최적화가
        // 왜 그 순서인지("여기서 가까운 곳이 1번") 알 수 있다(RouteEditorControls.swift).
        val here = myLocation.takeIf { showingMe }
        val toFit = stops.map { it.place } + listOfNotNull(here)
        if (toFit.isNotEmpty()) target.fit(toFit, density, screenHeight, panelHeight, 0.dp)
    }

    // **파란 점 자체는 카메라 맞추기와 별개다.** 위에서는 화면 범위만 맞췄지,
    // 지도에 점을 찍은 적이 없었다 — 「내 위치」를 켜고 위치 권한까지 줘도 점이
    // 안 보였다(실기 비교로 발견, 2026-09-28). 검색 탭은 `locationSource`를
    // 달아 SDK가 스스로 그리게 하는데, 여기 `myLocation`은 서버 호출용
    // `PlaceSummary`라 그 방식을 못 쓴다 — `locationOverlay`에 직접 좌표를
    // 찍는다(TripOverlay가 안내 중에 하는 것과 같은 방법). `trip.isActive`도
    // 키로 둔다 — 안내가 끝나면 TripOverlay가 점을 자기 마지막 상태로 두고
    // 손을 떼므로, 이 효과가 다시 돌아 「내 위치」 토글이 원하는 상태로
    // 되돌려야 한다.
    LaunchedEffect(map, showingMe, myLocation, trip.isActive) {
        val target = map ?: return@LaunchedEffect
        if (trip.isActive) return@LaunchedEffect
        val here = myLocation.takeIf { showingMe }
        target.locationOverlay.isVisible = here != null
        here?.let { target.locationOverlay.position = LatLng(it.latitude, it.longitude) }
    }

    // 카메라가 멈추면 그 범위의 주변 편의시설을 받는다. iOS `RouteEditorAmbient.viewportChanged`
    // — 너무 넓은 화면(줌 13 미만)에서는 안 부른다(점이 먼지처럼 흩어질 뿐이고 서버도 헛돈다),
    // `LaunchedEffect`가 새 뷰포트마다 이전 요청을 스스로 취소하고 350ms 조용해지길 기다린다.
    LaunchedEffect(viewport) {
        val v = viewport
        if (v == null || v.zoom < 13.0) {
            ambientPois = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        val bbox = "%.6f,%.6f,%.6f,%.6f".format(Locale.US, v.west, v.south, v.east, v.north)
        val found =
            runCatching {
                withContext(Dispatchers.IO) {
                    poisApi.listPois(bbox = bbox, lat = v.lat, lng = v.lng, sort = PoisApi.SortListPois.distance, limit = 30)
                }
            }.getOrNull()
        ambientPois = found?.items.orEmpty()
    }

    LaunchedEffect(map, pinning) {
        map?.setOnMapClickListener { _, coord ->
            if (pinning) {
                pendingPin = RoutePin(latitude = coord.latitude, longitude = coord.longitude)
                pinning = false
            }
        }
    }

    // 챗봇의 화면 명령 — 서버 계약 `GuideUiDirective`. 「의도 수준」이라 좌표·절차는
    // 여기서 정한다(계약 설명). iOS 자신도 `route.draw`를 `map.focus`와 같이 다룬다
    // (`RouteEditorGuide.applyGuideDirective`) — 임의 장소 사이의 추천 경로선은 iOS
    // 에도 아직 없다(계획 `guide-app.md` §3). 「AI 장소」핀이 다 보이게 맞추는 것으로
    // 충분하다.
    LaunchedEffect(guideSession.lastUi) {
        val target = map
        guideSession.lastUi.forEach { directive ->
            when (directive.op) {
                "sheet.collapse" -> {
                    showGuide = false
                }

                "course.open", "course.focus" -> {
                    directive.day?.let { day ->
                        dayIndex = (day - 1).coerceIn(0, (course.days.size - 1).coerceAtLeast(0))
                    }
                }

                "map.focus", "route.draw" -> {
                    guideSession.dismiss()
                    val ids = directive.placeIds.orEmpty().toSet()
                    val places = guideSession.places.filter { ids.contains(it.id) }.map { it.asPlaceSummary() }
                    if (target != null && places.isNotEmpty()) target.fit(places, density, screenHeight, panelHeight, 0.dp)
                }

                "place.card" -> {
                    guideSession.places.firstOrNull { it.id == directive.placeId }?.let { guideSession.pick(it) }
                }

                else -> {
                    Unit
                }
            }
        }
    }

    // 챗봇의 상태 명령 — 서버가 이미 적용했다(`GuideEffect` 계약, `cart.*`는 즉시 저장).
    // 화면은 반영된 결과를 다시 읽어 오기만 하면 된다.
    LaunchedEffect(guideSession.lastEffects) {
        if (guideSession.lastEffects.any { it.op == "cart.add" || it.op == "cart.remove" }) {
            cart.refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).then(if (inSheet) Modifier else Modifier.statusBarsPadding())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(IOS.systemBackground)
                    .padding(horizontal = 16.dp),
        ) {
            // iOS `RouteEditorView.topBar`: 「취소」 | 가운데 제목(눌러서 고침) + 연필 | 「만들기/저장」.
            // 제목칸은 테두리 없이 글자 폭을 따르고 200 에서 멈춘다 — iOS 와 같은 이유(320bc94).
            Text(
                "취소",
                fontSize = 17.sp,
                color = IOS.accent,
                modifier = Modifier.clickable { onClose(null) },
            )
            Spacer(Modifier.weight(1f))
            // 폭은 **글자를 재서 준다**(40~200dp). `IntrinsicSize` 로 맞추게 두었더니 입력칸이 제
            // 내용 폭을 못 대서 「내 코스」가 「내」까지만 보였다(2026-09-28 실기). 입력 중이
            // 아니면 넘치는 제목은 iOS 처럼 「…」로 줄인다.
            val titleMeasurer = rememberTextMeasurer()
            val titleDensity = LocalDensity.current
            val titleFocus = LocalFocusManager.current
            var titleFocused by remember { mutableStateOf(false) }
            val titleWidth =
                with(titleDensity) {
                    titleMeasurer
                        .measure(course.title.ifEmpty { "코스 이름" }, IOS.headline, maxLines = 1, softWrap = false)
                        .size.width
                        .toDp()
                }.plus(4.dp).coerceIn(40.dp, 200.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = course.title,
                    onValueChange = { course = course.copy(title = it) },
                    singleLine = true,
                    textStyle = IOS.headline.copy(color = IOS.label, textAlign = TextAlign.Center),
                    cursorBrush =
                        androidx.compose.ui.graphics
                            .SolidColor(IOS.accent),
                    keyboardOptions =
                        androidx.compose.foundation.text
                            .KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions =
                        androidx.compose.foundation.text
                            .KeyboardActions(onDone = { titleFocus.clearFocus() }),
                    modifier = Modifier.width(titleWidth).onFocusChanged { titleFocused = it.isFocused },
                    decorationBox = { inner ->
                        val ellipsize = !titleFocused && course.title.isNotEmpty()
                        Box(contentAlignment = Alignment.Center) {
                            if (course.title.isEmpty()) {
                                Text("코스 이름", style = IOS.headline, color = IOS.tertiaryLabel, maxLines = 1)
                            }
                            Box(modifier = Modifier.alpha(if (ellipsize) 0f else 1f)) { inner() }
                            if (ellipsize) {
                                Text(
                                    course.title,
                                    style = IOS.headline,
                                    color = IOS.label,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                )
                Icon(Icons.Filled.Edit, contentDescription = null, tint = IOS.secondaryLabel, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    if (course.serverId == null) "만들기" else "저장",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.accent,
                    modifier = Modifier.clickable { saveAndClose() },
                )
            }
        }

        if (course.madeByAI && isNew) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(IOS.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                SparklesIcon(IOS.accent, Modifier.size(13.dp))
                Text("AI 가 짠 일정입니다 · 아직 저장 전", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOS.accent)
            }
        }

        // 초안의 알림줄 — 접힌 한 줄("뺀 곳 7 · 주의 3")이고 누르면 펼쳐진다. 저장하면
        // 사라지는 값이라 저장 전에만 보인다(iOS `RouteEditorGuide.draftNotes`).
        if (course.draftNotes.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().clickable { showDraftNotes = !showDraftNotes },
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = IOS.secondaryLabel, modifier = Modifier.size(12.dp))
                    Text(
                        RouteGuidePlan.notesSummary(course.draftNotes),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = IOS.secondaryLabel,
                    )
                    Icon(
                        if (showDraftNotes) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = IOS.secondaryLabel,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (showDraftNotes) {
                    course.draftNotes.forEach { note ->
                        Text(note, fontSize = 11.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            NaverMapCanvas(
                modifier = Modifier.fillMaxSize(),
                sheetHeight = panelHeight,
                searchBarInset = 0.dp,
                onMapReady = { map = it },
                onCameraIdle = { bounds, center, zoom ->
                    viewport =
                        MapViewport(
                            south = bounds.southLatitude,
                            west = bounds.westLongitude,
                            north = bounds.northLatitude,
                            east = bounds.eastLongitude,
                            lat = center.latitude,
                            lng = center.longitude,
                            zoom = zoom,
                        )
                },
            )
            PlanOverlay(
                map = map,
                stops = stops,
                // 안내 중인 목표에서 나가는 선은 남긴다 — 다녀온 곳에서 끊되, 지금
                // 가는 길은 계획선으로도 보여야 한다(iOS `RouteMapTrip.drawLine`
                // 의 `keepFrom`).
                keepFromId = if (trip.phase == TripSession.Phase.GUIDING) trip.target?.id else null,
            )
            AmbientPoiPins(
                map = map,
                pois = visibleAmbientPois,
                // iOS `RouteMapAmbient`: 점을 눌러도 바로 담기지 않는다 — 카드가 먼저 뜨고
                // "경로에 추가"는 사람이 카드에서 직접 누른다.
                onTap = { poi -> guideSession.pick(poi.asGuidePlace()) },
            )
            AiPlacePins(
                map = map,
                places = visibleAiPlaces,
                onTap = { place -> guideSession.pick(place) },
            )
            MapPins(
                map = map,
                places = stops.map { it.place },
                numbered = true,
                onTap = { place -> focusedStopId = stops.firstOrNull { RouteDedupe.key(it.place) == RouteDedupe.key(place) }?.id },
            )
            PendingPinMarker(map = map, pin = pendingPin)
            TripOverlay(map = map, active = trip.isActive, here = trip.here, leg = trip.leg)
            FootprintTrail(
                map = map,
                points =
                    if (footprints.enabled) {
                        footprints.points.filter { it.at > System.currentTimeMillis() - 86_400_000L }
                    } else {
                        emptyList()
                    },
            )
            if (!pinning) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    RouteLocateButton(
                        on = showingMe,
                        onClick = {
                            if (trip.isActive) {
                                trip.here?.let { (lat, lng) ->
                                    map?.centerOn(PlaceSummary(id = 0, name = "여기", latitude = lat, longitude = lng))
                                }
                            } else {
                                showingMe = !showingMe
                            }
                        },
                    )
                    // 발자취 보기 토글은 여행 중에만 — 그 밖에는 볼 것이 없다
                    // (iOS `RouteEditorControls.map`: `if course.isRunning`).
                    if (course.isRunning) {
                        RouteFootprintButton(
                            on = footprints.enabled,
                            onClick = { footprints.updateEnabled(!footprints.enabled) },
                        )
                    }
                }
            }
            if (pinning) {
                Text(
                    "지도를 눌러 장소를 찍으세요",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = IOS.label,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(IOS.systemBackground.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            BottomSheet(
                detent = detent,
                onDetentChange = { detent = it },
                topInset = 8.dp,
                onHeightChange = { panelHeight = it },
                // 지도 40 : 일정 60 — 액션 줄·"코스 시작" 단추까지 처음부터 보여야
                // 한다(iOS `RouteEditorView`의 `mediumFraction: 0.60`, 2026-08-28
                // 사용자 요청: "일정 쪽에 단추가 늘며 좁아졌다").
                mediumFraction = 0.60f,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (trip.isActive) {
                        TripBanner(
                            trip = trip,
                            // 「안내 끝」은 **안내만** 멈춘다 — 여행 중 상태는 「여행 종료」의 몫이다
                            // (iOS `tripControls`: `trip.end()` 뿐). 앞서 여기서 코스까지 끝내서,
                            // 안내를 잠깐 멈춘 사람의 코스가 「예정」으로 돌아갔다(2026-09-28 실기).
                            onEnd = trip::end,
                            onArrivedNow = trip::markArrived,
                            onNext = { next -> trip.advance(next, scope) },
                            onRetry = { trip.retry(scope) },
                            unvisitedAfter = { current ->
                                val flat = course.days.flatMap { it.stops }
                                val index = flat.indexOfFirst { it.id == current.id }
                                if (index < 0) null else flat.drop(index + 1).firstOrNull { !it.visited }
                            },
                        )
                    }
                    DayTabs(
                        dayCount = course.days.size,
                        dayIndex = dayIndex,
                        onSelect = {
                            dayIndex = it
                            focusedStopId = null
                            fitToken += 1
                        },
                        onAddDay = {
                            if (course.days.size < RouteCourse.DAY_LIMIT.last) {
                                course = course.copy(days = course.days + RouteDay())
                                dayIndex = course.days.size - 1
                            }
                        },
                        onRemoveDay = {
                            val lastIndex = course.days.size - 1
                            if (course.days.size > RouteCourse.DAY_LIMIT.first) {
                                if (course.days[lastIndex].stops.isNotEmpty()) {
                                    blockedDayRemoval = true
                                } else {
                                    course = course.copy(days = course.days.dropLast(1))
                                    dayIndex = dayIndex.coerceAtMost(course.days.size - 1)
                                }
                            }
                        },
                    )
                    if (poisForChips.isNotEmpty() || aiPlacesForChip.isNotEmpty()) {
                        // 감춘 갈래의 고른 핀은 놓는다 — 지도에서 사라진 핀을 카드만 붙잡고
                        // 있으면 안 된다. AI 장소는 제 칩이 따로 놓는다(iOS
                        // `RouteEditorControls.poiFilter` onGroupOff).
                        fun dropPickedIfHidden(group: PoiCategoryGroup) {
                            val picked = guideSession.picked
                            if (picked != null && picked.categoryGroup == group && guideSession.places.none { it.id == picked.id }) {
                                guideSession.dismiss()
                            }
                        }
                        RoutePoiChips(
                            counts = poiCounts,
                            groupsOn = poiGroupsOn,
                            onToggleGroup = { group ->
                                val turningOff = poiGroupsOn.contains(group)
                                poiGroupsOn = if (turningOff) poiGroupsOn - group else poiGroupsOn + group
                                if (turningOff) dropPickedIfHidden(group)
                            },
                            onToggleAll = {
                                val allOn = poiGroupsOn.size == PoiCategoryGroup.entries.size
                                poiGroupsOn = if (allOn) emptySet() else PoiCategoryGroup.entries.toSet()
                                if (allOn) PoiCategoryGroup.entries.forEach { dropPickedIfHidden(it) }
                            },
                            aiCount = aiPlacesForChip.size,
                            aiOn = aiPlacesOn,
                            onToggleAi = {
                                aiPlacesOn = !aiPlacesOn
                                val picked = guideSession.picked
                                if (!aiPlacesOn && picked != null && guideSession.places.any { it.id == picked.id }) {
                                    guideSession.dismiss()
                                }
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        // iOS `.footnote`(13) + HStack(spacing: 6).
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${stops.size}곳", fontSize = 13.sp, color = IOS.secondaryLabel)
                            Text("·", fontSize = 13.sp, color = IOS.secondaryLabel)
                            Text(
                                "직선 ${RouteFormat.kilometers(RouteGeometry.totalKilometers(stops))}",
                                fontSize = 13.sp,
                                color = IOS.secondaryLabel,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text("이동 시간은 여행 중에", fontSize = 13.sp, color = IOS.tertiaryLabel)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    IOS.systemBackground,
                                ).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    ) {
                        EditorAction(label = "동선 최적화", icon = { SwapArrowsIcon(IOS.label, it) }, modifier = Modifier.weight(1f)) {
                            var ordered = stops
                            var head = pinStart
                            if (!pinStart) {
                                val anchor = RouteGeometry.usableAnchor(myLocation, ordered)
                                if (anchor != null) {
                                    ordered = RouteGeometry.startingNearest(ordered, anchor)
                                    head = true
                                }
                            }
                            updateDay(dayIndex) { d -> d.copy(stops = RouteGeometry.optimized(ordered, pinStart = head, pinEnd = pinEnd)) }
                            focusedStopId = null
                            fitToken += 1
                        }
                        EditorAction(label = "검색", icon = { MagnifierIcon(IOS.label, it) }, modifier = Modifier.weight(1f)) {
                            searching =
                                true
                        }
                        EditorAction(
                            label = "장바구니",
                            icon = { BagIcon(IOS.label, it) },
                            modifier = Modifier.weight(1f),
                        ) { showCart = true }
                        EditorAction(
                            label = if (pinning) "취소" else "핀 찍기",
                            icon = { MapPinEllipseIcon(IOS.label, it) },
                            modifier = Modifier.weight(1f),
                        ) { pinning = !pinning }
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                            RouteStopRow(
                                number = index + 1,
                                stop = stop,
                                nextKilometers = stops.getOrNull(index + 1)?.let { RouteGeometry.kilometers(stop.place, it.place) },
                                isFocused = focusedStopId == stop.id,
                                // 첫 줄에 "출발 고정", 마지막 줄에 "도착 고정". 한 곳뿐이면
                                // 고정할 것이 없다 — 그 하나가 출발이자 도착이라 뜻이 없다
                                // (iOS 실기 비교로 발견 — Android는 이 가드가 없어서 정지점이
                                // 하나뿐인 날에도 "출발 고정"이 붙어 있었다).
                                pinLabel =
                                    if (stops.size <= 1) {
                                        null
                                    } else {
                                        when (index) {
                                            0 -> "출발"
                                            stops.lastIndex -> "도착"
                                            else -> null
                                        }
                                    },
                                isPinned = if (index == 0) pinStart else pinEnd,
                                works = workTitles(stop),
                                onFocus = {
                                    // 한 번 더 누르면 놓는다 — 안 그러면 한 곳을 고른 뒤
                                    // 경로 전체를 다시 볼 방법이 없다(iOS
                                    // `RouteEditorView.stopRows` onFocus).
                                    if (focusedStopId == stop.id) {
                                        focusedStopId = null
                                        fitToken += 1
                                    } else {
                                        focusedStopId = stop.id
                                        map?.centerOn(stop.place)
                                    }
                                },
                                onStay = { stayTarget = stop },
                                onRemove = { updateDay(dayIndex) { d -> d.copy(stops = d.stops.filterNot { it.id == stop.id }) } },
                                onMoveUp =
                                    if (index > 0) {
                                        {
                                            updateDay(dayIndex) { d ->
                                                val list = d.stops.toMutableList()
                                                val tmp = list[index - 1]
                                                list[index - 1] = list[index]
                                                list[index] = tmp
                                                d.copy(stops = list)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                onMoveDown =
                                    if (index < stops.lastIndex) {
                                        {
                                            updateDay(dayIndex) { d ->
                                                val list = d.stops.toMutableList()
                                                val tmp = list[index + 1]
                                                list[index + 1] = list[index]
                                                list[index] = tmp
                                                d.copy(stops = list)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                onTogglePin = {
                                    if (index == 0) pinStart = !pinStart else pinEnd = !pinEnd
                                },
                                isTarget = trip.target?.id == stop.id,
                                onNavigate =
                                    if (course.isRunning) {
                                        {
                                            if (trip.isActive) {
                                                trip.advance(stop, scope)
                                            } else {
                                                course.serverId?.let { trip.start(it, stop, scope) }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                            )
                            // iOS `List` 줄 구분선 — 번호 뒤 글자 시작(16 + 22 + 12)부터.
                            if (index < stops.lastIndex) IOSListDivider(start = 50.dp)
                        }
                        if (stops.isEmpty()) {
                            item {
                                // iOS `RouteEditorView.stopRows` — "직접 짜기"로 빈 코스를
                                // 열었을 때 아무 안내도 없었다(실기 비교로 발견).
                                Text(
                                    "아직 담은 장소가 없습니다\n장바구니에서 담거나 지도에 핀을 찍어 보세요",
                                    fontSize = 12.sp,
                                    color = IOS.secondaryLabel,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                )
                            }
                        }
                        // 「코스 삭제」는 여기 없다 — iOS 는 코스 목록에서 밀어서만 지운다(2026-09-28 대조).
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                    // **스크롤과 무관하게 늘 보여야 한다.** iOS `RouteEditorTrip.bottomBar`
                    // (`planControls`)와 짝 — 정지점이 많은 일차에서는 스크롤해야만
                    // 나오던 "코스 시작"·저장 버튼이었다(실기 비교로 발견,
                    // 2026-09-28). 목록 맨 아래 항목이 아니라 목록 옆의 고정 줄로
                    // 옮긴다.
                    // iOS `RouteEditorTrip.planControls`: **캡슐 `.bordered`(회색 채움·accent 글자)** 와
                    // `.borderedProminent`(accent 채움·흰 글자), 글자 15 보통 굵기. 「여행 종료/코스 시작」은
                    // 글자 폭만, 저장이 나머지를 채운다. 여행 중이면 「N번으로 길찾기」가 맨 앞에 온다.
                    // 「코스 시작」은 **지금 보는 일차**로 시작한다(`dayNo = dayIndex + 1`, 그 일차의 첫
                    // 미방문 곳) — 앞서 Android 는 늘 1일차·전체 첫 미방문이었다(2026-09-28 대조).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(IOS.systemBackground)
                                // 제스처 막대와 겹쳤다 — iOS 는 안전 영역만큼 띄운다.
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        val serverId = course.serverId
                        if (serverId != null) {
                            val running = course.isRunning
                            val nextIndex = stops.indexOfFirst { !it.visited }
                            if (running && nextIndex >= 0 && !trip.isActive) {
                                EditorCapsuleButton(
                                    "${nextIndex + 1}번으로 길찾기",
                                    prominent = true,
                                    onClick = { trip.start(serverId, stops[nextIndex], scope) },
                                )
                            }
                            EditorCapsuleButton(
                                if (running) "여행 종료" else "코스 시작",
                                prominent = false,
                                onClick = {
                                    val turningOn = !running
                                    val dayNo = dayIndex + 1
                                    if (!turningOn) trip.end()
                                    scope.launch {
                                        store.setRunning(course, turningOn, dayNo = dayNo)
                                        course = course.copy(isRunning = turningOn)
                                        if (turningOn) {
                                            val first = stops.firstOrNull { !it.visited } ?: stops.firstOrNull()
                                            if (first != null) trip.start(serverId, first, scope)
                                        }
                                    }
                                },
                            )
                        }
                        EditorCapsuleButton(
                            if (saving) {
                                "…"
                            } else if (serverId == null) {
                                "코스 만들기"
                            } else {
                                "저장하고 닫기"
                            },
                            prominent = true,
                            enabled = !saving,
                            onClick = { saveAndClose() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            RouteGuideFloatingChip(
                hidden = pinning || showGuide,
                onTap = { showGuide = true },
                // iOS `guideFloatingChip`: 편집 화면 전체 위, 오른쪽 12 · 아래(안전 영역 위) 76 — 목록 패널 위에 뜬다.
                // 지도 쪽(패널 바로 위)에 두었더니 iOS 와 자리가 달랐다(2026-09-29 대조).
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 76.dp),
            )
        }
    }

    RouteGuidePanel(
        isOpen = showGuide,
        session = guideSession,
        here = myLocation?.let { it.latitude to it.longitude },
        onAdd = { place ->
            addStops(listOf(RouteStop(place = place.asPlaceSummary(), isPinned = place.source == GuidePlaceSource.poi)))
        },
        isAdded = { place -> stops.any { RouteDedupe.key(it.place) == RouteDedupe.key(place.asPlaceSummary()) } },
        onClose = { showGuide = false },
        onRemove = { place ->
            updateDay(dayIndex) { d ->
                d.copy(stops = d.stops.filterNot { RouteDedupe.key(it.place) == RouteDedupe.key(place.asPlaceSummary()) })
            }
        },
    )

    // 핀을 눌렀을 때 뜨는 정보 카드 — iOS `RouteEditorView`의 `.overlay(alignment: .bottom)`
    // 자리. 가이드 시트가 열려 있으면 시트가 바닥을 덮으므로 여기 안 띄운다.
    val pickedPlace = guideSession.picked
    if (pickedPlace != null && !showGuide) {
        Box(modifier = Modifier.fillMaxSize()) {
            RoutePlaceCard(
                place = pickedPlace,
                added = stops.any { RouteDedupe.key(it.place) == RouteDedupe.key(pickedPlace.asPlaceSummary()) },
                onAdd = {
                    addStops(
                        listOf(RouteStop(place = pickedPlace.asPlaceSummary(), isPinned = pickedPlace.source == GuidePlaceSource.poi)),
                    )
                    guideSession.dismiss()
                },
                onRemove = {
                    updateDay(dayIndex) { d ->
                        d.copy(stops = d.stops.filterNot { RouteDedupe.key(it.place) == RouteDedupe.key(pickedPlace.asPlaceSummary()) })
                    }
                },
                onClose = { guideSession.dismiss() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 90.dp),
            )
        }
    }

    if (searching) {
        PlaceSearchOverlay(
            store = store,
            taken = takenIds,
            onDismiss = { searching = false },
            onAdd = { places -> addStops(places.map { RouteStop(place = it) }) },
        )
    }

    if (showCart) {
        RouteCartSheet(
            cart = cart,
            store = store,
            taken = takenIds,
            onPreview = {},
            onPick = { places -> addStops(places.map { RouteStop(place = it) }) },
            onDismiss = { showCart = false },
        )
    }

    pendingPin?.let { pin ->
        RoutePinSheet(
            pin = pin,
            onDone = { name, category ->
                addStops(listOf(RouteStop(place = pinnedPlace(name, category, pin.latitude, pin.longitude), isPinned = true)))
            },
            onDismiss = { pendingPin = null },
        )
    }

    stayTarget?.let { target ->
        RouteStaySheet(
            stop = target,
            onPick = { minutes ->
                updateDay(dayIndex) { d -> d.copy(stops = d.stops.map { if (it.id == target.id) it.copy(stayMinutes = minutes) else it }) }
            },
            onDismiss = { stayTarget = null },
        )
    }

    if (blockedDayRemoval) {
        IOSAlert(
            title = "일차를 뺄 수 없습니다",
            message = "마지막 일차에 담긴 장소를 먼저 빼 주세요.",
            actions = listOf(IOSAction("확인", IOSRole.CANCEL) {}),
            onDismiss = { blockedDayRemoval = false },
        )
    }

    // 저장이 실패하면 이유를 말한다 — 안 그러면 단추가 안 먹는 것처럼 보여 같은
    // 단추를 계속 누르게 된다(iOS `RouteEditorView`의 "저장하지 못했습니다" alert).
    store.failure?.let { failure ->
        IOSAlert(
            title = "저장하지 못했습니다",
            message = failure.message,
            actions = listOf(IOSAction("확인") {}),
            onDismiss = { store.clearFailure() },
        )
    }
}

/** 직접 찍은 핀의 임시 장소. 저장 전에는 음수 id로 촬영지·초안과 겹치지 않는다. */
private fun pinnedPlace(
    name: String,
    category: String,
    lat: Double,
    lng: Double,
): PlaceSummary =
    PlaceSummary(id = -System.currentTimeMillis(), name = name, type = category, address = null, latitude = lat, longitude = lng)

/**
 * 가이드가 찾아 준 장소 → 담을 수 있는 장소. **편의시설(`poi`)은 저장 가능한 촬영지
 * id 가 아니므로 직접 찍은 핀으로 담는다** — iOS `RouteGuide.Place`의 같은 주석.
 */
private fun GuidePlace.asPlaceSummary(): PlaceSummary =
    PlaceSummary(id = id, name = name, type = category, address = address, latitude = latitude, longitude = longitude)

@Composable
private fun EditorAction(
    label: String,
    modifier: Modifier = Modifier,
    // iOS `action(_:symbol:)` — 아이콘이 위, 글자가 아래다(넷이 한 줄에 들어가야
    // 해서 나란히 두면 "동선 최적화" 하나가 폭 절반을 먹는다).
    // SF Symbols 외곽선 아이콘(`ui/OutlineIcons.kt`) — 크기 Modifier 를 받아 그린다.
    icon: (@Composable (Modifier) -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.systemGray6)
                .clickable(onClick = onClick)
                .padding(vertical = 7.dp),
    ) {
        icon?.invoke(Modifier.size(17.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = IOS.label,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 계획선 — 정지점을 번호 순서대로 잇는 **직선**. iOS `RouteMapTrip.drawLine`. 여행
 * 전 계획에서는 길찾기 API를 안 부르니 실제 도로 궤적을 모른다 — 곡선으로 그리면
 * 거짓말이 된다. 다녀온 곳에서 끊긴다(여러 토막일 수 있다) — 단 지금 안내 중인
 * 목표(`keepFromId`)로 나가는 선은 남긴다.
 */
@Composable
private fun PlanOverlay(
    map: NaverMap?,
    stops: List<RouteStop>,
    keepFromId: java.util.UUID?,
) {
    if (map == null || stops.size < 2) return
    androidx.compose.runtime.DisposableEffect(map, stops, keepFromId) {
        val segments = mutableListOf<List<LatLng>>()
        var current = mutableListOf<LatLng>()
        stops.forEachIndexed { index, stop ->
            current.add(LatLng(stop.place.latitude, stop.place.longitude))
            if (stop.visited && stop.id != keepFromId) {
                if (current.size >= 2) segments.add(current)
                current = mutableListOf(LatLng(stop.place.latitude, stop.place.longitude))
            }
            if (index == stops.lastIndex && current.size >= 2) segments.add(current)
        }
        val overlays =
            segments.map { points ->
                com.naver.maps.map.overlay.PathOverlay().apply {
                    coords = points
                    color = android.graphics.Color.parseColor("#7A68ED")
                    outlineColor = android.graphics.Color.WHITE
                    width = 4
                    outlineWidth = 1
                    this.map = map
                }
            }
        onDispose { overlays.forEach { it.map = null } }
    }
}

/** 안내 중인 경로선과 내 위치. iOS `RouteMapView`의 경로·내 위치 부분만 옮겼다. */
@Composable
private fun TripOverlay(
    map: NaverMap?,
    active: Boolean,
    here: Pair<Double, Double>?,
    leg: com.mz2az.scenetrip.sceneapi.client.model.NextLeg?,
) {
    if (map == null) return
    // **안내 중이 아니면 이 효과는 `locationOverlay`를 아예 건드리지 않는다.**
    // 안내 전에도 이 컴포저블은 늘 조립돼 있어서(`trip.here`가 항상 null),
    // `here != null` 만 보고 isVisible을 껐다 켰다 하면 「내 위치」 토글이
    // 이미 켜 둔 파란 점을 그 뒤에 도는 이 효과가 곧바로 꺼 버렸다 — 편집
    // 화면을 열자마자는 점이 안 보이고, 토글을 한 번 더 눌러야 나오던
    // 원인이었다(2026-09-28 실기 검증으로 확인). 안내 중에만 이 효과가
    // 살아 있는 위치로 점을 옮긴다.
    if (active) {
        androidx.compose.runtime.LaunchedEffect(here) {
            map.locationOverlay.isVisible = here != null
            here?.let { (lat, lng) -> map.locationOverlay.position = LatLng(lat, lng) }
        }
    }
    androidx.compose.runtime.DisposableEffect(map, leg) {
        val points = leg?.legs.orEmpty().flatMap { routeLeg -> routeLeg.path.coordinates.map { LatLng(it[1], it[0]) } }
        val overlay =
            if (points.size >= 2) {
                com.naver.maps.map.overlay.PathOverlay().apply {
                    coords = points
                    color = android.graphics.Color.parseColor("#7A68ED")
                    width = 12
                    this.map = map
                }
            } else {
                null
            }
        onDispose { overlay?.map = null }
    }
}

/** iOS 의 캡슐 버튼(`.bordered` / `.borderedProminent`, `.controlSize(.regular)`, 글자 15). */
@Composable
private fun EditorCapsuleButton(
    label: String,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        label,
        fontSize = 15.sp,
        color = if (prominent) Color.White else IOS.accent,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier =
            modifier
                .clip(CircleShape)
                .background(if (prominent) IOS.accent else IOS.tertiaryFill)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 구간 칩 하나 — 도보는 회색, 지하철은 파랑, 버스·그 밖의 탈것은 초록(iOS와 같은 세 갈래). */
@Composable
private fun LegChip(chip: RouteLegChip) {
    val background =
        when (chip.mode) {
            RouteLegMode.WALK -> IOS.systemGray5
            RouteLegMode.SUBWAY -> IOS.accent.copy(alpha = 0.16f)
            else -> IOS.systemGreen.copy(alpha = 0.18f)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val icon = Modifier.size(11.dp)
        when (chip.mode) {
            RouteLegMode.WALK -> WalkIcon(IOS.label, icon)
            RouteLegMode.BUS -> BusIcon(IOS.label, icon)
            RouteLegMode.SUBWAY -> SubwayIcon(IOS.label, icon)
            RouteLegMode.TRANSIT -> TransitIcon(IOS.label, icon)
        }
        Text(chip.text, fontSize = 11.sp, color = IOS.label, maxLines = 1)
        if (chip.hasStairs) StairsIcon(IOS.systemRed, Modifier.size(10.dp))
    }
}

/**
 * 안내 배너 — iOS는 지도 위 카드(도착)와 시트 안 줄(진행)로 나누는데, 여기서는 시트
 * 맨 위 한 자리로 합쳤다. "여기 도착함"은 GPS 판정을 기다리지 않는 수동 확인이다.
 */
@Composable
private fun TripBanner(
    trip: TripSession,
    onEnd: () -> Unit,
    onArrivedNow: () -> Unit,
    onNext: (RouteStop) -> Unit,
    onRetry: () -> Unit,
    unvisitedAfter: (RouteStop) -> RouteStop?,
) {
    val target = trip.target ?: return
    Column(modifier = Modifier.fillMaxWidth().background(IOS.pinDeep.copy(alpha = 0.08f)).padding(12.dp)) {
        if (trip.phase == TripSession.Phase.ARRIVED) {
            Text("성지 도착!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.pinDeep)
            Text(target.place.name, fontSize = 12.sp, color = IOS.secondaryLabel)
        } else {
            Text("${target.place.name}로 가는 중", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            // iOS `RouteEditorTrip.tripDetail` 순서 그대로: 받은 경로 → 실패(재시도) →
            // 구하는 중 → 자리를 못 찾음.
            when {
                trip.leg != null -> {
                    val result = trip.leg!!
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(RouteFormat.minutes(result.totalMinutes), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = IOS.label)
                        Text(
                            result.summaryLine(),
                            fontSize = 12.sp,
                            color = IOS.secondaryLabel,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    // 구간 — 도보·대중교통 조각을 한 줄로. 밀면 다 보인다(iOS `tripDetail`).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        result.chips().forEach { LegChip(it) }
                    }
                }

                trip.failure != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(trip.failure!!, fontSize = 11.sp, color = IOS.systemOrange)
                        Text(
                            "다시 시도",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IOS.accent,
                            modifier = Modifier.clickable(onClick = onRetry),
                        )
                    }
                }

                trip.asking -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
                        Text("길을 찾는 중입니다", fontSize = 11.sp, color = IOS.secondaryLabel)
                    }
                }

                trip.here == null -> {
                    Text("현재 위치를 찾는 중입니다", fontSize = 11.sp, color = IOS.secondaryLabel)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text(
                "안내 끝",
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(IOS.systemGray6)
                        .clickable(onClick = onEnd)
                        .padding(vertical = 6.dp, horizontal = 10.dp),
            )
            if (trip.phase == TripSession.Phase.GUIDING) {
                Text(
                    "여기 도착함",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.systemBackground,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IOS.accent)
                            .clickable(onClick = onArrivedNow)
                            .padding(vertical = 6.dp),
                )
            } else {
                val next = unvisitedAfter(target)
                Text(
                    if (next != null) "다음 · ${next.place.name}로 길찾기" else "코스 완료",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.systemBackground,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IOS.accent)
                            .clickable(enabled = next != null) { next?.let(onNext) }
                            .padding(vertical = 6.dp),
                )
            }
        }
    }
}

// iOS `RouteEditorControls.dayTabs`: 밑줄 탭(글자 15, 켜지면 semibold·accent, 밑줄은 **글자 폭**) +
// `minus.circle`·`plus.circle`(한도에 닿으면 흐리게). 앞서는 13sp·밑줄 24dp 고정·글자 「−」「+」였다.
@Composable
private fun DayTabs(
    dayCount: Int,
    dayIndex: Int,
    onSelect: (Int) -> Unit,
    onAddDay: () -> Unit,
    onRemoveDay: () -> Unit,
) {
    val canRemove = dayCount > RouteCourse.DAY_LIMIT.first
    val canAdd = dayCount < RouteCourse.DAY_LIMIT.last
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(start = 16.dp, end = 16.dp, top = 10.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed((0 until dayCount).toList()) { _, index ->
                val active = index == dayIndex
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(IntrinsicSize.Max).clickable { onSelect(index) },
                ) {
                    Text(
                        "${index + 1}일차",
                        fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) IOS.accent else IOS.secondaryLabel,
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) IOS.accent else Color.Transparent),
                    )
                }
            }
        }
        CircleSignIcon(
            plus = false,
            tint = if (canRemove) IOS.accent else IOS.tertiaryLabel,
            modifier = Modifier.size(18.dp).clickable(enabled = canRemove, onClick = onRemoveDay),
        )
        CircleSignIcon(
            plus = true,
            tint = if (canAdd) IOS.accent else IOS.tertiaryLabel,
            modifier = Modifier.size(18.dp).clickable(enabled = canAdd, onClick = onAddDay),
        )
    }
}

/**
 * 장소 검색. iOS `RouteSearchSheet`를 옮긴 것이다 — 이름·주소·**작품 이름**으로
 * [RouteStore.places](서버가 이미 다 들고 있는 155건)를 메모리에서 거른다. 글자를
 * 칠 때마다 서버를 부르지 않는다. 여럿 고르고 한 번에 담는다(번호 배지로 순서를
 * 보여 준다), 이미 담긴 곳은 흐리게 + 체크.
 */
@Composable
private fun PlaceSearchOverlayBody(
    store: RouteStore,
    taken: Set<Long>,
    onDismiss: () -> Unit,
    onAdd: (List<PlaceSummary>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(Unit) { store.loadPlaces() }

    val trimmed = query.trim()
    val results =
        if (trimmed.isEmpty()) {
            store.places.take(40)
        } else {
            store.places.filter { place ->
                place.name.contains(trimmed, ignoreCase = true) ||
                    place.address.orEmpty().contains(trimmed, ignoreCase = true) ||
                    place.contents.orEmpty().any { it.title.contains(trimmed, ignoreCase = true) }
            }
        }

    // iOS `RouteSearchSheet`: 툴바(닫기 · 장소 검색 · 추가 N), `.searchable` 알약 검색창, 구분선 있는 목록,
    // 바탕은 `.regularMaterial`. 결과가 없으면 `ContentUnavailableView.search`.
    // 머리(툴바·검색창)만 재질 회색이고 목록은 흰 바탕이다(iOS 실측).
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        Column(modifier = Modifier.background(IOSSheetMaterial)) {
            IOSSheetToolbar(
                title = "장소 검색",
                leading = "닫기",
                onLeading = onDismiss,
                trailing = "추가 ${picked.size}",
                trailingEnabled = picked.isNotEmpty(),
                onTrailing = {
                    val byId = store.places.associateBy { it.id }
                    onAdd(picked.mapNotNull { byId[it] })
                    onDismiss()
                },
            )
            IOSSearchField(query, { query = it }, "장소나 작품 이름", Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
        }
        if (results.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MagnifierIcon(IOS.secondaryLabel, Modifier.padding(bottom = 10.dp).size(51.dp))
                Text(
                    "‘$trimmed’에 대한 결과 없음",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = IOS.label,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "철자를 확인하거나 새로운 검색을 시도하십시오.",
                    fontSize = 15.sp,
                    color = IOS.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
            itemsIndexed(results, key = { _, place -> place.id }) { _, place ->
                val isTaken = taken.contains(place.id)
                val pickedOrder = picked.indexOf(place.id).takeIf { it >= 0 }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isTaken) {
                                picked = if (pickedOrder != null) picked - place.id else picked + place.id
                            }.padding(horizontal = 16.dp, vertical = 10.dp)
                            .alpha(if (isTaken) 0.5f else 1f),
                ) {
                    RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                        place.contents.orEmpty().firstOrNull()?.title?.let {
                            Text(it, fontSize = 12.sp, color = IOS.accent)
                        }
                        place.address?.let { Text(it, fontSize = 11.sp, color = IOS.tertiaryLabel, maxLines = 1) }
                    }
                    when {
                        isTaken -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "이미 담김",
                                tint = IOS.accent.copy(alpha = 0.45f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        pickedOrder != null -> {
                            Text(
                                "${pickedOrder + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(IOS.accent)
                                        .wrapContentHeight(),
                            )
                        }

                        else -> {
                            CircleSignIcon(plus = true, tint = IOS.tertiaryLabel, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                IOSListDivider(start = 72.dp)
            }
        }
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
private fun PlaceSearchOverlay(
    store: RouteStore,
    taken: Set<Long>,
    onDismiss: () -> Unit,
    onAdd: (List<PlaceSummary>) -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        PlaceSearchOverlayBody(store = store, taken = taken, onDismiss = onDismiss, onAdd = onAdd)
    }
}
