package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * iOS 26 시트 안쪽 부품 — `NavigationStack` 툴바의 유리 캡슐 단추, `.searchable` 의 알약 검색창, 목록 구분선.
 * Android 는 맨 글자 단추·Material 테두리 입력창·구분선 없음이라 시트 안이 한눈에 달랐다(2026-09-28 10차 대조).
 */

/** 시트 바탕 — iOS `.presentationBackground(.regularMaterial)` 를 흰 화면 위에서 본 옅은 회색. */
val IOSSheetMaterial = Color(0xFFF2F2F5)

/**
 * 시트 머리줄 — 왼쪽 취소 성격 단추, 가운데 제목(17 semibold), 오른쪽 확인 성격 단추(굵게).
 * 단추는 iOS 26 툴바처럼 흰 캡슐에 옅은 그림자. [trailing] 이 null 이면 오른쪽은 비운다.
 */
@Composable
fun IOSSheetToolbar(
    title: String,
    leading: String,
    onLeading: () -> Unit,
    trailing: String? = null,
    trailingEnabled: Boolean = true,
    onTrailing: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
        GlassCapsule(leading, bold = false, enabled = true, onClick = onLeading, modifier = Modifier.align(Alignment.CenterStart))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        if (trailing != null) {
            GlassCapsule(
                trailing,
                bold = true,
                enabled = trailingEnabled,
                onClick = onTrailing,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun GlassCapsule(
    label: String,
    bold: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(40.dp)
                .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(CircleShape)
                .background(IOS.systemBackground)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
    ) {
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) IOS.label else IOS.tertiaryLabel,
        )
    }
}

/** `.searchable` 의 알약 검색창 — 돋보기 + 자리표시 글. */
@Composable
fun IOSSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(42.dp)
                // iOS 26 `.searchable` 은 **흰 알약에 옅은 그림자**다(12차 실측) — 회색 채움이 아니다.
                .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                .clip(CircleShape)
                .background(IOS.systemBackground)
                .padding(horizontal = 12.dp),
    ) {
        MagnifierIcon(IOS.label.copy(alpha = 0.7f), Modifier.size(17.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = IOS.secondaryLabel, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = IOS.body.copy(color = IOS.label),
                cursorBrush = SolidColor(IOS.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 목록 줄 사이 선(iOS `List(.plain)`). [start] 는 글자 시작에 맞춘다. */
@Composable
fun IOSListDivider(start: Dp) {
    // iOS 목록 선은 오른쪽 끝에서도 약 15 들어와 끝난다.
    HorizontalDivider(thickness = 1.dp, color = Color(0xFFE5E5EA), modifier = Modifier.padding(start = start, end = 15.dp))
}
