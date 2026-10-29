package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.ChevronUpDownIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom

/** 한 일차의 체류 시간을 같은 시트에서 고치고 합계를 바로 갱신한다. */
@Composable
fun RouteDayStaySheet(
    stops: List<RouteStop>,
    estimatedTotalMinutes: Int?,
    onPick: (RouteStop, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // iOS 시트는 `.regularMaterial` 유리 + insetGrouped `List` — 시트 바탕 ≈242, 둥근 회색 카드 ≈227(2026-10-30 실측).
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss, containerColor = StaySheetColor) {
        Column {
            IOSSheetToolbar(title = tr("머무는 시간"), trailing = tr("닫기"), onTrailing = onDismiss)
            LazyColumn(contentPadding = sheetListBottom()) {
                item {
                    Text(
                        RouteDayEstimate.label(estimatedTotalMinutes, stops),
                        color = IOS.secondaryLabel,
                        // iOS 구역 머리글은 15pt 반굵은(90-stay 실측: 글자 높이 약 12, Android 13sp 는 한 단계 작았다).
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 31.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                item {
                    Column(
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(StayCardColor),
                    ) {
                        stops.forEachIndexed { index, stop ->
                            key(stop.id) {
                                DayStayRow(stop, index + 1) { minutes -> onPick(stop, minutes) }
                                if (index < stops.lastIndex) IOSListDivider(start = 20.dp, color = Color(0xFFCBCBCC))
                            }
                        }
                    }
                }
                item {
                    val message =
                        if (estimatedTotalMinutes != null) {
                            "이동을 포함한 어림이에요. 실제 이동 시간은 여행 중에 확인해 주세요."
                        } else {
                            "저장하면 이동을 포함한 시간을 다시 보여 드려요."
                        }
                    Text(
                        tr(message),
                        color = IOS.secondaryLabel,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 31.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

/** 유리 시트 바탕 — 뒤가 옅을 때의 `.regularMaterial`(실측 242). */
private val StaySheetColor = Color(0xFFF2F2F2)

/** insetGrouped `List` 의 둥근 카드 — 시트 위에서 한 단 어둡다(실측 227). */
private val StayCardColor = Color(0xFFE3E3E3)

@Composable
private fun DayStayRow(
    stop: RouteStop,
    number: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // 줄 높이 57.7(실측), 안쪽 왼쪽 20·오른쪽 15.
        modifier = Modifier.fillMaxWidth().heightIn(min = 57.7.dp).padding(start = 20.dp, end = 15.dp),
    ) {
        Text(
            number.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.secondaryLabel,
            modifier = Modifier.widthIn(min = 18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stop.label().title, fontSize = 17.sp, color = IOS.label, maxLines = 1)
            stop.label().reading?.let { Text(it, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1) }
        }
        StayMenu(stop, number, onPick)
    }
}

@Composable
private fun StayMenu(
    stop: RouteStop,
    number: Int,
    onPick: (Int) -> Unit,
) {
    var expanded by remember(stop.id) { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .height(28.7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.accent.copy(alpha = 0.12f))
                    .semantics { contentDescription = "$number ${stop.label().title} ${tr("머무는 시간")}: ${stop.stayLabel}" }
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp),
        ) {
            Text(stop.stayLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.accent)
            ChevronUpDownIcon(IOS.accent, Modifier.size(11.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RouteStop.stayChoices(stop.stayMinutes).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(RouteFormat.minutes(minutes)) },
                    trailingIcon = if (minutes == stop.stayMinutes) ({ Text("✓") }) else null,
                    onClick = {
                        expanded = false
                        onPick(minutes)
                    },
                )
            }
        }
    }
}
