package com.mz2az.scenetrip.routetab

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
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
import com.mz2az.scenetrip.searchtab.centerOn
import com.mz2az.scenetrip.searchtab.fit
import com.mz2az.scenetrip.searchtab.rememberLocate
import com.mz2az.scenetrip.ui.IOS
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
    var confirmingDelete by remember { mutableStateOf(false) }
    var blockedDayRemoval by remember { mutableStateOf(false) }
    var pinStart by remember { mutableStateOf(false) }
    var pinEnd by remember { mutableStateOf(false) }
    var myLocation by remember { mutableStateOf<PlaceSummary?>(null) }
    var showingMe by remember { mutableStateOf(false) }
    var fitToken by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var ambientPois by remember { mutableStateOf<List<PoiSummary>>(emptyList()) }
    var poiGroupsOn by remember { mutableStateOf(PoiCategoryGroup.entries.toSet()) }
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
    }

    LaunchedEffect(Unit) { trip.onArrived = { stop -> markVisited(stop) } }

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
    // 것인지 모른다(iOS `visibleAmbientPois`). 챗봇 결과(guide.places)는 Android
    // 지도에 아직 안 그리므로 그쪽 겹침은 따로 볼 것이 없다.
    val takenPlaceKeys =
        course.days
            .flatMap { it.stops }
            .map { RouteDedupe.key(it.place) }
            .toSet()
    val poisForChips = ambientPois.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val visibleAmbientPois = poisForChips.filter { poiGroupsOn.contains(it.categoryGroup) }
    val poiCounts = poisForChips.groupingBy { it.categoryGroup }.eachCount()

    LaunchedEffect(map, dayIndex, fitToken, showingMe, myLocation) {
        val target = map ?: return@LaunchedEffect
        // 「내 위치」 켜져 있으면 나와 촬영지가 같이 보이게 — 그래야 동선 최적화가
        // 왜 그 순서인지("여기서 가까운 곳이 1번") 알 수 있다(RouteEditorControls.swift).
        val here = myLocation.takeIf { showingMe }
        val toFit = stops.map { it.place } + listOfNotNull(here)
        if (toFit.isNotEmpty()) target.fit(toFit, density, screenHeight, panelHeight, 0.dp)
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

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(IOS.systemBackground)
                    .padding(horizontal = 12.dp),
        ) {
            Box(modifier = Modifier.size(32.dp).clickable { onClose(null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.label, modifier = Modifier.size(16.dp))
            }
            OutlinedTextField(
                value = course.title,
                onValueChange = { course = course.copy(title = it) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textStyle =
                    androidx.compose.ui.text
                        .TextStyle(fontSize = 16.sp),
                singleLine = true,
            )
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    if (course.serverId == null) "만들기" else "저장",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.accent,
                    modifier =
                        Modifier.clickable {
                            saving = true
                            scope.launch {
                                val saved = store.save(course)
                                saving = false
                                onClose(saved ?: course)
                            }
                        },
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
                Text("AI 가 짠 일정입니다 · 아직 저장 전", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = IOS.accent)
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
            AmbientPoiPins(
                map = map,
                pois = visibleAmbientPois,
                onTap = { poi -> addStops(listOf(RouteStop(place = poi.asPlaceSummary(), isPinned = true))) },
            )
            MapPins(
                map = map,
                places = stops.map { it.place },
                numbered = true,
                onTap = { place -> focusedStopId = stops.firstOrNull { RouteDedupe.key(it.place) == RouteDedupe.key(place) }?.id },
            )
            TripOverlay(map = map, here = trip.here, leg = trip.leg)
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
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (trip.isActive) {
                        TripBanner(
                            trip = trip,
                            onEnd = {
                                scope.launch {
                                    store.setRunning(course, false)
                                    course = course.copy(isRunning = false)
                                    trip.end()
                                }
                            },
                            onArrivedNow = trip::markArrived,
                            onNext = { next -> trip.advance(next, scope) },
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
                    if (poisForChips.isNotEmpty()) {
                        RoutePoiChips(
                            counts = poiCounts,
                            groupsOn = poiGroupsOn,
                            onToggleGroup = { group ->
                                poiGroupsOn = if (poiGroupsOn.contains(group)) poiGroupsOn - group else poiGroupsOn + group
                            },
                            onToggleAll = {
                                poiGroupsOn =
                                    if (poiGroupsOn.size == PoiCategoryGroup.entries.size) emptySet() else PoiCategoryGroup.entries.toSet()
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("${stops.size}곳", fontSize = 12.sp, color = IOS.secondaryLabel)
                        Text(" · ", fontSize = 12.sp, color = IOS.secondaryLabel)
                        Text(
                            "직선 ${RouteFormat.kilometers(RouteGeometry.totalKilometers(stops))}",
                            fontSize = 12.sp,
                            color = IOS.secondaryLabel,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("이동 시간은 여행 중에", fontSize = 12.sp, color = IOS.tertiaryLabel)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        EditorAction(label = "동선 최적화", modifier = Modifier.weight(1f)) {
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
                        EditorAction(label = "검색", modifier = Modifier.weight(1f)) { searching = true }
                        EditorAction(label = "장바구니", modifier = Modifier.weight(1f)) { showCart = true }
                        EditorAction(label = if (pinning) "취소" else "핀 찍기", modifier = Modifier.weight(1f)) { pinning = !pinning }
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                            RouteStopRow(
                                number = index + 1,
                                stop = stop,
                                nextKilometers = stops.getOrNull(index + 1)?.let { RouteGeometry.kilometers(stop.place, it.place) },
                                isFocused = focusedStopId == stop.id,
                                pinLabel =
                                    when (index) {
                                        0 -> "출발"
                                        stops.lastIndex -> "도착"
                                        else -> null
                                    },
                                isPinned = if (index == 0) pinStart else pinEnd,
                                onFocus = {
                                    focusedStopId = stop.id
                                    map?.centerOn(stop.place)
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
                            )
                        }
                        if (course.serverId != null) {
                            item {
                                val running = course.isRunning
                                Text(
                                    if (running) "여행 종료" else "코스 시작",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (running) IOS.systemRed else IOS.accent,
                                    textAlign = TextAlign.Center,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(IOS.systemGray6)
                                            .clickable {
                                                val turningOn = !running
                                                scope.launch {
                                                    store.setRunning(course, turningOn, dayNo = 1)
                                                    course = course.copy(isRunning = turningOn)
                                                    val serverId = course.serverId
                                                    if (turningOn && serverId != null) {
                                                        val firstUnvisited = course.days.flatMap { it.stops }.firstOrNull { !it.visited }
                                                        if (firstUnvisited != null) trip.start(serverId, firstUnvisited, scope)
                                                    } else {
                                                        trip.end()
                                                    }
                                                }
                                            }.padding(vertical = 12.dp),
                                )
                            }
                            item {
                                Text(
                                    "코스 삭제",
                                    fontSize = 14.sp,
                                    color = IOS.systemRed,
                                    textAlign = TextAlign.Center,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .padding(top = 4.dp, bottom = 24.dp)
                                            .clickable { confirmingDelete = true }
                                            .padding(vertical = 12.dp),
                                )
                            }
                        } else {
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
            RouteGuideFloatingChip(
                hidden = pinning || showGuide,
                onTap = { showGuide = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = panelHeight + 12.dp),
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
    )

    if (searching) {
        PlaceSearchOverlay(
            taken = takenIds,
            onDismiss = { searching = false },
            onPick = { place -> addStops(listOf(RouteStop(place = place))) },
        )
    }

    if (showCart) {
        RouteCartSheet(
            cart = cart,
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

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("\"${course.title}\"을 지울까요?") },
            text = { Text("되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        store.delete(course)
                        onClose(null)
                    }
                }) { Text("삭제", color = IOS.systemRed) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("취소") } },
        )
    }

    if (blockedDayRemoval) {
        AlertDialog(
            onDismissRequest = { blockedDayRemoval = false },
            title = { Text("일차를 뺄 수 없습니다") },
            text = { Text("마지막 일차에 담긴 장소를 먼저 빼 주세요.") },
            confirmButton = { TextButton(onClick = { blockedDayRemoval = false }) { Text("확인") } },
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
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = IOS.label,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.systemGray6)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
    )
}

/** 안내 중인 경로선과 내 위치. iOS `RouteMapView`의 경로·내 위치 부분만 옮겼다. */
@Composable
private fun TripOverlay(
    map: NaverMap?,
    here: Pair<Double, Double>?,
    leg: com.mz2az.scenetrip.sceneapi.client.model.NextLeg?,
) {
    if (map == null) return
    androidx.compose.runtime.LaunchedEffect(here) {
        map.locationOverlay.isVisible = here != null
        here?.let { (lat, lng) -> map.locationOverlay.position = LatLng(lat, lng) }
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
    unvisitedAfter: (RouteStop) -> RouteStop?,
) {
    val target = trip.target ?: return
    Column(modifier = Modifier.fillMaxWidth().background(IOS.pinDeep.copy(alpha = 0.08f)).padding(12.dp)) {
        if (trip.phase == TripSession.Phase.ARRIVED) {
            Text("성지 도착!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.pinDeep)
            Text(target.place.name, fontSize = 12.sp, color = IOS.secondaryLabel)
        } else {
            Text("${target.place.name}로 가는 중", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            trip.leg?.let { leg ->
                val walk = leg.walkMeters?.let { " · 도보 ${it}m" } ?: ""
                Text("${leg.totalMinutes}분 · 환승 ${leg.transfers}회$walk", fontSize = 11.sp, color = IOS.secondaryLabel)
            }
            trip.failure?.let { Text(it, fontSize = 11.sp, color = IOS.systemOrange) }
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

@Composable
private fun DayTabs(
    dayCount: Int,
    dayIndex: Int,
    onSelect: (Int) -> Unit,
    onAddDay: () -> Unit,
    onRemoveDay: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed((0 until dayCount).toList()) { _, index ->
                val active = index == dayIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(index) },
                ) {
                    Text(
                        "${index + 1}일차",
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) IOS.accent else IOS.secondaryLabel,
                    )
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .width(24.dp)
                                .height(2.dp)
                                .background(if (active) IOS.accent else Color.Transparent),
                    )
                }
            }
        }
        Text("−", fontSize = 18.sp, color = IOS.secondaryLabel, modifier = Modifier.clickable(onClick = onRemoveDay).padding(6.dp))
        Text("+", fontSize = 18.sp, color = IOS.accent, modifier = Modifier.clickable(onClick = onAddDay).padding(6.dp))
    }
}

/** 장소 검색. iOS `RouteSearchSheet`의 최소 이식 — 이미 담긴 곳은 흐리게 표시한다. */
@Composable
private fun PlaceSearchOverlay(
    taken: Set<Long>,
    onDismiss: () -> Unit,
    onPick: (PlaceSummary) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val api = PlacesApi(API_BASE)
        results =
            withContext(Dispatchers.IO) {
                runCatching { api.listPlaces(q = query, limit = 30) }.getOrNull()
            }?.items ?: emptyList()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("촬영지 이름으로 검색") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                "취소",
                fontSize = 15.sp,
                color = IOS.accent,
                modifier = Modifier.clickable(onClick = onDismiss).padding(start = 10.dp),
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(20.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(results, key = { _, place -> place.id }) { _, place ->
                val isTaken = taken.contains(place.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isTaken) { onPick(place) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(place.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                        place.address?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1) }
                    }
                    if (isTaken) Text("담김", fontSize = 12.sp, color = IOS.tertiaryLabel)
                }
            }
        }
    }
}
