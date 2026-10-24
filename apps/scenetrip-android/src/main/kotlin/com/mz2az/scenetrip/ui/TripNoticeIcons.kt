package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

// 여행 안내 띠와 한도 안내에 쓰는 SF Symbols 대응 아이콘 (MZ2AZ-478). material-icons-core 에 없어 직접 그린다
// (`TripLegIcons`·`OutlineIcons` 와 같은 자리).

/** `hourglass` — 한도 안내 줄. */
@Composable
fun HourglassIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p =
            Path().apply {
                moveTo(w * 0.22f, w * 0.08f)
                lineTo(w * 0.78f, w * 0.08f)
                lineTo(w * 0.78f, w * 0.2f)
                lineTo(w * 0.5f, w * 0.5f)
                lineTo(w * 0.78f, w * 0.8f)
                lineTo(w * 0.78f, w * 0.92f)
                lineTo(w * 0.22f, w * 0.92f)
                lineTo(w * 0.22f, w * 0.8f)
                lineTo(w * 0.5f, w * 0.5f)
                lineTo(w * 0.22f, w * 0.2f)
                close()
            }
        drawPath(p, tint, style = s)
    }
}

/** `checkmark.circle` — 한도가 풀렸다는 줄. */
@Composable
fun CheckCircleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(tint, radius = w * 0.44f, center = Offset(w * 0.5f, w * 0.5f), style = s)
        val tick =
            Path().apply {
                moveTo(w * 0.28f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.68f)
                lineTo(w * 0.74f, w * 0.34f)
            }
        drawPath(tick, tint, style = s)
    }
}

/** `checkmark.seal.fill` — 도착 알림 머리줄. 톱니 가장자리의 원 안에 흰 체크. */
@Composable
fun SealCheckIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val c = Offset(w * 0.5f, w * 0.5f)
        val seal =
            Path().apply {
                val teeth = 8
                val outer = w * 0.5f
                val inner = w * 0.42f
                for (i in 0 until teeth * 2) {
                    val r = if (i % 2 == 0) outer else inner
                    val angle = Math.PI * i / teeth - Math.PI / 2
                    val x = c.x + (r * Math.cos(angle)).toFloat()
                    val y = c.y + (r * Math.sin(angle)).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        drawPath(seal, tint)
        val tick =
            Path().apply {
                moveTo(w * 0.3f, w * 0.52f)
                lineTo(w * 0.45f, w * 0.67f)
                lineTo(w * 0.72f, w * 0.34f)
            }
        drawPath(tick, Color.White, style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** `arrow.up.forward.app` — 지도 앱으로 넘기는 단추. 모서리가 둥근 네모에서 오른쪽 위로 나가는 화살표. */
@Composable
fun ForwardAppIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.1f, w * 0.1f),
            size = Size(w * 0.8f, w * 0.8f),
            cornerRadius = CornerRadius(w * 0.22f, w * 0.22f),
            style = s,
        )
        drawLine(tint, Offset(w * 0.4f, w * 0.6f), Offset(w * 0.86f, w * 0.14f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        val head =
            Path().apply {
                moveTo(w * 0.52f, w * 0.14f)
                lineTo(w * 0.86f, w * 0.14f)
                lineTo(w * 0.86f, w * 0.48f)
            }
        drawPath(head, tint, style = s)
    }
}

/** `character.bubble` — 안내 언어가 앱 언어와 다르다는 줄. 말풍선 안에 점 셋. */
@Composable
fun SpeechBubbleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bubble =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        w * 0.08f,
                        w * 0.12f,
                        w * 0.92f,
                        w * 0.72f,
                        CornerRadius(w * 0.2f, w * 0.2f),
                    ),
                )
                moveTo(w * 0.3f, w * 0.72f)
                lineTo(w * 0.24f, w * 0.92f)
                lineTo(w * 0.5f, w * 0.72f)
            }
        drawPath(bubble, tint, style = s)
        listOf(0.32f, 0.5f, 0.68f).forEach { x -> drawCircle(tint, radius = w * 0.05f, center = Offset(x * w, w * 0.42f)) }
    }
}

/** `location.slash` — 위치를 아직 못 찾았다는 줄. */
@Composable
fun LocationSlashIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow =
            Path().apply {
                moveTo(w * 0.9f, w * 0.1f)
                lineTo(w * 0.1f, w * 0.46f)
                lineTo(w * 0.5f, w * 0.52f)
                lineTo(w * 0.56f, w * 0.9f)
                close()
            }
        drawPath(arrow, tint, style = s)
        drawLine(tint, Offset(w * 0.1f, w * 0.1f), Offset(w * 0.9f, w * 0.9f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

/** `location.north.fill` — 「여기로 길찾기」 단추. 위쪽을 가리키는 채운 화살표. */
@Composable
fun NorthArrowIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.5f, w * 0.06f)
                lineTo(w * 0.9f, w * 0.9f)
                lineTo(w * 0.5f, w * 0.68f)
                lineTo(w * 0.1f, w * 0.9f)
                close()
            }
        drawPath(p, tint)
    }
}
