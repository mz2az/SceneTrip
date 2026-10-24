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

/*
 * 가이드 대화창 안내 줄의 SF Symbols(`exclamationmark.triangle`·`checkmark.circle`) 외곽선 대응물.
 * material-icons-core 에는 모래시계가 없고, 경고 삼각형은 채운 모양이라 직접 그린다 (MZ2AZ-481).
 */

private fun noticeStroke(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** `exclamationmark.triangle` — 모서리가 둥근 삼각형 안의 느낌표. */
@Composable
fun WarningTriangleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val triangle =
            Path().apply {
                moveTo(w * 0.5f, w * 0.12f)
                lineTo(w * 0.92f, w * 0.84f)
                lineTo(w * 0.08f, w * 0.84f)
                close()
            }
        drawPath(triangle, tint, style = noticeStroke(w))
        drawLine(tint, Offset(w * 0.5f, w * 0.38f), Offset(w * 0.5f, w * 0.6f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.5f, w * 0.72f))
    }
}

/** `checkmark.circle` — 원 안의 체크. */
@Composable
fun CheckCircleOutlineIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        drawCircle(tint, radius = w * 0.44f, center = Offset(w * 0.5f, w * 0.5f), style = noticeStroke(w))
        val check =
            Path().apply {
                moveTo(w * 0.3f, w * 0.52f)
                lineTo(w * 0.45f, w * 0.67f)
                lineTo(w * 0.72f, w * 0.34f)
            }
        drawPath(check, tint, style = noticeStroke(w))
    }
}
