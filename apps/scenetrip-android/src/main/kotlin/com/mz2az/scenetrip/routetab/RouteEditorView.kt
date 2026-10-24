package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CartLoadState
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.CoordinateSources
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.requestLocation
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.sceneapi.client.model.GuideTrip
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.searchtab.BottomSheet
import com.mz2az.scenetrip.searchtab.Detent
import com.mz2az.scenetrip.searchtab.LocationTracker
import com.mz2az.scenetrip.searchtab.MapPins
import com.mz2az.scenetrip.searchtab.NaverMapCanvas
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.searchtab.centerOn
import com.mz2az.scenetrip.searchtab.fit
import com.mz2az.scenetrip.searchtab.focusOnStop
import com.mz2az.scenetrip.searchtab.hasLocationPermission
import com.mz2az.scenetrip.searchtab.regionSource
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
import com.mz2az.scenetrip.ui.KakaoDirectionsNoticeBanner
import com.mz2az.scenetrip.ui.LocationConsentSheetHost
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.StairsIcon
import com.mz2az.scenetrip.ui.SubwayIcon
import com.mz2az.scenetrip.ui.SwapArrowsIcon
import com.mz2az.scenetrip.ui.TransitIcon
import com.mz2az.scenetrip.ui.UnavailableNoticeRow
import com.mz2az.scenetrip.ui.WalkIcon
import com.mz2az.scenetrip.ui.sheetListBottom
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MAP_FIT_DEBOUNCE_MILLIS = 150L

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
    // 이 범위가 GPS(내 위치 포함 맞춤)에서 왔는가 — 주변 편의시설 조회에 위치 동의가 필요한지를 가른다(MZ2AZ-472).
    val source: CoordinateSource,
)

@Composable
fun RouteEditorView(
    store: RouteStore,
    initial: RouteCourse,
    isNew: Boolean = false,
    onClose: (RouteCourse?) -> Unit,
    // 마법사 시트 안에서 열릴 때 — 시트가 이미 상태바 아래에 있으니 상태바 여백을 또 두지 않는다.
    inSheet: Boolean = false,
    draftState: RouteEditorDraft? = null,
    operationScope: kotlinx.coroutines.CoroutineScope? = null,
    editorDismiss: java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>? = null,
) {
    val editorDraft = draftState ?: remember { RouteEditorDraft(initial) }
    var course by editorDraft.course
    var opened by remember(initial) { mutableStateOf(RouteBridge.outgoing(initial)) }
    var discarding by editorDraft.discarding
    var dayIndex by editorDraft.dayIndex
    var map by remember { mutableStateOf<NaverMap?>(null) }
    var detent by remember { mutableStateOf(Detent.MEDIUM) }
    var panelHeight by remember { mutableStateOf(0.dp) }
    var focusedStopId by remember { mutableStateOf<java.util.UUID?>(null) }

    // 번호 핀·목록 행을 눌러 카드를 띄운 정지점. 카드는 한 장만 — 편의시설·AI 점의 카드(`guideSession.picked`)와 같이 뜨지 않는다(iOS `pickedStop`).
    var pickedStop by remember { mutableStateOf<RouteStop?>(null) }

    // 검색·장바구니에서 체크한, 담을까 보는 곳 — 지도에 빨간 해태로 뜬다(iOS `previewPlaces`). 시트를 닫으면 비운다.
    var previewPlaces by remember { mutableStateOf<List<PlaceSummary>>(emptyList()) }

    // 도착 알림 카드의 높이 — 지도 오른쪽 위 단추가 카드 밑으로 내려가게(iOS `tripBannerHeight`).
    var arrivalHeight by remember { mutableStateOf(0.dp) }

    // 바닥 카드(편의시설·성지)의 높이 — 해태 칩이 카드 위로 비켜 서는 데 쓴다(MZ2AZ-367, iOS `guideCardTop`).
    var cardHeight by remember { mutableStateOf(0.dp) }
    var pinning by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<RoutePin?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var showDayStay by remember { mutableStateOf(false) }
    var saving by editorDraft.saving
    var saveFailure by editorDraft.saveFailure
    var blockedDayRemoval by remember { mutableStateOf(false) }
    var pinStart by remember { mutableStateOf(false) }
    var pinEnd by remember { mutableStateOf(false) }
    var myLocation by remember { mutableStateOf<PlaceSummary?>(null) }
    // 켜짐이 기본이다(2026-08-28 iOS 결정) — 코스를 보는 사람은 대개 자기 위치와
    // 견주고 싶어서 본다. 끄는 것은 선택으로 남는다.
    var showingMe by remember { mutableStateOf(true) }
    var fitToken by remember { mutableStateOf(0) }
    var optimizeNudge by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // 가이드 기준: 기기 위치가 있어도 지도 중심을 쓰는가, 그리고 「지도 주변 촬영지 보기」를 누른 시점에 고정한 지도 중심.
    var guideUsesMapCenter by remember { mutableStateOf(false) }
    var nearbyAnchor by remember { mutableStateOf<RouteGuideAnchor?>(null) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var ambientPois by remember { mutableStateOf<List<PoiSummary>>(emptyList()) }
    var poiGroupsOn by remember { mutableStateOf(PoiCategoryGroup.entries.toSet()) }
    var aiPlacesOn by remember { mutableStateOf(true) }
    var showDraftNotes by remember { mutableStateOf(false) }
    val localScope = rememberCoroutineScope()
    val scope = operationScope ?: localScope
    val context = LocalContext.current
    val cart = remember { CartStore(context) }
    // 직접 짜기의 새 빈 코스에서 장바구니 버튼을 한 번 알려 준다(MZ2AZ-444).
    var cartHintState by remember { mutableStateOf(RouteCartHintState()) }
    val cartHint = remember { RouteCartHint(PreferencesCartHintStorage(context)) { cartHintState = it } }
    val rootView = androidx.compose.ui.platform.LocalView.current
    val guideSession = remember { RouteGuideSession(context, scope) }
    val trip = remember { TripSession(context) }
    var visitStates by remember { mutableStateOf<Map<VisitKey, VisitStatus>>(emptyMap()) }
    val visitSync = remember { VisitSync(store::markVisited, { AuthStore.epoch }) { visitStates = it } }
    LaunchedEffect(AuthStore.epoch) { visitSync.reset() }

    fun visitStatus(stop: RouteStop?): VisitStatus? {
        val courseId = course.serverId ?: return null
        val itemId = stop?.serverItemId ?: return null
        return visitStates[VisitKey(courseId, itemId)]
    }

    fun visitBlocked(): Boolean = visitStates.any { (key, status) -> key.courseId == course.serverId && status != VisitStatus.SAVED }
    val footprints = remember { FootprintStore.getInstance(context) }
    val poisApi = remember { PoisApi(API_BASE) }
    val density = LocalDensity.current
    var screenHeight by remember { mutableStateOf(0.dp) }
    val guideEntry =
        RouteGuideEntry.placement(
            guiding = trip.phase == TripSession.Phase.GUIDING,
            hasTarget = trip.target != null,
            hasCard = guideSession.picked != null || pickedStop != null || focusedStopId != null,
            panelOpen = showGuide,
            pinning = pinning,
        )

    // 「마법사를 열어 달라」는 표시는 편집 화면이 실제로 닫힐 때만 올린다 — 알림 창이 onDismiss 를 먼저 부르므로 거기서 만지면 안 된다.
    val plannerExit = remember { PlannerExit { TabRouter.pendingPlanner = it } }

    fun cancel(planner: Boolean = false) {
        if (saving) return
        val changed = if (initial.serverId == null) !RouteBridge.isBlank(course) else RouteBridge.changed(opened, course)
        if (plannerExit.request(planner, changed) { onClose(null) }) discarding = true
    }

    // 가이드 창의 「AI 로 짜기」 — 이 편집 화면을 「취소」와 같은 길로 닫고(바꾼 것이 있으면 버릴지 묻는다),
    // 다 닫히면 코스 목록이 마법사를 띄운다(`RouteTabView.openPlannerIfAsked`). iOS `leaveForPlanner`.
    fun leaveForPlanner() {
        if (saving) return
        showGuide = false
        cancel(planner = true)
    }
    androidx.activity.compose.BackHandler { cancel() }
    androidx.compose.runtime.SideEffect { editorDismiss?.set { cancel() } }
    androidx.compose.runtime.DisposableEffect(editorDismiss) { onDispose { editorDismiss?.set(null) } }

    LaunchedEffect(Unit) { cart.refresh() }
    // 편집기에 들어서면 링을 바로 켜고, 문구는 장바구니 조회가 끝난 뒤 낭독(TalkBack)과 함께 한 번 보인다.
    LaunchedEffect(Unit) {
        cartHint.present(
            course = course,
            cart = {
                val (loadState, isEmpty) =
                    snapshotFlow { cart.loadState to cart.items.isEmpty() }
                        .first { it.first == CartLoadState.LOADED || it.first == CartLoadState.FAILED }
                CartOutcome(loadState, isEmpty)
            },
            announce = { rootView.announceForAccessibility(it) },
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { trip.end() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, trip) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) trip.foreground(false, scope)
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) trip.foreground(true, scope)
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            trip.foreground(false, scope)
        }
    }
    // 권한을 받고 첫 자리를 얻었는가 — 이 뒤로 「내 위치」 토글이 켜져 있는 동안 계속 받는다(아래).
    var locationReady by remember { mutableStateOf(false) }
    val requestLocation =
        rememberLocate(
            onLocated = { found ->
                locationReady = true
                myLocation = PlaceSummary(id = 0, name = "여기", latitude = found.latitude, longitude = found.longitude)
            },
            onFailure = {},
        )
    // 못 받아도 코스는 만들어진다 — 실패는 조용히 넘긴다(동선 최적화가 못 쓸 뿐).
    LaunchedEffect(Unit) { requestLocation() }

    // **토글이 켜져 있는 동안 계속 받는다**(MZ2AZ-509, iOS `RouteMapLocate.startLocating`). 첫 값은 대략값일 수 있어
    // 더 나은 값으로 점을 옮긴다. 끄거나 여행 안내가 자리를 주기 시작하면 멈춘다 — 두 출처가 점을 서로 끌어당기면 튄다.
    androidx.compose.runtime.DisposableEffect(showingMe, trip.isActive, locationReady) {
        val follower = LocationTracker(context)
        if (showingMe && !trip.isActive && locationReady && context.hasLocationPermission) {
            follower.start(
                minTimeMs = 1_000L,
                minDistanceM = 5f,
                onFix = { found, _ ->
                    myLocation = PlaceSummary(id = 0, name = "여기", latitude = found.latitude, longitude = found.longitude)
                },
            )
        }
        onDispose { follower.stop() }
    }

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

    fun markVisitedLocally(stop: RouteStop) {
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

    fun markVisited(stop: RouteStop) {
        val courseId = course.serverId
        val itemId = stop.serverItemId
        if (courseId == null || itemId == null) {
            markVisitedLocally(stop)
            return
        }
        scope.launch {
            if (visitSync.save(courseId, itemId) && course.serverId == courseId) markVisitedLocally(stop)
        }
    }

    fun saveAndClose() {
        // 상세를 받지 못한 서버 코스는 저장하지 않는다 — 빈 일정으로 서버 코스를 덮어쓴다(저장 경로에도 같은 막이 있다).
        if (saving || !course.canSave) return
        // 제목을 비운 채 저장하면 목록에 이름 없는 코스가 생긴다 — iOS `saveAndClose`와 같이 기본 이름으로.
        course = course.copy(title = course.title.trim().ifEmpty { tr("내 코스", "코스 제목") })
        saving = true
        scope.launch {
            val saved = store.save(course)
            saving = false
            if (saved != null) onClose(saved) else saveFailure = store.saveFailure
        }
    }

    // 검색에서 담은 작품 제목을 먼저 쓰고, 저장된 코스는 상세 조회 결과로 채운다.
    fun workTitles(stop: RouteStop): String {
        val placeId = stop.savablePlaceId ?: return ""
        val carried =
            stop.place.contents
                .orEmpty()
                .map { it.title }
        return carried.ifEmpty { store.workTitles(placeId) }.take(2).joinToString(" · ")
    }
    LaunchedEffect(Unit) { store.loadPlaces() }
    LaunchedEffect(course.days.flatMap { it.stops }.map { it.savablePlaceId }) {
        store.loadWorkTitles(course.days.flatMap { it.stops })
    }

    LaunchedEffect(Unit) { trip.onArrived = { stop -> markVisited(stop) } }

    // 홈 「이어서 길찾기」 — 코스가 열리면 첫 미방문 성지로 안내를 켠다. 표시는 한 번
    // 읽고 끈다(iOS `RouteEditorView.runPendingTripStart`).
    LaunchedEffect(course) {
        if (!TabRouter.pendingTripStart) return@LaunchedEffect
        val allStops = course.days.flatMap { it.stops }
        if (allStops.isEmpty()) return@LaunchedEffect
        TabRouter.pendingTripStart = false
        // 숨김·폐업 행은 건너뛴다. 갈 수 있는 곳이 없으면 코스를 시작하지 않는다(MZ2AZ-458).
        val next = RouteAvailability.startTarget(allStops) ?: return@LaunchedEffect
        val serverId = course.serverId ?: return@LaunchedEffect
        store.setRunning(course, true, dayNo = 1)
        course = course.copy(isRunning = true)
        trip.start(serverId, next, scope)
    }

    fun addStops(
        newStops: List<RouteStop>,
        asNext: Boolean = false,
    ) {
        val toAdd = RouteDedupe.fresh(newStops, course.stops)
        if (toAdd.isEmpty()) return
        updateDay(dayIndex) { day ->
            val slot =
                if (asNext && course.isRunning) {
                    RouteGeometry.nextSlot(
                        day.stops,
                        trip.target,
                        trip.phase == TripSession.Phase.ARRIVED,
                    )
                } else {
                    day.stops.size
                }
            day.copy(stops = day.stops.take(slot) + toAdd + day.stops.drop(slot))
        }
        fitToken += 1
        optimizeNudge = course.days[dayIndex].stops.size >= 2
    }

    val day = course.days.getOrNull(dayIndex)
    val stops = day?.stops ?: emptyList()

    // 카드가 가리키던 정지점이 일차에서 빠지면(지움·일차 바꿈) 카드도 내린다.
    LaunchedEffect(stops, pickedStop) {
        val shown = pickedStop
        if (shown != null && stops.none { it.id == shown.id }) pickedStop = null
    }

    // 정지점을 고른다 — 카드를 올리고 그곳으로 확대한다. 내 자리가 3 km 넘게 멀면 그곳만 보여 준다(`focusOnStop`).
    fun focusStop(
        stop: RouteStop,
        withCard: Boolean = true,
    ) {
        focusedStopId = stop.id
        // 번호 핀을 누르면 카드가 뜬다. 목록 행을 누르면 지도만 간다(iOS `onFocus`).
        pickedStop = if (withCard) stop else null
        guideSession.dismiss() // 카드는 한 장만
        val here = if (trip.isActive) trip.here else myLocation?.let { it.latitude to it.longitude }
        map?.focusOnStop(stop.place, here, showingMe, density, screenHeight, panelHeight)
    }

    // 이 정지점으로 안내를 켠다. 번호는 **그 일차 안의 순서**다(지도·목록의 번호와 같다).
    fun beginTrip(
        stop: RouteStop,
        serverCourseId: Long?,
    ) {
        if (!stop.canNavigate) {
            pickedStop = stop
            return
        }
        // 계약이 목적지를 코스 항목으로 받으므로 코스의 서버 id 도 함께 준다(저장 전이면 길을 못 묻는다).
        val courseId = serverCourseId ?: return
        if (visitBlocked()) return
        focusedStopId = null
        pickedStop = null
        guideSession.dismiss()
        if (trip.isActive) trip.advance(stop, scope) else trip.start(courseId, stop, scope)
    }

    /**
     * 이 정지점으로 안내를 켠다 (iOS `startTrip(to:)`, MZ2AZ-478 B7).
     *
     * **방금 담은 곳은 먼저 저장한다.** 길찾기 계약은 목적지를 저장된 코스 항목으로만 받는다 — 챗봇이 찾아 준 가게를 담고 곧바로
     * 「다음 · 2번으로 길찾기」를 누르면 「저장된 코스의 장소에서만…」으로 막혔다. 저장이 실패하면 시작하지 않는다(알림은 저장 쪽이 띄운다).
     */
    fun startTrip(stop: RouteStop) {
        if (!stop.canNavigate) {
            pickedStop = stop
            return
        }
        if (stop.serverItemId != null || course.serverId == null || stop.placeMissing) {
            beginTrip(stop, course.serverId)
            return
        }
        if (saving || !course.canSave) return
        saving = true
        scope.launch {
            val saved = store.save(course)
            saving = false
            if (saved == null) {
                saveFailure = store.saveFailure
                return@launch
            }
            course = saved // 항목 id 가 붙어 온다. 다녀옴·여행 상태도 서버 것 그대로다.
            // 여기까지는 서버에 있다 — 「취소」가 이미 저장된 것을 버릴지 묻지 않게 기준을 옮긴다.
            opened = RouteBridge.outgoing(saved)
            // 촬영지 줄은 촬영지 id 로 찾는다 — 편의시설 이름으로 담긴 줄은 서버가 촬영지 이름·좌표로 돌려준다.
            beginTrip(
                stop.rowIn(
                    saved.days
                        .getOrNull(dayIndex)
                        ?.stops
                        .orEmpty(),
                ) ?: stop,
                saved.serverId,
            )
        }
    }
    androidx.compose.runtime.SideEffect {
        guideSession.contextOf = {
            val picked = stops.firstOrNull { it.id == focusedStopId }
            val recent = footprints.points.filter { it.at >= System.currentTimeMillis() - 86_400_000L }
            val walked =
                recent.zipWithNext().sumOf { (a, b) ->
                    com.mz2az.scenetrip.data
                        .haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
                }
            com.mz2az.scenetrip.sceneapi.client.model.GuideContext(
                plan = RouteGuidePlan.plan(course),
                picked =
                    picked?.let {
                        com.mz2az.scenetrip.sceneapi.client.model.GuideStop(
                            number = 0,
                            name = it.place.name,
                            latitude = it.place.latitude,
                            longitude = it.place.longitude,
                            category = it.place.type,
                            visited = it.visited,
                        )
                    },
                trip =
                    if (course.isRunning) {
                        GuideTrip(
                            phase =
                                when (trip.phase) {
                                    TripSession.Phase.PLAN -> GuideTrip.Phase.plan
                                    TripSession.Phase.GUIDING -> GuideTrip.Phase.guiding
                                    TripSession.Phase.ARRIVED -> GuideTrip.Phase.arrived
                                },
                            course = course.title,
                            day = dayIndex + 1,
                            days = maxOf(1, course.days.size),
                            walkedKilometers = kotlin.math.round(walked * 10) / 10,
                            targetNumber =
                                trip.target?.let { target ->
                                    stops.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.plus(1)
                                },
                            targetMeters =
                                trip.target?.let { target ->
                                    trip.here?.let { (lat, lng) ->
                                        (
                                            com.mz2az.scenetrip.data
                                                .haversineKm(lat, lng, target.place.latitude, target.place.longitude) *
                                                1000
                                        ).toInt()
                                    }
                                },
                        )
                    } else {
                        null
                    },
                stops =
                    stops.take(50).mapIndexed { index, stop ->
                        com.mz2az.scenetrip.sceneapi.client.model.GuideStop(
                            number = index + 1,
                            name = stop.place.name,
                            latitude = stop.place.latitude,
                            longitude = stop.place.longitude,
                            category = stop.place.type,
                            visited = stop.visited,
                        )
                    },
            )
        }
    }
    val accountEpoch = com.mz2az.scenetrip.auth.AuthStore.epoch
    var lastAccountEpoch by remember { mutableStateOf(accountEpoch) }
    LaunchedEffect(accountEpoch) {
        if (lastAccountEpoch != accountEpoch) {
            lastAccountEpoch = accountEpoch
            guideSession.accountChanged()
            trip.end()
        }
    }
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
    val drawnPlaceIds = stops.mapNotNull { it.savablePlaceId }.toSet()
    val takenPoiIds = stops.mapNotNull { it.poiId }.toSet()
    val visibleAmbientPois =
        poisForChips.filter {
            poiGroupsOn.contains(it.categoryGroup) && it.placeId !in drawnPlaceIds && it.id !in takenPoiIds
        }
    val poiCounts = poisForChips.groupingBy { it.categoryGroup }.eachCount()
    val aiPlacesForChip = guideSession.places.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val visibleAiPlaces = if (aiPlacesOn) aiPlacesForChip.filterNot { place -> stops.any { place.matches(it) } } else emptyList()

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

    // 열쇠에 `myLocation` 자체를 두지 않는다 — 계속 받는 값이 올 때마다 화면을 다시 맞추면 손으로 옮긴 지도가 되돌아간다.
    // 자리를 처음 알게 된 때(`myLocation != null`)만 다시 맞춘다.
    LaunchedEffect(map, dayIndex, fitToken, showingMe, myLocation != null, screenHeight, panelHeight) {
        val target = map ?: return@LaunchedEffect
        if (screenHeight <= 0.dp || panelHeight <= 0.dp) return@LaunchedEffect
        delay(MAP_FIT_DEBOUNCE_MILLIS) // 시트 드래그 중 반복 맞추기를 취소하고 마지막 보이는 영역에 맞춘다.
        // `map`이 막 생겼을 때는 `NaverMapCanvas`의 `AndroidView`가 아직 배치되기 전이다
        // — 그 상태로 `fitBounds`를 부르면 크기 0 인 화면 기준으로 계산돼 한 단계 축소된
        // 값이 나온다(실기 확인, searchtab `NaverMap.kt`의 최초 진입 카메라와 같은 함정).
        // 한 프레임 기다리면 풀린다.
        withFrameNanos {}
        // 「내 위치」 켜져 있으면 나와 촬영지가 같이 보이게 — 그래야 동선 최적화가
        // 왜 그 순서인지("여기서 가까운 곳이 1번") 알 수 있다(RouteEditorControls.swift).
        // 정지점을 골라 둔 채면 일차 전체가 아니라 그곳을 다시 맞춘다(iOS `RouteMapView.render` 의 `move(to: focused)`).
        val focused = stops.firstOrNull { it.id == focusedStopId }
        if (focused != null) {
            target.focusOnStop(
                focused.place,
                myLocation?.let { it.latitude to it.longitude },
                showingMe,
                density,
                screenHeight,
                panelHeight,
            )
            return@LaunchedEffect
        }
        val here = myLocation.takeIf { showingMe }
        val toFit = stops.map { it.place } + listOfNotNull(here)
        if (toFit.isNotEmpty()) {
            target.fit(
                toFit,
                density,
                screenHeight,
                panelHeight,
                0.dp,
                com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE,
                // 내 위치가 들어간 맞춤은 기기 위치에서 온 범위다(iOS `RouteMapView.fit`).
                source = if (here != null) CoordinateSource.DEVICE else CoordinateSource.USER_SELECTED,
            )
        }
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
    //
    // 범위가 GPS 에서 왔으면(내 위치 포함 맞춤) 이 조회가 기기 위치를 서버로 보내는 것이므로 위치기반서비스 동의를 먼저 받는다.
    // 취소하면 조회하지 않는다. 손으로 옮긴 범위는 사용자가 고른 영역이라 묻지 않는다(MZ2AZ-472).
    val locationStore = remember { LocationConsentStore.getInstance(context) }
    val ambientGate = remember { AIConsentGate(locationStore) }
    LocationConsentSheetHost(ambientGate)
    var ambientRequest by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(viewport) {
        val v = viewport
        ambientRequest?.cancel()
        ambientGate.cancel()
        if (v == null || v.zoom < 13.0) {
            ambientPois = emptyList()
            return@LaunchedEffect
        }
        // 여행 시작의 동의 시트와 겹치지 않는다. 허용 뒤 경로 맞춤에서 다시 조회한다.
        if (v.source == CoordinateSource.DEVICE && trip.isActive && !locationStore.isAllowed) {
            ambientPois = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        val bbox = "%.6f,%.6f,%.6f,%.6f".format(Locale.US, v.west, v.south, v.east, v.north)
        ambientGate.requestLocation(v.source) {
            ambientRequest =
                scope.launch {
                    val found =
                        apiResult {
                            CoordinateSources.with(v.source) {
                                poisApi.listPois(bbox = bbox, lat = v.lat, lng = v.lng, sort = PoisApi.SortListPois.distance, limit = 30)
                            }
                        }.getOrNull()
                    ambientPois = found?.items.orEmpty()
                }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            ambientRequest?.cancel()
            ambientGate.cancel()
        }
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
    LaunchedEffect(guideSession.replyRevision) {
        // 새 계획을 먼저 적용해야 course.open의 날짜가 이전 코스 길이로 잘리지 않는다.
        guideSession.lastEffects.forEach { effect ->
            if (effect.op in listOf("plan.draft", "plan.revise", "plan.move")) {
                effect.plan?.let { plan ->
                    course = RouteGuidePlan.revising(course, plan)
                    dayIndex = dayIndex.coerceAtMost(course.days.lastIndex.coerceAtLeast(0))
                    focusedStopId = null
                    fitToken += 1
                }
            }
        }
        if (guideSession.lastEffects.any { it.op == "cart.add" || it.op == "cart.remove" }) {
            cart.refresh()
        }
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
                    if (target != null &&
                        places.isNotEmpty()
                    ) {
                        target.fit(places, density, screenHeight, panelHeight, 0.dp, com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE)
                    }
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

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).then(if (inSheet) Modifier else Modifier.statusBarsPadding())) {
        // iOS `RouteEditorView.topBar`: 「취소」 | 가운데 제목(눌러서 고침) + 연필 | 「만들기/저장」. 큰 글자에서는 동작과 제목을 두 줄에 둔다
        // (MZ2AZ-388, `stackedTopBar`) — 한 줄에 셋을 두면 제목이 단추를 밀어낸다.
        val stackTopBar = isAccessibilityFontScale()
        val cancelAction: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 44.dp).clickable { cancel() }) {
                Text(tr("취소"), fontSize = 17.sp, color = IOS.accent)
            }
        }
        val saveAction: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.heightIn(min = 44.dp)) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (course.serverId == null) tr("만들기") else tr("저장"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (course.canSave) IOS.accent else IOS.accent.copy(alpha = 0.4f),
                        modifier = Modifier.clickable(enabled = course.canSave) { saveAndClose() },
                    )
                }
            }
        }
        // 제목칸은 테두리 없이 글자 폭을 따르고 200 에서 멈춘다 — iOS 와 같은 이유(320bc94).
        val titleField: @Composable () -> Unit = {
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
                        .measure(course.title.ifEmpty { tr("코스 이름") }, IOS.headline, maxLines = 1, softWrap = false)
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
                                Text(tr("코스 이름"), style = IOS.headline, color = IOS.tertiaryLabel, maxLines = 1)
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
        }
        if (stackTopBar) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    cancelAction()
                    Spacer(Modifier.weight(1f))
                    saveAction()
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) { titleField() }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(IOS.systemBackground)
                        .padding(horizontal = 16.dp),
            ) {
                cancelAction()
                Spacer(Modifier.weight(1f))
                titleField()
                Spacer(Modifier.weight(1f))
                saveAction()
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
                Text(tr("AI 가 짠 일정입니다 · 아직 저장 전"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = IOS.accent)
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
                modifier = Modifier.fillMaxSize().onSizeChanged { screenHeight = with(density) { it.height.toDp() } },
                sheetHeight = panelHeight,
                searchBarInset = 0.dp,
                insetMode = com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE,
                onMapReady = { map = it },
                onCameraIdle = { bounds, center, zoom, source ->
                    viewport =
                        MapViewport(
                            south = bounds.southLatitude,
                            west = bounds.westLongitude,
                            north = bounds.northLatitude,
                            east = bounds.eastLongitude,
                            lat = center.latitude,
                            lng = center.longitude,
                            zoom = zoom,
                            source = source,
                        )
                },
            )
            PlanOverlay(
                map = map,
                allStops = stops,
                // 안내 중인 목표에서 나가는 선은 남긴다 — 다녀온 곳에서 끊되, 지금
                // 가는 길은 계획선으로도 보여야 한다(iOS `RouteMapTrip.drawLine`
                // 의 `keepFrom`).
                keepFromId = if (trip.phase == TripSession.Phase.GUIDING) trip.target?.id else null,
            )
            AmbientPoiPins(
                map = map,
                pois = visibleAmbientPois,
                pickedId = guideSession.picked?.takeIf { it.source == com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource.poi }?.id,
                // iOS `RouteMapAmbient`: 점을 눌러도 바로 담기지 않는다 — 카드가 먼저 뜨고
                // "경로에 추가"는 사람이 카드에서 직접 누른다.
                onTap = { poi -> guideSession.pick(poi) },
            )
            AiPlacePins(
                map = map,
                places = visibleAiPlaces,
                picked = guideSession.picked,
                onTap = { place -> guideSession.pick(place) },
            )
            // 담을까 보는 곳 — 빨간 해태. 코스 핀보다 위에 올린다(iOS `previews`).
            PreviewPins(
                map = map,
                places = previewPlaces,
                onTap = { summary ->
                    // 검색·장바구니에서 온 미리보기가 이미 코스에 있으면 그 정지점의 카드를 띄운다(iOS `onTapPreview`).
                    stops.firstOrNull { RouteDedupe.key(it.place) == RouteDedupe.key(summary) }?.let { focusStop(it) }
                },
            )
            // 정지점 핀: 다녀온 곳=발바닥, 고른 곳·안내 중인 목적지=해태, 그 밖=번호. 누른 곳으로 확대해 들어간다 —
            // 일차 전체를 맞춘 화면 그대로면 무엇을 눌렀는지 지도에서 알 수 없다(MZ2AZ-509, iOS #326).
            CourseStopPins(
                map = map,
                stops = stops,
                focusedId = focusedStopId,
                targetId = trip.target?.id,
                onTap = { stop -> focusStop(stop) },
            )
            PendingPinMarker(map = map, pin = pendingPin)
            TripOverlay(map = map, active = trip.isActive, here = trip.here, leg = trip.leg, target = trip.target, density = density)
            // 안내 경로 — 구간마다 따로 그려 도보(점선)와 대중교통(실선)이 갈리고, 걸어온 구간은 지운다(iOS `renderLegs`).
            TripLegLines(map = map, legs = trip.leg?.legs.orEmpty(), target = trip.target, here = trip.here)
            // 「내 자리 → 다음 곳」 직선 미리보기 — 실제 경로가 오면 사라진다. 가는 중인데 경로가 아직 안 왔으면 목적지,
            // 도착해 다음을 고르기 전이면 다음 곳(iOS `previewTarget`).
            TripPreviewLine(
                map = map,
                here = trip.here,
                target =
                    when (trip.phase) {
                        TripSession.Phase.GUIDING -> if (trip.leg == null) trip.target else null
                        TripSession.Phase.ARRIVED -> RouteAvailability.nextUnvisited(stops)?.second
                        TripSession.Phase.PLAN -> null
                    },
            )
            MyLocationPulse(map = map, visible = if (trip.isActive) trip.here != null else showingMe && myLocation != null)
            FootprintTrail(
                map = map,
                points =
                    if (footprints.drawsTrail) {
                        footprints.points.filter { it.at > System.currentTimeMillis() - 86_400_000L }
                    } else {
                        emptyList()
                    },
            )
            if (!pinning) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // 도착 알림 카드가 떠 있으면 그 밑으로 내려온다.
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = if (trip.phase == TripSession.Phase.ARRIVED) arrivalHeight else 0.dp)
                            .padding(10.dp),
                ) {
                    RouteLocateButton(
                        on = showingMe,
                        onClick = {
                            if (trip.isActive) {
                                trip.here?.let { (lat, lng) ->
                                    map?.centerOn(
                                        PlaceSummary(id = 0, name = "여기", latitude = lat, longitude = lng),
                                        CoordinateSource.DEVICE,
                                    )
                                }
                            } else {
                                showingMe = !showingMe
                            }
                        },
                    )
                    // 발자취 단추 — 여행 중이고 **마이페이지의 「지도에 발자취 보기」가
                    // 켜져 있을 때만** 있다. 설정을 끄면 단추째 사라진다(MZ2AZ-348).
                    // 단추 자체는 발자국을 지금 그릴지(trailVisible)를 켜고 끈다.
                    if (course.isRunning && footprints.enabled) {
                        RouteFootprintButton(
                            on = footprints.trailVisible,
                            onClick = { footprints.updateTrailVisible(!footprints.trailVisible) },
                        )
                    }
                }
            }
            if (pinning) {
                Text(
                    tr("지도를 눌러 장소를 찍으세요"),
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
            // 도착 알림 카드 — 지도 맨 위에 떠서 성지 목록 자리를 먹지 않는다(2026-09-03 사용자 요청).
            if (trip.phase == TripSession.Phase.ARRIVED) {
                TripArrivalCard(
                    trip = trip,
                    targetNumber = trip.target?.let { target -> stops.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.plus(1) },
                    next = RouteAvailability.nextUnvisited(stops),
                    visitStatus = visitStatus(trip.target),
                    onRetryVisit = { trip.target?.let { markVisited(it) } },
                    onGuide = if (guideEntry == RouteGuideEntry.TRIP_HEADER) ({ showGuide = true }) else null,
                    onEnd = trip::end,
                    onHeight = { arrivalHeight = it },
                    modifier = Modifier.align(Alignment.TopCenter),
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
                    if (trip.phase == TripSession.Phase.GUIDING) {
                        TripGuidingBanner(
                            trip = trip,
                            targetNumber =
                                trip.target?.let { target ->
                                    stops.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.plus(1)
                                },
                            onGuide = if (guideEntry == RouteGuideEntry.TRIP_HEADER) ({ showGuide = true }) else null,
                            onEnd = trip::end,
                            onRetry = { trip.retry(scope) },
                        )
                    }
                    DayTabs(
                        dayCount = course.days.size,
                        dayIndex = dayIndex,
                        onSelect = {
                            dayIndex = it
                            focusedStopId = null
                            pickedStop = null
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
                    RouteDaySummary(course.days.getOrNull(dayIndex)) { showDayStay = true }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    IOS.systemBackground,
                                ).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    ) {
                        EditorAction(
                            label = tr("동선 최적화"),
                            icon = { SwapArrowsIcon(if (optimizeNudge) Color.White else IOS.label, it) },
                            highlight = optimizeNudge,
                            modifier = Modifier.weight(1f),
                        ) {
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
                            pickedStop = null
                            fitToken += 1
                            optimizeNudge = false
                        }
                        EditorAction(label = tr("검색"), icon = { MagnifierIcon(IOS.label, it) }, modifier = Modifier.weight(1f)) {
                            searching =
                                true
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EditorAction(
                                label = tr("장바구니"),
                                icon = { BagIcon(IOS.label, it) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                cartHint.dismiss()
                                showCart = true
                            }
                            if (cartHintState.isHighlighted) RouteCartHintRing(Modifier.matchParentSize())
                        }
                        EditorAction(
                            label = if (pinning) tr("취소") else tr("핀 찍기"),
                            icon = { MapPinEllipseIcon(IOS.label, it) },
                            modifier = Modifier.weight(1f),
                        ) { pinning = !pinning }
                    }
                    cartHintState.message?.let { text -> RouteCartHintNotice(text, onDismiss = { cartHint.dismiss() }) }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                            RouteStopRow(
                                number = index + 1,
                                stop = stop,
                                nextKilometers = RouteAvailability.nextKilometers(index, stops),
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
                                            0 -> tr("출발")
                                            stops.lastIndex -> tr("도착")
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
                                        pickedStop = null
                                        fitToken += 1
                                    } else {
                                        focusStop(stop, withCard = false)
                                    }
                                },
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
                                // 도착하면 「안내 중」은 내린다 — 그 자리는 「다녀옴」의 것이다(2026-09-03 사용자 지적).
                                isTarget = trip.phase == TripSession.Phase.GUIDING && trip.target?.id == stop.id,
                                running = course.isRunning,
                                // 여행 중이면 어느 곳이든 「길찾기」 — 다녀온 곳도 다시 갈 수 있다. 이 지도에 경로가 그려진다.
                                onNavigate = if (course.isRunning && stop.canNavigate && !visitBlocked()) ({ startTrip(stop) }) else null,
                            )
                            // 목록에서 빠진 촬영지·폐업한 편의시설: 지우지 않고 이유를 알리며, 뺄지는 사용자가 정한다(MZ2AZ-458).
                            stop.unavailable?.let { notice ->
                                UnavailableNoticeRow(
                                    item = notice,
                                    onRemove = {
                                        updateDay(dayIndex) { d -> d.copy(stops = d.stops.filterNot { it.id == stop.id }) }
                                        optimizeNudge = stops.size - 1 >= 2
                                    },
                                    modifier = Modifier.padding(start = 50.dp, end = 12.dp),
                                )
                            }
                            val rowVisit = visitStatus(stop)
                            if (rowVisit == VisitStatus.PENDING || rowVisit == VisitStatus.FAILED) {
                                Box(Modifier.padding(start = 50.dp, end = 16.dp, bottom = 8.dp)) {
                                    VisitSaveNotice(rowVisit) { markVisited(stop) }
                                }
                            }
                            // iOS `List` 줄 구분선 — 번호 뒤 글자 시작(16 + 22 + 12)부터.
                            if (index < stops.lastIndex) IOSListDivider(start = 50.dp)
                        }
                        if (stops.isEmpty()) {
                            item {
                                // iOS `RouteEditorView.stopRows` — "직접 짜기"로 빈 코스를
                                // 열었을 때 아무 안내도 없었다(실기 비교로 발견).
                                Text(
                                    tr("아직 담은 장소가 없습니다\n장바구니에서 담거나 지도에 핀을 찍어 보세요"),
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
                    // **스크롤과 무관하게 늘 보여야 한다.** iOS `RouteEditorTrip.bottomBar` 와 짝 — 정지점이 많은 일차에서는 스크롤해야만
                    // 나오던 "코스 시작"·저장 버튼이었다(실기 비교로 발견, 2026-09-28). 목록 맨 아래 항목이 아니라 목록 옆의 고정 줄이다.
                    // 안내 중에는 이 줄이 **안내의 것**이다 — 도착함 / 다음으로 / 안내 끝. 아니면 계획의 것이다 — 코스 만들기 / 코스 시작 /
                    // N번으로 길찾기. **저장된 코스에는 저장 단추가 없다**(MZ2AZ-368) — 저장은 머리줄 하나다.
                    // 「코스 시작」은 **지금 보는 일차**로 시작한다(`dayNo = dayIndex + 1`, 그 일차의 첫 미방문 곳).
                    // 남은 곳이 숨김·폐업뿐이면 「코스를 모두 돌았다」가 아니다 — 완료와 구별해 알리고 시작하지 않는다(MZ2AZ-458).
                    val nextStop = RouteAvailability.nextUnvisited(stops)
                    val onlyUnavailable = RouteAvailability.hasOnlyUnavailableRemaining(stops)
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(IOS.systemBackground)
                                // 제스처 막대와 겹쳤다 — iOS 는 안전 영역만큼 띄운다.
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (trip.isActive) {
                            TripControls(
                                arrived = trip.phase == TripSession.Phase.ARRIVED,
                                stamped = trip.stamped,
                                next = nextStop,
                                onlyUnavailable = onlyUnavailable,
                                nextBlocked =
                                    visitStatus(
                                        trip.target,
                                    ).let { it == VisitStatus.PENDING || it == VisitStatus.FAILED } || visitBlocked(),
                                onEnd = trip::end,
                                onNext = { next -> startTrip(next) },
                                onArriveNow = trip::markArrived,
                            )
                        } else {
                            TripPlanControls(
                                isNew = course.serverId == null,
                                running = course.isRunning,
                                saving = saving,
                                canSave = course.canSave,
                                stopsEmpty = stops.isEmpty(),
                                onlyUnavailable = onlyUnavailable,
                                next = nextStop,
                                startBlocked = visitBlocked(),
                                onCreate = { saveAndClose() },
                                onToggleRunning = {
                                    val turningOn = !course.isRunning
                                    val dayNo = dayIndex + 1
                                    // 시작할 목적지를 먼저 확인한다 — 없으면 화면·서버의 활성 상태를 바꾸지 않는다.
                                    val first = if (turningOn) RouteAvailability.start(course, dayIndex)?.second else null
                                    if (!turningOn || first != null) {
                                        if (!turningOn) trip.end()
                                        // **서버가 「여행 중」을 안 뒤에 길을 묻는다**(iOS 2026-09-17). 상태 요청을 던져 놓기만 하고 곧바로
                                        // 길찾기를 부르면, 길찾기가 먼저 닿을 때 서버가 아직 시작 전 코스로 보고 거절한다.
                                        scope.launch {
                                            store.setRunning(course, turningOn, dayNo = dayNo)
                                            course = course.copy(isRunning = turningOn)
                                            if (first != null) startTrip(first)
                                        }
                                    }
                                },
                                onStartTrip = { target -> startTrip(target) },
                            )
                        }
                    }
                }
            }
            RouteGuideFloatingChip(
                hidden = guideEntry != RouteGuideEntry.FLOATING,
                bubble = guideSession.picked == null && pickedStop == null && focusedStopId == null,
                cardTop =
                    if ((guideSession.picked != null || pickedStop != null) && !showGuide) CARD_BOTTOM_PADDING + cardHeight else null,
                onTap = { showGuide = true },
                // iOS `guideFloatingChip`: 편집 화면 전체 위, 오른쪽 12 · 아래(안전 영역 위) 76 — 목록 패널 위에 뜬다.
                // 지도 쪽(패널 바로 위)에 두었더니 iOS 와 자리가 달랐다(2026-09-29 대조).
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 76.dp),
            )
        }
    }

    // 길 안내는 요청마다 위치기반서비스 동의를 확인한다. 취소하면 안내 띠에 동의 안내와 「다시 시도」 가 남는다.
    // 성공하면 사용자 요청 하나에 한 번 「카카오에 현재 위치를 보냈어요」 를 알린다(MZ2AZ-472).
    LocationConsentSheetHost(trip.directionsConsent.gate, onCancel = trip::cancelLocationRequest)
    KakaoDirectionsNoticeBanner(
        trip.directionsConsent.notice,
        modifier = Modifier.fillMaxSize().then(if (inSheet) Modifier else Modifier.statusBarsPadding()).padding(top = 54.dp),
    )

    // 가이드의 「주변」 기준: 기기 위치(지도의 파란 점)를 먼저 쓰고, 없거나 사용자가 바꾸면 보이는 지도 중심을 쓴다.
    // 지도 중심의 출처는 보이는 범위가 GPS 에서 왔는지에 따른다 — 기기 위치로 맞춘 지도는 기기 출처다(MZ2AZ-472).
    val guideMapCenter = viewport?.let { it.lat to it.lng }
    val guideMapSource = viewport?.source ?: CoordinateSource.USER_SELECTED
    val guideAnchor =
        RouteGuideAnchor.resolve(
            current = myLocation?.let { it.latitude to it.longitude },
            mapCenter = guideMapCenter,
            preferMap = guideUsesMapCenter,
            mapSource = guideMapSource,
        )
    RouteGuidePanel(
        isOpen = showGuide,
        session = guideSession,
        here = guideAnchor?.let { it.latitude to it.longitude },
        // 기기 위치 기준일 때만 질문 전에 위치 동의를 묻는다. 지도 중심(사용자가 고른 영역)은 AI 동의만 필요하다(MZ2AZ-472).
        source = guideAnchor?.source ?: CoordinateSource.USER_SELECTED,
        anchorDescription =
            if (guideAnchor?.isMapCenter == true) {
                tr("현재 지도 중심 주변을 찾아 드립니다.")
            } else {
                tr("지도에 표시된 내 위치 주변을 찾아 드립니다.")
            },
        anchorActionTitle = if (guideAnchor?.isMapCenter == true) tr("내 위치 주변") else tr("지도 중심 주변"),
        onChangeAnchor = if (guideMapCenter != null && myLocation != null) ({ guideUsesMapCenter = !guideUsesMapCenter }) else null,
        cartPlaces = cart.items.map { it.name },
        onOpenCart = {
            showGuide = false
            showCart = true
        },
        // AI 의 기준과 별개로 누른 시점의 지도 중심을 고정한다(iOS `openNearbyPlaces`).
        onOpenNearby =
            guideMapCenter?.let { center ->
                { nearbyAnchor = RouteGuideAnchor(center.first, center.second, isMapCenter = true, source = guideMapSource) }
            },
        onOpenPlanner = { leaveForPlanner() },
        onAdd = { place ->
            addStops(listOf(place.courseStop()))
        },
        isAdded = { place -> stops.any { place.matches(it) } },
        onClose = { showGuide = false },
        onRemove = { place ->
            updateDay(dayIndex) { d ->
                d.copy(stops = d.stops.filterNot { place.matches(it) })
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
                added = stops.any { pickedPlace.matches(it) },
                onAdd = {
                    addStops(
                        listOf(pickedPlace.courseStop()),
                    )
                    guideSession.dismiss()
                },
                onRemove = {
                    updateDay(dayIndex) { d ->
                        d.copy(stops = d.stops.filterNot { pickedPlace.matches(it) })
                    }
                },
                onClose = { guideSession.dismiss() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = CARD_BOTTOM_PADDING)
                        .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
                // 코스의 이 행이 목록에서 빠졌거나 폐업했다면 안내를 붙이고 새로 넣는 단추를 숨긴다.
                unavailable = stops.firstOrNull { pickedPlace.matches(it) }?.unavailable,
            )
        }
    }

    // 번호 핀을 눌렀을 때 — 장면 설명 + (여행 중이면) 여기로 길찾기. 편의시설·AI 점의 카드가 떠 있으면 그것이 먼저다(카드는 한 장만).
    val stopCard = pickedStop
    if (stopCard != null && !showGuide && guideSession.picked == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            RouteCourseStopCard(
                stop = stopCard,
                onReroute =
                    if (course.isRunning && stopCard.canNavigate && trip.target?.id != stopCard.id) ({ startTrip(stopCard) }) else null,
                onClose = {
                    pickedStop = null
                    // 핀을 눌러 확대해 들어왔다면 카드를 닫을 때 포커스를 풀고 일차 전체로 다시 맞춘다(iOS #326).
                    if (focusedStopId == stopCard.id) {
                        focusedStopId = null
                        fitToken += 1
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = CARD_BOTTOM_PADDING)
                        .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
            )
        }
    }

    // 도착 스탬프 — 화면 가운데 발바닥이 쾅. 연출이 끝나도 **다음으로 넘어가지 않는다.**
    val stampTarget = trip.target
    if (trip.stamped && stampTarget != null) {
        PawStampOverlay(
            title = stampTitle(stops.indexOfFirst { it.id == stampTarget.id }.takeIf { it >= 0 }?.plus(1)),
            subtitle = stampTarget.label().title,
            onDone = trip::stampDone,
        )
    }

    if (searching) {
        PlaceSearchOverlay(
            taken = takenIds,
            onDismiss = {
                searching = false
                previewPlaces = emptyList()
            },
            onPreview = { previewPlaces = it },
            onAdd = { places -> addStops(places.map { RouteStop(place = it) }) },
        )
    }

    nearbyAnchor?.let { anchor ->
        RouteNearbySheet(
            mapAnchor = anchor,
            deviceLocation = myLocation?.let { it.latitude to it.longitude },
            taken = takenIds,
            onAdd = { place -> addStops(listOf(RouteStop(place = place))) },
            onDismiss = { nearbyAnchor = null },
        )
    }

    if (showCart) {
        RouteCartSheet(
            cart = cart,
            store = store,
            taken = takenIds,
            onPreview = { previewPlaces = it },
            onPick = { places -> addStops(places.map { RouteStop(place = it) }) },
            onDismiss = {
                showCart = false
                previewPlaces = emptyList()
            },
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

    if (showDayStay) {
        RouteDayStaySheet(
            stops = stops,
            estimatedTotalMinutes =
                course.days
                    .getOrNull(dayIndex)
                    ?.estimate
                    ?.total(stops),
            onPick = { target, minutes ->
                updateDay(dayIndex) { d -> d.copy(stops = d.stops.map { if (it.id == target.id) it.copy(stayMinutes = minutes) else it }) }
            },
            onDismiss = { showDayStay = false },
        )
    }

    if (blockedDayRemoval) {
        IOSAlert(
            title = tr("일차를 뺄 수 없습니다"),
            message = tr("%d일차에 담은 장소를 먼저 빼 주세요").format(course.days.size),
            actions = listOf(IOSAction(tr("확인"), IOSRole.CANCEL) {}),
            onDismiss = { blockedDayRemoval = false },
        )
    }

    if (discarding) {
        IOSAlert(
            title = tr("바꾼 내용을 버릴까요?"),
            message = null,
            // 계속 편집하면 마법사를 열어 달라던 표시도 거둔다 — 남기면 다음 닫기에 엉뚱하게 마법사가 뜬다.
            actions =
                listOf(
                    IOSAction(tr("계속 편집"), IOSRole.CANCEL) { plannerExit.keepEditing() },
                    IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { plannerExit.discard { onClose(null) } },
                ),
            onDismiss = { discarding = false },
        )
    }

    // 저장이 실패하면 이유를 말한다 — 안 그러면 단추가 안 먹는 것처럼 보여 같은
    // 단추를 계속 누르게 된다(iOS `RouteEditorView`의 "저장하지 못했습니다" alert).
    saveFailure?.let { failure ->
        IOSAlert(
            title = tr("저장하지 못했습니다"),
            message = failure.courseSaveMessage,
            actions = listOf(IOSAction(tr("확인")) {}),
            onDismiss = {
                saveFailure = null
                store.clearFailure()
            },
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
    highlight: Boolean = false,
    // iOS `action(_:symbol:)` — 아이콘이 위, 글자가 아래다(넷이 한 줄에 들어가야
    // 해서 나란히 두면 "동선 최적화" 하나가 폭 절반을 먹는다).
    // SF Symbols 외곽선 아이콘(`ui/OutlineIcons.kt`) — 크기 Modifier 를 받아 그린다.
    icon: (@Composable (Modifier) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val glow =
        if (highlight) {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "optimize-nudge").animateFloat(
                initialValue = 0.55f,
                targetValue = 0.95f,
                animationSpec =
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core
                            .tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                label = "optimize-glow",
            )
            pulse
        } else {
            0f
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.systemGray6)
                .then(
                    if (highlight) Modifier.background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)), alpha = glow) else Modifier,
                ).clickable(onClick = onClick)
                .padding(vertical = 7.dp),
    ) {
        icon?.invoke(Modifier.size(17.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (highlight) Color.White else IOS.label,
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
    allStops: List<RouteStop>,
    keepFromId: java.util.UUID?,
) {
    // 숨김·폐업 행은 핀과 번호는 남기되 계획선에서는 건너뛴다(MZ2AZ-458, iOS `drawLine`).
    val stops = allStops.filter { it.canNavigate }
    if (map == null || stops.size < 2) return
    val density = LocalDensity.current.density
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
                    width = (4 * density).toInt()
                    outlineWidth = (1 * density).toInt()
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
    target: RouteStop?,
    density: Density,
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
    // **안내 중에는 카메라가 「나와 목적지와 길」을 본다.** iOS `RouteMapTrip.fitTrip`을
    // 옮긴 것이다 — 경로(`leg`)가 새로 오거나 목적지가 바뀔 때만 움직인다. `here`를
    // 열쇠에 넣지 않는 것이 핵심이다 — 걸을 때마다 따라가면 손으로 옮긴 화면이 계속
    // 되돌아간다(iOS 주석과 같은 이유). 실기에서 "생성 눌러도 줌이 그대로"로 보인
    // 원인 중 하나였다 — 카메라를 옮기는 효과 자체가 없었다.
    if (active && target != null) {
        val legSignature = leg?.legs.orEmpty().joinToString(",") { "${it.mode}${it.path.coordinates.size}" }
        val tripKey = "${target.id}|$legSignature"
        androidx.compose.runtime.LaunchedEffect(tripKey) {
            map.fitTrip(target, leg?.legs.orEmpty(), here, density)
        }
    }
}

/**
 * 안내 중 — 내 자리·목적지·경로선이 다 들어오게. 자리를 모르면 목적지만.
 * iOS `RouteMapTrip.fitTrip`을 그대로 옮긴 것이다.
 */
private fun NaverMap.fitTrip(
    target: RouteStop,
    legs: List<com.mz2az.scenetrip.sceneapi.client.model.RouteLeg>,
    here: Pair<Double, Double>?,
    density: Density,
) {
    // 내 자리나 받은 경로(내 위치에서 출발한 길)가 들어간 범위는 기기 위치에서 온 것이다(iOS `RouteMapTrip.fitTrip`).
    regionSource.source = if (here != null || legs.isNotEmpty()) CoordinateSource.DEVICE else CoordinateSource.USER_SELECTED
    val goal = LatLng(target.place.latitude, target.place.longitude)
    val points = mutableListOf<LatLng>()
    legs.forEach { routeLeg ->
        routeLeg.path.coordinates.forEach { coord ->
            if (coord.size >= 2) points.add(LatLng(coord[1], coord[0])) // [경도, 위도]
        }
    }
    points.add(goal)
    here?.let { (lat, lng) -> points.add(LatLng(lat, lng)) }

    if (points.size <= 1) {
        moveCamera(CameraUpdate.scrollAndZoomTo(goal, 15.0).animate(CameraAnimation.Easing))
        return
    }

    val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    val span = maxOf(bounds.northLatitude - bounds.southLatitude, bounds.eastLongitude - bounds.westLongitude)
    // **도착했을 때는 살짝만 다가간다**(iOS 주석과 같은 함정) — 나와 목적지가 십여 m
    // 거리면 범위 그대로 맞춰서 SDK 가 축척 20 m 까지 확대해 버린다.
    if (span < 0.0015) {
        val zoom = if (cameraPosition.zoom >= 16.5) cameraPosition.zoom else minOf(cameraPosition.zoom + 0.5, 16.5)
        moveCamera(CameraUpdate.scrollAndZoomTo(goal, zoom).animate(CameraAnimation.Easing, 600L))
        return
    }
    val padding = with(density) { 56.dp.roundToPx() }
    moveCamera(CameraUpdate.fitBounds(bounds, padding).animate(CameraAnimation.Easing, 400L))
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
                    modifier =
                        Modifier
                            .width(
                                IntrinsicSize.Max,
                            ).selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab) {
                                onSelect(index)
                            },
                ) {
                    Text(
                        tr("%d일차").format(index + 1),
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
            tint = if (canRemove) IOS.label else IOS.tertiaryLabel,
            modifier = Modifier.size(18.dp).clickable(enabled = canRemove, onClick = onRemoveDay),
        )
        CircleSignIcon(
            plus = true,
            tint = if (canAdd) IOS.label else IOS.tertiaryLabel,
            modifier = Modifier.size(18.dp).clickable(enabled = canAdd, onClick = onAddDay),
        )
    }
}

/**
 * 장소 검색. iOS `RouteSearchSheet`를 옮긴 것이다 — 장소 이름·**작품 이름**으로 찾는다. 서버가 찾는다(MZ2AZ-372, MZ2AZ-482):
 * [RoutePlaceSearch] 가 입력이 멎은 뒤 한 번 묻고(앞 요청은 취소), 목록 끝에서 이어 받는다. 서버 검색은 이름·별칭·설명·작품·출연진을 본다.
 * 여럿 고르고 한 번에 담는다(번호 배지로 순서를 보여 준다), 이미 담긴 곳은 흐리게 + 체크. 고르는 대로 지도에 빨간 해태로 미리 뜬다.
 */
@Composable
private fun PlaceSearchOverlayBody(
    taken: Set<Long>,
    onDismiss: () -> Unit,
    onPreview: (List<PlaceSummary>) -> Unit,
    onAdd: (List<PlaceSummary>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 검색 상태가 바뀔 때마다 다시 그린다 — `RoutePlaceSearch` 는 Compose 상태를 들지 않는다.
    var revision by remember { mutableStateOf(0) }
    val search = remember { RoutePlaceSearch(scope).also { it.onChange = { revision += 1 } } }
    check(revision >= 0)
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<List<Long>>(emptyList()) }
    // 글자마다 부르지 않는다 — 입력이 멎으면 한 번(`RoutePlaceSearch.DEBOUNCE_MS`). 처음 열 때는 인기순.
    LaunchedEffect(query) { search.search(query) }

    // id 를 장소로 되짚는다. 고른 순서를 지킨다. 검색어를 바꿔 목록에서 사라진 곳도 고른 채로 남는다.
    fun resolve(ids: List<Long>): List<PlaceSummary> = ids.mapNotNull { search.place(it) }

    // iOS `RouteSearchSheet`: 툴바(닫기 · 장소 검색 · 추가 N), `.searchable` 알약 검색창, 구분선 있는 목록,
    // 바탕은 `.regularMaterial`. 결과가 없으면 `ContentUnavailableView.search`.
    // 머리(툴바·검색창)만 재질 회색이고 목록은 흰 바탕이다(iOS 실측).
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        Column(modifier = Modifier.background(IOSSheetMaterial)) {
            IOSSheetToolbar(
                title = tr("장소 검색"),
                leading = tr("닫기"),
                onLeading = onDismiss,
                trailing = tr("추가 %d").format(picked.size),
                trailingEnabled = picked.isNotEmpty(),
                onTrailing = {
                    // **고른 순서대로** 넣는다. 결과 순서로 넣으면 3·1·2 로 골라도 목록 순서대로 들어가 의도가 사라진다.
                    onAdd(resolve(picked))
                    onDismiss()
                },
            )
            IOSSearchField(query, { query = it.take(RoutePlaceSearch.MAX_LENGTH) }, tr("장소나 작품 이름"), Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(10.dp))
        }
        when {
            search.phase == RoutePlaceSearch.Phase.FAILED -> {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    com.mz2az.scenetrip.searchtab
                        .ErrorView(failure = search.failure, onRetry = { search.search(query) })
                }
            }

            search.phase == RoutePlaceSearch.Phase.LOADING && search.results.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }

            search.phase == RoutePlaceSearch.Phase.LOADED && search.results.isEmpty() -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MagnifierIcon(IOS.secondaryLabel, Modifier.padding(bottom = 10.dp).size(51.dp))
                    Text(
                        // iOS 는 이 자리가 시스템 `ContentUnavailableView.search`라 OS 가
                        // 알아서 번역한다 — Android 는 직접 그려서 번역표에 대응이 없다.
                        tr("‘%s’에 대한 결과 없음").format(query.trim()),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = IOS.label,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        tr("철자를 확인하거나 새로운 검색을 시도하십시오."),
                        fontSize = 15.sp,
                        color = IOS.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = sheetListBottom()) {
                    itemsIndexed(search.results, key = { _, place -> place.id }) { _, place ->
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
                                        onPreview(resolve(picked))
                                    }.padding(horizontal = 16.dp, vertical = 10.dp)
                                    .alpha(if (isTaken) 0.5f else 1f),
                        ) {
                            RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(place.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                                // 어느 작품에 나왔는지가 이름보다 중요한 단서일 때가 많다.
                                place.contents.orEmpty().firstOrNull()?.title?.let {
                                    Text(it, fontSize = 12.sp, color = IOS.accent)
                                }
                                place.address?.let { Text(it, fontSize = 11.sp, color = IOS.tertiaryLabel, maxLines = 1) }
                            }
                            when {
                                // 이미 코스에 있다. 「담긴 것」과 「지금 고른 것」을 갈라 보여야 왜 못 고르는지 안다.
                                isTaken -> {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = tr("이미 담김"),
                                        tint = IOS.accent.copy(alpha = 0.45f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                // 고른 순서를 번호로 보여 준다. 여러 곳을 고를 때 어느 순서로 들어가는지 미리 알아야 한다.
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
                    // 목록 끝 — 더 있으면 이어 받고, 못 받았으면 다시 누르게 한다.
                    if (search.moreFailed) {
                        item {
                            Text(
                                tr("다시 시도"),
                                fontSize = 15.sp,
                                color = IOS.accent,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clickable { search.loadMore() }
                                        .padding(12.dp),
                            )
                        }
                    } else if (search.paging.hasMore) {
                        item(key = "more") {
                            LaunchedEffect(search.paging.offset) { search.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
private fun PlaceSearchOverlay(
    taken: Set<Long>,
    onDismiss: () -> Unit,
    onPreview: (List<PlaceSummary>) -> Unit,
    onAdd: (List<PlaceSummary>) -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        PlaceSearchOverlayBody(taken = taken, onDismiss = onDismiss, onPreview = onPreview, onAdd = onAdd)
    }
}

/** 바닥 카드가 화면 바닥에서 떠 있는 거리. iOS `.padding(.bottom, 90)`. */
private val CARD_BOTTOM_PADDING = 90.dp
