package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 마이페이지에서 열리는 `.medium` 시트(내 코스·찜한 작품·장바구니·내가 쓴 글)의 바탕 — `.regularMaterial` 유리가 뒤 화면을
 * 비쳐 균일한 옅은 회색(236)으로 보인다(2026-10-29 실측). 뒤를 흐리는 것은 Compose 가 시트 뒤 화면을 읽을 수 없어 못 하고,
 * 그 색만 옮긴다.
 */
val IOSGlassSheetColor = Color(0xFFECECEC)

/**
 * 시트 머리줄 — 왼쪽 취소 성격 단추, 가운데 제목(17 semibold), 오른쪽 확인 성격 단추(굵게).
 * 단추는 iOS 26 툴바처럼 흰 캡슐에 옅은 그림자. [trailing] 이 null 이면 오른쪽은 비운다.
 */
@Composable
fun IOSSheetToolbar(
    title: String,
    leading: String? = null,
    onLeading: () -> Unit = {},
    trailing: String? = null,
    trailingEnabled: Boolean = true,
    onTrailing: () -> Unit = {},
    // 보내는 중이면 글자 대신 돌아가는 표시를 보인다(iOS 툴바의 `ProgressView`).
    trailingBusy: Boolean = false,
    // 제목 아래 작은 글씨 — 리뷰 쓰기 머리줄의 대상 이름(iOS `header`). 읽는 법이 따로 있으면 그 아래 한 줄 더.
    subtitle: String? = null,
    reading: String? = null,
    // 유리 시트 위에서는 단추도 유리 — 흰색이 아니라 시트보다 한 톤 짙은 회색(iOS 실측 233).
    capsuleColor: Color = IOS.systemBackground,
) {
    // iOS 내비게이션 줄의 제목·단추 중심은 시트 윗에서 36.4(7차 감사 ST1·ES1: 38.7 이라 2.3 아래였다). 아래 칸은 그대로라 그림만 올린다.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp)
                .offset(y = (-2.3).dp),
    ) {
        if (leading != null) {
            GlassCapsule(
                leading,
                bold = false,
                enabled = true,
                onClick = onLeading,
                color = capsuleColor,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (subtitle == null) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        } else {
            // 단추는 머리줄이 길어져도 첫 56 의 가운데 — 늘 같은 자리다.
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 76.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                Text(subtitle, style = IOS.caption, color = IOS.secondaryLabel, maxLines = 2, textAlign = TextAlign.Center)
                if (reading != null) Text(reading, style = IOS.caption2, color = IOS.secondaryLabel, maxLines = 1)
            }
        }
        if (trailing != null) {
            GlassCapsule(
                trailing,
                bold = true,
                enabled = trailingEnabled && !trailingBusy,
                onClick = onTrailing,
                modifier = Modifier.align(Alignment.CenterEnd),
                busy = trailingBusy,
                color = capsuleColor,
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
    busy: Boolean = false,
    color: Color = IOS.systemBackground,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(40.dp)
                .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(CircleShape)
                .background(color)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
        } else {
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) IOS.label else IOS.tertiaryLabel,
            )
        }
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
fun IOSListDivider(
    start: Dp,
    color: Color = Color(0xFFE5E5EA),
    // true 면 선이 칸을 차지하지 않고 앞 줄 맨 아래 1dp 에 겹친다 — iOS 목록은 줄 높이 안에 선이 들어 있다(7차 감사 C2: 피치 118.0).
    overlay: Boolean = false,
) {
    // iOS 목록 선은 오른쪽 끝에서도 약 15 들어와 끝난다.
    HorizontalDivider(
        thickness = 1.dp,
        color = color,
        modifier =
            (
                if (overlay) {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                    }
                } else {
                    Modifier
                }
            ).padding(start = start, end = 15.dp),
    )
}
