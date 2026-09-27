package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * SF Symbols `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다. 탭바의 커뮤니티
 * 아이콘([com.mz2az.scenetrip.RootTabs])과 커뮤니티 탭의 빈 상태(iOS
 * `ContentUnavailableView`의 같은 심벌)가 같이 쓴다 — material-icons-core 에 말풍선
 * 쌍이 없고, 아이콘 하나 때문에 material-icons-extended 를 더할 것이 아니라서 직접
 * 그린다.
 */
@Composable
fun BubblesIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // **모서리 반지름은 말풍선 자신의 높이에 붙인다.** 예전엔 캔버스 너비의
        // 16%(`w * 0.16f`)를 썼는데, 말풍선 높이(`h * 0.42f`)의 76%나 돼서 탭바의
        // 20dp에서는 안티에일리어싱에 묻혀 안 보였지만 빈 상태의 52dp로 키우니
        // 모서리 두 쪽이 맞닿아 사슬고리처럼 보였다(2026-09-28 실측). 말풍선
        // 높이에 비례해 둬야 아이콘 크기가 달라져도 "말풍선"으로 읽힌다.
        val stroke = w * 0.09f

        fun bubble(
            x: Float,
            y: Float,
            bw: Float,
            bh: Float,
            tailAt: Float,
        ) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, y),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bh * 0.28f),
                style = Stroke(width = stroke),
            )
            val tx = x + bw * tailAt
            drawLine(tint, Offset(tx, y + bh), Offset(tx, y + bh + h * 0.12f), strokeWidth = stroke)
        }

        bubble(0f, h * 0.06f, w * 0.60f, h * 0.42f, tailAt = 0.24f)
        bubble(w * 0.40f, h * 0.34f, w * 0.60f, h * 0.42f, tailAt = 0.76f)
    }
}
