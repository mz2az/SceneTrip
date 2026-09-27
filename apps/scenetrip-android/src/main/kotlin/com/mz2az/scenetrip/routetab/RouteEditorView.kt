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
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.BottomSheet
import com.mz2az.scenetrip.searchtab.Detent
import com.mz2az.scenetrip.searchtab.MapPins
import com.mz2az.scenetrip.searchtab.NaverMapCanvas
import com.mz2az.scenetrip.searchtab.centerOn
import com.mz2az.scenetrip.searchtab.fit
import com.mz2az.scenetrip.searchtab.rememberLocate
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 코스 편집. iOS `RouteTab/RouteEditorView.swift`(+Controls/+Parts, 지도 중심 화면)를
 * 옮긴 것이다 — **처음 이 화면을 목록으로만 만들었다가, iOS 시뮬레이터와 나란히 대조해
 * 보고서야 지도가 주인공인 화면이라는 것을 알았다.** 검색 탭의 지도·바텀시트를 그대로
 * 재사용한다.
 *
 * 여행 중(길찾기·발자취·트립배너)·챗봇(RouteGuide)·주변 편의시설 칩·데모 주행은 아직
 * 없다 — 그 화면들 자체가 다음 단계다. 코스를 지도로 보며 순서를 바꾸고, 동선을
 * 최적화하고, 검색·장바구니·핀 찍기로 담고, 저장·삭제·여행 시작/종료하는 핵심은 된다.
 */
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
    var fitToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cart = remember { CartStore(context) }
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(Unit) { cart.refresh() }
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

    LaunchedEffect(map, dayIndex, fitToken) {
        val target = map ?: return@LaunchedEffect
        if (stops.isNotEmpty()) target.fit(stops.map { it.place }, density, screenHeight, panelHeight, 0.dp)
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
            )
            MapPins(
                map = map,
                places = stops.map { it.place },
                numbered = true,
                onTap = { place -> focusedStopId = stops.firstOrNull { RouteDedupe.key(it.place) == RouteDedupe.key(place) }?.id },
            )
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
                                                scope.launch {
                                                    store.setRunning(course, !running, dayNo = 1)
                                                    course = course.copy(isRunning = !running)
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
        }
    }

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
