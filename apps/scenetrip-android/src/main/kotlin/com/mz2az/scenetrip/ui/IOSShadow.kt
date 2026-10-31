package com.mz2az.scenetrip.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp

/**
 * SwiftUI `.shadow(color:radius:y:)` 를 그대로 옮긴 그림자.
 *
 * Compose `Modifier.shadow` 는 안드로이드 고도(elevation) 그림자라 색의 투명도에 플랫폼 상수(주변 0.039·직사 0.19)가
 * 한 번 더 곱해진다 — iOS 의 `opacity(0.28)` 을 넣어도 거의 안 보였다. 여기서는 모서리 [cornerRadius] 의 불투명한 도형을
 * `setShadowLayer` 로 그려 번짐·세기·아래쪽 치우침을 iOS 값 그대로 쓴다. **카드 바탕을 이 위에 따로 그려야 한다**(그림자 도형은
 * 카드와 같은 크기라 카드가 덮는다).
 *
 * SwiftUI 의 `radius` 는 가우시안 표준편차의 2배(σ = radius / 2)이고, `setShadowLayer` 의 번짐 반경은 σ = 0.57735·r + 0.5 라서
 * 그 관계로 환산한다.
 */
fun Modifier.iosShadow(
    color: Color,
    radius: Dp,
    y: Dp,
    cornerRadius: Dp,
): Modifier =
    drawBehind {
        val sigma = radius.toPx() / 2f
        val blur = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.copy(alpha = 1f).toArgb()
                setShadowLayer(blur, 0f, y.toPx(), color.toArgb())
            }
        drawIntoCanvas {
            // 도형을 가장자리에서 1px 안으로 그린다 — 카드 가장자리 안티앨리어싱에 도형의 검은 바탕이 비쳐 가는 회색 테두리가 생겼다(8차 O2).
            val r = (cornerRadius.toPx() - 1f).coerceAtLeast(0f)
            it.nativeCanvas.drawRoundRect(1f, 1f, size.width - 1f, size.height - 1f, r, r, paint)
        }
    }
