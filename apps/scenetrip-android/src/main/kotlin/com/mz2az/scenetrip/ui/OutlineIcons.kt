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
 * SF Symbols 의 **외곽선** 아이콘들. material-icons-core(49개)의 대응물은 전부 채운 모양이라
 * iOS 와 나란히 두면 무게가 달라 보인다(2026-09-28 화면 대조 #12·#19). 선 굵기는 크기의 약 9%.
 */

private fun stroke(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 사진 추가 칸의 외곽선 사진과 오른쪽 아래 더하기 배지. */
@Composable
fun PhotoAddIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            tint,
            Offset(w * 0.06f, h * 0.08f),
            Size(w * 0.74f, h * 0.62f),
            androidx.compose.ui.geometry
                .CornerRadius(w * 0.07f),
            style = Stroke(w * 0.07f),
        )
        drawCircle(tint, w * 0.065f, Offset(w * 0.25f, h * 0.26f))
        val mountains =
            Path().apply {
                moveTo(w * 0.08f, h * 0.63f)
                lineTo(w * 0.3f, h * 0.41f)
                lineTo(w * 0.43f, h * 0.54f)
                lineTo(w * 0.6f, h * 0.36f)
                lineTo(w * 0.77f, h * 0.53f)
                lineTo(w * 0.77f, h * 0.65f)
                close()
            }
        drawPath(mountains, tint)
        val badge = Offset(w * 0.79f, h * 0.75f)
        drawCircle(IOS.systemGray6, w * 0.24f, badge)
        drawCircle(tint, w * 0.19f, badge, style = Stroke(w * 0.055f))
        drawLine(tint, Offset(w * 0.69f, h * 0.75f), Offset(w * 0.89f, h * 0.75f), w * 0.05f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.79f, h * 0.65f), Offset(w * 0.79f, h * 0.85f), w * 0.05f, StrokeCap.Round)
    }
}

@Composable
fun MapSearchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = h * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val map =
            Path().apply {
                moveTo(w * 0.06f, h * 0.2f)
                lineTo(w * 0.28f, h * 0.07f)
                lineTo(w * 0.5f, h * 0.2f)
                lineTo(w * 0.72f, h * 0.07f)
                lineTo(w * 0.72f, h * 0.65f)
                lineTo(w * 0.5f, h * 0.78f)
                lineTo(w * 0.28f, h * 0.65f)
                lineTo(w * 0.06f, h * 0.78f)
                close()
                moveTo(w * 0.5f, h * 0.2f)
                lineTo(w * 0.5f, h * 0.78f)
                moveTo(w * 0.28f, h * 0.07f)
                lineTo(w * 0.28f, h * 0.65f)
            }
        drawPath(map, tint, style = s)
        drawCircle(IOS.systemBackground, radius = h * 0.3f, center = Offset(w * 0.72f, h * 0.64f))
        drawCircle(tint, radius = h * 0.22f, center = Offset(w * 0.72f, h * 0.64f), style = s)
        drawLine(tint, Offset(w * 0.84f, h * 0.8f), Offset(w * 0.96f, h * 0.97f), strokeWidth = s.width, cap = StrokeCap.Round)
    }
}

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

/** `plus.magnifyingglass`(더하기) · `minus.magnifyingglass`(빼기) — 돋보기 렌즈 안에 가로(+세로)선. */
@Composable
fun MagnifierZoomIcon(
    tint: Color,
    plus: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val c = Offset(w * 0.42f, w * 0.42f)
        drawCircle(tint, radius = w * 0.34f, center = c, style = s)
        drawLine(tint, Offset(w * 0.67f, w * 0.67f), Offset(w * 0.93f, w * 0.93f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(c.x - w * 0.15f, c.y), Offset(c.x + w * 0.15f, c.y), strokeWidth = s.width, cap = StrokeCap.Round)
        if (plus) {
            drawLine(tint, Offset(c.x, c.y - w * 0.15f), Offset(c.x, c.y + w * 0.15f), strokeWidth = s.width, cap = StrokeCap.Round)
        }
    }
}

/** `xmark` — 굵은 엑스. 닫기. */
@Composable
fun XmarkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val sw = w * 0.16f
        val a = w * 0.08f
        val b = w * 0.92f
        drawLine(tint, Offset(a, a), Offset(b, b), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(b, a), Offset(a, b), strokeWidth = sw, cap = StrokeCap.Round)
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

/**
 * `arrow.triangle.swap` — 한 줄이 ∩∪ 로 굽이치고 양 끝이 화살표(왼쪽 끝은 아래, 오른쪽 끝은 위).
 * 곧은 ⇅ 두 개로 그렸더니 모양이 한눈에 달랐다(2차 대조).
 */
@Composable
fun SwapArrowsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val r = w * 0.15f
        val wave =
            Path().apply {
                moveTo(w * 0.2f, w * 0.86f)
                lineTo(w * 0.2f, w * 0.34f)
                // ∩ — 왼쪽 기둥 꼭대기에서 가운데 기둥으로
                cubicTo(w * 0.2f, w * 0.34f - r * 1.33f, w * 0.5f, w * 0.34f - r * 1.33f, w * 0.5f, w * 0.34f)
                lineTo(w * 0.5f, w * 0.66f)
                // ∪ — 가운데 기둥 바닥에서 오른쪽 기둥으로
                cubicTo(w * 0.5f, w * 0.66f + r * 1.33f, w * 0.8f, w * 0.66f + r * 1.33f, w * 0.8f, w * 0.66f)
                lineTo(w * 0.8f, w * 0.14f)
            }
        drawPath(wave, tint, style = s)
        val down =
            Path().apply {
                moveTo(w * 0.06f, w * 0.7f)
                lineTo(w * 0.2f, w * 0.88f)
                lineTo(w * 0.34f, w * 0.7f)
            }
        drawPath(down, tint, style = s)
        val up =
            Path().apply {
                moveTo(w * 0.66f, w * 0.3f)
                lineTo(w * 0.8f, w * 0.12f)
                lineTo(w * 0.94f, w * 0.3f)
            }
        drawPath(up, tint, style = s)
    }
}

/**
 * `sparkles` — 가는 네 갈래 별 셋(큰 것 하나는 오른쪽 아래, 작은 둘은 왼쪽 위). 좌표는 iOS 15pt 글리프를 감사 스크린샷
 * (2026-10-29)에서 쟀다. 별의 옆구리를 가늘게 오므린 모양(꼭짓점이 뾰족하다)이라 이전의 통통한 마름모와 달랐다.
 */
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
            val k = r * 0.08f
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
        star(w * 0.583f, w * 0.628f, w * 0.355f)
        star(w * 0.267f, w * 0.294f, w * 0.17f)
        star(w * 0.5f, w * 0.056f, w * 0.117f)
    }
}

/**
 * `chevron.right` — 가는 꺾쇠. [weight] 는 선 굵기(크기 대비). 작게 쓸 때는 기본값, 17pt 본문 크기의 큰 단추에서는
 * 같은 굵기로는 두꺼워 보이므로 줄여 쓴다. 왼쪽(`chevron.left`)은 180도 돌려 쓴다.
 */
@Composable
fun ChevronRightIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    weight: Float = 0.14f,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.34f, w * 0.14f)
                lineTo(w * 0.7f, w * 0.5f)
                lineTo(w * 0.34f, w * 0.86f)
            }
        drawPath(p, tint, style = Stroke(width = w * weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
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

/** `exclamationmark.circle` — 원 안의 느낌표. 불러오기 실패 같은 가벼운 경고 줄에 쓴다. */
@Composable
fun ExclamationCircleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        drawCircle(tint, radius = w * 0.44f, center = Offset(w * 0.5f, w * 0.5f), style = s)
        drawLine(tint, Offset(w * 0.5f, w * 0.26f), Offset(w * 0.5f, w * 0.56f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.5f, w * 0.72f))
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

/**
 * `location` / `location.fill` — 오른쪽 위를 가리키는 내비 화살표. 좌표는 iOS 15pt 글리프(폭 17 × 높이 16.3)를 감사
 * 스크린샷(2026-10-29)에서 쟀다 — 가는 선, 끝이 둥글다.
 */
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
                moveTo(w * 0.95f, w * 0.06f)
                lineTo(w * 0.06f, w * 0.46f)
                lineTo(w * 0.5f, w * 0.55f)
                lineTo(w * 0.53f, w * 0.94f)
                close()
            }
        if (filled) {
            drawPath(p, tint)
        } else {
            drawPath(p, tint, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * `square.grid.2x2` — 속이 빈 둥근 칸 넷(사진 수 알약의 「전체 보기」 표시). 9칸 글자 ▦ 가 아니다(2026-10-29 감사 #19).
 */
@Composable
fun SquareGrid2x2Icon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(width = w * 0.1f, join = StrokeJoin.Round)
        val cell = w * 0.38f
        val near = w * 0.06f
        val far = w - near - cell
        listOf(near, far).forEach { y ->
            listOf(near, far).forEach { x ->
                drawRoundRect(tint, topLeft = Offset(x, y), size = Size(cell, cell), cornerRadius = CornerRadius(w * 0.1f), style = st)
            }
        }
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

/** `text.alignleft` — 왼쪽 맞춤 네 줄(길게·짧게·길게·짧게). 장면 카드 설명 영역의 「더 보기」 표시. */
@Composable
fun TextAlignLeftIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = h * 0.12f
        listOf(0.1f to 1f, 0.37f to 0.62f, 0.63f to 1f, 0.9f to 0.62f).forEach { (y, length) ->
            drawLine(tint, Offset(sw / 2, h * y), Offset(w * length - sw / 2, h * y), strokeWidth = sw, cap = StrokeCap.Round)
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

/** `camera` — 둥근 몸통, 위 가운데 턱, 가운데 렌즈. */
@Composable
fun CameraIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val body =
            Path().apply {
                moveTo(w * 0.1f, w * 0.36f)
                lineTo(w * 0.1f, w * 0.78f)
                quadraticTo(w * 0.1f, w * 0.86f, w * 0.18f, w * 0.86f)
                lineTo(w * 0.82f, w * 0.86f)
                quadraticTo(w * 0.9f, w * 0.86f, w * 0.9f, w * 0.78f)
                lineTo(w * 0.9f, w * 0.36f)
                quadraticTo(w * 0.9f, w * 0.28f, w * 0.82f, w * 0.28f)
                lineTo(w * 0.68f, w * 0.28f)
                lineTo(w * 0.6f, w * 0.16f)
                lineTo(w * 0.4f, w * 0.16f)
                lineTo(w * 0.32f, w * 0.28f)
                lineTo(w * 0.18f, w * 0.28f)
                quadraticTo(w * 0.1f, w * 0.28f, w * 0.1f, w * 0.36f)
                close()
            }
        drawPath(body, tint, style = s)
        drawCircle(tint, radius = w * 0.15f, center = Offset(w * 0.5f, w * 0.56f), style = s)
    }
}

/** `chevron.up.chevron.down` — iOS 메뉴 `Picker` 값 옆의 위아래 꺾쇠. */
@Composable
fun ChevronUpDownIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val up =
            Path().apply {
                moveTo(w * 0.28f, w * 0.38f)
                lineTo(w * 0.5f, w * 0.16f)
                lineTo(w * 0.72f, w * 0.38f)
            }
        val down =
            Path().apply {
                moveTo(w * 0.28f, w * 0.62f)
                lineTo(w * 0.5f, w * 0.84f)
                lineTo(w * 0.72f, w * 0.62f)
            }
        drawPath(up, tint, style = st)
        drawPath(down, tint, style = st)
    }
}

/** `arrow.clockwise`(↻) — 오른쪽 위가 트인 원호, 12시 끝에 오른쪽을 향한 화살촉. */
@Composable
fun ArrowClockwiseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val inset = w * 0.14f
        // 2시(-30°)에서 시계 방향으로 300° 돌아 12시에서 끝난다 — 화살촉이 그 끝에 붙는다.
        drawArc(
            tint,
            startAngle = -30f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset + w * 0.04f),
            size = Size(w - inset * 2, w - inset * 2),
            style = st,
        )
        val head =
            Path().apply {
                moveTo(w * 0.5f, w * 0.02f)
                lineTo(w * 0.72f, w * 0.18f)
                lineTo(w * 0.5f, w * 0.34f)
                close()
            }
        drawPath(head, tint)
    }
}

/**
 * `arrow.up.left.and.arrow.down.right` — 씬 카드 사진의 확대 표시. 왼쪽 위·오른쪽 아래 모서리를 향한
 * 화살표 둘이 가운데에서 갈라져 있고, 촉은 각 모서리를 낀 ㄱ자 두 획이다.
 */
@Composable
fun ArrowsExpandIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val a = w * 0.08f
        val b = w * 0.92f
        val head = w * 0.34f
        val shaft = w * 0.42f
        val topLeft =
            Path().apply {
                moveTo(a, a + head)
                lineTo(a, a)
                lineTo(a + head, a)
                moveTo(a, a)
                lineTo(shaft, shaft)
            }
        val bottomRight =
            Path().apply {
                moveTo(b, b - head)
                lineTo(b, b)
                lineTo(b - head, b)
                moveTo(b, b)
                lineTo(w - shaft, w - shaft)
            }
        drawPath(topLeft, tint, style = st)
        drawPath(bottomRight, tint, style = st)
    }
}

/**
 * `arrow.up.right.square` — 둥근 네모 안에서 오른쪽 위로 향하는 화살표. 작품 포스터의 제작사 페이지 링크.
 * 네모는 닫혀 있고(`square.and.arrow.up` 처럼 열리지 않는다) 화살표는 네모 안쪽에 머문다.
 */
@Composable
fun ArrowUpRightSquareIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val inset = w * 0.06f
        drawRoundRect(
            tint,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2, w - inset * 2),
            cornerRadius = CornerRadius(w * 0.2f),
            style = st,
        )
        val arrow =
            Path().apply {
                moveTo(w * 0.32f, w * 0.68f)
                lineTo(w * 0.68f, w * 0.32f)
                moveTo(w * 0.4f, w * 0.32f)
                lineTo(w * 0.68f, w * 0.32f)
                lineTo(w * 0.68f, w * 0.6f)
            }
        drawPath(arrow, tint, style = st)
    }
}

/** `cart` — 왼쪽 위 손잡이에서 내려오는 바구니(아래가 좁은 사다리꼴)와 바퀴 둘. */
@Composable
fun CartIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val basket =
            Path().apply {
                moveTo(w * 0.04f, w * 0.14f)
                lineTo(w * 0.18f, w * 0.14f)
                lineTo(w * 0.3f, w * 0.64f)
                lineTo(w * 0.8f, w * 0.64f)
                lineTo(w * 0.92f, w * 0.28f)
                lineTo(w * 0.22f, w * 0.28f)
            }
        drawPath(basket, tint, style = s)
        drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.35f, w * 0.83f))
        drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.76f, w * 0.83f))
    }
}

/** `rectangle.portrait.and.arrow.right` — 세로 네모(오른쪽이 열림)에서 오른쪽으로 나가는 화살표. 로그아웃. */
@Composable
fun SignOutIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = stroke(w)
        val door =
            Path().apply {
                moveTo(w * 0.56f, w * 0.3f)
                lineTo(w * 0.56f, w * 0.2f)
                quadraticTo(w * 0.56f, w * 0.12f, w * 0.48f, w * 0.12f)
                lineTo(w * 0.2f, w * 0.12f)
                quadraticTo(w * 0.12f, w * 0.12f, w * 0.12f, w * 0.2f)
                lineTo(w * 0.12f, w * 0.8f)
                quadraticTo(w * 0.12f, w * 0.88f, w * 0.2f, w * 0.88f)
                lineTo(w * 0.48f, w * 0.88f)
                quadraticTo(w * 0.56f, w * 0.88f, w * 0.56f, w * 0.8f)
                lineTo(w * 0.56f, w * 0.7f)
            }
        drawPath(door, tint, style = s)
        drawLine(tint, Offset(w * 0.36f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), strokeWidth = s.width, cap = StrokeCap.Round)
        val head =
            Path().apply {
                moveTo(w * 0.74f, w * 0.34f)
                lineTo(w * 0.9f, w * 0.5f)
                lineTo(w * 0.74f, w * 0.66f)
            }
        drawPath(head, tint, style = s)
    }
}

/**
 * `sparkles.rectangle.stack` — 앞의 둥근 사각 틀 안에 네 갈래 별 둘(큰 것·작은 것), 틀 뒤로 겹친 두 장의 윗변.
 * 좌표는 마이페이지 「AI 여행 릴스」 줄의 26×26 글리프를 재감사 스크린샷(2026-10-30)에서 쟀다(칸 크기 대비 비율).
 */
@Composable
fun SparklesRectangleStackIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val sw = w * 0.045f
        val s = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 뒤에 겹친 두 장 — 윗변만 보인다.
        drawLine(tint, Offset(w * 0.32f, w * 0.185f), Offset(w * 0.67f, w * 0.185f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.26f, w * 0.26f), Offset(w * 0.73f, w * 0.26f), strokeWidth = sw, cap = StrokeCap.Round)
        drawRoundRect(tint, Offset(w * 0.2f, w * 0.34f), Size(w * 0.55f, w * 0.45f), CornerRadius(w * 0.07f), style = s)

        fun star(
            cx: Float,
            cy: Float,
            r: Float,
        ) {
            val k = r * 0.12f
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
        star(w * 0.52f, w * 0.55f, w * 0.115f)
        star(w * 0.385f, w * 0.65f, w * 0.055f)
    }
}

/**
 * `trash.fill` — 밀어서 지우기 단추의 휴지통. 채운 통 몸에 세로 틈 셋, 위에 뚜껑과 손잡이. 틈은 [cut] 색(단추 바탕)으로 긋는다.
 * 좌표는 iOS 코스 목록 단추의 글리프(가로 16.6×세로 19pt)를 감사 스크린샷(2026-10-30)에서 쟀다.
 */
@Composable
fun TrashFillIcon(
    tint: Color,
    cut: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val handle =
            Path().apply {
                moveTo(w * 0.38f, w * 0.2f)
                lineTo(w * 0.38f, w * 0.1f)
                lineTo(w * 0.62f, w * 0.1f)
                lineTo(w * 0.62f, w * 0.2f)
            }
        drawPath(handle, tint, style = Stroke(width = w * 0.09f, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawLine(tint, Offset(w * 0.1f, w * 0.24f), Offset(w * 0.9f, w * 0.24f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        val bin =
            Path().apply {
                moveTo(w * 0.2f, w * 0.34f)
                lineTo(w * 0.8f, w * 0.34f)
                lineTo(w * 0.75f, w * 0.88f)
                quadraticTo(w * 0.74f, w * 0.95f, w * 0.67f, w * 0.95f)
                lineTo(w * 0.33f, w * 0.95f)
                quadraticTo(w * 0.26f, w * 0.95f, w * 0.25f, w * 0.88f)
                close()
            }
        drawPath(bin, tint)
        for (x in listOf(0.38f, 0.5f, 0.62f)) {
            drawLine(cut, Offset(w * x, w * 0.46f), Offset(w * x, w * 0.83f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        }
    }
}
