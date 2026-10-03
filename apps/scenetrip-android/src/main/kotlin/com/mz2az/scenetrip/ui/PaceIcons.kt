package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer

/**
 * SF Symbols `bolt.fill` — "빡빡하게" 페이스. material-icons-core(49개뿐)에 번개가
 * 없어 직접 그린다.
 */
@Composable
fun BoltIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                moveTo(w * 0.58f, 0f)
                lineTo(w * 0.10f, h * 0.58f)
                lineTo(w * 0.42f, h * 0.58f)
                lineTo(w * 0.34f, h)
                lineTo(w * 0.90f, h * 0.40f)
                lineTo(w * 0.56f, h * 0.40f)
                close()
            }
        drawPath(path, color = tint)
    }
}

/**
 * SF Symbols `leaf.fill` — "널널하게" 페이스.
 */
@Composable
fun LeafIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // SF `leaf.fill` 를 확대해 좌표를 옮겼다(2026-09-28, 세 번째): **뾰족한 끝이 왼쪽 위**, 둥근 쪽이
    // 오른쪽, 줄기는 **오른쪽 아래로** 휘어 내려가고, 잎맥은 흰 **파임**(S 곡선)이다. 파임은 바탕을
    // 드러내야 선택 상태(흰 잎·파란 바탕)에서도 맞으므로 `BlendMode.Clear` 로 뚫는다.
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val w = size.width
        // iOS 잎은 더 납작하다(18×15.3) — 세로를 94% 로 눌러 칸 가운데에 둔다(88% 는 14.1 로 과했다).
        val h = size.height * 0.94f
        translate(top = size.height * 0.03f) {
            val leaf =
                Path().apply {
                    // 윗변이 **볼록하게 부푼다** — 평평한 윗변은 잎이 아니라 조각처럼 보였다(7차 대조).
                    moveTo(w * 0.04f, h * 0.08f)
                    cubicTo(w * 0.3f, h * -0.04f, w * 0.72f, h * 0.0f, w * 0.9f, h * 0.3f)
                    cubicTo(w * 1.0f, h * 0.52f, w * 0.88f, h * 0.76f, w * 0.56f, h * 0.76f)
                    cubicTo(w * 0.24f, h * 0.76f, w * 0.04f, h * 0.54f, w * 0.04f, h * 0.08f)
                    close()
                }
            drawPath(leaf, color = tint)
            // 잎맥은 밑동까지 이어지고, 줄기는 **잎맥을 뚫은 뒤에** 그려 끊기지 않게 붙인다(iOS 확대 실측).
            val vein =
                Path().apply {
                    moveTo(w * 0.26f, h * 0.3f)
                    cubicTo(w * 0.5f, h * 0.2f, w * 0.48f, h * 0.6f, w * 0.8f, h * 0.66f)
                }
            drawPath(vein, Color.Black, style = Stroke(width = w * 0.1f, cap = StrokeCap.Round), blendMode = BlendMode.Clear)
            val stem =
                Path().apply {
                    moveTo(w * 0.78f, h * 0.62f)
                    quadraticTo(w * 0.93f, h * 0.62f, w * 0.97f, h * 0.84f)
                }
            drawPath(stem, tint, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
        }
    }
}
