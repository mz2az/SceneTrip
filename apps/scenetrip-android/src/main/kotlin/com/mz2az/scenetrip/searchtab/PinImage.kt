package com.mz2az.scenetrip.searchtab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.toArgb
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.map.overlay.OverlayImage

/**
 * 지도 핀을 직접 그린다.
 *
 * iOS `NaverMapView.swift` 의 `PinImage` 를 그대로 옮긴 것이다. 그쪽 주석이 이유를
 * 적어 뒀다 — 단색 빨강은 "옛날 앱" 처럼 보인다는 피드백으로 파스텔 하늘→보라
 * 그러데이션에 그림자를 깔고, 번호는 머리의 흰 원 배지 안에 컬러로 넣는다.
 *
 * **치수를 짐작하지 않는다.** 38×50, 머리 중심 (19,17) 반지름 14, 꼬리 끝 (19,45),
 * 흰 배지 반지름 10, 글자 11.5 — 전부 iOS 소스의 값이다. 하나라도 다르면 두 앱의
 * 지도가 다르게 보인다.
 *
 * iOS 는 이 값을 pt 로 쓰고 화면 배율만큼 알아서 굽는다. 안드로이드는 픽셀이므로
 * 여기서 밀도를 곱한다 — 곱하지 않으면 고밀도 화면에서 핀이 좁쌀만 해진다.
 *
 * 같은 번호는 캐시로 재사용한다. 계획서가 "타이핑마다 수십 개를 다시 굽으면
 * 버벅인다" 고 적은 그 비용을 피하는 장치다.
 */
object PinImage {
    /** 번호 없는 핀은 -1 로 담는다 — 번호가 유일한 차이라 캐시를 나눌 이유가 없다. */
    private val cache = mutableMapOf<Int, OverlayImage>()

    private const val W = 38f
    private const val H = 50f
    private const val HEAD_X = 19f
    private const val HEAD_Y = 17f
    private const val HEAD_R = 14f
    private const val TIP_Y = 45f
    private const val BADGE_R = 10f

    /** `number` 가 null 이면 흰 배지와 숫자 없이 **민 핀**을 그린다. */
    fun numbered(
        number: Int?,
        metrics: DisplayMetrics,
    ): OverlayImage {
        val key = number ?: -1
        cache[key]?.let { return it }

        // **iOS 보다 14.6% 작게 나왔다**(6 차 실측 69×102 vs 79×117px).
        // iOS 는 38×50 pt 를 @3x 로 굽는데, 안드로이드는 density 2.625 라
        // 그대로 곱하면 그만큼 작다. 논리 크기를 맞추려면 보정해야 한다.
        val s = metrics.density * 1.146f
        val bitmap = Bitmap.createBitmap((W * s).toInt(), (H * s).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(s, s)

        val deep = IOS.pinDeep.toArgb()
        val light = IOS.pinLight.toArgb()

        // 머리 원의 아래쪽 좌우(135°→45°, 시계방향으로 위를 지나)에서 꼬리 끝점으로
        // 이어 물방울 모양을 만든다.
        val path =
            Path().apply {
                val box = RectF(HEAD_X - HEAD_R, HEAD_Y - HEAD_R, HEAD_X + HEAD_R, HEAD_Y + HEAD_R)
                // 안드로이드의 각도는 3시 방향이 0 이고 시계방향이 양수다. iOS 의
                // 0.75π(=135°) 에서 0.25π(=45°) 까지 시계방향으로 **위를 지나는** 호는
                // 135° 에서 270° 만큼 도는 것과 같다.
                arcTo(box, 135f, 270f, true)
                lineTo(HEAD_X, TIP_Y)
                close()
            }

        // 바닥에 살짝 뜬 그림자 — 지도 위에 얹힌 입체감을 만든다.
        val shadow =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = deep
                setShadowLayer(3.5f, 0f, 2f, 0x47000000) // 검정 28%
            }
        canvas.drawPath(path, shadow)

        // 세로 그러데이션.
        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        HEAD_X,
                        0f,
                        HEAD_X,
                        TIP_Y,
                        light,
                        deep,
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawPath(path, fill)

        val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = android.graphics.Color.WHITE
            }
        canvas.drawPath(path, stroke)

        if (number != null) {
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawCircle(HEAD_X, HEAD_Y, BADGE_R, badge)

            val text =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = deep
                    textSize = if (number < 100) 11.5f else 9f
                    typeface =
                        android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD,
                        )
                    textAlign = Paint.Align.CENTER
                }
            // 세로 가운데 맞춤 — baseline 은 글자 아래라 그대로 두면 아래로 쏠린다.
            val offset = (text.descent() + text.ascent()) / 2
            canvas.drawText("$number", HEAD_X, HEAD_Y - offset, text)
        }

        val overlay = OverlayImage.fromBitmap(bitmap)
        cache[key] = overlay
        return overlay
    }
}
