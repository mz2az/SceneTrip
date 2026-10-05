package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * iOS `DatePicker(...).datePickerStyle(.graphical)` — 머리 「2026년 9월 ›」(17 semibold) + 오른쪽 ‹ ›(accent,
 * 못 가는 쪽은 회색), 요일 13 semibold 회색, 날짜 20(지난 날은 옅은 회색, 오늘은 파랑, 고른 날은 44 파란 원에
 * 흰 글자). Material `DatePicker` 는 머리·요일·선택 모양이 모두 달라 한눈에 다른 달력이었다(2026-09-28 대조).
 *
 * 카드(흰 바탕·모서리)는 부르는 쪽이 준다 — iOS 도 `.background(RoundedRectangle(12))` 를 바깥에서 씌운다.
 */
@Composable
fun IOSGraphicalDatePicker(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    minDate: LocalDate,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val today = LocalDate.now()
    val canGoBack = month > YearMonth.from(minDate)
    // SwiftUI 의 `DatePicker` 는 머리·요일을 기기 로케일로 알아서 그려 준다 — 손수
    // 그리는 Android 는 **앱 언어**로 맞춰 줘야 한다(기기 로케일이 아니다).
    val locale = if (AppLanguage.current == Lang.ko) Locale.KOREAN else Locale.ENGLISH
    Column(modifier = modifier.padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val header =
                if (locale == Locale.KOREAN) {
                    "${month.year}년 ${month.monthValue}월"
                } else {
                    "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}"
                }
            Text(header, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Spacer(Modifier.width(6.dp))
            ChevronRightIcon(IOS.accent, Modifier.size(13.dp))
            Spacer(Modifier.weight(1f))
            ChevronRightIcon(
                if (canGoBack) IOS.accent else IOS.systemGray3,
                Modifier
                    .size(20.dp)
                    .graphicsLayer(scaleX = -1f)
                    .clickable(enabled = canGoBack) { month = month.minusMonths(1) },
            )
            Spacer(Modifier.width(28.dp))
            ChevronRightIcon(IOS.accent, Modifier.size(20.dp).clickable { month = month.plusMonths(1) })
        }
        Spacer(Modifier.height(25.dp))
        Row {
            // 일요일부터 — DayOfWeek 는 월=1..일=7 이라 일요일을 맨 뒤로 돌린다.
            val weekdays =
                listOf(
                    DayOfWeek.SUNDAY,
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY,
                    DayOfWeek.SATURDAY,
                ).map { it.getDisplayName(TextStyle.NARROW, locale) }
            weekdays.forEach {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WEEKDAY_GRAY,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val first = month.atDay(1)
        // 일요일이 첫 칸 — java 의 DayOfWeek 는 월=1 … 일=7.
        val lead = first.dayOfWeek.value % 7
        val cells = lead + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                for (c in 0 until 7) {
                    val dayNumber = r * 7 + c - lead + 1
                    Box(modifier = Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            val enabled = !date.isBefore(minDate)
                            val isSelected = date == selected
                            val isToday = date == today
                            // iOS: 오늘을 고르면 **진한** 파란 원·흰 글자, 다른 날을 고르면 **옅은** 파란 원·파란 굵은 글자.
                            val filled = isSelected && isToday
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                filled -> IOS.accent
                                                isSelected -> IOS.accent.copy(alpha = 0.14f)
                                                else -> Color.Transparent
                                            },
                                        ).clickable(enabled = enabled) { onSelect(date) },
                            ) {
                                Text(
                                    "$dayNumber",
                                    fontSize = 20.sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    color =
                                        when {
                                            filled -> Color.White
                                            isSelected -> IOS.accent
                                            !enabled -> PAST_GRAY
                                            isToday -> IOS.accent
                                            else -> IOS.label
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 요일 글자 — iOS 보조 회색(138,138,142). */
private val WEEKDAY_GRAY = Color(0xFF8A8A8E)

/** 지난 날짜 — iOS 는 systemGray3 보다 옅다(221). */
private val PAST_GRAY = Color(0xFFDDDDDF)
