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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 경로여정(코스) 탭 — 첫 화면. iOS `RouteTab/RouteTabView.swift`를 옮긴 것이다.
 *
 * 코스가 없으면 "AI 로 짜기 / 직접 짜기" 갈림길이, 있으면 목록이 뜬다. **AI 로 짜기는
 * 아직 없다** — RouteGuide(챗봇) 자체가 다음 단계라 눌러도 "준비 중"만 안내한다.
 * "직접 짜기"는 [RouteWizardView]로 이어진다.
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
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "닫기",
                        tint = IOS.label,
                        modifier = Modifier.size(16.dp).clickable(onClick = onClose),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (segment == Segment.MINE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(IOS.accent.copy(alpha = 0.12f))
                                .clickable { fork = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(13.dp))
                        Text("코스 추가", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IOS.accent)
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(IOS.systemGray5),
        ) {
            Segment.entries.forEach { each ->
                val isOn = each == segment
                Text(
                    each.label,
                    fontSize = 13.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isOn) IOS.label else IOS.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isOn) IOS.systemBackground else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { segment = each }
                            .padding(vertical = 7.dp),
                )
            }
        }

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

    val toDelete = doomed
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { doomed = null },
            title = { Text("\"${toDelete.title}\"을 지울까요?") },
            text = { Text("되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    doomed = null
                    scope.launch { store.delete(toDelete) }
                }) { Text("삭제", color = IOS.systemRed) }
            },
            dismissButton = { TextButton(onClick = { doomed = null }) { Text("취소") } },
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
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("아직 만든 코스가 없습니다", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Spacer(Modifier.height(4.dp))
        Text(
            "보고 싶은 작품과 기간만 고르면,\n촬영지를 이어서 일차별 일정으로 짜 드립니다",
            fontSize = 14.sp,
            color = IOS.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "AI 로 여정 짜기",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = androidx.compose.ui.graphics.Color.White,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IOS.accent)
                    .clickable(onClick = onAI)
                    .padding(vertical = 14.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "직접 짜기",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = IOS.label,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IOS.systemBackground)
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
) {
    val running = store.courses.filter { it.status == CourseStatus.active }
    val planned = store.courses.filter { it.status != CourseStatus.active }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (running.isNotEmpty()) {
            item { SectionHeader("여행 중 ${running.size}") }
            items(running, key = { it.id }) { course -> CourseRow(course, onOpen, onDelete) }
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
        items(planned, key = { it.id }) { course -> CourseRow(course, onOpen, onDelete) }
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

@Composable
private fun CourseRow(
    course: com.mz2az.scenetrip.sceneapi.client.model.CourseSummary,
    onOpen: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
    onDelete: (com.mz2az.scenetrip.sceneapi.client.model.CourseSummary) -> Unit,
) {
    val route = RouteBridge.course(course)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.systemBackground)
                .clickable { onOpen(course) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (route.madeByAI) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(11.dp))
                }
                Text(course.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            }
            Spacer(Modifier.height(2.dp))
            Text(route.dateLabel ?: route.spanLabel, fontSize = 11.sp, color = IOS.secondaryLabel)
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = "삭제",
            tint = IOS.tertiaryLabel,
            modifier = Modifier.size(16.dp).clickable { onDelete(course) }.padding(4.dp),
        )
    }
}

@Composable
private fun ForkSheet(
    onDismiss: () -> Unit,
    onAI: () -> Unit,
    onManual: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Color.Black
                        .copy(alpha = 0.35f),
                ).clickable(onClick = onDismiss),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(IOS.systemGray6)
                    .clickable(enabled = false) {}
                    .padding(bottom = 24.dp, top = 8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(
                            Alignment.CenterHorizontally,
                        ).size(width = 40.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IOS.tertiaryLabel),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "코스를 어떻게 만들까요?",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ForkCard(title = "AI 로 짜기", caption = "기간·작품만 고르면 동선까지 짜 드립니다", onClick = onAI)
                ForkCard(title = "직접 짜기", caption = "장바구니에서 하나씩 담습니다", onClick = onManual)
            }
        }
    }
}

@Composable
private fun ForkCard(
    title: String,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(IOS.systemBackground)
                .clickable(onClick = onClick)
                .padding(16.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(IOS.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(caption, fontSize = 11.sp, color = IOS.secondaryLabel)
        }
    }
}
