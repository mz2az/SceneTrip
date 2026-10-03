package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 26 시트 안 `Form` 의 한 `Section` — 굵은 회색 머리글(약 17) + 모서리 둥근 카드.
 * 흰(재질) 바탕 위 **회색 카드**다 — 회색 바탕 위 흰 카드로 하면 톤이 반대였다(12차 실측).
 * 핀 이름 시트와 커뮤니티 글쓰기가 같이 쓴다.
 */
@Composable
fun IOSFormSection(
    title: String?,
    // 핀 이름 시트(반쯤)는 흰 바탕 위 회색 카드, 글쓰기(큰 시트)는 회색 바탕 위 **흰 카드**다(21차 대조).
    card: Color = IOS.systemGray6,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
        if (title != null) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(card)) {
            content()
        }
    }
}
