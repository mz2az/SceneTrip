package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * 신고·차단 화면(MZ2AZ-461)의 SF Symbols 외곽선: `ellipsis`, `exclamationmark.bubble`, `nosign`, `envelope`.
 * material-icons-core 에 없거나 채운 모양이라 직접 그린다(OutlineIcons.kt 와 같은 선 굵기).
 */

private fun lineStroke(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** `ellipsis` — 가로로 놓인 점 셋. */
@Composable
fun EllipsisIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val r = w * 0.085f
        val y = size.height / 2
        listOf(0.2f, 0.5f, 0.8f).forEach { x -> drawCircle(tint, radius = r, center = Offset(w * x, y)) }
    }
}

/** `nosign` — 원과 왼쪽 위에서 오른쪽 아래로 가는 줄. */
@Composable
fun NoSignIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = lineStroke(w)
        val radius = w * 0.42f
        val c = Offset(w / 2, w / 2)
        drawCircle(tint, radius = radius, center = c, style = s)
        val d = radius * 0.7071f
        drawLine(tint, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = s.width, cap = StrokeCap.Round)
    }
}

/** `exclamationmark.bubble` — 말풍선 안의 느낌표. */
@Composable
fun ReportBubbleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = lineStroke(w)
        val bubble =
            Path().apply {
                moveTo(w * 0.22f, w * 0.14f)
                lineTo(w * 0.78f, w * 0.14f)
                quadraticTo(w * 0.92f, w * 0.14f, w * 0.92f, w * 0.28f)
                lineTo(w * 0.92f, w * 0.56f)
                quadraticTo(w * 0.92f, w * 0.70f, w * 0.78f, w * 0.70f)
                lineTo(w * 0.46f, w * 0.70f)
                lineTo(w * 0.24f, w * 0.88f)
                lineTo(w * 0.24f, w * 0.70f)
                quadraticTo(w * 0.08f, w * 0.70f, w * 0.08f, w * 0.56f)
                lineTo(w * 0.08f, w * 0.28f)
                quadraticTo(w * 0.08f, w * 0.14f, w * 0.22f, w * 0.14f)
                close()
            }
        drawPath(bubble, tint, style = s)
        drawLine(tint, Offset(w * 0.5f, w * 0.28f), Offset(w * 0.5f, w * 0.46f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawCircle(tint, radius = s.width * 0.6f, center = Offset(w * 0.5f, w * 0.57f))
    }
}

/** `envelope` — 모서리 둥근 네모와 뚜껑 선. */
@Composable
fun EnvelopeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = lineStroke(w)
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.08f, w * 0.2f),
            size = Size(w * 0.84f, w * 0.6f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = s,
        )
        val flap =
            Path().apply {
                moveTo(w * 0.1f, w * 0.26f)
                lineTo(w * 0.5f, w * 0.58f)
                lineTo(w * 0.9f, w * 0.26f)
            }
        drawPath(flap, tint, style = s)
    }
}
