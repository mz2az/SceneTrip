package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.data.trCount
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS

/** 큰 글자나 좁은 화면에서는 거리와 시간을 두 줄로 보여 준다. */
@Composable
fun RouteDaySummary(
    day: RouteDay?,
    // 「머무는 시간 ›」 단추의 틀(창 좌표) — 해태 칩이 카드를 비켜 선 자리에서 이 단추를 덮지 않게 알린다(iOS `stayFrame`). 단추가 없으면 null.
    onStayFrame: (Rect?) -> Unit = {},
    onStay: () -> Unit,
) {
    val stops = day?.stops.orEmpty()
    val distance =
        trCount("%d곳", stops.size) + " · " +
            tr("직선 %s").format(RouteFormat.kilometers(RouteGeometry.totalKilometers(stops)))
    val duration = if (stops.isEmpty()) tr("이동 시간은 여행 중에") else RouteDayEstimate.label(day?.estimate?.total(stops), stops)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textWidth = listOf(distance, duration).sumOf { measurer.measure(AnnotatedString(it), style = IOS.footnote).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().background(IOS.systemBackground).padding(horizontal = 16.dp, vertical = 10.dp)) {
        val stacked = density.fontScale >= 1.3f || textWidth + with(density) { 20.dp.toPx() } > constraints.maxWidth
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(distance, style = IOS.footnote, color = IOS.secondaryLabel)
                DayDuration(duration, stops.isNotEmpty(), onStay, onStayFrame)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(distance, style = IOS.footnote, color = IOS.secondaryLabel)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                DayDuration(duration, stops.isNotEmpty(), onStay, onStayFrame)
            }
        }
    }
}

@Composable
private fun DayDuration(
    label: String,
    editable: Boolean,
    onStay: () -> Unit,
    onStayFrame: (Rect?) -> Unit,
) {
    DisposableEffect(Unit) { onDispose { onStayFrame(null) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        // 접근성: 눌렀을 때 무엇이 되는지(iOS `accessibilityHint`).
        modifier =
            Modifier
                .onGloballyPositioned { if (editable) onStayFrame(it.boundsInWindow()) else onStayFrame(null) }
                .clickable(enabled = editable, onClickLabel = tr("장소별 머무는 시간을 고칩니다"), onClick = onStay),
    ) {
        Text(label, style = IOS.footnote, color = if (editable) IOS.accent else IOS.tertiaryLabel)
        if (editable) ChevronRightIcon(IOS.accent, Modifier.size(9.dp))
    }
}
