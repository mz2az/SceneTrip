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
 * SF Symbols `leaf.fill` — "널널하게" 페이스. 잎맥 하나를 곁들인 물방울 모양.
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
        // iOS 잎은 더 납작하다(18×15) — 세로를 88% 로 눌러 칸 가운데에 둔다.
        val h = size.height * 0.88f
        translate(top = size.height * 0.06f) {
            val leaf =
                Path().apply {
                    moveTo(w * 0.07f, h * 0.12f)
                    cubicTo(w * 0.32f, h * 0.08f, w * 0.76f, h * 0.06f, w * 0.9f, h * 0.36f)
                    cubicTo(w * 0.98f, h * 0.56f, w * 0.84f, h * 0.73f, w * 0.55f, h * 0.73f)
                    cubicTo(w * 0.24f, h * 0.73f, w * 0.05f, h * 0.5f, w * 0.07f, h * 0.12f)
                    close()
                }
            drawPath(leaf, color = tint)
            // 잎맥은 밑동까지 이어지고, 줄기는 **잎맥을 뚫은 뒤에** 그려 끊기지 않게 붙인다(iOS 확대 실측).
            val vein =
                Path().apply {
                    moveTo(w * 0.3f, h * 0.36f)
                    cubicTo(w * 0.5f, h * 0.3f, w * 0.56f, h * 0.54f, w * 0.8f, h * 0.64f)
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
