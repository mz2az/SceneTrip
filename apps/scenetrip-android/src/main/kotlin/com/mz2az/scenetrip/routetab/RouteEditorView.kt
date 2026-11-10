package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.mz2az.scenetrip.coach.CoachRules
import com.mz2az.scenetrip.coach.CoachTour
import com.mz2az.scenetrip.coach.CoachTourEffect
import com.mz2az.scenetrip.coach.coachTarget
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
import com.mz2az.scenetrip.data.haversineKm
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
import com.mz2az.scenetrip.searchtab.MyLocationPulse
import com.mz2az.scenetrip.searchtab.NaverMapCanvas
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.searchtab.centerOn
import com.mz2az.scenetrip.searchtab.fit
import com.mz2az.scenetrip.searchtab.focusOnStop
import com.mz2az.scenetrip.searchtab.hasLocationPermission
import com.mz2az.scenetrip.searchtab.regionSource
import com.mz2az.scenetrip.searchtab.rememberLocate
import com.mz2az.scenetrip.searchtab.widenToInclude
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
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.StairsIcon
import com.mz2az.scenetrip.ui.SubwayIcon
import com.mz2az.scenetrip.ui.SwapArrowsIcon
import com.mz2az.scenetrip.ui.TransitIcon
import com.mz2az.scenetrip.ui.UnavailableNoticeRow
import com.mz2az.scenetrip.ui.WalkIcon
import com.mz2az.scenetrip.ui.sfLine
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

/** 계획 화면 서랍이 단을 옮기는 시간(ms) — 웹 `BottomSheet` `SLIDE_MS`(MZ2AZ-547 M4–M6). */
private const val PLAN_SHEET_MILLIS = 320

/** 코스에 핀이 없을 때 내 자리를 담으러 범위를 넓히는 한계. 이보다 멀면 세계 줌이 된다(MZ2AZ-511). */
private const val MAX_WIDEN_KM = 300.0

/**
 * 코스 편집. iOS `RouteTab/RouteEditorView.swift`(+Controls/+Parts, 지도 중심 화면)를
 * 옮긴 것이다 — **처음 이 화면을 목록으로만 만들었다가, iOS 시뮬레이터와 나란히 대조해
 * 보고서야 지도가 주인공인 화면이라는 것을 알았다.** 검색 탭의 지도·바텀시트를 그대로
 * 재사용한다.
 *
 * 발자취(황금 점선)·데모 주행은 아직 없다. 코스를 지도로 보며 순서를 바꾸고, 동선을
 * 최적화하고, 검색·장바구니·핀 찍기로 담고, 저장·삭제·여행 시작/종료·실시간 안내
 * (길찾기·도착 판정)·챗봇(RouteGuide)·지도 편의시설 칩·지도 검색(`RouteEditorMapPoi.kt`)은 된다.
 *
 * 코스 편집 화면. **겹쳐 뜨는 것(정지점 카드·도착 스탬프·검색 시트)은 화면 전체를 덮는 형제로 올라오므로 부모가 `Box` 여야 한다.**
 * 마법사 시트처럼 부모가 `Column` 인 곳에서 열리면(저장 전 새 코스) 본문이 높이를 다 가져가 형제 `fillMaxSize` 상자가 0 높이가 되어 카드가
 * 보이지 않았다 — 어디서 열려도 같게 여기서 `Box` 로 감싼다.
 */
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
    // 방금 「도시로」 만든 코스를 저장 없이 나갔다 — 부르는 쪽이 서버에서 지운다(MZ2AZ-547 E16). 편집 화면이 닫히며 범위가 끝나도
    // 지우기는 끝까지 가야 하므로 바깥(코스 목록)의 범위에서 돈다.
    onDiscardFresh: ((RouteCourse) -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RouteEditorContent(store, initial, isNew, onClose, inSheet, draftState, operationScope, editorDismiss, onDiscardFresh)
    }
}

@Composable
private fun RouteEditorContent(
    store: RouteStore,
    initial: RouteCourse,
    isNew: Boolean,
    onClose: (RouteCourse?) -> Unit,
    inSheet: Boolean,
    draftState: RouteEditorDraft?,
    operationScope: kotlinx.coroutines.CoroutineScope?,
    editorDismiss: java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>?,
    onDiscardFresh: ((RouteCourse) -> Unit)?,
) {
    val editorDraft = draftState ?: remember { RouteEditorDraft(initial) }
    var course by editorDraft.course
    var opened by remember(initial) { mutableStateOf(RouteBridge.outgoing(initial)) }
    var discarding by editorDraft.discarding
    var dayIndex by editorDraft.dayIndex
    var map by remember { mutableStateOf<NaverMap?>(null) }
    var detent by remember { mutableStateOf(Detent.MEDIUM) }
    // 「목록만」 으로 바뀌는 중(서랍이 맨 위로 올라가는 0.32초) — 「지도」 알약·＋ 단추를 숨긴다(M5).
    var mapSwitching by remember { mutableStateOf(false) }
    // 일정 서랍을 새로 그릴 때 시작할 높이(화면 비율) — 「지도」 를 켜면 1(맨 위), 검색 결과를 닫으면 결과 서랍의 높이(M4·M8).
    var sheetEnter by remember { mutableStateOf<Float?>(null) }
    var panelHeight by remember { mutableStateOf(0.dp) }
    var focusedStopId by remember { mutableStateOf<java.util.UUID?>(null) }

    // 번호 핀·목록 행을 눌러 카드를 띄운 정지점. 카드는 한 장만 — 편의시설·AI 점의 카드(`guideSession.picked`)와 같이 뜨지 않는다(iOS `pickedStop`).
    var pickedStop by remember { mutableStateOf<RouteStop?>(null) }

    // 검색·장바구니에서 체크한, 담을까 보는 곳 — 지도에 번호 없는 보라 핀으로 뜬다(iOS `previewPlaces`, §10-1). 시트를 닫으면 비운다.
    var previewPlaces by remember { mutableStateOf<List<PlaceSummary>>(emptyList()) }

    // 도착 알림 카드의 높이 — 지도 오른쪽 위 단추가 카드 밑으로 내려가게(iOS `tripBannerHeight`).
    var arrivalHeight by remember { mutableStateOf(0.dp) }

    // 바닥 카드(편의시설·성지)의 높이 — 해태 칩이 카드 위로 비켜 서는 데 쓴다(MZ2AZ-367, iOS `guideCardTop`).
    var cardHeight by remember { mutableStateOf(0.dp) }

    // 「머무는 시간 ›」 단추의 틀(창 좌표) — 칩이 카드를 비켜 선 자리에서 이 단추를 덮지 않게 한다(iOS `stayFrame`).
    var stayFrame by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var pinning by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<RoutePin?>(null) }
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
    // 밀어서 휴지통을 드러낸 장소 줄 — 열린 줄은 하나뿐이다(iOS `List` 의 swipeActions).
    var openStopId by remember { mutableStateOf<java.util.UUID?>(null) }
    var fitToken by remember { mutableStateOf(0) }
    var optimizeNudge by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // 가이드 기준: 기기 위치가 있어도 지도 중심을 쓰는가, 그리고 「지도 주변 촬영지 보기」를 누른 시점에 고정한 지도 중심.
    var guideUsesMapCenter by remember { mutableStateOf(false) }
    var nearbyAnchor by remember { mutableStateOf<RouteGuideAnchor?>(null) }
    // 지도 위 편의시설 칩·지도 검색(MZ2AZ-547 P5 M1–M7) — 상태는 `RouteEditorMapPoi.kt`, 규칙은 `MapPoiRules.kt`·`MapSearchRules.kt`.
    // 지도 검색은 가게·장소만(`GET /pois?q`) 찾는다 — 촬영지는 찾지 않는다(2026-11-10 사용자 요청).
    val mapPoiScope = rememberCoroutineScope()
    val mapPoi =
        remember {
            val api = PoisApi(API_BASE)
            // 범위 없이 화면 가운데에서 가까운 순(§8·§10-2). 기준점이 없으면 서버 기본 이름순.
            EditorMapPoi(mapPoiScope) { q, origin, limit, offset ->
                val page =
                    apiResult {
                        CoordinateSources.with(origin?.source ?: CoordinateSource.USER_SELECTED) {
                            api.listPois(
                                lat = origin?.lat,
                                lng = origin?.lng,
                                q = q,
                                sort = if (origin == null) null else PoisApi.SortListPois.distance,
                                limit = limit,
                                offset = offset,
                            )
                        }
                    }.getOrThrow()
                MapShopPage(page.items, page.total)
            }
        }
    val viewport = mapPoi.viewport
    var aiPlacesOn by remember { mutableStateOf(true) }
    var showDraftNotes by remember { mutableStateOf(false) }

    // MZ2AZ-547 P4 — 보기/편집(E1), 방금 만든 코스(E16), 지도 켜고 끄기(E4), 장소 넣기(E8–E10), 줄 사진의 장소 상세(E6).
    // 규칙은 `RouteEditorRules.kt`, 부품은 `RouteEditorModes.kt`·`CourseAddViews.kt`.
    val isDraft = isNew || initial.serverId == null
    val debug = remember { RouteEditorDebug.take() }
    var fresh by remember { mutableStateOf(!isDraft && TabRouter.isFreshCourse(initial.serverId)) }
    var mode by remember {
        mutableStateOf(if (debug.first == "edit") RouteEditorMode.EDITING else RouteEditorRules.initialMode(isDraft, fresh))
    }
    // 「편집」 을 누른 때의 모습 — 저장된 코스의 「취소」 가 여기로 되돌린다(E3).
    var beforeEdit by remember { mutableStateOf(initial) }
    var confirmingRevert by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    // 가이드 창의 「AI 로 짜기」 로 나가는 중에 방금 만든 코스를 지울지 묻는다 — 「나가기」 면 마법사를 연다.
    var leaveForPlannerAfter by remember { mutableStateOf(false) }
    var mapToggle by remember {
        mutableStateOf(RouteMapToggle.initial(initial.isRunning).let { if (debug.second) it.open().toggle else it })
    }
    var addMenuOpen by remember { mutableStateOf(debug.third == "menu") }
    var showMapAdd by remember { mutableStateOf(debug.third == "map") }
    var showSavedAdd by remember { mutableStateOf(debug.third == "saved") }
    var detailOf by remember { mutableStateOf<PlaceSummary?>(null) }
    val editTools = RouteEditorRules.showsEditTools(mode)
    val showsBottomBar = RouteEditorRules.showsBottomBar(mode, isDraft)
    val localScope = rememberCoroutineScope()
    val scope = operationScope ?: localScope
    val context = LocalContext.current
    val cart = remember { CartStore(context) }
    // 가이드 대화는 앱에 하나뿐이고 코스의 것이다 — 같은 코스를 다시 열면 이어지고 다른 코스면 새로 시작한다(iOS `guide.bind`).
    val guideSession = remember { RouteGuideSession.shared(context) }
    LaunchedEffect(Unit) { guideSession.bind(RouteGuidePlan.conversationKey(course)) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { guideSession.contextOf = { null } } }
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

    // 편집을 마치고 보기로 — 핀 찍기·＋ 메뉴·고른 줄을 놓는다(놓기만 하면 지도는 그대로다, `RouteCameraRules.moves`).
    fun endEditing() {
        mode = RouteEditorMode.VIEWING
        pinning = false
        addMenuOpen = false
        focusedStopId = null
        pickedStop = null
        dayIndex = dayIndex.coerceAtMost((course.days.size - 1).coerceAtLeast(0))
    }

    fun startEdit() {
        beforeEdit = course
        mode = RouteEditorMode.EDITING
    }

    // 보기 중에 코스가 바뀌는 길(가이드 초안·카드의 「담기」)이 부른다 — 바뀌기 전 모습을 남기고 편집으로 넘어간다.
    // 안 넘기면 바뀐 것을 저장할 단추가 없다.
    fun beginEditIfViewing() {
        if (mode == RouteEditorMode.VIEWING) startEdit()
    }

    fun revert() {
        course = beforeEdit
        endEditing()
    }

    // 방금 만든 코스를 저장 없이 나간다 — 서버에 만들어 둔 것을 지운다(E16).
    fun leaveFresh() {
        TabRouter.clearFreshCourse()
        if (leaveForPlannerAfter) TabRouter.pendingPlanner = true
        leaveForPlannerAfter = false
        onDiscardFresh?.invoke(course)
        onClose(null)
    }

    // 「취소」·뒤로. 보기면 그냥 닫고, 편집이면 `RouteEditorRules.cancel` 대로 — 새 초안은 닫고, 저장된 코스는 편집 전으로 되돌려 보기로,
    // 방금 만든 코스는 지울지 묻는다(E3·E16). [planner] 는 가이드 창의 「AI 로 짜기」 — 화면을 떠나는 길이라 되돌리지 않고 닫는다.
    fun cancel(planner: Boolean = false) {
        if (saving) return
        if (mode == RouteEditorMode.VIEWING) {
            plannerExit.request(planner, changed = false) { onClose(null) }
            return
        }
        if (planner) {
            if (fresh && !isDraft) {
                leaveForPlannerAfter = true
                confirmingLeave = true
                return
            }
            val changedSinceOpen = if (isDraft) !RouteBridge.isBlank(course) else RouteBridge.changed(opened, course)
            if (plannerExit.request(true, changedSinceOpen) { onClose(null) }) discarding = true
            return
        }
        val changed = if (isDraft) !RouteBridge.isBlank(course) else RouteBridge.changed(RouteBridge.outgoing(beforeEdit), course)
        when (RouteEditorRules.cancel(isDraft, fresh, changed)) {
            RouteEditorRules.LeaveDecision.CLOSE -> {
                plannerExit.request(false, changed = false) { onClose(null) }
            }

            RouteEditorRules.LeaveDecision.REVERT_TO_VIEW -> {
                revert()
            }

            RouteEditorRules.LeaveDecision.CONFIRM_DISCARD_THEN_CLOSE -> {
                plannerExit.request(false, changed = true) {}
                discarding = true
            }

            RouteEditorRules.LeaveDecision.CONFIRM_DISCARD_THEN_REVERT -> {
                confirmingRevert = true
            }

            RouteEditorRules.LeaveDecision.CONFIRM_LEAVE_AND_DELETE -> {
                confirmingLeave = true
            }
        }
    }

    // 가이드 창의 「AI 로 짜기」 — 이 편집 화면을 「취소」와 같은 길로 닫고(바꾼 것이 있으면 버릴지 묻는다),
    // 다 닫히면 코스 목록이 마법사를 띄운다(`RouteTabView.openPlannerIfAsked`). iOS `leaveForPlanner`.
    fun leaveForPlanner() {
        if (saving) return
        showGuide = false
        cancel(planner = true)
    }
    // 가이드 창이 열려 있으면 뒤로가는 아무것도 안 한다 — iOS 는 접기(X) 단추로만 닫히고 편집 화면은 그 뒤에 그대로 있다(MZ2AZ-513).
    // ＋ 메뉴가 열려 있으면 그것부터 닫는다.
    androidx.activity.compose.BackHandler {
        if (addMenuOpen) {
            addMenuOpen = false
        } else if (!showGuide) {
            cancel()
        }
    }
    androidx.compose.runtime.SideEffect { editorDismiss?.set { cancel() } }
    androidx.compose.runtime.DisposableEffect(editorDismiss) { onDispose { editorDismiss?.set(null) } }

    LaunchedEffect(Unit) { cart.refresh() }
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
            // 방금 저장한 이 코스를 다시 열면 가이드 대화가 이어진다.
            saved?.let { guideSession.rekey(RouteGuidePlan.conversationKey(it)) }
            if (saved == null) {
                saveFailure = store.saveFailure
                return@launch
            }
            // 저장했다 — 더는 「방금 만든 코스」 가 아니다(E16).
            if (fresh) {
                fresh = false
                TabRouter.clearFreshCourse()
            }
            // 새 초안의 「만들기」 는 닫고, 저장된 코스의 「저장」 은 서버가 준 모습(항목 id 가 붙은)으로 보기로 돌아간다(E3).
            when (RouteEditorRules.afterSave(isDraft)) {
                RouteEditorRules.SaveOutcome.CLOSE -> {
                    onClose(saved)
                }

                RouteEditorRules.SaveOutcome.VIEW -> {
                    course = saved
                    opened = RouteBridge.outgoing(saved)
                    beforeEdit = saved
                    endEditing()
                }
            }
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
        beginEditIfViewing()
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
        if (RouteCameraRules.refits(RouteCameraRules.Change.ADD)) fitToken += 1
        optimizeNudge = course.days[dayIndex].stops.size >= 2
    }

    // 「지도에서 추가」·「보관함에서 추가」 — 고른 일차의 끝에 고른 순서대로 넣는다. 다른 일차면 그 일차로 옮겨 보여 주고 지도를 맞춘다(E9·E10·E12).
    fun addToDay(
        places: List<PlaceSummary>,
        day: Int,
    ) {
        val result = CourseAddRules.adding(places, day, course)
        if (result.added <= 0) return
        beginEditIfViewing()
        course = result.course
        dayIndex = day
        focusedStopId = null
        pickedStop = null
        if (RouteCameraRules.refits(RouteCameraRules.Change.ADD)) fitToken += 1
        optimizeNudge = course.days[day].stops.size >= 2
    }

    // 「지도」 알약(E4) — 처음 펼칠 때만 코스 전체에 맞춘다. **서랍이 자리를 잡은 뒤에**(높이가 정해진 뒤) 맞춘다 — 0 높이에서 맞추면
    // 크기 0 기준으로 계산돼 한 단계 축소된다(아래 카메라 효과의 같은 함정).
    fun fitAfterOpening() {
        if (!RouteCameraRules.refits(RouteCameraRules.Change.MAP_FIRST_OPEN)) return
        localScope.launch {
            snapshotFlow { panelHeight }.first { it > 0.dp }
            delay(320)
            fitToken += 1
        }
    }

    // 「지도」: 서랍을 맨 위에서 그린 뒤 중간 단으로 0.32초에 내려앉는다(M4). 「목록만」: 서랍이 맨 위까지 0.32초에 올라간 뒤
    // 화면 전체 목록으로 바뀌고, 그동안 「지도」 알약·＋ 단추를 숨긴다(M5). 웹 `RouteEditor` `openMap`·`closeMap`.
    fun toggleMap() {
        if (mapSwitching) return
        val wasOpen = mapToggle.isOpen
        val step = mapToggle.toggle()
        if (wasOpen) {
            mapSwitching = true
            detent = Detent.EXPANDED
            localScope.launch {
                delay(PLAN_SHEET_MILLIS.toLong())
                mapToggle = step.toggle
                pinning = false
                panelHeight = 0.dp
                mapSwitching = false
            }
        } else {
            mapToggle = step.toggle
            sheetEnter = 1f
            detent = Detent.MEDIUM
        }
        if (step.fits) fitAfterOpening()
    }

    // 줄을 눌렀다·핀 찍기를 켰다 — 접혀 있으면 펼친다(E5). 펼치는 움직임은 「지도」 와 같다.
    fun revealMap() {
        val wasOpen = mapToggle.isOpen
        val step = mapToggle.reveal()
        mapToggle = step.toggle
        if (!wasOpen) {
            sheetEnter = 1f
            detent = Detent.MEDIUM
        }
        if (step.fits) fitAfterOpening()
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
        // 지도는 아래 카메라 효과가 옮긴다 — 고른 줄이 카메라 열쇠에 들어 있다.
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
    val poisForChips = mapPoi.pois.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val drawnPlaceIds = stops.mapNotNull { it.savablePlaceId }.toSet()
    val takenPoiIds = stops.mapNotNull { it.poiId }.toSet()
    val visibleAmbientPois =
        poisForChips.filter {
            mapPoi.chips.isOn(it.categoryGroup) && it.placeId !in drawnPlaceIds && it.id !in takenPoiIds
        }
    val aiPlacesForChip = guideSession.places.filterNot { takenPlaceKeys.contains(RouteDedupe.key(it.asPlaceSummary())) }
    val visibleAiPlaces = if (aiPlacesOn) aiPlacesForChip.filterNot { place -> stops.any { place.matches(it) } } else emptyList()

    // ── 지도 편의시설 칩·지도 검색(MZ2AZ-547 P5, iOS `RouteEditorMapPoi.swift`) ──
    // 칩 줄은 편집 중이고 지도가 펼쳐져 있고 여행 안내 중이 아닐 때만(M1). 결과 지도가 떠 있으면 일정 서랍·알약·＋·해태는 비킨다.
    val showsMapPoiBar = MapPoiChips.showsBar(mode, mapToggle.isOpen, trip.isActive)
    val mapResultsOpen = showsMapPoiBar && mapPoi.results.isOpen
    val pickedPoi = guideSession.picked?.takeIf { it.source == com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource.poi }
    val pickedPoiId = pickedPoi?.id
    // 칩 아이콘·AI 핀에 없는 고른 곳(검색에서 고른 곳) — 따로 큰 갈래 아이콘을 단다(§9).
    val loosePicked =
        pickedPoi?.takeIf { picked -> visibleAmbientPois.none { it.id == picked.id } && guideSession.places.none { it.id == picked.id } }
    // 지도 검색의 핀 — 엔터 뒤 결과 서랍의 앞 100개만(§8-2, 목록은 더 길어도). 글자 치는 동안은 지도에 찍지 않는다(§7 구글처럼).
    // 정보 카드가 뜬 곳은 고른 핀만 — 100개 밖이어도 고른 핀(`loosePicked`)이 그린다.
    val searchDots =
        MapSearchRules.mapDots(
            searching = false,
            listed = emptyList(),
            results =
                if (mapResultsOpen) {
                    mapPoi.results.state
                        ?.shops
                        ?.let { MapSearchPagingRules.pinnedResults(it) }
                } else {
                    null
                },
            pickedId = pickedPoiId,
        )
    // 지도 아래를 덮는 높이 — 결과 서랍(지도 칸의 42%·78%) 또는 일정 서랍. 지도가 접혀 있으면 서랍이 없다(E4).
    val mapCover =
        when {
            mapResultsOpen -> screenHeight * (mapPoi.results.state?.drawerShare ?: 0.0).toFloat()
            mapToggle.isOpen -> panelHeight
            else -> 0.dp
        }

    // 감춘 갈래의 고른 핀은 놓는다 — 지도에 없는 것을 계속 골라 두면 카드만 남는다. AI 장소는 제 칩이 따로 놓는다.
    fun dropPickedIfHidden(group: PoiCategoryGroup) {
        val picked = guideSession.picked
        if (picked != null && picked.categoryGroup == group && guideSession.places.none { it.id == picked.id }) {
            guideSession.dismiss()
        }
    }

    fun toggleMapChip(group: PoiCategoryGroup) {
        mapPoi.chips = mapPoi.chips.toggle(group)
        if (!mapPoi.chips.isOn(group)) dropPickedIfHidden(group)
    }

    fun toggleAllMapChips() {
        val wasAll = mapPoi.chips.allOn
        mapPoi.chips = mapPoi.chips.toggleAll()
        if (wasAll) PoiCategoryGroup.entries.forEach { dropPickedIfHidden(it) }
    }

    // 검색 목록·결과의 가게를 골랐다 — 정보 카드가 뜨고 지도가 그곳으로 간다(카드의 「코스에 담기」 로 넣는다).
    // 시트·결과 서랍·카드가 덮지 않은 곳의 가운데로 옮긴다(`focusOnPicked`, `MapSearchRules.extraLift`). 카드를 닫아도 지도는 그대로다.
    fun pickSearchPlace(poi: PoiSummary) {
        mapPoi.results.update { it.markSelfMove(System.currentTimeMillis()) }
        pickedStop = null
        guideSession.pick(poi.asGuidePlace())
        map?.focusOnPicked(poi.latitude, poi.longitude, density.density)
    }

    // 처음 쪽을 받았다 — 결과가 다 보이게 맞춘다(서랍이 덮은 아래는 빼고). 더 받을 때는 지도를 움직이지 않는다(§7-4).
    LaunchedEffect(mapPoi.results.fitTick) {
        val shops = mapPoi.results.state?.shops
        val target = map
        if (mapPoi.results.fitTick == 0 || shops.isNullOrEmpty() || target == null) return@LaunchedEffect
        mapPoi.results.update { it.markSelfMove(System.currentTimeMillis()) }
        withFrameNanos {}
        target.fit(
            shops.map { it.asPlaceSummary() },
            density,
            screenHeight,
            mapCover,
            0.dp,
            com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE,
        )
    }

    // 챗봇이 장소를 찾아 오면 지도엔 그것만 남긴다(iOS `applyGuideAnswer`) — 주변 점
    // 서른 개 사이에서는 방금 추천받은 곳을 못 찾는다. 갈래는 칩으로 다시 켤 수 있다.
    LaunchedEffect(guideSession.places) {
        if (guideSession.places.isNotEmpty()) {
            mapPoi.chips = mapPoi.chips.reset()
            aiPlacesOn = true
        }
    }

    // 안내 중에는 편의시설 점을 다 끈다(2026-09-04 사용자 요청) — 경로선이 주인공인데 음식점·명소 점이 그 위를 덮었다.
    // 칩 줄(MZ2AZ-547 M1)은 처음엔 다 꺼져 있다 — 안내가 끝나도 켜지 않는다. 보기로 돌아가도 끈다(칩은 고칠 때만).
    LaunchedEffect(trip.isActive) {
        if (trip.isActive) mapPoi.reset()
    }
    LaunchedEffect(mode) {
        if (mode == RouteEditorMode.VIEWING) mapPoi.reset()
    }

    // 열쇠에 `myLocation` 자체를 두지 않는다 — 계속 받는 값이 올 때마다 화면을 다시 맞추면 손으로 옮긴 지도가 되돌아간다.
    // 맞춤에 **나를 담을 수 있는 자리를 처음 알게 된 때**(`fitsMe`)만 다시 맞춘다. 멀리 있는 자리(MZ2AZ-511)는 담지 않으므로
    // 열쇠도 바꾸지 않는다 — 코스 핀에 맞춘 화면이 늦게 온 위치 때문에 다시 움직일 까닭이 없다.
    val fitsMe = RouteGeometry.fitHere(myLocation, showingMe, stops) != null
    // 8차 EP4: 미리보기(검색 시트에서 고른 곳)도 맞춤에 든다 — iOS `RouteMapView.fit(stops, previews:)` 는 시트가 열린 채 바로 맞춘다.
    val previewKey = previewPlaces.map { it.id }
    // **카메라 열쇠**(MZ2AZ-547 E12, `RouteCameraRules.moves`) — 고른 줄과 그 밖의 것(일차·내 위치·미리보기). 서랍 높이·코스 순서는
    // 열쇠에 없다 — 끌기·빼기·최적화·서랍 높이로는 다시 맞추지 않는다. 일차 바꾸기·장소 넣기·처음 펼치기는 `fitToken` 을 올린다.
    // 고른 줄을 **놓기만** 한 것(카드 ✕·줄 다시 누르기)도 옮기지 않는다 — 사람이 확대해 둔 화면이 풀렸다(2026-11-10 사용자 지적).
    val cameraKey =
        RouteCameraKey(
            focused = focusedStopId?.toString(),
            context = "$dayIndex|$showingMe|$fitsMe|${previewKey.joinToString(",")}",
        )
    val cameraMemo = remember { CameraMemo() }
    val cameraReady = screenHeight > 0.dp && (!mapToggle.isOpen || panelHeight > 0.dp)
    LaunchedEffect(map, cameraKey, fitToken, cameraReady) {
        val target = map ?: return@LaunchedEffect
        if (!cameraReady) return@LaunchedEffect
        delay(MAP_FIT_DEBOUNCE_MILLIS) // 시트 드래그 중 반복 맞추기를 취소하고 마지막 보이는 영역에 맞춘다.
        // `map`이 막 생겼을 때는 `NaverMapCanvas`의 `AndroidView`가 아직 배치되기 전이다
        // — 그 상태로 `fitBounds`를 부르면 크기 0 인 화면 기준으로 계산돼 한 단계 축소된
        // 값이 나온다(실기 확인, searchtab `NaverMap.kt`의 최초 진입 카메라와 같은 함정).
        // 한 프레임 기다리면 풀린다.
        withFrameNanos {}
        val moves = RouteCameraRules.moves(cameraMemo.key, cameraKey, fitChanged = fitToken != cameraMemo.fit)
        cameraMemo.key = cameraKey
        cameraMemo.fit = fitToken
        if (!moves) return@LaunchedEffect
        // 지도가 접혀 있으면(목록만) 서랍이 없다(E4).
        val sheet = if (mapToggle.isOpen) panelHeight else 0.dp
        // 「내 위치」 켜져 있으면 나와 촬영지가 같이 보이게 — 그래야 동선 최적화가
        // 왜 그 순서인지("여기서 가까운 곳이 1번") 알 수 있다(RouteEditorControls.swift).
        // 정지점을 골라 둔 채면 일차 전체가 아니라 그곳을 다시 맞춘다(iOS `RouteMapView.render` 의 `move(to: focused)`).
        val focused = stops.firstOrNull { it.id == focusedStopId }
        if (focused != null) {
            target.focusOnStop(
                focused.place,
                if (trip.isActive) trip.here else myLocation?.let { it.latitude to it.longitude },
                showingMe,
                density,
                screenHeight,
                sheet,
            )
            return@LaunchedEffect
        }
        // 같은 지역의 자리만 담는다 — 멀리 있는 자리를 넣으면 범위가 대륙만 해져 세계 줌에 멈춘다(MZ2AZ-511, `fitHere`).
        val here = RouteGeometry.fitHere(myLocation, showingMe, stops)
        val toFit = stops.map { it.place } + previewPlaces + listOfNotNull(here)
        if (toFit.isNotEmpty()) {
            target.fit(
                toFit,
                density,
                screenHeight,
                sheet,
                0.dp,
                com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE,
                // 내 위치가 들어간 맞춤은 기기 위치에서 온 범위다(iOS `RouteMapView.fit`).
                source = if (here != null) CoordinateSource.DEVICE else CoordinateSource.USER_SELECTED,
            )
        }
    }

    // 코스에 핀이 없는 첫 화면에서 자리가 오면 — 지금 보이는 범위(지도 기본 카메라)에 내가 없을 때만 범위를 넓혀 나를 담는다
    // (iOS `RouteMapLocate.didUpdateLocations`). 핀이 있으면 위의 맞춤이 같은 지역의 자리를 이미 담고, 멀리 있는 자리는 일부러
    // 담지 않으므로(MZ2AZ-511) 여기서 다시 끌어오지 않는다. 대륙을 가로지르는 자리도 세계 줌이 되므로 담지 않는다.
    LaunchedEffect(map, myLocation, showingMe, trip.isActive, stops.isEmpty()) {
        val target = map ?: return@LaunchedEffect
        val here = myLocation ?: return@LaunchedEffect
        if (!showingMe || trip.isActive || stops.isNotEmpty()) return@LaunchedEffect
        if (screenHeight <= 0.dp || panelHeight <= 0.dp) return@LaunchedEffect
        withFrameNanos {}
        val center = target.cameraPosition.target
        if (haversineKm(center.latitude, center.longitude, here.latitude, here.longitude) > MAX_WIDEN_KM) {
            return@LaunchedEffect
        }
        target.widenToInclude(here.latitude, here.longitude, density)
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

    // 칩을 켜거나 「↻ 이 지역에서 다시 찾기」 를 누를 때만 지금 범위를 서버의 「골고루」 순서로 받는다(MZ2AZ-547 M2·M3, iOS
    // `fetchMapPois`). 지도를 옮기는 것만으로는 받지 않는다 — 예전의 「멈출 때마다 · 줌 13 이상」 조회를 없앴다. 다 꺼지면 점을 비운다.
    //
    // 범위가 GPS 에서 왔으면(내 위치 포함 맞춤) 이 조회가 기기 위치를 서버로 보내는 것이므로 위치기반서비스 동의를 먼저 받는다.
    // 취소하면 조회하지 않는다. 손으로 옮긴 범위는 사용자가 고른 영역이라 묻지 않는다(MZ2AZ-472).
    val locationStore = remember { LocationConsentStore.getInstance(context) }
    val ambientGate = remember { AIConsentGate(locationStore) }
    LocationConsentSheetHost(ambientGate)
    var ambientRequest by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun fetchMapPois() {
        ambientRequest?.cancel()
        ambientGate.cancel()
        val v = mapPoi.viewport
        val request = v?.let { mapPoi.chips.request(it.zoom) }
        if (v == null || request == null) {
            mapPoi.pois = emptyList()
            mapPoi.fetch = mapPoi.fetch.clear()
            return
        }
        val chips = mapPoi.chips
        ambientGate.requestLocation(v.source) {
            mapPoi.fetch = mapPoi.fetch.begin()
            ambientRequest =
                scope.launch {
                    val found =
                        apiResult {
                            CoordinateSources.with(v.source) {
                                poisApi.listPois(
                                    bbox = v.bbox,
                                    lat = v.centerLat,
                                    lng = v.centerLng,
                                    categoryGroup = request.categoryGroup,
                                    sort = request.sort,
                                    spreadZoom = request.spreadZoom,
                                    limit = request.limit,
                                )
                            }
                        }.getOrNull()
                    mapPoi.pois = chips.keep(found?.items.orEmpty())
                    mapPoi.fetch = mapPoi.fetch.finish()
                }
        }
    }
    LaunchedEffect(mapPoi.chips.ask) { fetchMapPois() }

    // 🔍 — 화면 가운데를 기준점으로 찾으므로, 그 화면이 기기 위치에서 왔으면 위치 동의를 먼저 묻는다.
    fun openMapSearch() {
        mapPoi.searchText = ""
        ambientGate.requestLocation(mapPoi.viewport?.source ?: CoordinateSource.USER_SELECTED) { mapPoi.searching = true }
    }

    // 확인용 뒷문(`MapPoiDebug`) — 지도가 한 번 멈추고 코스에 맞춘 뒤에 칩을 켜고·검색칸·결과 지도를 연다.
    LaunchedEffect(Unit) {
        val wanted = MapPoiDebug.take()
        if (!wanted.any) return@LaunchedEffect
        repeat(50) { if (mapPoi.viewport == null) delay(100) }
        delay(2500)
        when (val poi = wanted.poi) {
            null -> Unit
            "all" -> toggleAllMapChips()
            else -> PoiCategoryGroup.entries.firstOrNull { it.value == poi }?.let { toggleMapChip(it) }
        }
        wanted.search?.let {
            openMapSearch()
            mapPoi.searchText = it
        }
        wanted.results?.let { query ->
            mapPoi.results.open(query, mapPoi.viewport)
            repeat(50) { if (mapPoi.results.state?.shops == null) delay(100) }
            mapPoi.results.state?.shops?.firstOrNull()?.let {
                delay(1500)
                pickSearchPlace(it)
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
                    beginEditIfViewing()
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

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6)) {
        // 저장된 코스를 덮개로 열면 상태줄 영역도 머리줄과 같은 흰색이다(iOS, 5차 감사 open2: 회색 242,242,247 이었다).
        if (!inSheet) {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(IOS.systemBackground))
        }
        // iOS `RouteEditorView.topBar`: 「취소」 | 가운데 제목(눌러서 고침) + 연필 | 「만들기/저장」. 큰 글자에서는 동작과 제목을 두 줄에 둔다
        // (MZ2AZ-388, `stackedTopBar`) — 한 줄에 셋을 두면 제목이 단추를 밀어낸다.
        val stackTopBar = isAccessibilityFontScale()
        val cancelAction: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 44.dp).clickable { cancel() }) {
                Text(tr("취소"), fontSize = 17.sp, color = IOS.accent)
            }
        }
        val saveAction: @Composable () -> Unit = {
            // 처음 안내(C4) 마지막 단계가 가리킨다.
            Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.coachTarget("editor-save").heightIn(min = 44.dp)) {
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
        if (mode == RouteEditorMode.VIEWING) {
            RouteViewBar(title = course.title, onBack = { cancel() }, onEdit = { startEdit() })
        } else if (stackTopBar) {
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
                        // iOS `topBar`: 단추 최소 44 + 위아래 12 = 68 (5차 감사: 52.7 로 15 낮았다).
                        .height(68.dp)
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
                // 검색 결과가 떠 있으면 결과 서랍이 일정 서랍 자리를 덮는다(M7).
                sheetHeight = mapCover,
                searchBarInset = 0.dp,
                insetMode = com.mz2az.scenetrip.searchtab.MapInsetMode.ROUTE,
                onMapReady = { map = it },
                onCameraIdle = { bounds, center, zoom, source ->
                    mapPoi.cameraIdle(
                        MapViewport(
                            south = bounds.southLatitude,
                            west = bounds.westLongitude,
                            north = bounds.northLatitude,
                            east = bounds.eastLongitude,
                            centerLat = center.latitude,
                            centerLng = center.longitude,
                            zoom = zoom,
                            source = source,
                        ),
                        System.currentTimeMillis(),
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
            // 칩으로 받은 편의시설 — 칸마다 1등만 갈래 아이콘, 나머지는 그리지 않는다(M4·§7). 확대하면 다시 받지 않고 다시 솎는다.
            // 점을 눌러도 바로 담기지 않는다 — 카드가 먼저 뜨고 「경로에 추가」 는 사람이 카드에서 누른다(iOS `RouteMapAmbient`).
            SpreadPoiPins(
                map = map,
                pois = visibleAmbientPois,
                zoom = viewport?.zoom ?: 0.0,
                pickedId = pickedPoiId,
                onTap = { poi -> guideSession.pick(poi) },
            )
            // 지도 검색 결과 핀(M7·§7·§8) — 엔터 뒤 결과의 앞 100개를 갈래 아이콘으로. 고른 곳은 큰 아이콘으로 따로(§9).
            MapSearchDots(map = map, shops = searchDots, onTap = { pickSearchPlace(it) })
            PickedPoiPin(map = map, place = loosePicked)
            AiPlacePins(
                map = map,
                places = visibleAiPlaces,
                picked = guideSession.picked,
                onTap = { place -> guideSession.pick(place) },
            )
            // 담을까 보는 곳 — 번호 없는 보라 핀(§10-1). 코스 핀보다 위에 올린다(iOS `previews`).
            PreviewPins(
                map = map,
                places = previewPlaces,
                onTap = { summary ->
                    // 검색·장바구니에서 온 미리보기가 이미 코스에 있으면 그 정지점의 카드를 띄운다(iOS `onTapPreview`).
                    stops.firstOrNull { RouteDedupe.key(it.place) == RouteDedupe.key(summary) }?.let { focusStop(it) }
                },
            )
            // 정지점 핀: 다녀온 곳=발바닥, 고른 곳·안내 중인 목적지=같은 번호 핀 1.4배(§9·§10-1), 그 밖=번호. 누른 곳으로 확대해 들어간다 —
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
            // 내 자리의 레이더 파문 — 검색 탭과 같은 세 겹(`searchtab/RadarPulse.kt`, iOS `RadarPulse`). 위치 오버레이 좌표를 읽는다.
            MyLocationPulse(
                map = map,
                requireTracking = false,
                visible = if (trip.isActive) trip.here != null else showingMe && myLocation != null,
            )
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
                            // 편집 중엔 칩 줄 아래, 보기면 맨 위(E14).
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = RouteEditorRules.locateTop(mode).dp),
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
            // 일정 — 지도를 펼치면 끌어 올렸다 내렸다 하는 서랍(지도 40 : 일정 60), 접으면 화면 전체 목록(E4). 지도는 접혀 있어도
            // 목록 밑에 제 크기로 살아 있다 — 다시 펼칠 때 사람이 맞춰 둔 확대가 남는다.
            val panel: @Composable () -> Unit = {
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
                        // 일차 ＋/− 는 고칠 때만(E2).
                        editable = editTools,
                        onSelect = {
                            dayIndex = it
                            focusedStopId = null
                            pickedStop = null
                            if (RouteCameraRules.refits(RouteCameraRules.Change.DAY_SWITCH)) fitToken += 1
                        },
                        onAddDay = {
                            if (course.days.size < RouteCourse.DAY_LIMIT.last) {
                                course = course.copy(days = course.days + RouteDay())
                                dayIndex = course.days.size - 1
                                focusedStopId = null
                                pickedStop = null
                                if (RouteCameraRules.refits(RouteCameraRules.Change.DAY_SWITCH)) fitToken += 1
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
                    // 칩 자리에는 「AI 장소」 칩만 — 편의시설 갈래 칩은 지도 맨 위(`MapPoiBar`)로 옮겼다(M5). 볼 때는 없다(E2).
                    if (editTools && aiPlacesForChip.isNotEmpty()) {
                        RoutePoiChips(
                            counts = emptyMap(),
                            groupsOn = emptySet(),
                            onToggleGroup = {},
                            onToggleAll = {},
                            aiCount = aiPlacesForChip.size,
                            aiOn = aiPlacesOn,
                            onToggleAi = {
                                aiPlacesOn = !aiPlacesOn
                                val picked = guideSession.picked
                                if (!aiPlacesOn && picked != null && guideSession.places.any { it.id == picked.id }) {
                                    guideSession.dismiss()
                                }
                            },
                            showsAll = false,
                        )
                    }
                    RouteDaySummary(course.days.getOrNull(dayIndex), onStayFrame = { stayFrame = it }) { showDayStay = true }
                    // 동작 줄 — **동선 최적화 · 핀 찍기 둘뿐이다**(E7, 웹 시안). 검색·장바구니는 떠 있는 ＋ 의 「지도에서 추가」·「보관함에서
                    // 추가」 로 옮겨 갔다. 고칠 때만 보인다(E2).
                    if (editTools) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(IOS.systemBackground)
                                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        ) {
                            EditorAction(
                                label = tr("동선 최적화"),
                                icon = { SwapArrowsIcon(if (optimizeNudge) Color.White else IOS.label, it) },
                                highlight = optimizeNudge,
                                modifier = Modifier.weight(1f),
                            ) {
                                // 현재 위치를 알고 출발이 안 정해졌으면 가장 가까운 곳이 출발이다(2026-09-04). 고정은 이번 계산에만 준다.
                                // **지도는 그대로 둔다**(E13 — 2026-09-17 의 「코스 전체에 맞춤」 을 뒤집었다, 웹 2026-11-09: 누르면 확대가 다 풀렸다).
                                var ordered = stops
                                var head = pinStart
                                if (!pinStart) {
                                    val anchor = RouteGeometry.usableAnchor(myLocation, ordered)
                                    if (anchor != null) {
                                        ordered = RouteGeometry.startingNearest(ordered, anchor)
                                        head = true
                                    }
                                }
                                updateDay(
                                    dayIndex,
                                ) { d -> d.copy(stops = RouteGeometry.optimized(ordered, pinStart = head, pinEnd = pinEnd)) }
                                if (RouteCameraRules.refits(RouteCameraRules.Change.OPTIMIZE)) fitToken += 1
                                optimizeNudge = false
                            }
                            EditorAction(
                                label = if (pinning) tr("취소") else tr("핀 찍기"),
                                icon = { MapPinEllipseIcon(IOS.label, it) },
                                iconHeight = 19.7.dp,
                                modifier = Modifier.weight(1f),
                            ) {
                                // 핀은 지도를 눌러 찍는다 — 접혀 있으면 펼친다(E5).
                                if (!pinning) revealMap()
                                pinning = !pinning
                            }
                        }
                    }
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
                                isArrivalPin = stops.size > 1 && index == stops.lastIndex,
                                // 보기에서는 고정 단추가 없다(E2).
                                pinLabel =
                                    if (!editTools || stops.size <= 1) {
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
                                    // 지도가 접혀 있으면 펼치고 그곳을 가리킨다(E5).
                                    if (!mapToggle.isOpen) {
                                        revealMap()
                                        focusStop(stop, withCard = false)
                                    } else if (focusedStopId == stop.id) {
                                        // 한 번 더 누르면 놓는다 — 놓기만 하면 지도는 그대로다(E12, `RouteCameraRules.moves`).
                                        focusedStopId = null
                                        pickedStop = null
                                    } else {
                                        focusStop(stop, withCard = false)
                                    }
                                },
                                onRemove = { updateDay(dayIndex) { d -> d.copy(stops = d.stops.filterNot { it.id == stop.id }) } },
                                isOpen = openStopId == stop.id,
                                onClaim = { openStopId = stop.id },
                                onRelease = { if (openStopId == stop.id) openStopId = null },
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
                                // 오른쪽 4:3 사진, 촬영지만 ⓘ 로 장소 상세(E6). 끌기·밀어서 빼기는 고칠 때만(E2).
                                photo =
                                    StopPhoto(
                                        url = stop.place.imageUrl?.toString(),
                                        kind = stop.photoKind,
                                        onOpen = stop.savablePlaceId?.let { id -> { detailOf = stop.place.copy(id = id) } },
                                    ),
                                movable = editTools,
                            )
                            // 목록에서 빠진 촬영지·폐업한 편의시설: 지우지 않고 이유를 알리며, 뺄지는 사용자가 정한다(MZ2AZ-458).
                            stop.unavailable?.let { notice ->
                                UnavailableNoticeRow(
                                    item = notice,
                                    // 「빼기」 는 고칠 때만(E2).
                                    onRemove =
                                        if (!editTools) {
                                            null
                                        } else {
                                            {
                                                updateDay(dayIndex) { d -> d.copy(stops = d.stops.filterNot { it.id == stop.id }) }
                                                optimizeNudge = stops.size - 1 >= 2
                                            }
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
                            // 마지막 줄 아래에도 선이 있다 — iOS 는 24 에서 시작한다(5차 감사 ed3-ios 실측: 24→385.7, 1pt).
                            IOSListDivider(start = if (index < stops.lastIndex) 175.7.dp else 24.dp, overlay = true)
                        }
                        if (stops.isEmpty()) {
                            item {
                                // iOS `RouteEditorList` — `.footnote`(13pt, 줄 18) · 가운데 놓인 덩어리 안에서 줄은 **왼쪽 맞춤** · `.padding(.vertical, 24)`
                                // 아래 목록 구분선(x 85부터). 8차 N6: 첫 줄 중심 227.3. 9차 ED4': iOS 구분선은 핸들 기준 +287.4(글 끝줄 아래 40.3) — 8차의 y≈297 은 실측이 아니었다.
                                Column(Modifier.fillMaxWidth()) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 37.2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            tr("아직 담은 장소가 없습니다\n장바구니에서 담거나 지도에 핀을 찍어 보세요"),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = IOS.secondaryLabel,
                                            textAlign = TextAlign.Start,
                                        )
                                    }
                                    IOSListDivider(start = 85.dp)
                                }
                            }
                        }
                        // 「코스 삭제」는 여기 없다 — iOS 는 코스 목록에서 밀어서만 지운다(2026-09-28 대조).
                        // 떠 있는 「지도」 알약(44 + 아래 16)에 마지막 줄이 가리지 않게 띄운다.
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
            // 결과 서랍이 떠 있는 동안 그 높이를 기억한다 — 결과를 닫으면 일정 서랍이 그 자리에서 제 단으로 움직인다(같은 0.32초).
            LaunchedEffect(mapResultsOpen, mapPoi.results.state?.tall) {
                if (mapResultsOpen) {
                    sheetEnter = (mapPoi.results.state?.drawerShare ?: MapSearchDrawerRules.LOW_FRACTION.toDouble()).toFloat()
                }
            }
            if (mapToggle.isOpen && !mapResultsOpen) {
                BottomSheet(
                    detent = detent,
                    onDetentChange = { detent = it },
                    topInset = 8.dp,
                    onHeightChange = { panelHeight = it },
                    animationSpec = tween(PLAN_SHEET_MILLIS, easing = ProtoMotion.EaseOutSheet),
                    enterFraction = sheetEnter,
                    // 지도 40 : 일정 60 — 액션 줄·"코스 시작" 단추까지 처음부터 보여야
                    // 한다(iOS `RouteEditorView`의 `mediumFraction: 0.60`, 2026-08-28
                    // 사용자 요청: "일정 쪽에 단추가 늘며 좁아졌다").
                    mediumFraction = 0.60f,
                ) { panel() }
            } else if (!mapToggle.isOpen) {
                Box(Modifier.fillMaxSize().background(IOS.systemBackground)) { panel() }
            }
            // 지도 맨 위 칩 줄·검색칸·결과 지도(M1·M6·M7). 일정 서랍 위까지 덮을 수 있게 지도와 서랍을 함께 담은 이 칸에 얹는다.
            if (showsMapPoiBar) {
                EditorMapTop(
                    state = mapPoi,
                    onSearch = { openMapSearch() },
                    onToggle = { toggleMapChip(it) },
                    onAll = { toggleAllMapChips() },
                    onPick = { pickSearchPlace(it) },
                    onCommit = { query ->
                        guideSession.dismiss()
                        mapPoi.results.open(query, mapPoi.viewport)
                    },
                )
            }
            if (!mapResultsOpen && !mapSwitching) {
                RouteMapPill(
                    toggle = mapToggle,
                    onClick = { toggleMap() },
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .then(if (showsBottomBar) Modifier else Modifier.navigationBarsPadding()),
                )
            }
        }
        // 아래 줄 — 저장된 코스를 고치는 동안은 숨긴다(E3).
        if (showsBottomBar) {
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
    // 처음 안내(MZ2AZ-547 C4·C5) — 처음 만든 코스의 지도·해태·＋·저장, 지도를 처음 펼칠 때 🔍·칩.
    CoachTourEffect(
        CoachTour.editorFirst,
        active =
            CoachRules.editorFirstActive(
                editing = mode == RouteEditorMode.EDITING,
                isNew = isNew,
                fresh = fresh,
                confirming = confirmingLeave || discarding || confirmingRevert,
            ),
    )
    CoachTourEffect(
        CoachTour.mapSearch,
        active = CoachRules.mapSearchActive(barShown = showsMapPoiBar, searching = mapPoi.searching, resultsOpen = mapPoi.results.isOpen),
    )
    // 떠 있는 ＋ — 해태 동그라미 바로 위, 편집 중에만(E8). 가이드 창·핀 찍기·여행 중에는 없다.
    if (editTools && !showGuide && !pinning && !trip.isActive && !mapResultsOpen && !mapSwitching) {
        CourseAddFab(
            open = addMenuOpen,
            onToggle = { addMenuOpen = !addMenuOpen },
            onMap = { showMapAdd = true },
            onSaved = { showSavedAdd = true },
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        RouteGuideFloatingChip(
            hidden = guideEntry != RouteGuideEntry.FLOATING || mapResultsOpen,
            bubble = guideSession.picked == null && pickedStop == null && focusedStopId == null,
            cardTop =
                if ((guideSession.picked != null || pickedStop != null) && !showGuide) CARD_BOTTOM_PADDING + cardHeight else null,
            keepClear = stayFrame,
            onTap = { showGuide = true },
            // iOS `guideFloatingChip`: 편집 화면 전체 위, 오른쪽 12 · 아래(안전 영역 위) 76 — 목록 패널 위에 뜬다.
            // 지도 쪽(패널 바로 위)에 두었더니 iOS 와 자리가 달랐다(2026-09-29 대조).
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 76.dp),
        )
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
    val guideMapCenter = viewport?.let { it.centerLat to it.centerLng }
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
            beginEditIfViewing()
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
                    beginEditIfViewing()
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
                    // 카드를 닫으면 고른 표시만 놓는다 — **지도는 그대로**(E12 `CARD_CLOSE`, 2026-11-10 사용자 지적: 확대해 둔 것이 풀렸다).
                    if (focusedStopId == stopCard.id) focusedStopId = null
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

    // 저장된 코스의 「취소」 — 편집 전으로 되돌려 보기로(E3).
    if (confirmingRevert) {
        IOSAlert(
            title = tr("바꾼 내용을 버릴까요?"),
            message = null,
            actions =
                listOf(
                    IOSAction(tr("계속 편집"), IOSRole.CANCEL) {},
                    IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { revert() },
                ),
            onDismiss = { confirmingRevert = false },
        )
    }

    // 방금 「도시로」 만든 코스를 저장 없이 나간다 — 서버에서 지운다(E16).
    if (confirmingLeave) {
        IOSAlert(
            title = tr("저장하지 않고 나갈까요?"),
            message = tr("방금 만든 코스가 지워져요."),
            actions =
                listOf(
                    IOSAction(tr("계속 편집"), IOSRole.CANCEL) { leaveForPlannerAfter = false },
                    IOSAction(tr("나가기"), IOSRole.DESTRUCTIVE) { leaveFresh() },
                ),
            onDismiss = { confirmingLeave = false },
        )
    }

    if (showSavedAdd) {
        SavedAddSheet(
            cart = cart,
            dayCounts = course.days.map { it.stops.size },
            current = dayIndex,
            taken = takenIds,
            onAdd = { places, day -> addToDay(places, day) },
            onDismiss = { showSavedAdd = false },
        )
    }

    if (showMapAdd) {
        MapAddScreen(
            dayCounts = course.days.map { it.stops.size },
            current = dayIndex,
            taken = takenIds,
            onAdd = { place, day -> addToDay(listOf(place), day) },
            onClose = { showMapAdd = false },
        )
    }

    detailOf?.let { place -> RoutePlaceDetailCover(place = place, cart = cart, onClose = { detailOf = null }) }

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
    // 「핀 찍기」 는 그림(`mappin.and.ellipse`)이 더 커서 단추가 51.7 이다(iOS 실측, 다른 셋은 49.0).
    iconHeight: androidx.compose.ui.unit.Dp = 17.dp,
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
                .padding(vertical = 8.dp),
    ) {
        if (icon != null) {
            Box(modifier = Modifier.height(iconHeight), contentAlignment = Alignment.Center) { icon(Modifier.size(17.dp)) }
        }
        Text(
            label,
            fontSize = 11.sp,
            color = if (highlight) Color.White else IOS.label,
            textAlign = TextAlign.Center,
            maxLines = 1,
            // iOS caption2 11pt → 13.1 (7차 감사 ED5: 단추 49.0).
            modifier = Modifier.sfLine(11f),
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
    editable: Boolean,
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
                        // iOS 탭 25.9 = 글 17.9 + 6 + 밑줄 2 (7차 감사 ED1·ED3: 글꼴 제 높이라 줄이 3.4 컸다).
                        modifier = Modifier.sfLine(15f),
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
        if (editable) {
            CircleSignIcon(
                plus = false,
                tint = if (canRemove) IOS.label else IOS.tertiaryLabel,
                modifier = Modifier.size(17.dp).clickable(enabled = canRemove, onClick = onRemoveDay),
            )
            CircleSignIcon(
                plus = true,
                tint = if (canAdd) IOS.label else IOS.tertiaryLabel,
                modifier = Modifier.size(17.dp).clickable(enabled = canAdd, onClick = onAddDay),
            )
        }
    }
}

/** 바닥 카드가 화면 바닥에서 떠 있는 거리. iOS `.padding(.bottom, 90)`. */
private val CARD_BOTTOM_PADDING = 90.dp

/** 카메라 효과가 마지막으로 본 열쇠·맞춤 신호. 상태가 아니다 — 바뀌어도 다시 그릴 까닭이 없다. */
private class CameraMemo {
    var key: RouteCameraKey? = null
    var fit: Int = 0
}
