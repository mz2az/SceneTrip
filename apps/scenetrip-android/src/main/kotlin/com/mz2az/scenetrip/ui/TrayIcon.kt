package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * SF Symbols `tray` — "둘러보기"(마켓) 빈 상태. material-icons-core(49개뿐)에 없어
 * 직접 그린다. 사다리꼴 바구니 + 가운데 홈, 전부 외곽선이다(SF Symbols 자체가 채움이
 * 아니라 선 하나짜리 심벌이다).
 */
@Composable
fun TrayIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.07f
        val outline =
            Path().apply {
                moveTo(w * 0.06f, h * 0.42f)
                lineTo(w * 0.28f, h * 0.06f)
                lineTo(w * 0.72f, h * 0.06f)
                lineTo(w * 0.94f, h * 0.42f)
                lineTo(w * 0.94f, h * 0.88f)
                lineTo(w * 0.06f, h * 0.88f)
                close()
            }
        drawPath(outline, color = tint, style = Stroke(width = stroke, pathEffect = PathEffect.cornerPathEffect(w * 0.05f)))
        drawLine(tint, Offset(w * 0.06f, h * 0.42f), Offset(w * 0.94f, h * 0.42f), strokeWidth = stroke)
        // 가운데 오목한 손잡이 홈.
        val notch =
            Path().apply {
                moveTo(w * 0.38f, h * 0.42f)
                lineTo(w * 0.38f, h * 0.56f)
                lineTo(w * 0.62f, h * 0.56f)
                lineTo(w * 0.62f, h * 0.42f)
            }
        drawPath(notch, color = tint, style = Stroke(width = stroke * 0.8f))
    }
}
