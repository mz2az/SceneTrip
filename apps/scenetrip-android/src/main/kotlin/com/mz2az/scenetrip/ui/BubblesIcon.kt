package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * SF Symbols `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다. 탭바의 커뮤니티
 * 아이콘([com.mz2az.scenetrip.RootTabs])과 커뮤니티 탭의 빈 상태(iOS
 * `ContentUnavailableView`의 같은 심벌)가 같이 쓴다 — material-icons-core 에 말풍선
 * 쌍이 없고, 아이콘 하나 때문에 material-icons-extended 를 더할 것이 아니라서 직접
 * 그린다.
 */
@Composable
fun BubblesIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // **모서리 반지름은 말풍선 자신의 높이에 붙인다.** 예전엔 캔버스 너비의
        // 16%(`w * 0.16f`)를 썼는데, 말풍선 높이(`h * 0.42f`)의 76%나 돼서 탭바의
        // 20dp에서는 안티에일리어싱에 묻혀 안 보였지만 빈 상태의 52dp로 키우니
        // 모서리 두 쪽이 맞닿아 사슬고리처럼 보였다(2026-09-28 실측). 말풍선
        // 높이에 비례해 둬야 아이콘 크기가 달라져도 "말풍선"으로 읽힌다.
        // iOS 글리프를 확대해 좌표를 옮겼다(2026-09-28, 세 번째): 둥근 사각 말풍선 둘, **꼬리는 몸통에
        // 붙은 삼각형**(뒤는 왼쪽 아래, 앞은 오른쪽 아래), 뒤 말풍선은 앞 말풍선에 가려진다. 꼬리를
        // 떨어진 사선으로 그렸더니 돋보기처럼 보였다. 한 획의 경로라 반투명 색도 겹쳐 진해지지 않는다.
        val stroke = w * 0.06f
        val back =
            Path().apply {
                val l = 0.03f * w
                val t = 0.02f * h
                val r = 0.76f * w
                val b = 0.69f * h
                val c = 0.14f * w
                moveTo(l + c, t)
                lineTo(r - c, t)
                quadraticTo(r, t, r, t + c)
                lineTo(r, b - c)
                quadraticTo(r, b, r - c, b)
                lineTo(0.36f * w, b)
                lineTo(0.2f * w, 0.88f * h)
                lineTo(0.2f * w, b)
                lineTo(l + c, b)
                quadraticTo(l, b, l, b - c)
                lineTo(l, t + c)
                quadraticTo(l, t, l + c, t)
                close()
            }
        val frontBox = Rect(Offset(0.38f * w, 0.24f * h), Offset(0.97f * w, 0.82f * h))
        val front =
            Path().apply {
                val l = frontBox.left
                val t = frontBox.top
                val r = frontBox.right
                val b = frontBox.bottom
                val c = 0.13f * w
                moveTo(l + c, t)
                lineTo(r - c, t)
                quadraticTo(r, t, r, t + c)
                lineTo(r, b - c)
                quadraticTo(r, b, r - c, b)
                lineTo(0.82f * w, b)
                lineTo(0.82f * w, 0.98f * h)
                lineTo(0.62f * w, b)
                lineTo(l + c, b)
                quadraticTo(l, b, l, b - c)
                lineTo(l, t + c)
                quadraticTo(l, t, l + c, t)
                close()
            }
        val cover =
            Path().apply {
                addRoundRect(RoundRect(frontBox.inflate(stroke), CornerRadius(0.13f * w + stroke)))
            }
        val style = Stroke(width = stroke, join = StrokeJoin.Round)
        clipPath(cover, clipOp = ClipOp.Difference) {
            drawPath(back, tint, style = style)
        }
        drawPath(front, tint, style = style)
    }
}
