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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS

/** 큰 글자나 좁은 화면에서는 거리와 시간을 두 줄로 보여 준다. */
@Composable
fun RouteDaySummary(
    day: RouteDay?,
    onStay: () -> Unit,
) {
    val stops = day?.stops.orEmpty()
    val distance =
        tr("%d곳").format(stops.size) + " · " +
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
                DayDuration(duration, stops.isNotEmpty(), onStay)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(distance, style = IOS.footnote, color = IOS.secondaryLabel)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                DayDuration(duration, stops.isNotEmpty(), onStay)
            }
        }
    }
}

@Composable
private fun DayDuration(
    label: String,
    editable: Boolean,
    onStay: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clickable(enabled = editable, onClick = onStay),
    ) {
        Text(label, style = IOS.footnote, color = if (editable) IOS.accent else IOS.tertiaryLabel)
        if (editable) ChevronRightIcon(IOS.accent, Modifier.size(9.dp))
    }
}
