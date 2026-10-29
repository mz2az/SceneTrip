package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * SF Symbols `flag`(출발 고정) · `flag.checkered`(도착 고정) — 정지점 줄의 출발/도착 고정 배지.
 * 깃대 + 네모 물결 깃발. [checkered] 면 깃발 안을 체크무늬(4×3 칸, 대각 번갈아)로 채운다 —
 * iOS `RouteStopRow.PinKind.symbol` 이 도착에 `flag.checkered` 를 쓴다. 채운 깃발([filled])에서는
 * 흰 칸이 바탕색이 비쳐야 하므로 그 자리를 [knockout](칩 바탕색)으로 칠한다.
 */
@Composable
fun FlagIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    // iOS: 고정 안 함 = `flag`(외곽선), 고정 = `flag.fill`.
    filled: Boolean = true,
    checkered: Boolean = false,
    knockout: Color = Color.Transparent,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(
            tint,
            Offset(w * 0.14f, h * 0.05f),
            Offset(w * 0.14f, h * 0.95f),
            strokeWidth = w * 0.14f,
        )
        // SF `flag`: 깃대 오른쪽의 **네모난 물결 깃발**(윗변·아랫변이 살짝 굽는다). 세모로 그렸더니 iOS 와
        // 모양이 달랐다(16차 대조).
        val flag =
            Path().apply {
                moveTo(w * 0.20f, h * 0.08f)
                cubicTo(w * 0.42f, h * 0.0f, w * 0.62f, h * 0.18f, w * 0.90f, h * 0.08f)
                lineTo(w * 0.90f, h * 0.56f)
                cubicTo(w * 0.62f, h * 0.66f, w * 0.42f, h * 0.48f, w * 0.20f, h * 0.56f)
                close()
            }
        if (checkered) {
            // 칸은 깃발 영역 안에서만 — 물결진 윗·아랫변을 따라 잘린다.
            val left = w * 0.20f
            val right = w * 0.90f
            val top = h * 0.0f
            val bottom = h * 0.66f
            val cols = 4
            val rows = 3
            val cw = (right - left) / cols
            val ch = (bottom - top) / rows
            if (filled) drawPath(flag, color = tint)
            clipPath(flag) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if ((r + c) % 2 == 1) {
                            drawRect(
                                if (filled) knockout else tint,
                                topLeft = Offset(left + c * cw, top + r * ch),
                                size =
                                    androidx.compose.ui.geometry
                                        .Size(cw, ch),
                            )
                        }
                    }
                }
            }
            if (!filled) {
                drawPath(
                    flag,
                    color = tint,
                    style =
                        androidx.compose.ui.graphics.drawscope
                            .Stroke(width = w * 0.1f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            }
        } else if (filled) {
            drawPath(flag, color = tint)
        } else {
            drawPath(
                flag,
                color = tint,
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(width = w * 0.1f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}
