package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 코스를 만들기 전에 기간을 묻는 질문 흐름. iOS `RouteTab/RouteWizardView.swift`를
 * 옮긴 것이다 — **작품·페이스를 고르는 두 단계(`.works`·`.pace`)는 빠졌다**, 그 UI가
 * 아직 없어 [isAiPlan]일 때는 인기 작품 상위 3개·빡빡 페이스로 자동 채운다
 * (`RouteStore.guideDraft`). "review" 단계도 없다 — 답을 받으면 iOS와 동일하게
 * **바로 저장하지 않고** [RouteEditorView]로 넘겨 거기서 고치게 한다.
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
    var hasDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<RouteCourse?>(null) }
    var planning by remember { mutableStateOf(false) }
    var planFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val currentDraft = draft
    if (currentDraft != null) {
        RouteEditorView(store = store, initial = currentDraft, onClose = onClose)
        return
    }

    val steps = 2
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
                if (stepIndex == 0) "얼마나 다녀오나요?" else "언제 떠나나요?",
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
            } else {
                Column {
                    Text(
                        if (hasDate) "떠나는 날 ${RouteFormat.day(pickedDate)}" else "떠나는 날을 정해 주세요",
                        fontSize = 14.sp,
                        color = IOS.label,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(IOS.systemBackground)
                                .clickable { showDatePicker = true }
                                .padding(16.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (hasDate) {
                        val back = pickedDate.plusDays(span.nights.toLong())
                        Text("돌아오는 날 ${RouteFormat.day(back)} · 자동", fontSize = 12.sp, color = IOS.secondaryLabel)
                        Text(
                            "날짜 지우기",
                            fontSize = 12.sp,
                            color = IOS.accent,
                            modifier = Modifier.clickable { hasDate = false }.padding(top = 6.dp),
                        )
                    } else {
                        Text("날짜는 나중에 정해도 됩니다", fontSize = 12.sp, color = IOS.secondaryLabel)
                    }
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
                                val result = store.guideDraft(span, startDate, RoutePace.TIGHT)
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

    if (showDatePicker) {
        val state =
            rememberDatePickerState(initialSelectedDateMillis = pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        hasDate = true
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }
}
