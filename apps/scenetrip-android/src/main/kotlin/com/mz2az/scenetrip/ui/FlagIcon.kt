package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * SF Symbols `flag`(출발 고정) — 정지점 줄의 출발/도착 고정 배지. 깃대 + 세모 깃발.
 * `flag.checkered`(도착)까지는 이 크기(9~10dp)에서 체크무늬가 안 읽혀 같은 모양을
 * 쓴다 — "출발"/"도착" 글자가 이미 구분해 준다.
 */
@Composable
fun FlagIcon(
    tint: Color,
    modifier: Modifier = Modifier,
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
        val flag =
            Path().apply {
                moveTo(w * 0.20f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.28f)
                lineTo(w * 0.20f, h * 0.48f)
                close()
            }
        drawPath(flag, color = tint)
    }
}
