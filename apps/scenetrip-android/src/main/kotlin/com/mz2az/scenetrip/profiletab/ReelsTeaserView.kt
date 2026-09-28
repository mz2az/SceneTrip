package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.onboarding.PinoPose
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.SparklesIcon

/**
 * AI 여행 릴스 — 예고편. iOS `ProfileTab/ReelsTeaserView.swift`를 옮긴 것이다.
 *
 * 아직 만드는 기능이 아니다. 무엇이 올지 한 장으로 보여 주고, 열리면 알림을
 * 받겠다는 마음만 받아 둔다.
 */
@Composable
fun ReelsTeaserView(onClose: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(IOS.systemBackground)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.size(32.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.secondaryLabel, modifier = Modifier.size(12.dp))
            }
        }

        PinoMascot(pose = PinoPose.SPARKLE, width = 110.dp)

        Text("AI 여행 릴스", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = IOS.label)

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            TeaserRow("다녀온 코스의 동선과 장소를 AI 가 읽고")
            TeaserRow("여행 사진을 골라 장면 순서로 엮어서")
            TeaserRow("인스타그램에 올릴 15초 릴스를 만들어 드릴 예정이에요")
        }

        Spacer(Modifier.weight(1f))

        Text(
            "준비 중입니다 — 열리면 마이페이지에서 가장 먼저 보여요",
            fontSize = 12.sp,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun TeaserRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SparklesIcon(IOS.pinDeep, Modifier.size(14.dp))
        Text(text, fontSize = 15.sp, color = IOS.label)
    }
}
