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
    private val cache = mutableMapOf<Triple<Int, Boolean, Float>, OverlayImage>()

    fun image(
        number: Int,
        metrics: DisplayMetrics,
    ): OverlayImage = image(number, metrics, selected = false)

    /**
     * 검색 탭 지도에도 쓴다(MZ2AZ-547 G1). [number] 가 없으면(목록에 없는 장소를 열어 덧붙인 핀) 번호 대신 흰 점.
     * 고른 핀은 그림을 통째로 [RouteNumberPinSpec.scale] 배로 **한 번만** 굽는다 — 마커 쪽에서 다시 키우지 않는다.
     */
    fun image(
        number: Int?,
        metrics: DisplayMetrics,
        selected: Boolean,
    ): OverlayImage {
        val s = metrics.density
        val key = Triple(number ?: -1, selected, s)
        cache[key]?.let { return it }
        val scale = RouteNumberPinSpec.scale(selected)
        val bitmap =
            Bitmap.createBitmap(
                (RouteNumberPinSpec.WIDTH * scale * s).toInt(),
                (RouteNumberPinSpec.HEIGHT * scale * s).toInt(),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        // 30×38 좌표로 그리고 통째로 키운다 — 꼬리 끝의 비율 자리는 그대로다.
        canvas.scale(s * scale, s * scale)
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

        if (number == null) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawCircle(15f, 14f, 4.5f, dot)
            return OverlayImage.fromBitmap(bitmap).also { cache[key] = it }
        }

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
        cache[key] = image
        return image
    }
}
