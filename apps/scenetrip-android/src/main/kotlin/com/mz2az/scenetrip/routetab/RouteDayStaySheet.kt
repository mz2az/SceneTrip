package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
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
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        Column {
            IOSSheetToolbar(title = tr("머무는 시간"), trailing = tr("닫기"), onTrailing = onDismiss)
            LazyColumn(contentPadding = sheetListBottom()) {
                item {
                    Text(
                        RouteDayEstimate.label(estimatedTotalMinutes, stops),
                        color = IOS.secondaryLabel,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                    DayStayRow(stop, index + 1) { minutes -> onPick(stop, minutes) }
                    IOSListDivider(start = 16.dp)
                }
                item {
                    val message =
                        if (estimatedTotalMinutes != null) {
                            "이동을 포함한 어림이에요. 실제 이동 시간은 여행 중에 확인해 주세요."
                        } else {
                            "저장하면 이동을 포함한 시간을 다시 보여 드려요."
                        }
                    Text(tr(message), color = IOS.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DayStayRow(
    stop: RouteStop,
    number: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(number.toString(), fontSize = 12.sp, color = IOS.secondaryLabel)
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
        Text(
            stop.stayLabel + " ↕",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = IOS.accent,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(IOS.accent.copy(alpha = 0.12f))
                    .semantics { contentDescription = "$number ${stop.label().title} ${tr("머무는 시간")}: ${stop.stayLabel}" }
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
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
