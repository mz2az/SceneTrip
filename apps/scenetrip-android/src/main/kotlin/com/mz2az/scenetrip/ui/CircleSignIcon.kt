package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** SF Symbols `plus.circle` / `minus.circle` — 원 테두리 안의 더하기·빼기. core 아이콘 49개에 없다. */
@Composable
fun CircleSignIcon(
    plus: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.075f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(tint, radius = w / 2 - stroke / 2, center = c, style = Stroke(width = stroke))
        val arm = w * 0.24f
        drawLine(tint, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        if (plus) {
            drawLine(tint, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
