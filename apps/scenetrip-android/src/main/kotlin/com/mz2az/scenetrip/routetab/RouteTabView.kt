package com.mz2az.scenetrip.routetab

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.ExclamationCircleIcon
import com.mz2az.scenetrip.ui.HandDrawIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.XMarkIcon
import com.mz2az.scenetrip.ui.iosLineBox
import kotlinx.coroutines.launch

/**
 * 경로여정(코스) 탭 — 첫 화면. iOS `RouteTab/RouteTabView.swift`를 옮긴 것이다.
 *
 * 코스가 없으면 "AI 로 짜기 / 직접 짜기" 갈림길이, 있으면 목록이 뜬다. 둘 다
 * [RouteWizardView]로 이어진다 — AI 쪽은 `isAiPlan = true`로 열어 `/guide/plan`을 부른다.
 */
@Composable
fun RouteTabView(
    store: RouteStore,
    onClose: (() -> Unit)? = null,
) {
    var fork by remember { mutableStateOf(false) }
    var wizardOpen by remember { mutableStateOf(false) }
    var aiWizardOpen by remember { mutableStateOf(false) }
    // 목록 요약으로 편집 화면을 열지 않는다 — 상세 조회에 성공한 코스만 `opening.editing`에 오른다.
    var openingState by remember { mutableStateOf(RouteOpeningState()) }
    val opening = remember { RouteCourseOpening { openingState = it } }
    var doomed by remember { mutableStateOf<RouteCourse?>(null) }
    val scope = rememberCoroutineScope()

    // 가이드 창의 「AI 로 짜기」 — 편집 화면(또는 마법사)이 다 닫힌 뒤에 마법사를 띄운다. 새 마법사라 열 때마다 키를 올려 답변을 비운다.
    var plannerEpoch by remember { mutableStateOf(0) }

    fun openPlannerIfAsked() {
        if (!TabRouter.pendingPlanner) return
        TabRouter.pendingPlanner = false
        plannerEpoch += 1
        aiWizardOpen = true
    }

    LaunchedEffect(AuthStore.epoch) {
        store.refresh()
    }

    // 하드웨어 뒤로 (MZ2AZ-513) — 목록에서는 iOS 의 X 와 같이 덮개를 닫는다. 편집기·마법사·시트는 자기 처리가 있고
    // **나중에 열리므로** 이 처리보다 먼저 받는다(나중에 등록한 것이 이긴다).
    if (onClose != null) BackHandler(onBack = onClose)

    // 홈에서 넘어온 코스. 목록이 아직 없으면 목록이 오는 대로 다시 시도한다(iOS `openPending`은 새로고침 뒤에 부른다).
    LaunchedEffect(TabRouter.pendingCourseId, store.courses) {
        val wanted = TabRouter.pendingCourseId ?: return@LaunchedEffect
        val found = store.courses.firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        TabRouter.pendingCourseId = null
        // 효과가 아니라 화면의 범위에서 연다 — 목록이 다시 와서 효과가 재시작돼도 열던 조회가 끊기지 않는다.
        scope.launch { opening.open(RouteBridge.course(found), store::routeCourse) }
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                tr("코스", "화면 제목"),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onClose != null) {
                    // iOS `xmark`(.body.semibold) 글리프 약 13.7pt — Material Close 16dp 는 9dp 로 작았고, 둥근 선 끝까지
                    // 치면 캔버스 15 가 맞다(18 은 16.4 로 컸다). 중심도 iOS 처럼 조금 안쪽.
                    val closeLabel = tr("닫기")
                    XMarkIcon(
                        IOS.label,
                        Modifier
                            .padding(start = 8.dp)
                            .size(15.dp)
                            .semantics { contentDescription = closeLabel }
                            .clickable(onClick = onClose),
                    )
                }
                Spacer(Modifier.weight(1f))
                // iOS `PinoNudge` — 핀 그러데이션(파랑→보라) 위 흰 글자, 그러데이션이 0.55↔0.95 로
                // 0.7초마다 숨 쉰다. 정적으로 두었더니 iOS 보다 진하고 멈춰 보였다(2차 대조).
                val glow by rememberInfiniteTransition(label = "nudge").animateFloat(
                    initialValue = 0.55f,
                    targetValue = 0.95f,
                    animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "nudge-glow",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(Brush.linearGradient(colors = listOf(IOS.pinLight, IOS.pinDeep)), alpha = glow)
                            .clickable { fork = true }
                            .padding(horizontal = 10.dp)
                            .iosLineBox(26.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    // iOS 알약 높이 26pt(112-sbs 실측). 글꼴 제 높이로는 28.7dp 였다.
                    Text(tr("코스 추가"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }

        if (openingState.failedCourse != null) {
            DetailLoadError(onRetry = { scope.launch { opening.retry(store::routeCourse) } })
        }

        // 「둘러보기」(코스마켓) 세그먼트가 있었다 — 쓰지 않는 기능이라 걷어냈다
        // (2026-10-03 팀 회의, MZ2AZ-350). 이 화면은 내 코스 하나다.
        val phase = CourseListPhase.of(store.courses.isNotEmpty(), store.courseList, store.loading)
        if (phase == CourseListPhase.LOADING) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
            }
        } else if (phase == CourseListPhase.FAILED) {
            DetailLoadError(onRetry = { scope.launch { store.refresh() } })
        } else if (phase == CourseListPhase.EMPTY) {
            EmptyState(onAI = { aiWizardOpen = true }, onManual = { wizardOpen = true })
        } else {
            CourseList(
                store = store,
                // 목록 카드에는 일차 속이 없다 — 열 때 상세를 받아오고, 못 받으면 편집 화면을 열지 않는다.
                onOpen = { course ->
                    if (!openingState.isLoading) scope.launch { opening.open(RouteBridge.course(course), store::routeCourse) }
                },
                onDelete = { course -> doomed = RouteBridge.course(course) },
                confirmingId = doomed?.serverId,
                // 팝오버 단추는 onDismiss(= onCancelDelete 가 doomed 를 비운다)를 먼저 부르고 그 다음 동작을 부른다.
                // 그래서 여기서 doomed 를 읽으면 늘 null 이라 삭제 요청이 나가지 않았다 — 줄이 들고 있던 코스를 받는다.
                onConfirmDelete = { course ->
                    doomed = null
                    scope.launch { store.delete(RouteBridge.course(course)) }
                },
                onCancelDelete = { doomed = null },
            )
        }
    }

    if (fork) {
        ForkSheet(
            onDismiss = { fork = false },
            onAI = {
                fork = false
                aiWizardOpen = true
            },
            onManual = {
                fork = false
                wizardOpen = true
            },
        )
    }

    if (aiWizardOpen) {
        key(plannerEpoch) {
            RouteWizardView(
                store = store,
                isAiPlan = true,
                // 저장하면 편집기가 닫히고 목록으로 돌아온다 — iOS `saveAndClose` 는 `dismiss()` 만 부른다(마법사는 콜백이 없다).
                onClose = {
                    aiWizardOpen = false
                    openPlannerIfAsked()
                },
            )
        }
    }

    if (wizardOpen) {
        RouteWizardView(
            store = store,
            onClose = {
                wizardOpen = false
                openPlannerIfAsked()
            },
        )
    }

    val current = openingState.editing
    if (current != null) {
        RouteEditorView(
            store = store,
            initial = current,
            onClose = {
                opening.close()
                openPlannerIfAsked()
            },
        )
    }
}

/**
 * 상세 조회가 실패했다 — 편집 화면은 열지 않았다는 안내와 「다시 시도」. iOS `detailLoadError`:
 * 왼쪽 정렬 `exclamationmark.circle` 라벨(subheadline) 아래 bordered 버튼, 가로 16 · 세로 10.
 */
@Composable
private fun DetailLoadError(onRetry: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExclamationCircleIcon(IOS.label, Modifier.size(16.dp))
            Text(tr("코스를 불러오지 못했어요"), fontSize = 15.sp, color = IOS.label)
        }
        Text(
            tr("다시 시도"),
            fontSize = 15.sp,
            color = IOS.accent,
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(IOS.tertiaryFill)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyState(
    onAI: () -> Unit,
    onManual: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
            contentAlignment = Alignment.Center,
        ) {
            SparklesIcon(androidx.compose.ui.graphics.Color.White, Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("아직 만든 코스가 없습니다"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Spacer(Modifier.height(4.dp))
        Text(
            tr("보고 싶은 작품과 기간만 고르면,\n촬영지를 이어서 일차별 일정으로 짜 드립니다"),
            fontSize = 15.sp,
            color = IOS.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    // iOS `.controlSize(.large)`는 완전히 둥근 알약이다.
                    .clip(CircleShape)
                    .background(IOS.accent)
                    .clickable(onClick = onAI)
                    .padding(vertical = 14.dp),
        ) {
            SparklesIcon(androidx.compose.ui.graphics.Color.White, Modifier.size(16.dp))
            Text(
                tr("AI 로 여정 짜기"),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.height(10.dp))
        // iOS `.buttonStyle(.bordered)`의 기본 칠은 회색 바탕 + 강조색 글자다 —
        // 옅은 파란 바탕에 검정 글자였던 것을 그 조합으로 맞춘다.
        Text(
            tr("직접 짜기"),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = IOS.accent,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(IOS.tertiaryFill)
                    .clickable(onClick = onManual)
                    .padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun CourseList(
    store: RouteStore,
    onOpen: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    onDelete: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    confirmingId: Long?,
    onConfirmDelete: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    onCancelDelete: () -> Unit,
) {
    // 열려 있는(밀려 휴지통이 드러난) 줄의 id — **한 줄만** 열린다. 다른 줄을 밀면 앞 줄은 닫힌다.
    var openId by remember { mutableStateOf<Long?>(null) }
    // 확인 팝오버는 **밀었던 그 줄 아래에** 붙는다(iOS 26 `.confirmationDialog` 는 팝오버다).
    val row: @Composable (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit = { course ->
        Box {
            CourseRow(
                course = course,
                onOpen = onOpen,
                onDelete = onDelete,
                isOpen = openId == course.id,
                onClaim = { openId = CourseSwipe.claim(openId, course.id) },
                onRelease = { openId = CourseSwipe.release(openId, course.id) },
            )
            if (confirmingId == course.id) {
                IOSConfirmPopover(
                    title = tr("「%s」을 지울까요?").format(course.title),
                    message = tr("되돌릴 수 없습니다."),
                    actions = listOf(IOSAction(tr("삭제"), IOSRole.DESTRUCTIVE) { onConfirmDelete(course) }),
                    anchorX = 0.dp,
                    onDismiss = onCancelDelete,
                    below = true,
                    centered = true,
                )
            }
        }
    }
    val running = store.courses.filter { it.status == CourseStatus.active }
    val planned = store.courses.filter { it.status != CourseStatus.active }
    // iOS `.listStyle(.insetGrouped)` — 좌우 16 들여 쓴 둥근 묶음(모서리 26) 하나에 한 절의 줄이 다 들어가고, 줄 사이는 가는 선이다.
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (running.isNotEmpty()) {
            item { SectionHeader(tr("여행 중 %d").format(running.size)) }
            item { CourseGroup(running, openId, row) }
        }
        item { SectionHeader(tr("예정 %d").format(planned.size)) }
        if (planned.isEmpty()) {
            item {
                Text(
                    if (running.isEmpty()) tr("아직 만든 코스가 없습니다") else tr("모든 코스가 여행 중이에요"),
                    fontSize = 12.sp,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            item { CourseGroup(planned, openId, row) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 한 절의 줄을 둥근 묶음 하나로 — iOS `insetGrouped` 의 모서리 약 26(실측: 줄 높이 69 에서 원호 반지름 26). */
@Composable
private fun CourseGroup(
    courses: List<com.mz2az.scenetrip.sceneapi.client.model.CourseSummary>,
    openId: Long?,
    row: @Composable (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(GROUP_RADIUS))) {
        courses.forEachIndexed { index, course ->
            key(course.id) {
                row(course)
                if (CourseSwipe.showsDivider(openId, course.id, isLast = index == courses.lastIndex)) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = IOS.systemGray4,
                    )
                }
            }
        }
    }
}

/**
 * 절 제목. iOS `Section { } header: { Text("예정 N") }` — 묶음 왼쪽에서 16 더 안쪽(=화면 왼쪽에서 32)에 15 semibold 로
 * 놓인다(실측: 글자 너비 38.7pt).
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = IOS.secondaryLabel,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 11.dp),
    )
}

private val GROUP_RADIUS = 26.dp

/** 밀었을 때 드러나는 영역 — 단추 60 + 양옆 여백 10 + 10(실측: 줄 오른쪽 끝이 80 안쪽으로 물러난다). */
private val SWIPE_REVEAL = 80.dp

// iOS `RouteTabView.rowButton` + `.swipeActions`: 제목 15 semibold(AI 코스면 앞에 `sparkles`), 오른쪽 `chevron.right`,
// 날짜 12, 줄 안쪽 여백 가로 16 · 세로 14(한글 제목 줄 높이 41 + 28 = 69).
// **삭제는 밀어서** — iOS 26 은 줄을 밀면 줄이 둥근 알약으로 줄어들며 오른쪽에 **빨간 휴지통 알약(60×41)과 그 아래 「삭제」**
// 가 나온다. 그 휴지통을 눌러야 확인 팝오버(`doomed`)가 뜬다. 끝까지(절반 이상) 밀면 바로 팝오버가 뜬다(`allowsFullSwipe`).
@Composable
private fun CourseRow(
    course: com.mz2az.scenetrip.sceneapi.client.model.CourseSummary,
    onOpen: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    onDelete: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    isOpen: Boolean,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
) {
    val route = RouteBridge.course(course)
    // 밀기 모양(알약으로 줄어듦 · 휴지통 · 끝까지 밀면 바로 동작)은 코스 편집기의 장소 줄과 함께 쓴다 — [SwipeDeleteRow].
    SwipeDeleteRow(
        onDelete = { onDelete(course) },
        isOpen = isOpen,
        onClaim = onClaim,
        onRelease = onRelease,
        corner = GROUP_RADIUS,
        reveal = SWIPE_REVEAL,
        // iOS 는 밀려 단추가 드러난 줄을 눌린 회색(systemGray5, 229·229·234)으로 둔다(48b-list-swipe-settled).
        revealedColor = IOS.systemGray5,
        onTap = { onOpen(course) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (route.madeByAI) {
                        SparklesIcon(IOS.accent, Modifier.size(12.dp))
                    }
                    Text(course.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 1)
                }
                Text(route.dateLabel ?: route.spanLabel, fontSize = 12.sp, color = IOS.secondaryLabel)
            }
            ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(12.dp))
        }
    }
}

@Composable
private fun ForkSheet(
    onDismiss: () -> Unit,
    onAI: () -> Unit,
    onManual: () -> Unit,
) {
    // iOS `forkSheet`: `.presentationDetents([.height(420)])` 시트 — 손잡이(40×5)는 내용이 직접 그린다.
    // 바탕은 iOS 유리 시트가 어두운 뒤 화면을 비춰 옅은 회색(233·233·235, 61-wizard-ios 실측)이고 그 위에 흰 카드가 얹힌다.
    IOSSheet(
        detents = listOf(SheetDetent.MEDIUM),
        fixedHeight = 420.dp,
        onDismiss = onDismiss,
        containerColor =
            androidx.compose.ui.graphics
                .Color(0xFFE9E9EB),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IOS.systemGray3),
            )
            Text(tr("코스를 어떻게 만들까요?"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ForkCard(title = tr("AI 로 짜기"), caption = tr("기간·작품만 고르면 동선까지 짜 드립니다"), onClick = onAI) { SparklesIcon(IOS.accent, it) }
                ForkCard(title = tr("직접 짜기"), caption = tr("장바구니에서 하나씩 담습니다"), onClick = onManual) { HandDrawIcon(IOS.accent, it) }
            }
        }
    }
}

// iOS `RouteForkCards.card`: 흰 카드(모서리 14, 옅은 그림자 6%), 44 원 안의 아이콘(title2), 17 semibold 제목 +
// 12 설명(간격 4), 오른쪽 `chevron.right`. 「직접 짜기」 아이콘은 `hand.draw`(반짝이가 아니다).
@Composable
private fun ForkCard(
    title: String,
    caption: String,
    onClick: () -> Unit,
    icon: @Composable (Modifier) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    4.dp,
                    RoundedCornerShape(14.dp),
                    ambientColor =
                        androidx.compose.ui.graphics.Color.Black
                            .copy(alpha = 0.06f),
                    spotColor =
                        androidx.compose.ui.graphics.Color.Black
                            .copy(alpha = 0.06f),
                ).clip(RoundedCornerShape(14.dp))
                .background(IOS.systemBackground)
                .clickable(onClick = onClick)
                // iOS 카드 높이 72pt(줄 간격 84pt = 72 + 12, 61-wizard-ios 실측). 글꼴 줄 높이가 달라 패딩이 아니라 높이로 고정한다.
                .height(72.dp)
                .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(IOS.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            icon(Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(caption, fontSize = 12.sp, color = IOS.secondaryLabel)
        }
        ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(12.dp))
    }
}
