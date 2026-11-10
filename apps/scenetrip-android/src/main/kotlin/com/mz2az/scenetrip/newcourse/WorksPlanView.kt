package com.mz2az.scenetrip.newcourse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.AIConsentStore
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.UnavailableItem
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteEditorDraft
import com.mz2az.scenetrip.routetab.RouteEditorView
import com.mz2az.scenetrip.routetab.RouteFormat
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.routetab.RouteSpan
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.searchtab.rememberLocate
import com.mz2az.scenetrip.ui.AIConsentSheetHost
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.LikeFailureNotice
import com.mz2az.scenetrip.ui.LocationConsentSheetHost
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.StepTransition
import com.mz2az.scenetrip.ui.XmarkIcon
import com.mz2az.scenetrip.ui.pressClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/**
 * 작품으로(AI) — 「어떤 여행이에요?」 → 「누구의 장면을 따라갈까요?」 → 짜는 중 → 저장 전 초안 편집
 * (MZ2AZ-547 A1–A3, 웹 `proto/ProtoPlanFlows.tsx` `ProtoAIPlan`·`ProtoWizardSteps.tsx` `ProtoWorkStep`, iOS `WorksPlanView`).
 *
 * 일정은 서버 창구가 짠다(`POST /guide/plan`, [RouteStore.guideDraft]). 응답은 초안이고 **저장하지 않는다** — 편집 화면(isNew)에서
 * 「만들기」 를 눌러야 서버에 들어간다.
 */
@Composable
fun WorksPlanView(
    routes: RouteStore,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 확인용 뒷문 — `worksStep=works` 면 작품 고르기(A2)부터(프로세스에 한 번).
    var worksStep by remember {
        val jump = NewCourseDebug.worksStep == "works"
        NewCourseDebug.worksStep = null
        mutableStateOf(jump)
    }
    var span by remember { mutableStateOf(RouteSpan.ONE_NIGHT) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var pace by remember { mutableStateOf(RoutePace.LOOSE) }
    var workIds by remember { mutableStateOf(emptySet<Long>()) }
    // 고른 작품의 기억 — 검색·쪽 넘김으로 화면에서 사라져도 제목·포스터를 쓴다(MZ2AZ-547 §1).
    var remembered by remember { mutableStateOf(emptyMap<Long, ContentSummary>()) }
    // 작품 검색칸(「작품 이름으로 찾기」) — 글, 서버에 보낸 q, 그 결과.
    var searchText by remember { mutableStateOf("") }
    val search = remember { WorksSearch() }
    var preset by remember { mutableStateOf(false) }
    var tooMany by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    // 「그만두기」 를 누르면 올라가는 번호 — 늦게 온 응답이 이미 닫은 짜는 중 화면을 열지 않게.
    var run by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<RouteEditorDraft?>(null) }
    var failed by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // 지금 어디에 있나 — 시작할 때 한 번 물어 둔다. 못 받아도 코스는 만들어진다.
    val requestLocation = rememberLocate(onLocated = { here = it.latitude to it.longitude }, onFailure = {})
    val consent = remember { AIConsentGate(AIConsentStore.getInstance(context)) }
    val locationStore = remember { LocationConsentStore.getInstance(context) }
    val locationConsent = remember { AIConsentGate(locationStore) }

    LaunchedEffect(Unit) {
        requestLocation()
        routes.loadWorks()
    }
    // 찜한 작품을 미리 골라 둔다 — 작품 목록이 온 뒤 한 번만(사람이 고친 것을 덮지 않게).
    LaunchedEffect(routes.works.size) {
        if (preset || routes.works.isEmpty()) return@LaunchedEffect
        preset = true
        val works = routes.sortedWorks
        workIds = WorkPickRules.preselect(works, works.map { it.id }.filter { routes.isFavoriteWork(it) }.toSet())
    }

    fun plan() {
        if (planning) return
        run += 1
        val mine = run
        planning = true
        val near = here.takeIf { locationStore.isAllowed }
        scope.launch {
            val result =
                routes.guideDraft(
                    workIds,
                    span,
                    date,
                    pace,
                    latitude = near?.first,
                    longitude = near?.second,
                    remembered = remembered,
                )
            if (mine != run) return@launch
            planning = false
            if (result != null) draft = RouteEditorDraft(result) else failed = true
        }
    }

    val current = draft
    if (current != null) {
        DraftEditor(routes, current, onClose)
        return
    }

    AIConsentSheetHost(consent)
    LocationConsentSheetHost(locationConsent, onCancel = { plan() })

    val liked =
        routes.works
            .map { it.id }
            .filter { routes.isFavoriteWork(it) }
            .toSet() +
            search.items.map { it.id }.filter { routes.isFavoriteWork(it) }
    val sorted = routes.sortedWorks
    // 지금 격자에 보일 것 — 검색 중이면 검색 결과, 아니면 받은 작품 전부. 둘 다 찜 먼저.
    val shown = if (search.query == null) sorted else WorksBrowseRules.ordered(search.items, liked)
    LaunchedEffect(workIds, shown) { remembered = WorksBrowseRules.rememberPicked(remembered, workIds, shown + sorted) }
    // 0.3초 멈추면 묻는다. 비우면 원래 목록으로.
    LaunchedEffect(searchText) {
        val q = WorksBrowseRules.searchQuery(searchText)
        if (q == null) {
            search.reset()
            return@LaunchedEffect
        }
        if (q == search.query) return@LaunchedEffect
        delay(WorksBrowseRules.SEARCH_DEBOUNCE_MILLIS)
        search.start(q)
        search.load(routes)
    }
    val chosen = workIds.mapNotNull { remembered[it] ?: sorted.firstOrNull { w -> w.id == it } }
    val step =
        when {
            planning -> WorksStep.PLANNING
            worksStep -> WorksStep.WORKS
            else -> WorksStep.INFO
        }
    // 뒤로 — 짜는 중이면 그만두기, 작품 고르기면 여행 정보로, 여행 정보면 닫기. 넘기는 동안 나가는 칸이 받지 않게 칸 밖에 둔다.
    BackHandler {
        when (step) {
            WorksStep.PLANNING -> {
                run += 1
                planning = false
            }

            WorksStep.WORKS -> {
                worksStep = false
            }

            WorksStep.INFO -> {
                onClose()
            }
        }
    }
    // 단계가 바뀌면 옆으로 넘긴다(§5) — 여행 정보 → 작품 → 짜는 중.
    StepTransition(
        state = step,
        order = { it.ordinal },
        modifier = Modifier.fillMaxSize().background(NewCourseStyle.grouped),
        stepNumber = { it.dots },
    ) { slot ->
        when (slot) {
            WorksStep.PLANNING -> {
                val posters =
                    (if (chosen.isEmpty()) sorted.take(3) else chosen)
                        .mapNotNull { it.posterUrl?.toString() }
                        .filter { NewCourseText.usablePhoto(it) }
                val summary =
                    listOfNotNull(
                        span.label,
                        date?.let { tr("%s 출발").format(RouteFormat.day(it)) },
                        pace.label,
                        if (chosen.isEmpty()) tr("인기 작품") else tr("작품 %d").format(chosen.size),
                    )
                PlanningScreen(posters, summary) {
                    run += 1
                    planning = false
                }
            }

            WorksStep.INFO -> {
                NewCourseScreen(
                    onBack = onClose,
                    step = 1 to 2,
                    footer = { NewCoursePrimaryButton(tr("다음")) { worksStep = true } },
                ) {
                    NewCourseTitle(tr("어떤 여행이에요?"))
                    TripScheduleGroup(span = span, onSpan = { span = it }, date = date, onDate = { date = it })
                    NewCourseGroup(label = tr("다니는 속도"), footnote = tr("방문 수는 고른 작품과 이동 거리에 따라 달라져요")) {
                        PaceRows(pace) { pace = it }
                    }
                }
            }

            WorksStep.WORKS -> {
                NewCourseScreen(
                    onBack = { worksStep = false },
                    step = 2 to 2,
                    footer = {
                        Text(
                            if (chosen.isEmpty()) {
                                tr("안 고르면 인기 작품으로 짜요")
                            } else {
                                tr("%d개 골랐어요 · 최대 %d개").format(chosen.size, WorkPickRules.MAX_WORKS)
                            },
                            fontSize = 12.sp,
                            color = IOS.secondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                        NewCoursePrimaryButton(
                            title = tr("AI로 코스 짜기"),
                            leading = { SparklesIcon(Color.White, Modifier.size(15.dp)) },
                        ) {
                            consent.request {
                                if (here != null) locationConsent.request { plan() } else plan()
                            }
                        }
                    },
                ) {
                    NewCourseTitle(tr("누구의 장면을 따라갈까요?"))
                    Text(tr("여러 개 골라도 돼요. 안 고르면 인기 작품으로 짜요"), fontSize = 14.sp, color = IOS.secondaryLabel)
                    WorksSearchField(searchText, onChange = { searchText = it })
                    val q = search.query
                    if (q != null && search.loaded && search.items.isEmpty() && !search.failed) {
                        Text(
                            WorksBrowseRules.noMatch(q),
                            fontSize = 14.sp,
                            color = IOS.secondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                    PosterGrid(
                        works = shown,
                        onNearEnd = { lastVisible ->
                            if (!WorksBrowseRules.nearEnd(lastVisible, shown.size)) return@PosterGrid
                            scope.launch { if (search.query == null) routes.loadMoreWorks() else search.load(routes) }
                        },
                        selected = workIds,
                        liked = { routes.isFavoriteWork(it) },
                        onToggle = { id ->
                            val next = WorkPickRules.toggle(workIds, id)
                            workIds = next.selected
                            tooMany = next.tooMany
                        },
                        onLike = { routes.toggleFavoriteWork(it) },
                    )
                    val loadingMore = if (search.query == null) routes.worksLoadingMore else search.loading
                    val moreFailed = if (search.query == null) routes.worksMoreFailed else search.failed && search.loaded
                    if (loadingMore || (search.query != null && !search.loaded && !search.failed)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                        }
                    } else if (moreFailed || (search.query != null && search.failed)) {
                        Text(
                            tr("작품을 더 불러오지 못했어요 · 다시 시도"),
                            fontSize = 14.sp,
                            color = IOS.accent,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { if (search.query == null) routes.loadMoreWorks() else search.load(routes) }
                                    }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    // 작품 찜이 거절되면(숨긴 작품 등) 이유를 말한다 — 검색·찜 목록과 같은 안내(MZ2AZ-458).
    LikeFailureNotice(routes.likeFailure, onClose = { routes.clearLikeFailure() })

    if (tooMany) {
        IOSAlert(
            title = tr("작품은 %d개까지 고를 수 있어요").format(WorkPickRules.MAX_WORKS),
            message = tr("하나를 빼고 다시 골라 주세요"),
            actions = listOf(IOSAction(tr("확인")) {}),
            onDismiss = { tooMany = false },
        )
    }
    if (failed) {
        IOSAlert(
            title = tr("일정을 짜지 못했어요"),
            message = routes.planFailure?.message ?: tr("잠시 후 다시 시도해 주세요."),
            actions = listOf(IOSAction(tr("확인")) {}),
            onDismiss = { failed = false },
        )
    }
}

/** 저장 전 초안 편집(isNew) — 편집 화면의 닫기(저장·버리기)가 새 코스 덮개를 닫는다. */
@Composable
internal fun DraftEditor(
    routes: RouteStore,
    draft: RouteEditorDraft,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().coverTouches().background(IOS.systemBackground)) {
        RouteEditorView(
            store = routes,
            initial = draft.initial,
            draftState = draft,
            operationScope = scope,
            isNew = true,
            onClose = { _: RouteCourse? -> onClose() },
        )
    }
}

/** 작품 검색의 상태 — 지금 q, 받은 결과, 총수. 쪽 넘김은 [WorksBrowseRules] 를 따른다. */
@Stable
private class WorksSearch {
    var query by mutableStateOf<String?>(null)
        private set
    var items by mutableStateOf<List<ContentSummary>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
        private set

    /** 첫 쪽을 받았다(결과가 0개여도). 「맞는 작품이 없어요」 는 받은 뒤에만. */
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    // 검색어가 바뀐 뒤 늦게 온 응답을 버리려는 번호.
    private var generation = 0

    fun reset() {
        generation += 1
        query = null
        items = emptyList()
        total = null
        loaded = false
        loading = false
        failed = false
    }

    fun start(q: String) {
        reset()
        query = q
    }

    /** 다음 쪽(처음이면 첫 쪽). 이미 받는 중이거나 총수에 닿았으면 묻지 않는다. */
    suspend fun load(routes: RouteStore) {
        val q = query ?: return
        if (loading) return
        val offset = WorksBrowseRules.nextOffset(items.size, total) ?: return
        val mine = generation
        loading = true
        failed = false
        val page = routes.searchWorks(q, offset)
        if (mine != generation) return
        loading = false
        if (page == null) {
            failed = true
            return
        }
        items = WorksBrowseRules.appended(items, page.items)
        total = if (page.items.isEmpty()) items.size else page.total
        loaded = true
    }
}

/** 안내 글 아래 검색칸 「작품 이름으로 찾기」 — 커뮤니티 검색칸과 같은 모양(돋보기·지우기). */
@Composable
private fun WorksSearchField(
    text: String,
    onChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(IOS.systemBackground)
                .padding(horizontal = 12.dp),
    ) {
        MagnifierIcon(IOS.secondaryLabel, Modifier.size(16.dp))
        BasicTextField(
            text,
            onChange,
            singleLine = true,
            textStyle = IOS.body.copy(fontSize = 16.sp, color = IOS.label),
            cursorBrush = SolidColor(IOS.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = tr("작품 이름으로 찾기") }
                    .testTag("works-search-field"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(tr("작품 이름으로 찾기"), fontSize = 16.sp, color = IOS.tertiaryLabel)
                    inner()
                }
            },
        )
        if (text.isNotEmpty()) {
            Box(
                Modifier.size(28.dp).semantics { contentDescription = tr("지우기") }.clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(IOS.systemGray3),
                    contentAlignment = Alignment.Center,
                ) { XmarkIcon(Color.White, Modifier.size(7.dp)) }
            }
        }
    }
}

/** 작품 포스터 3열 — 포스터를 누르면 고름(보라 테두리·✓), 왼쪽 위 하트는 찜 (A2). 하트는 포스터 단추 **밖의 형제 단추**다. */
@Composable
private fun PosterGrid(
    works: List<ContentSummary>,
    onNearEnd: (lastVisibleIndex: Int) -> Unit,
    selected: Set<Long>,
    liked: (Long) -> Boolean,
    onToggle: (Long) -> Unit,
    onLike: (Long) -> Unit,
) {
    // 바깥이 세로 스크롤이라 지연 격자 대신 줄을 직접 짠다.
    // 마지막 줄이 화면에 들어오면(바깥 스크롤에 잘리지 않은 높이가 생기면) 다음 쪽을 묻는다(MZ2AZ-547 §1).
    val rows = works.chunked(WorksBrowseRules.COLUMNS)
    val latestNearEnd by rememberUpdatedState(onNearEnd)
    var lastRowVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastRowVisible, works.size) { if (lastRowVisible) latestNearEnd(works.lastIndex) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEachIndexed { index, row ->
            val last = index == rows.lastIndex
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    if (last) {
                        Modifier.onGloballyPositioned { lastRowVisible = it.boundsInWindow().height > 0f }
                    } else {
                        Modifier
                    },
            ) {
                row.forEach { work -> Box(Modifier.weight(1f)) { PosterCell(work, work.id in selected, liked(work.id), onToggle, onLike) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PosterCell(
    work: ContentSummary,
    on: Boolean,
    heart: Boolean,
    onToggle: (Long) -> Unit,
    onLike: (Long) -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusS)
    Box {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .pressClickable { onToggle(work.id) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        selected = on
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(shape)
                        .then(if (on) Modifier.border(3.dp, NewCourseStyle.purple, shape) else Modifier),
            ) {
                NewCoursePhoto(work.posterUrl?.toString(), Modifier.fillMaxSize())
                if (on) Box(Modifier.fillMaxSize().background(Color(0x473C28A0)))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (on) NewCourseStyle.purple else Color.Black.copy(alpha = 0.35f))
                            .border(2.dp, Color.White, CircleShape),
                ) {
                    if (on) CheckmarkIcon(Color.White, Modifier.size(11.dp))
                }
                // 테두리는 사진 위에 다시 그린다 — 사진이 테두리를 덮지 않게.
                if (on) Box(Modifier.fillMaxSize().border(3.dp, NewCourseStyle.purple, shape))
            }
            Text(
                work.title,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                color = if (on) NewCourseStyle.purple else IOS.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val canToggle = UnavailableItem.canToggle(delisted = work.delisted, saved = heart)
        val label = tr("%s 찜").format(work.title)
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(44.dp)
                    .alpha(if (canToggle) 1f else 0.4f)
                    .clickable(enabled = canToggle) { onLike(work.id) }
                    .semantics {
                        contentDescription = label
                        selected = heart
                    },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
            ) {
                Icon(
                    if (heart) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (heart) IOS.systemRed else Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 작품으로 단계 — 순서대로 앞으로. [dots] 는 단계 점 번호(짜는 중은 점이 없다). */
private enum class WorksStep(
    val dots: Int?,
) {
    INFO(1),
    WORKS(2),
    PLANNING(null),
}

/** 짜는 중 줄 셋 — 서버가 단계를 알려 주지 않으므로 시각으로 넘기는 눈속임이다. 마지막 줄은 끝날 때까지 돈다. */
private val STAGES = listOf("고른 작품의 촬영지를 모으는 중", "가까운 곳끼리 묶는 중", "하루씩 동선을 잇는 중")

/** 짜는 중 화면 — 고른 포스터 부채꼴, 「코스를 짜고 있어요」 + 요약 칩, 지금 하는 일 세 줄(0 · 3.5 · 8초), [그만두기] (A3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanningScreen(
    posters: List<String>,
    summary: List<String>,
    onCancel: () -> Unit,
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - started
            delay(500)
        }
    }
    val currentStage = PlanningStages.current(elapsed)
    NewCourseScreen(
        onBack = null,
        footer = {
            val shape = RoundedCornerShape(NewCourseStyle.radiusM)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .pressClickable(onClick = onCancel)
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(shape)
                        .background(IOS.systemBackground)
                        .border(1.dp, IOS.separator, shape),
            ) {
                Text(tr("그만두기"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            }
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PosterFan(posters, Modifier.padding(top = 24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("코스를 짜고 있어요"),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = IOS.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    summary.forEach { text ->
                        Text(
                            text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NewCourseStyle.purple,
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .background(NewCourseStyle.purple.copy(alpha = 0.10f))
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                STAGES.forEachIndexed { index, stage ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.alpha(if (index > currentStage) 0.4f else 1f),
                    ) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            when {
                                index < currentStage -> {
                                    Box(
                                        Modifier.size(20.dp).clip(CircleShape).background(NewCourseStyle.purple),
                                        contentAlignment = Alignment.Center,
                                    ) { CheckmarkIcon(Color.White, Modifier.size(10.dp)) }
                                }

                                index == currentStage -> {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                                }

                                else -> {
                                    Box(Modifier.size(18.dp).border(1.5.dp, IOS.systemGray3, CircleShape))
                                }
                            }
                        }
                        Text(
                            tr(stage),
                            fontSize = 15.sp,
                            fontWeight = if (index == currentStage) FontWeight.Bold else FontWeight.Normal,
                            color = IOS.label,
                        )
                    }
                }
            }
            Text(
                tr("조금 걸릴 수 있어요. 다 되면 바로 고칠 수 있게 열어 드려요"),
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val FLOAT_MILLIS = 2_400
private const val FLOAT_STAGGER_MILLIS = 250

/** 고른 포스터(최대 3장) 부채꼴. 없으면 브랜드 바탕 한 장. */
@Composable
private fun PosterFan(
    posters: List<String>,
    modifier: Modifier,
) {
    val shown = posters.take(3)
    // 포스터가 2.4초 주기로 8dp 떠올랐다 내려온다, 장마다 0.25초 늦게 — 화면이 멈춘 것이 아니라는 표시(MZ2AZ-547 M9, 웹 `.proto-float`).
    val floating = rememberInfiniteTransition(label = "posterFloat")
    val lifts =
        shown.indices.map { index ->
            floating.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        tween(FLOAT_MILLIS, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * FLOAT_STAGGER_MILLIS),
                    ),
                label = "posterLift$index",
            )
        }
    Box(modifier = modifier.size(width = 200.dp, height = 150.dp), contentAlignment = Alignment.Center) {
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .size(
                        width = 80.dp,
                        height = 110.dp,
                    ).clip(RoundedCornerShape(NewCourseStyle.radiusM))
                    .background(NewCourseStyle.brandFill),
            )
        }
        val mid = (shown.size - 1) / 2f
        shown.forEachIndexed { index, url ->
            val shift = index - mid
            NewCoursePhoto(
                url,
                Modifier
                    .offset(x = (shift * 46).dp, y = (abs(shift) * 8).dp)
                    .graphicsLayer {
                        rotationZ = shift * 9f
                        translationY = -lifts[index].value * 8.dp.toPx()
                    }.size(width = 72.dp, height = 108.dp)
                    .shadow(8.dp, RoundedCornerShape(NewCourseStyle.radiusS))
                    .clip(RoundedCornerShape(NewCourseStyle.radiusS)),
            )
        }
    }
}
