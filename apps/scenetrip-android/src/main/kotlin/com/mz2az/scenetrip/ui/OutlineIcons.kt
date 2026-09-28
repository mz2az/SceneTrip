package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * SF Symbols 의 **외곽선** 아이콘들. material-icons-core(49개)의 대응물은 전부 채운 모양이라
 * iOS 와 나란히 두면 무게가 달라 보인다(2026-09-28 화면 대조 #12·#19). 선 굵기는 크기의 약 9%.
 */

private fun stroke(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** `magnifyingglass` */
@Composable
fun MagnifierIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawCircle(tint, radius = w * 0.3f, center = Offset(w * 0.42f, w * 0.42f), style = s)
        drawLine(tint, Offset(w * 0.64f, w * 0.64f), Offset(w * 0.9f, w * 0.9f), strokeWidth = s.width, cap = StrokeCap.Round)
    }
}

/** `bag` — 네모 가방과 둥근 손잡이. */
@Composable
fun BagIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.14f, w * 0.32f),
            size = Size(w * 0.72f, w * 0.58f),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(w * 0.1f),
            style = s,
        )
        val handle =
            Path().apply {
                moveTo(w * 0.34f, w * 0.42f)
                lineTo(w * 0.34f, w * 0.26f)
                cubicTo(w * 0.34f, w * 0.06f, w * 0.66f, w * 0.06f, w * 0.66f, w * 0.26f)
                lineTo(w * 0.66f, w * 0.42f)
            }
        drawPath(handle, tint, style = s)
    }
}

/** `mappin.and.ellipse` — 핀 머리·핀 끝과 그 아래 납작한 타원. */
@Composable
fun MapPinEllipseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.5f, w * 0.26f))
        drawLine(tint, Offset(w * 0.5f, w * 0.43f), Offset(w * 0.5f, w * 0.78f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawOval(tint, topLeft = Offset(w * 0.12f, w * 0.7f), size = Size(w * 0.76f, w * 0.2f), style = s)
    }
}

/** `arrow.triangle.swap` — 위로 가는 화살표와 아래로 가는 화살표가 엇갈린 모양. */
@Composable
fun SwapArrowsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        // 왼쪽: 아래에서 위로
        drawLine(tint, Offset(w * 0.3f, w * 0.88f), Offset(w * 0.3f, w * 0.14f), strokeWidth = s.width, cap = StrokeCap.Round)
        val up =
            Path().apply {
                moveTo(w * 0.1f, w * 0.34f)
                lineTo(w * 0.3f, w * 0.12f)
                lineTo(w * 0.5f, w * 0.34f)
            }
        drawPath(up, tint, style = s)
        // 오른쪽: 위에서 아래로
        drawLine(tint, Offset(w * 0.7f, w * 0.12f), Offset(w * 0.7f, w * 0.86f), strokeWidth = s.width, cap = StrokeCap.Round)
        val down =
            Path().apply {
                moveTo(w * 0.5f, w * 0.66f)
                lineTo(w * 0.7f, w * 0.88f)
                lineTo(w * 0.9f, w * 0.66f)
            }
        drawPath(down, tint, style = s)
    }
}

/** `sparkles` — 큰 네 갈래 별 하나와 작은 별 둘. */
@Composable
fun SparklesIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension

        fun star(
            cx: Float,
            cy: Float,
            r: Float,
        ) {
            val k = r * 0.28f
            val p =
                Path().apply {
                    moveTo(cx, cy - r)
                    quadraticTo(cx + k, cy - k, cx + r, cy)
                    quadraticTo(cx + k, cy + k, cx, cy + r)
                    quadraticTo(cx - k, cy + k, cx - r, cy)
                    quadraticTo(cx - k, cy - k, cx, cy - r)
                    close()
                }
            drawPath(p, tint)
        }
        star(w * 0.42f, w * 0.56f, w * 0.38f)
        star(w * 0.8f, w * 0.2f, w * 0.17f)
        star(w * 0.82f, w * 0.78f, w * 0.12f)
    }
}

/** `chevron.right` — 가는 꺾쇠. */
@Composable
fun ChevronRightIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.34f, w * 0.14f)
                lineTo(w * 0.7f, w * 0.5f)
                lineTo(w * 0.34f, w * 0.86f)
            }
        drawPath(p, tint, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** `person` — 동그란 머리와 둥근 어깨선. Material 의 Person 은 머리가 작고 몸이 네모났다. */
@Composable
fun PersonOutlineIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawCircle(tint, radius = w * 0.2f, center = Offset(w * 0.5f, w * 0.3f), style = s)
        val body =
            Path().apply {
                moveTo(w * 0.12f, w * 0.92f)
                cubicTo(w * 0.12f, w * 0.6f, w * 0.88f, w * 0.6f, w * 0.88f, w * 0.92f)
                close()
            }
        drawPath(body, tint, style = s)
    }
}

/** `hand.draw` — 검지를 편 손과 그 끝에서 이어지는 구불구불한 선(직접 그린다). */
@Composable
fun HandDrawIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val hand =
            Path().apply {
                // 검지(왼쪽 위로) → 손등 → 손목
                moveTo(w * 0.42f, w * 0.12f)
                cubicTo(w * 0.36f, w * 0.06f, w * 0.28f, w * 0.1f, w * 0.3f, w * 0.18f)
                lineTo(w * 0.42f, w * 0.52f)
                cubicTo(w * 0.36f, w * 0.5f, w * 0.26f, w * 0.52f, w * 0.3f, w * 0.62f)
                cubicTo(w * 0.38f, w * 0.78f, w * 0.52f, w * 0.9f, w * 0.72f, w * 0.88f)
                cubicTo(w * 0.9f, w * 0.86f, w * 0.94f, w * 0.7f, w * 0.88f, w * 0.52f)
                lineTo(w * 0.8f, w * 0.34f)
                cubicTo(w * 0.76f, w * 0.26f, w * 0.66f, w * 0.28f, w * 0.64f, w * 0.34f)
                cubicTo(w * 0.6f, w * 0.28f, w * 0.52f, w * 0.3f, w * 0.52f, w * 0.36f)
                close()
            }
        drawPath(hand, tint, style = s)
        val squiggle =
            Path().apply {
                moveTo(w * 0.04f, w * 0.34f)
                cubicTo(w * 0.1f, w * 0.2f, w * 0.18f, w * 0.44f, w * 0.24f, w * 0.3f)
            }
        drawPath(squiggle, tint, style = s)
    }
}

/** `checkmark` — 굵은 체크. */
@Composable
fun CheckmarkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.1f, w * 0.52f)
                lineTo(w * 0.38f, w * 0.8f)
                lineTo(w * 0.92f, w * 0.18f)
            }
        drawPath(p, tint, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
