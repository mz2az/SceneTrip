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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.ui.IOS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 코스를 만들기 전에 기간을 묻는 질문 흐름. iOS `RouteTab/RouteWizardView.swift`의
 * **"직접 짜기"(`kind == .manual`) 경로만** 옮긴 것이다 — 회의 확정대로 직접 짜는
 * 사람에게는 기간(및 선택적 출발일)만 묻는다.
 *
 * AI 로 초안을 짜는 5단계(`.aiPlan`: span·dates·works·pace·review, `guideDraft` 호출)는
 * 아직 없다 — RouteGuide(챗봇) 자체가 다음 단계라 여기서 만들지 않는다.
 *
 * 답을 다 받으면 iOS와 동일하게 **바로 저장하지 않고** [RouteEditorView]로 넘긴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteWizardView(
    store: RouteStore,
    onClose: (RouteCourse?) -> Unit,
) {
    var stepIndex by remember { mutableStateOf(0) }
    var span by remember { mutableStateOf(RouteSpan.ONE_NIGHT) }
    var hasDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<RouteCourse?>(null) }

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
                TextButton(onClick = { stepIndex -= 1 }) { Text("이전") }
            }
            Text(
                if (isLast) "코스 만들기" else "다음",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.systemBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IOS.accent)
                        .clickable {
                            if (isLast) {
                                draft = store.emptyCourse(span, if (hasDate) pickedDate else null)
                            } else {
                                stepIndex += 1
                            }
                        }.padding(vertical = 14.dp),
            )
        }
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
