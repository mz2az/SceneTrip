package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 바닥에서 떠 있는 탭바가 차지하는 높이 — 알약 몫 74 + 기기 내비게이션 막대 (MZ2AZ-547 T2·T3, iOS `tabBarClearance`).
 *
 * 탭 내용은 알약 뒤까지 내려가 비치므로, 목록·서랍·토스트처럼 바닥에 붙는 것은 이만큼 비운다. `RootTabs` 가 탭 내용에만
 * 넣어 준다 — 탭 밖(경로여정·마이페이지 덮개)에서는 0 이다.
 */
val LocalTabBarClearance = compositionLocalOf { 0.dp }

/** 바닥 여백 — 탭 안이면 탭바 몫, 탭 밖이면 [fallback](보통 내비게이션 막대). */
@Composable
fun tabBarBottom(fallback: Dp): Dp {
    val clearance = LocalTabBarClearance.current
    return if (clearance > 0.dp) clearance else fallback
}

/** `map` — 접힌 지도. 작품검색 탭 아이콘(MZ2AZ-547 T1, 웹 시안과 같다). */
@Composable
fun MapIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = h * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val map =
            Path().apply {
                moveTo(w * 0.06f, h * 0.22f)
                lineTo(w * 0.35f, h * 0.08f)
                lineTo(w * 0.65f, h * 0.22f)
                lineTo(w * 0.94f, h * 0.08f)
                lineTo(w * 0.94f, h * 0.78f)
                lineTo(w * 0.65f, h * 0.92f)
                lineTo(w * 0.35f, h * 0.78f)
                lineTo(w * 0.06f, h * 0.92f)
                close()
                moveTo(w * 0.35f, h * 0.08f)
                lineTo(w * 0.35f, h * 0.78f)
                moveTo(w * 0.65f, h * 0.22f)
                lineTo(w * 0.65f, h * 0.92f)
            }
        drawPath(map, tint, style = s)
    }
}

/** `paperplane.fill` — 「여행 코스 짜기」 단추. */
@Composable
fun PaperPlaneIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val plane =
            Path().apply {
                moveTo(w * 0.95f, w * 0.05f)
                lineTo(w * 0.05f, w * 0.45f)
                lineTo(w * 0.4f, w * 0.6f)
                lineTo(w * 0.55f, w * 0.95f)
                close()
            }
        if (filled) {
            drawPath(plane, tint)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(w * 0.4f, w * 0.6f), Offset(w * 0.95f, w * 0.05f), strokeWidth = w * 0.06f)
        } else {
            drawPath(plane, tint, style = Stroke(width = w * 0.09f, join = StrokeJoin.Round))
            drawLine(tint, Offset(w * 0.4f, w * 0.6f), Offset(w * 0.95f, w * 0.05f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        }
    }
}

/** `list.bullet` — 점 셋 + 줄 셋. */
@Composable
fun ListBulletIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.11f
        for (i in 0..2) {
            val y = w * (0.2f + 0.3f * i)
            drawCircle(tint, radius = w * 0.075f, center = Offset(w * 0.1f, y))
            drawLine(tint, Offset(w * 0.32f, y), Offset(w * 0.95f, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** `plus` — 굵은 더하기. */
@Composable
fun PlusIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.14f
        drawLine(tint, Offset(w * 0.5f, w * 0.1f), Offset(w * 0.5f, w * 0.9f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.1f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** `chevron.left` — 뒤로. */
@Composable
fun ChevronLeftIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val p =
            Path().apply {
                moveTo(w * 0.66f, w * 0.1f)
                lineTo(w * 0.28f, w * 0.5f)
                lineTo(w * 0.66f, w * 0.9f)
            }
        drawPath(p, tint, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
