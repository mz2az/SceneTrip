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

/** `arrow.uturn.left` — 위에서 **왼쪽을 가리키고**, 오른쪽에서 둥글게 돌아 아래 줄로 되돌아오는 U자(iOS 확대 실측). */
@Composable
fun UTurnLeftIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val p =
            Path().apply {
                moveTo(w * 0.12f, w * 0.3f)
                lineTo(w * 0.62f, w * 0.3f)
                cubicTo(w * 0.94f, w * 0.3f, w * 0.94f, w * 0.84f, w * 0.62f, w * 0.84f)
                lineTo(w * 0.34f, w * 0.84f)
            }
        drawPath(p, tint, style = s)
        val head =
            Path().apply {
                moveTo(w * 0.32f, w * 0.1f)
                lineTo(w * 0.12f, w * 0.3f)
                lineTo(w * 0.32f, w * 0.5f)
            }
        drawPath(head, tint, style = s)
    }
}

/** `location` / `location.fill` — 오른쪽 위를 가리키는 내비 화살표. */
@Composable
fun LocationArrowIcon(
    tint: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.9f, w * 0.1f)
                lineTo(w * 0.1f, w * 0.46f)
                lineTo(w * 0.5f, w * 0.52f)
                lineTo(w * 0.56f, w * 0.9f)
                close()
            }
        if (filled) drawPath(p, tint) else drawPath(p, tint, style = stroke(w))
    }
}

/** `arrow.down` */
@Composable
fun ArrowDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawLine(tint, Offset(w * 0.5f, w * 0.1f), Offset(w * 0.5f, w * 0.88f), strokeWidth = s.width, cap = StrokeCap.Round)
        val head =
            Path().apply {
                moveTo(w * 0.2f, w * 0.6f)
                lineTo(w * 0.5f, w * 0.9f)
                lineTo(w * 0.8f, w * 0.6f)
            }
        drawPath(head, tint, style = s)
    }
}

/** `line.3.horizontal` — 끌기 손잡이. */
@Composable
fun GripLinesIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val sw = w * 0.1f
        // iOS 글리프는 11.7×5.7 — 선 사이가 좁다.
        listOf(0.26f, 0.5f, 0.74f).map { 0.5f + (it - 0.5f) * 0.9f }.forEach { y ->
            drawLine(tint, Offset(w * 0.1f, w * y), Offset(w * 0.9f, w * y), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

/**
 * `arrow.down.right.and.arrow.up.left` — 창 줄이기: **왼쪽 위에서 아래오른쪽으로, 오른쪽 아래에서 위왼쪽으로**
 * 두 화살이 가운데로 모인다(↘ ↖). 반대 대각선으로 그렸더니 iOS 와 달랐다(18차 대조).
 */
@Composable
fun CollapseArrowsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 왼쪽 위 → 가운데(↘), 화살촉은 가운데 쪽
        // 가운데에 틈(약 0.2w)을 둔다 — 화살촉이 맞닿으면 X 로 보였다(19차).
        drawLine(tint, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.4f, w * 0.4f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.4f, w * 0.18f)
                lineTo(w * 0.4f, w * 0.4f)
                lineTo(w * 0.18f, w * 0.4f)
            },
            tint,
            style = s,
        )
        // 오른쪽 아래 → 가운데(↖)
        drawLine(tint, Offset(w * 0.88f, w * 0.88f), Offset(w * 0.6f, w * 0.6f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.6f, w * 0.82f)
                lineTo(w * 0.6f, w * 0.6f)
                lineTo(w * 0.82f, w * 0.6f)
            },
            tint,
            style = s,
        )
    }
}

/** `arrow.up.circle.fill` — 채운 원 안의 흰 위쪽 화살표(보내기). */
@Composable
fun SendArrowCircleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    // SF `.fill` 기호의 화살은 **뚫려 있어** 바탕이 비친다 — 흰색이 아니라 바탕색을 준다.
    arrow: Color = Color.White,
) {
    Canvas(modifier) {
        val w = size.minDimension
        drawCircle(tint, radius = w / 2)
        val s = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(arrow, Offset(w * 0.5f, w * 0.74f), Offset(w * 0.5f, w * 0.28f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.3f, w * 0.46f)
                lineTo(w * 0.5f, w * 0.26f)
                lineTo(w * 0.7f, w * 0.46f)
            },
            arrow,
            style = s,
        )
    }
}
