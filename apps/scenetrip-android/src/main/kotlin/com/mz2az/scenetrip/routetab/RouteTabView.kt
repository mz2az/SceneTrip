package com.mz2az.scenetrip.routetab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.newcourse.CreatedCourseBanner
import com.mz2az.scenetrip.newcourse.NewCourseSheet
import com.mz2az.scenetrip.newcourse.NewCourseStart
import com.mz2az.scenetrip.newcourse.NewCourseStyle
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.ExclamationCircleIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.PlusIcon
import com.mz2az.scenetrip.ui.TrashFillIcon
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.launch

/** 코스 목록 확인용 뒷문(MZ2AZ-547 P7, iOS `-courseList menu|pick|confirm`) — `adb … -e courseList pick`. **지우지는 않는다.** */
object CourseListDebug {
    var mode: String? = null
}

/**
 * 경로여정(코스) 화면 — 「내 여행」 코스 목록 (MZ2AZ-547 P7 L1–L4·E17, iOS `RouteTab/RouteTabView.swift`, 웹 `features/proto/ProtoCourse.tsx`).
 *
 * 머리: ✕ · 「내 여행」 · 보라 「+ 새 코스」. 「+ 새 코스」 는 **「새 여행 코스」 시트**([NewCourseSheet])를 연다 — 도시로 시작 ·
 * 작품으로(AI) · 담은 곳으로의 세 갈래(F1·F2). 고른 흐름은 홈의 덮개(`Cover.NewCourse`)로 갈아 끼워 띄운다.
 *
 * 목록: 「여행 중」·「다가오는 여행」 묶음, 줄마다 사진·이름·「N일차 · N박 N일 · N곳」·작품([CourseListRow]).
 * 지우기는 줄 오른쪽 「⋯」 → 「삭제」 → 확인. 줄을 꾹(0.2초) 누르면 여러 개 고르기 → 아래 빨간 「N개 삭제」.
 * 코스를 여는 동안(상세 받기)은 목록을 덮개와 스피너로 덮고, 실패하면 「다시 시도」 띠(E17).
 */
@Composable
fun RouteTabView(
    store: RouteStore,
    onClose: (() -> Unit)? = null,
) {
    var fork by remember { mutableStateOf(false) }
    // 목록 요약으로 편집 화면을 열지 않는다 — 상세 조회에 성공한 코스만 `opening.editing`에 오른다.
    var openingState by remember { mutableStateOf(RouteOpeningState()) }
    val opening = remember { RouteCourseOpening { openingState = it } }
    var doomed by remember { mutableStateOf<CourseSummary?>(null) }
    // 여러 개 고르기(L4) — 꺼져 있으면 `picked == null`.
    var picking by remember { mutableStateOf(CourseListPicking()) }
    var confirmingBulk by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // 「⋯」 메뉴가 떠 있는 줄의 열쇠.
    var menuKey by remember { mutableStateOf<String?>(null) }
    // 줄마다 사진·작품을 채우려고 받은 코스 상세 — 한 코스에 한 번만. 편집 화면에서 돌아오면 잊는다(고친 사진·작품이 다시 보여야 한다).
    val details = remember { mutableStateMapOf<String, RouteCourse>() }
    val asking = remember { mutableSetOf<String>() }
    // 받아 둔 상세를 잊은 횟수 — 줄의 받기 효과가 이 값으로 다시 돈다(열쇠만으로는 같은 줄이 다시 묻지 않는다).
    var detailsVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // 가이드 창의 「AI 로 짜기 열기」 — 편집 화면이 다 닫힌 뒤에 「작품으로」(AI) 흐름을 띄운다(MZ2AZ-547, iOS 와 같다).
    fun openPlannerIfAsked() {
        if (!TabRouter.pendingPlanner) return
        TabRouter.pendingPlanner = false
        TabRouter.chooseNewCourse(NewCourseStart.Works)
    }

    LaunchedEffect(AuthStore.epoch) {
        store.refresh()
    }

    // 하드웨어 뒤로 (MZ2AZ-513) — 목록에서는 iOS 의 X 와 같이 덮개를 닫는다. 편집기·마법사·시트는 자기 처리가 있고
    // **나중에 열리므로** 이 처리보다 먼저 받는다(나중에 등록한 것이 이긴다).
    if (onClose != null) BackHandler(onBack = onClose)
    // 고르는 중이면 뒤로는 고르기만 끈다(L4) — 위 처리보다 나중에 등록해 먼저 받는다.
    BackHandler(enabled = picking.isOn && !removing) { picking = picking.cancel() }

    // 홈에서 넘어온 코스. 목록이 아직 없으면 목록이 오는 대로 다시 시도한다(iOS `openPending`은 새로고침 뒤에 부른다).
    LaunchedEffect(TabRouter.pendingCourseId, store.courses) {
        val wanted = TabRouter.pendingCourseId ?: return@LaunchedEffect
        val found = store.courses.firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        TabRouter.pendingCourseId = null
        // 효과가 아니라 화면의 범위에서 연다 — 목록이 다시 와서 효과가 재시작돼도 열던 조회가 끊기지 않는다.
        scope.launch { opening.open(RouteBridge.course(found), store::routeCourse) }
    }

    // 목록이 바뀌었다(지워짐·새로 받음) — 고른 것 중 사라진 것을 뺀다.
    LaunchedEffect(store.courses) { picking = picking.keepOnly(store.courses.map(CourseListRules::key)) }

    // 확인용 뒷문 — 목록이 처음 오면 한 번만.
    LaunchedEffect(store.courses.isNotEmpty()) {
        val keys = store.courses.map(CourseListRules::key)
        val first = keys.firstOrNull() ?: return@LaunchedEffect
        when (CourseListDebug.mode) {
            "menu" -> {
                menuKey = first
            }

            "pick", "confirm" -> {
                picking = picking.hold(first).let { if (keys.size > 1) it.toggle(keys[1]) else it }
                confirmingBulk = CourseListDebug.mode == "confirm"
            }
        }
        CourseListDebug.mode = null
    }

    fun removePicked() {
        val list = CourseListRules.doomed(store.courses, picking)
        removing = true
        scope.launch {
            CourseListRules.removeOneByOne(list) { store.delete(RouteBridge.course(it)) }
            removing = false
            picking = picking.cancel()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        if (picking.isOn) {
            PickingHeader(
                picking = picking,
                keys = store.courses.map(CourseListRules::key),
                enabled = !removing,
                onCancel = { picking = picking.cancel() },
                onToggleAll = { picking = picking.toggleAll(it) },
            )
        } else {
            Header(title = CourseListText.headerTitle(picking), onClose = onClose, onNew = { fork = true })
        }

        Box(modifier = Modifier.weight(1f)) {
            val phase = CourseListPhase.of(store.courses.isNotEmpty(), store.courseList, store.loading)
            when (phase) {
                CourseListPhase.LOADING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                    }
                }

                CourseListPhase.FAILED -> {
                    DetailLoadError(onRetry = { scope.launch { store.refresh() } })
                }

                else -> {
                    val (running, upcoming) = CourseListRules.sections(store.courses)
                    val row: @Composable (CourseSummary, Boolean) -> Unit = { course, first ->
                        val key = CourseListRules.key(course)
                        LaunchedEffect(key, detailsVersion) {
                            if (details[key] != null || !asking.add(key)) return@LaunchedEffect
                            try {
                                // 상세를 못 받으면 사진·작품만 비운 채 둔다 — 목록은 요약만으로도 그려진다. 실패를 남기지 않는다.
                                val detail = store.detail(course.id) ?: return@LaunchedEffect
                                val route = RouteBridge.course(detail)
                                details[key] = route
                                store.loadWorkTitles(route.stops.take(CourseListRules.WORK_STOPS))
                            } finally {
                                asking.remove(key)
                            }
                        }
                        val stops = details[key]?.stops.orEmpty()
                        Box {
                            CourseListRow(
                                course = course,
                                first = first,
                                photo = CourseListRules.photo(course.thumbnailUrl?.toString(), stops),
                                works =
                                    CourseListRules.works(
                                        stops,
                                    ) { stop -> stop.savablePlaceId?.let { store.workTitles(it).firstOrNull() } },
                                picking = picking.isOn,
                                checked = picking.isPicked(key),
                                enabled = !openingState.isLoading && !removing,
                                menuOpen = menuKey == key,
                                onMenu = { menuKey = if (it) key else null },
                                // 목록 카드에는 일차 속이 없다 — 열 때 상세를 받아오고, 못 받으면 편집 화면을 열지 않는다.
                                onOpen = {
                                    if (!openingState.isLoading) {
                                        scope.launch {
                                            opening.open(
                                                RouteBridge.course(course),
                                                store::routeCourse,
                                            )
                                        }
                                    }
                                },
                                onHold = { picking = picking.hold(key) },
                                onToggle = { picking = picking.toggle(key) },
                                onDelete = { doomed = course },
                            )
                            if (doomed?.id == course.id) {
                                // 팝오버 단추는 onDismiss(= doomed 비우기)를 먼저 부르고 그 다음 동작을 부른다 — 줄이 든 코스를 쓴다.
                                IOSConfirmPopover(
                                    title = tr("「%s」을 지울까요?").format(course.title),
                                    message = tr("되돌릴 수 없습니다."),
                                    actions =
                                        listOf(
                                            IOSAction(tr("삭제"), IOSRole.DESTRUCTIVE) {
                                                doomed = null
                                                scope.launch { store.delete(RouteBridge.course(course)) }
                                            },
                                        ),
                                    anchorX = 0.dp,
                                    onDismiss = { doomed = null },
                                    below = true,
                                    centered = true,
                                )
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (phase == CourseListPhase.EMPTY) item { EmptyState() }
                        if (running.isNotEmpty()) item { CourseListGroup(tr("여행 중"), running, row) }
                        if (upcoming.isNotEmpty()) item { CourseListGroup(tr("다가오는 여행"), upcoming, row) }
                    }
                }
            }
            // 실패 띠는 목록 위에 뜬다(E17) — 다시 여는 중이 아닐 때만(`CourseOpeningRules.showsFailure`).
            if (CourseOpeningRules.showsFailure(openingState)) {
                DetailLoadError(onRetry = { scope.launch { opening.retry(store::routeCourse) } })
            }
        }

        if (picking.isOn) {
            DeleteBar(
                count = picking.count,
                removing = removing,
                onDelete = { confirmingBulk = true },
            )
        }
    }

    if (confirmingBulk) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IOSConfirmPopover(
                title = CourseListText.bulkConfirmTitle(picking.count),
                message = tr("되돌릴 수 없습니다."),
                actions = listOf(IOSAction(tr("삭제"), IOSRole.DESTRUCTIVE) { removePicked() }),
                anchorX = 0.dp,
                onDismiss = { confirmingBulk = false },
                centered = true,
            )
        }
    }

    // 「새 여행 코스」 시트(MZ2AZ-547 F1·F2) — 고르면 이 덮개를 그 흐름으로 갈아 끼운다.
    if (fork) {
        NewCourseSheet(
            onDismiss = { fork = false },
            onChoose = {
                fork = false
                TabRouter.chooseNewCourse(it)
            },
        )
    }

    // 코스 속을 받는 동안 목록에 멈춘 것처럼 보이지 않게 덮는다(E17, iOS `opening.isLoading` 덮개) — 머리까지 덮어 두 번 누르지 못하게.
    if (CourseOpeningRules.showsOverlay(openingState)) {
        val loadingLabel = tr("불러오는 중입니다")
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(IOS.systemGray6.copy(alpha = 0.7f))
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .semantics { contentDescription = loadingLabel },
        ) {
            CircularProgressIndicator(Modifier.size(36.dp), color = IOS.secondaryLabel, strokeWidth = 3.dp)
        }
    }

    val current = openingState.editing
    if (current != null) {
        RouteEditorView(
            store = store,
            initial = current,
            onClose = {
                opening.close()
                // 고친 사진·작품이 줄에 다시 보이게 받아 둔 상세를 잊는다.
                details.clear()
                detailsVersion++
                openPlannerIfAsked()
            },
            // 방금 만든 코스를 저장 없이 나갔다 — 이 화면의 범위에서 지운다(E16). 편집 화면이 닫혀도 끝까지 간다.
            onDiscardFresh = { doomedFresh -> scope.launch { store.delete(doomedFresh) } },
        )
        // 방금 「도시로」 만든 코스의 안내 띠(R6). 저장된 코스는 보기로, 방금 만든 코스는 편집으로 열린다(E1).
        CreatedCourseBanner()
    }
}

/** 손수 그린 머리줄 — ✕ · 「내 여행」 · 보라 「+ 새 코스」(L1). 아래 얇은 선으로 목록과 나눈다. */
@Composable
private fun Header(
    title: String,
    onClose: (() -> Unit)?,
    onNew: () -> Unit,
) {
    HeaderBar {
        if (onClose != null) {
            val closeLabel = tr("닫기")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .semantics { contentDescription = closeLabel }
                        .clickable(onClick = onClose),
            ) {
                XMarkIcon(IOS.label, Modifier.size(16.dp))
            }
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = IOS.label,
            modifier = Modifier.padding(start = if (onClose == null) 4.dp else 0.dp).semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(NewCourseStyle.purple)
                    .clickable(onClick = onNew)
                    .padding(horizontal = 14.dp),
        ) {
            PlusIcon(Color.White, Modifier.size(13.dp))
            Text(tr("새 코스"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** 고르는 중 머리 — [취소] · 「N개 선택」 · [전체 선택 / 선택 해제] (L4). */
@Composable
private fun PickingHeader(
    picking: CourseListPicking,
    keys: List<String>,
    enabled: Boolean,
    onCancel: () -> Unit,
    onToggleAll: (List<String>) -> Unit,
) {
    val all = picking.allPicked(keys)
    HeaderBar {
        HeaderTextButton(tr("취소"), enabled, onCancel)
        Spacer(Modifier.weight(1f))
        Text(
            CourseListText.headerTitle(picking),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = IOS.label,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        HeaderTextButton(CourseListText.allButton(all), enabled) { onToggleAll(keys) }
    }
}

@Composable
private fun HeaderBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(IOS.systemBackground)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            content = content,
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
    }
}

@Composable
private fun HeaderTextButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 6.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** 아래 고정 빨간 「N개 삭제」 — 하나도 안 골랐거나 지우는 중이면 흐리게. */
@Composable
private fun DeleteBar(
    count: Int,
    removing: Boolean,
    onDelete: () -> Unit,
) {
    val off = count == 0 || removing
    Column(Modifier.fillMaxWidth().background(IOS.systemBackground).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .alpha(if (off) 0.4f else 1f)
                    .clip(RoundedCornerShape(NewCourseStyle.radiusM))
                    .background(IOS.systemRed)
                    .clickable(enabled = !off, onClick = onDelete),
        ) {
            TrashFillIcon(Color.White, IOS.systemRed, Modifier.size(16.dp))
            Text(CourseListText.deleteButton(count, removing), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** 코스가 없을 때(L2) — 「새 코스」 로 첫 여행을 짜라고 말한다. 단추는 머리의 「+ 새 코스」 하나다. */
@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
    ) {
        Text(tr("아직 코스가 없어요"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        Text(tr("「새 코스」 로 첫 여행을 짜 보세요"), fontSize = 15.sp, color = IOS.secondaryLabel, textAlign = TextAlign.Center)
    }
}

/** 상세 조회가 실패했다 — 목록 위에 뜨는 띠(E17): 왼쪽 안내, 오른쪽 보라 「다시 시도」. */
@Composable
private fun DetailLoadError(onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        ExclamationCircleIcon(IOS.label, Modifier.size(16.dp))
        Text(tr("코스를 불러오지 못했어요"), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f))
        Text(
            tr("다시 시도"),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier =
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(NewCourseStyle.purple)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
