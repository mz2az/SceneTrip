package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** `pencil` — 닉네임 옆의 「바꾸기」 표시. 비스듬한 연필 몸통과 끝. */
@Composable
fun PencilIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val body =
            Path().apply {
                moveTo(w * 0.7f, w * 0.12f)
                lineTo(w * 0.88f, w * 0.3f)
                lineTo(w * 0.36f, w * 0.82f)
                lineTo(w * 0.1f, w * 0.9f)
                lineTo(w * 0.18f, w * 0.64f)
                close()
            }
        drawPath(body, tint, style = Stroke(width = w * 0.12f, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawLine(tint, Offset(w * 0.58f, w * 0.24f), Offset(w * 0.76f, w * 0.42f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
    }
}
