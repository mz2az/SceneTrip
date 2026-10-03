package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * 여행 안내 구간 칩의 이동수단 아이콘 — SF Symbols `figure.walk`/`bus.fill`/`tram.fill`/
 * `train.side.front.car`(iOS `RouteLegMode.symbol`, `RouteNavModels.swift`) 대응.
 * material-icons-core 49개 안에 이 넷이 없어 `FlagIcon`·`TrayIcon`과 같은 자리에
 * 직접 그린다.
 */
@Composable
fun WalkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.60f, h * 0.16f))
        drawLine(tint, Offset(w * 0.56f, h * 0.32f), Offset(w * 0.38f, h * 0.58f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, h * 0.58f), Offset(w * 0.58f, h * 0.94f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, h * 0.58f), Offset(w * 0.16f, h * 0.78f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.54f, h * 0.38f), Offset(w * 0.80f, h * 0.52f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

/** 버스 — 창이 하나로 이어진 낮은 상자 + 바퀴 둘. */
@Composable
fun BusIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.06f, h * 0.10f),
            size = Size(w * 0.88f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
        val windowY = h * 0.22f
        val windowH = h * 0.24f
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * 0.16f, windowY),
            size = Size(w * 0.68f, windowH),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.26f, h * 0.86f))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.74f, h * 0.86f))
    }
}

/** 지하철·전철 — 창 두 칸에 지붕이 둥근 좀 더 긴 차량. 버스와 실루엣으로 갈린다. */
@Composable
fun SubwayIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.02f, h * 0.08f),
            size = Size(w * 0.96f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.22f, w * 0.22f),
        )
        val windowY = h * 0.20f
        val windowH = h * 0.22f
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * 0.10f, windowY),
            size = Size(w * 0.34f, windowH),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * 0.56f, windowY),
            size = Size(w * 0.34f, windowH),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.26f, h * 0.86f))
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.74f, h * 0.86f))
    }
}

/** 종류를 모르는 대중교통(기차·고속버스·해운) — 둥근 창 하나만 있는 실루엣. */
@Composable
fun TransitIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.04f, h * 0.14f),
            size = Size(w * 0.92f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.30f, w * 0.30f),
        )
        drawCircle(Color.White, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.43f))
        drawLine(tint, Offset(w * 0.20f, h * 0.90f), Offset(w * 0.80f, h * 0.90f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    }
}

/** 계단 경고 — SF Symbols `stairs`. 층계 세 칸을 대각선으로. */
@Composable
fun StairsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = w * 0.32f
        for (i in 0..2) {
            val x0 = i * step
            val yTop = h - (i + 1) * (h / 3f)
            drawLine(tint, Offset(x0, h), Offset(x0, yTop), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            drawLine(tint, Offset(x0, yTop), Offset(x0 + step, yTop), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
        }
    }
}
