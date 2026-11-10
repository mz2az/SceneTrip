package com.mz2az.scenetrip.routetab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.DisplayMetrics
import com.naver.maps.map.overlay.OverlayImage

/**
 * 계획 화면 지도의 코스 번호 핀 (MZ2AZ-547 E11, 웹 `proto/protoPoi.tsx` 의 `protoNumberedPinSvg`, iOS `RouteNumberPin`).
 *
 * 보라 단색 동그라미(반지름 13, 흰 테두리 2)에 흰 굵은 번호, 아래 짧은 꼬리. 30×38dp, 꼬리 끝 (15, 37) — 치수는 [RouteNumberPinSpec].
 * 예전 핀(`PinImage.numbered` — 하늘→보라 물방울 속 흰 원에 보라 번호)은 크고 겹이 많았다(웹 2026-11-08 사용자 지적).
 * dp 로 굽는다 — 밀도가 무엇이든 논리 크기가 iOS 의 pt 와 같다(`PinImage` 의 같은 주석).
 */
object RouteNumberPin {
    private val cache = mutableMapOf<Pair<Int, Float>, OverlayImage>()

    fun image(
        number: Int,
        metrics: DisplayMetrics,
    ): OverlayImage {
        val s = metrics.density
        cache[number to s]?.let { return it }
        val bitmap =
            Bitmap.createBitmap(
                (RouteNumberPinSpec.WIDTH * s).toInt(),
                (RouteNumberPinSpec.HEIGHT * s).toInt(),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.scale(s, s)
        val purple = RouteNumberPinSpec.PURPLE.toInt()
        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = purple
                style = Paint.Style.FILL
                setShadowLayer(3f, 0f, 2f, 0x4D000000) // 검정 30%
            }
        val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeJoin = Paint.Join.ROUND
            }
        // 꼬리(흰 테두리) → 동그라미(흰 테두리) → 꼬리 안쪽을 다시 칠해 둘 사이의 흰 줄을 지운다(웹 SVG·iOS 와 같은 순서).
        val tail =
            Path().apply {
                moveTo(10f, 25f)
                lineTo(15f, 37f)
                lineTo(20f, 25f)
                close()
            }
        canvas.drawPath(tail, fill)
        canvas.drawPath(tail, stroke)
        canvas.drawCircle(15f, 14f, 13f, fill)
        canvas.drawCircle(15f, 14f, 13f, stroke)
        val seam =
            Path().apply {
                moveTo(11f, 25.5f)
                lineTo(19f, 25.5f)
                lineTo(15f, 34f)
                close()
            }
        fill.clearShadowLayer()
        canvas.drawPath(seam, fill)

        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = RouteNumberPinSpec.fontSize(number)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        // 글자 가운데를 (15, 14.5) 에 — 기준선은 글꼴 높이의 반만큼 아래.
        val metricsText = text.fontMetrics
        canvas.drawText("$number", 15f, 14.5f - (metricsText.ascent + metricsText.descent) / 2f, text)
        val image = OverlayImage.fromBitmap(bitmap)
        cache[number to s] = image
        return image
    }
}
