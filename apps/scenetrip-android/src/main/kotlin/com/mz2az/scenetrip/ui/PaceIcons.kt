package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

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
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val leaf =
            Path().apply {
                moveTo(w * 0.5f, h * 0.02f)
                cubicTo(w * 0.98f, h * 0.10f, w * 0.98f, h * 0.65f, w * 0.5f, h * 0.98f)
                cubicTo(w * 0.02f, h * 0.65f, w * 0.02f, h * 0.10f, w * 0.5f, h * 0.02f)
                close()
            }
        drawPath(leaf, color = tint)
        val vein =
            Path().apply {
                moveTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.5f, h * 0.92f)
            }
        drawPath(
            vein,
            color = Color.White.copy(alpha = 0.55f),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = w * 0.06f),
        )
    }
}
