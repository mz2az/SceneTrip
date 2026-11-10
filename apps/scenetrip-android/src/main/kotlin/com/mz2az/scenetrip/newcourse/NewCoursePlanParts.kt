package com.mz2az.scenetrip.newcourse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteFormat
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.routetab.RouteSpan
import com.mz2az.scenetrip.ui.BoltIcon
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSGraphicalDatePicker
import com.mz2az.scenetrip.ui.LeafIcon
import com.mz2az.scenetrip.ui.pressClickable
import java.time.LocalDate

/**
 * 일정 묶음 — 기간 칩(3열, 당일치기~5박 6일) + 「떠나는 날」 한 줄(펼치는 달력, 「날짜 지우기」) (MZ2AZ-547 A1·A4, 웹 `TripGroup`).
 * 작품으로·담은 곳으로가 같이 쓴다. 날짜는 정하지 않아도 된다(기본 「정하지 않음」).
 */
@Composable
fun TripScheduleGroup(
    span: RouteSpan,
    onSpan: (RouteSpan) -> Unit,
    date: LocalDate?,
    onDate: (LocalDate?) -> Unit,
) {
    var calendarOpen by remember { mutableStateOf(false) }
    NewCourseGroup(label = tr("일정")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            RouteSpan.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { each ->
                        val on = each == span
                        Text(
                            each.label,
                            fontSize = 15.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (on) Color.White else IOS.label,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .pressClickable { onSpan(each) }
                                    .clip(RoundedCornerShape(NewCourseStyle.radiusS))
                                    .background(if (on) NewCourseStyle.purple else IOS.systemGray6)
                                    .semantics {
                                        role = Role.Button
                                        selected = on
                                    }.heightIn(min = 44.dp)
                                    .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
        Hairline()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { calendarOpen = !calendarOpen }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp),
        ) {
            Text(tr("떠나는 날"), fontSize = 15.sp, color = IOS.label)
            Spacer(Modifier.weight(1f))
            Text(dateLabel(span, date), fontSize = 15.sp, color = if (date == null) IOS.secondaryLabel else IOS.label)
            ChevronRightIcon(
                IOS.tertiaryLabel,
                Modifier.size(width = 7.dp, height = 12.dp).graphicsLayer { rotationZ = if (calendarOpen) -90f else 90f },
            )
        }
        if (calendarOpen) {
            Hairline()
            IOSGraphicalDatePicker(
                selected = date ?: LocalDate.now(),
                onSelect = {
                    onDate(it)
                    calendarOpen = false
                },
                minDate = LocalDate.now(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            )
        }
        if (date != null) {
            Hairline()
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDate(null) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp),
            ) {
                Text(tr("날짜 지우기"), fontSize = 15.sp, color = IOS.systemRed)
            }
        }
    }
}

private fun dateLabel(
    span: RouteSpan,
    date: LocalDate?,
): String {
    if (date == null) return tr("정하지 않음")
    if (span.nights <= 0) return RouteFormat.day(date)
    return "${RouteFormat.day(date)} – ${RouteFormat.day(date.plusDays(span.nights.toLong()))}"
}

/** 다니는 속도 — 묶음 안 두 줄(널널하게 leaf · 빡빡하게 bolt), 고른 줄 오른쪽에 체크(앱 설정 화면처럼) (A1). */
@Composable
fun PaceRows(
    pace: RoutePace,
    onPace: (RoutePace) -> Unit,
) {
    listOf(RoutePace.LOOSE, RoutePace.TIGHT).forEachIndexed { index, each ->
        val on = each == pace
        if (index > 0) Hairline()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onPace(each) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        selected = on
                    }.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (each == RoutePace.TIGHT) {
                    BoltIcon(NewCourseStyle.purple, Modifier.size(17.dp))
                } else {
                    LeafIcon(NewCourseStyle.purple, Modifier.size(17.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
                Text(each.label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = IOS.label)
                Text(each.caption, fontSize = 12.sp, color = IOS.secondaryLabel)
            }
            if (on) CheckmarkIcon(NewCourseStyle.purple, Modifier.size(15.dp))
        }
    }
}
