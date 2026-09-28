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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.HandDrawIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.XMarkIcon
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
    startInMarket: Boolean = false,
) {
    var segment by remember { mutableStateOf(if (startInMarket) Segment.MARKET else Segment.MINE) }
    var fork by remember { mutableStateOf(false) }
    var wizardOpen by remember { mutableStateOf(false) }
    var aiWizardOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RouteCourse?>(null) }
    var doomed by remember { mutableStateOf<RouteCourse?>(null) }
    var marketLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        store.refresh()
        store.refreshMarket()
        marketLoaded = true
    }

    LaunchedEffect(TabRouter.pendingCourseId) {
        val wanted = TabRouter.pendingCourseId ?: return@LaunchedEffect
        val found = store.courses.firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        TabRouter.pendingCourseId = null
        editing = store.detail(found.id)?.let { RouteBridge.course(it) } ?: RouteBridge.course(found)
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("코스", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, modifier = Modifier.align(Alignment.Center))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onClose != null) {
                    // iOS `xmark`(.body.semibold) 글리프 약 13.7pt — Material Close 16dp 는 9dp 로 작았고, 둥근 선 끝까지
                    // 치면 캔버스 15 가 맞다(18 은 16.4 로 컸다). 중심도 iOS 처럼 조금 안쪽.
                    XMarkIcon(IOS.label, Modifier.padding(start = 8.dp).size(15.dp).clickable(onClick = onClose))
                }
                Spacer(Modifier.weight(1f))
                if (segment == Segment.MINE) {
                    // iOS `PinoNudge` — 늘 반짝이는 핀 그러데이션(파랑→보라) 배경에
                    // 흰 글자다. 이 탭의 첫 행동이라 이렇게 눈에 띄게 해 둔다. 은은한
                    // 깜빡임(0.55↔0.95 투명도)까지는 옮기지 않았다 — 정적인 그러데이션
                    // 만으로도 이전의 옅은 파란 배경보다 훨씬 도드라진다.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(Brush.linearGradient(colors = listOf(IOS.pinLight, IOS.pinDeep)))
                                .clickable { fork = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Text("코스 추가", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }

        com.mz2az.scenetrip.searchtab.SegmentedControl(
            options = Segment.entries,
            selected = segment,
            label = { it.label },
            onSelect = { segment = it },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        when (segment) {
            Segment.MINE -> {
                if (store.courses.isEmpty()) {
                    EmptyState(onAI = { aiWizardOpen = true }, onManual = { wizardOpen = true })
                } else {
                    CourseList(
                        store = store,
                        onOpen = { course ->
                            scope.launch {
                                editing = store.detail(course.id)?.let { RouteBridge.course(it) } ?: RouteBridge.course(course)
                            }
                        },
                        onDelete = { course -> doomed = RouteBridge.course(course) },
                        confirmingId = doomed?.serverId,
                        onConfirmDelete = {
                            val course = doomed
                            doomed = null
                            if (course != null) scope.launch { store.delete(course) }
                        },
                        onCancelDelete = { doomed = null },
                    )
                }
            }

            Segment.MARKET -> {
                RouteMarketView(store = store, marketLoaded = marketLoaded)
            }
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
        RouteWizardView(
            store = store,
            isAiPlan = true,
            onClose = { saved ->
                aiWizardOpen = false
                if (saved != null) editing = saved
            },
        )
    }

    if (wizardOpen) {
        RouteWizardView(
            store = store,
            onClose = { saved ->
                wizardOpen = false
                if (saved != null) editing = saved
            },
        )
    }

    val current = editing
    if (current != null) {
        RouteEditorView(
            store = store,
            initial = current,
            onClose = { editing = null },
        )
    }
}

private enum class Segment(
    val label: String,
) {
    MINE("내 코스"),
    MARKET("둘러보기"),
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
        Text("아직 만든 코스가 없습니다", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Spacer(Modifier.height(4.dp))
        Text(
            "보고 싶은 작품과 기간만 고르면,\n촬영지를 이어서 일차별 일정으로 짜 드립니다",
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
                "AI 로 여정 짜기",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.height(10.dp))
        // iOS `.buttonStyle(.bordered)`의 기본 칠은 회색 바탕 + 강조색 글자다 —
        // 옅은 파란 바탕에 검정 글자였던 것을 그 조합으로 맞춘다.
        Text(
            "직접 짜기",
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
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    // 확인 팝오버는 **밀었던 그 줄 아래에** 붙는다(iOS 26 `.confirmationDialog` 는 팝오버다).
    val row: @Composable (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit = { course ->
        CourseRow(course, onOpen, onDelete)
        if (confirmingId == course.id) {
            IOSConfirmPopover(
                title = "「${course.title}」을 지울까요?",
                message = "되돌릴 수 없습니다.",
                actions = listOf(IOSAction("삭제", IOSRole.DESTRUCTIVE, onConfirmDelete)),
                anchorX = 0.dp,
                onDismiss = onCancelDelete,
                below = true,
            )
        }
    }
    val running = store.courses.filter { it.status == CourseStatus.active }
    val planned = store.courses.filter { it.status != CourseStatus.active }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (running.isNotEmpty()) {
            item { SectionHeader("여행 중 ${running.size}") }
            items(running, key = { it.id }) { course -> Box { row(course) } }
        }
        item { SectionHeader("예정 ${planned.size}") }
        if (planned.isEmpty()) {
            item {
                Text(
                    if (running.isEmpty()) "아직 만든 코스가 없습니다" else "모든 코스가 여행 중이에요",
                    fontSize = 12.sp,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(planned, key = { it.id }) { course -> Box { row(course) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = IOS.secondaryLabel,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

// iOS `RouteTabView.rowButton` + `.swipeActions`: 제목 15 semibold(AI 코스면 앞에 `sparkles`),
// 오른쪽 `chevron.right`, 날짜 12. **삭제는 밀어서** — 휴지통을 늘 보이던 것을 iOS 처럼 숨긴다.
// 끝까지 밀면 확인 팝오버(`doomed`)를 띄우고 행은 제자리로 돌아온다.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CourseRow(
    course: com.mz2az.scenetrip.sceneapi.client.model.CourseSummary,
    onOpen: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    onDelete: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
) {
    val route = RouteBridge.course(course)
    val swipe =
        androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) onDelete(course)
                false
            },
        )
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)),
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                modifier = Modifier.fillMaxSize().background(IOS.systemRed).padding(horizontal = 18.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text("삭제", fontSize = 15.sp, color = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(IOS.systemBackground)
                    .clickable { onOpen(course) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), fixedHeight = 420.dp, onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IOS.systemGray3),
            )
            Text("코스를 어떻게 만들까요?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ForkCard(title = "AI 로 짜기", caption = "기간·작품만 고르면 동선까지 짜 드립니다", onClick = onAI) { SparklesIcon(IOS.accent, it) }
                ForkCard(title = "직접 짜기", caption = "장바구니에서 하나씩 담습니다", onClick = onManual) { HandDrawIcon(IOS.accent, it) }
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
                .padding(16.dp),
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
