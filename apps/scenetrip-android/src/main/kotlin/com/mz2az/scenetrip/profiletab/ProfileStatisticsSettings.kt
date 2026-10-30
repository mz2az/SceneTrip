package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.analytics.UsageStatistics
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.ChartBarIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSToggle

/**
 * 마이페이지의 「이용 통계 수집」 토글 (MZ2AZ-475). iOS `ProfileTabView.statisticsSection`을 옮긴 것이다.
 * 기본은 켜짐이고, 끄면 Firebase Analytics 수집을 멈춘다. iOS의 「광고 추적 허용」 줄(ATT)은 iOS에만 있다.
 */
@Composable
fun ProfileStatisticsSettings() {
    val statistics = UsageStatistics.getInstance(LocalContext.current)
    var enabled by remember { mutableStateOf(statistics.isEnabled) }
    ProfileSection(null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(PROFILE_ROW_HEIGHT).padding(horizontal = 16.dp),
        ) {
            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                ChartBarIcon(IOS.accent, Modifier.size(18.dp))
            }
            Text(tr(UsageStatistics.TITLE_KO), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f), maxLines = 2)
            IOSToggle(checked = enabled, onCheckedChange = {
                statistics.setEnabled(it)
                enabled = it
            })
        }
    }
    ProfileFooter(tr(UsageStatistics.FOOTNOTE_KO))
}
