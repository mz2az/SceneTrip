package com.mz2az.scenetrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS `ContentUnavailableView`: 회색 기호(잉크 약 44) + 22 굵은 검정 제목 + 15 회색 설명, **카드 정중앙**.
 * 머리줄 아래 영역 가운데에 두면 아래로 처져 보여서 위로 올린다(2026-09-28 실측, 2차 대조로 기호를 키움).
 * 마이페이지 시트와 작품검색 장바구니가 같이 쓴다.
 */
@Composable
fun IOSEmptyState(
    title: String,
    description: String,
    icon: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().offset(y = (-42).dp).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 여백을 **먼저** 두고 정사각 50 — 62×50 틀이면 캔버스 아이콘이 왼쪽 정사각에만 그려져 6 치우쳤다.
        icon(Modifier.padding(bottom = 12.dp).size(50.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label, textAlign = TextAlign.Center)
        Text(
            description,
            fontSize = 15.sp,
            color = IOS.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
