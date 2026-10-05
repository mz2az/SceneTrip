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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.onboarding.PinoPose
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.PhotosIcon
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.XMarkIcon

/**
 * AI 여행 릴스 — 예고편. iOS `ProfileTab/ReelsTeaserView.swift`를 옮긴 것이다.
 *
 * 아직 만드는 기능이 아니다. 무엇이 올지 한 장으로 보여 주고, 열리면 알림을
 * 받겠다는 마음만 받아 둔다.
 */
@Composable
private fun ReelsTeaserViewBody(onClose: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(IOS.systemBackground)
                .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.size(32.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                XMarkIcon(IOS.secondaryLabel, Modifier.size(11.dp))
            }
        }

        PinoMascot(pose = PinoPose.SPARKLE, width = 110.dp)

        Text(tr("AI 여행 릴스"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = IOS.label)

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            TeaserRow(tr("다녀온 코스의 동선과 장소를 AI 가 읽고")) { RouteCurveIcon(IOS.pinDeep, it) }
            TeaserRow(tr("여행 사진을 골라 장면 순서로 엮어서")) { PhotosIcon(IOS.pinDeep, it) }
            TeaserRow(tr("인스타그램에 올릴 15초 릴스를 만들어 드릴 예정이에요")) {
                com.mz2az.scenetrip.searchtab
                    .FilmIcon(IOS.pinDeep, it)
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            tr("준비 중입니다 — 열리면 마이페이지에서 가장 먼저 보여요"),
            fontSize = 12.sp,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun TeaserRow(
    text: String,
    icon: @Composable (Modifier) -> Unit,
) {
    // iOS `teaserRow(symbol:text:)`: 아이콘 칸 24(글리프 14), 글자 15, 줄은 왼쪽으로 붙는다.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { icon(Modifier.size(16.dp)) }
        Text(text, fontSize = 15.sp, color = IOS.label)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun ReelsTeaserView(onClose: () -> Unit) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), onDismiss = onClose) {
        ReelsTeaserViewBody(onClose = onClose)
    }
}
