package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 코스를 만들기 전에 기간(과 [isAiPlan]이면 작품)을 묻는 질문 흐름. iOS
 * `RouteTab/RouteWizardView.swift`를 옮긴 것이다 — **페이스를 고르는 단계(`.pace`)와
 * "review" 단계는 아직 없다**, 페이스는 항상 빡빡하게로 짠다(`RoutePace.TIGHT`).
 * 답을 받으면 iOS와 동일하게 **바로 저장하지 않고** [RouteEditorView]로 넘겨 거기서
 * 고치게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteWizardView(
    store: RouteStore,
    isAiPlan: Boolean = false,
    onClose: (RouteCourse?) -> Unit,
) {
    var stepIndex by remember { mutableStateOf(0) }
    var span by remember { mutableStateOf(RouteSpan.ONE_NIGHT) }
    val dateState = rememberDatePickerState()
    val hasDate = dateState.selectedDateMillis != null
    val pickedDate =
        dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
    var selectedWorkIds by remember { mutableStateOf(setOf<Long>()) }
    var pace by remember { mutableStateOf(RoutePace.TIGHT) }
    var draft by remember { mutableStateOf<RouteCourse?>(null) }
    var planning by remember { mutableStateOf(false) }
    var planFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isAiPlan) { if (isAiPlan) store.loadWorks() }

    val currentDraft = draft
    if (currentDraft != null) {
        RouteEditorView(store = store, initial = currentDraft, isNew = true, onClose = onClose)
        return
    }

    val steps = if (isAiPlan) 5 else 2
    val isLast = stepIndex == steps - 1

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("취소", fontSize = 15.sp, color = IOS.accent, modifier = Modifier.clickable { onClose(null) })
                Spacer(Modifier.weight(1f))
                Text("${stepIndex + 1} / $steps", fontSize = 12.sp, color = IOS.secondaryLabel)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (stepIndex + 1) / steps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = IOS.accent,
            )
            if (isAiPlan) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IOS.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(13.dp))
                    Text("AI 가 일정을 짜 드립니다", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = IOS.accent)
                }
            }
        }

        Column(modifier = Modifier.weight(1f).padding(20.dp)) {
            Text(
                when (stepIndex) {
                    0 -> "얼마나 다녀오나요?"
                    1 -> "언제 떠나나요?"
                    2 -> "어떤 작품을 좋아하세요?"
                    3 -> "어떻게 다닐까요?"
                    else -> "이렇게 짜 드립니다"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
            )
            Spacer(Modifier.height(16.dp))
            if (stepIndex == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(RouteSpan.entries.toList()) { each ->
                        val isOn = span == each
                        Text(
                            each.label,
                            fontSize = 14.sp,
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isOn) IOS.systemBackground else IOS.label,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isOn) IOS.accent else IOS.systemBackground)
                                    .clickable { span = each }
                                    .padding(vertical = 14.dp),
                        )
                    }
                }
            } else if (stepIndex == 1) {
                Column {
                    DatePicker(
                        state = dateState,
                        title = null,
                        headline = null,
                        showModeToggle = false,
                        colors = DatePickerDefaults.colors(containerColor = IOS.systemBackground),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(IOS.systemBackground),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (hasDate) {
                        val back = pickedDate.plusDays(span.nights.toLong())
                        Text("돌아오는 날 ${RouteFormat.day(back)} · 자동", fontSize = 12.sp, color = IOS.secondaryLabel)
                        Text(
                            "날짜 지우기",
                            fontSize = 12.sp,
                            color = IOS.accent,
                            modifier = Modifier.clickable { dateState.selectedDateMillis = null }.padding(top = 6.dp),
                        )
                    } else {
                        Text("날짜는 나중에 정해도 됩니다", fontSize = 12.sp, color = IOS.secondaryLabel)
                    }
                }
            } else if (stepIndex == 2) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(store.sortedWorks, key = { it.id }) { work ->
                            WorkRow(
                                work = work,
                                isFavorite = store.isFavoriteWork(work.id),
                                isSelected = selectedWorkIds.contains(work.id),
                                onToggleFavorite = { store.toggleFavoriteWork(work.id) },
                                onToggleSelected = {
                                    selectedWorkIds =
                                        if (selectedWorkIds.contains(work.id)) selectedWorkIds - work.id else selectedWorkIds + work.id
                                },
                            )
                        }
                    }
                    Text(
                        "고르지 않으면 인기 작품의 촬영지에서 뽑습니다",
                        fontSize = 11.sp,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else if (stepIndex == 3) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoutePace.entries.forEach { each ->
                        val isOn = pace == each
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isOn) IOS.accent else IOS.systemBackground)
                                    .clickable { pace = each }
                                    .padding(14.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isOn) IOS.systemBackground else IOS.accent),
                            )
                            Column {
                                Text(
                                    each.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isOn) IOS.systemBackground else IOS.label,
                                )
                                Text(
                                    each.caption,
                                    fontSize = 12.sp,
                                    color = if (isOn) IOS.systemBackground.copy(alpha = 0.9f) else IOS.secondaryLabel,
                                )
                            }
                        }
                    }
                    Text(
                        "빡빡하게는 하루 7곳까지, 널널하게는 3곳까지 담습니다",
                        fontSize = 11.sp,
                        color = IOS.tertiaryLabel,
                    )
                }
            } else {
                val pickedTitles = store.works.filter { selectedWorkIds.contains(it.id) }.map { it.title }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IOS.systemBackground)
                            .padding(horizontal = 14.dp),
                ) {
                    ReviewRow("기간", span.label)
                    ReviewRow("떠나는 날", if (hasDate) RouteFormat.day(pickedDate) else "정하지 않음")
                    ReviewRow("작품", if (pickedTitles.isEmpty()) "인기 작품" else pickedTitles.joinToString(", "))
                    ReviewRow("스타일", pace.label, showDivider = false)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(IOS.systemBackground).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (stepIndex > 0) {
                TextButton(onClick = { stepIndex -= 1 }, enabled = !planning) { Text("이전") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IOS.accent)
                        .clickable(enabled = !planning) {
                            if (!isLast) {
                                stepIndex += 1
                                return@clickable
                            }
                            val startDate = if (hasDate) pickedDate else null
                            if (!isAiPlan) {
                                draft = store.emptyCourse(span, startDate)
                                return@clickable
                            }
                            planning = true
                            scope.launch {
                                val result = store.guideDraft(selectedWorkIds, span, startDate, pace)
                                planning = false
                                if (result != null) draft = result else planFailed = true
                            }
                        }.padding(vertical = 14.dp),
            ) {
                if (planning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IOS.systemBackground)
                }
                Text(
                    if (planning) {
                        "일정을 짜는 중입니다"
                    } else if (isLast) {
                        (if (isAiPlan) "AI 로 일정 짜기" else "코스 만들기")
                    } else {
                        "다음"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.systemBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    if (planFailed) {
        AlertDialog(
            onDismissRequest = { planFailed = false },
            title = { Text("일정을 짜지 못했습니다") },
            text = { Text(store.failure?.message ?: "잠시 후 다시 시도해 주세요.") },
            confirmButton = { TextButton(onClick = { planFailed = false }) { Text("확인") } },
        )
    }
}

/** 검토 화면의 한 줄. iOS `RouteWizardView.summary(_:_:)`. */
@Composable
private fun ReviewRow(
    label: String,
    value: String,
    showDivider: Boolean = true,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(label, fontSize = 14.sp, color = IOS.secondaryLabel)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = IOS.label,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(IOS.systemGray5))
        }
    }
}

/** 작품 한 줄 — 하트는 찜, 체크는 이번 코스에 쓸지. iOS `RouteWizardView.workRow`. */
@Composable
private fun WorkRow(
    work: ContentSummary,
    isFavorite: Boolean,
    isSelected: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleSelected).padding(vertical = 10.dp),
    ) {
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "찜",
            tint = if (isFavorite) IOS.systemRed else IOS.secondaryLabel,
            modifier = Modifier.size(18.dp).clickable(onClick = onToggleFavorite),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(work.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            val subtitle = listOfNotNull(work.broadcaster, work.releaseYear?.toString()).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 11.sp, color = IOS.secondaryLabel)
            }
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "선택됨",
                tint = IOS.accent,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(width = 1.5.dp, color = IOS.systemGray3, shape = CircleShape),
            )
        }
    }
}
