package com.mz2az.scenetrip.routetab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.map.overlay.OverlayImage
import androidx.compose.foundation.Canvas as ComposeCanvas

/** 지도 아이콘은 밀도·종류별로 한 번만 구워 카메라 이동 중 다시 만들지 않는다. */
class PoiPinImage(
    private val context: Context,
) {
    private val density = context.resources.displayMetrics.density

    fun dot(place: GuidePlace): OverlayImage {
        val glyph = PoiGlyph.of(place.categoryGroup, place.category)
        return cached("dot:${place.categoryGroup}:$glyph") {
            image(26f, 26f) { canvas ->
                circle(canvas, 13f, 11f, RoutePoiTone.of(place.categoryGroup.toRoutePoiGroup()).toArgb(), 1.5f)
                canvas.save()
                canvas.translate(5f, 5f)
                PoiGlyph.draw(canvas, glyph, Color.WHITE)
                canvas.restore()
            }
        }
    }

    fun cluster(count: Int): OverlayImage =
        cached("cluster:$count") {
            image(42f, 42f) { canvas ->
                circle(canvas, 21f, 18f, IOS.accent.toArgb(), 2f)
                val text =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = Paint.Align.CENTER
                    }
                canvas.drawText(count.toString(), 21f, 21f - (text.ascent() + text.descent()) / 2, text)
            }
        }

    /** 가이드가 찾아 준 곳(작은 해태, 0.85)과 그중 고른 하나(빨간 글로우, 1.1). iOS `PinoPin.marker(.ai/.picked)`. */
    fun mascot(picked: Boolean): OverlayImage = mascotImage(if (picked) 1.1f else 0.85f, picked)

    /** 지금 고른 정지점과 **안내 중인 목적지** — 보통 크기(1.0)의 해태. iOS `PinoPin.marker(.normal)` (MZ2AZ-478 E7). */
    fun focusMascot(): OverlayImage = mascotImage(1f, false)

    private fun mascotImage(
        scale: Float,
        picked: Boolean,
    ): OverlayImage =
        cached("mascot:$scale:$picked") {
            val source = BitmapFactory.decodeResource(context.resources, R.drawable.haetae_pinhold)
            val width = 40f * scale
            val height = width * source.height / source.width
            image(width + 8f, height + 8f) { canvas ->
                val scaled = Bitmap.createScaledBitmap(source, (width * density).toInt(), (height * density).toInt(), true)
                val glow =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (picked) Color.rgb(227, 41, 51) else IOS.pinDeep.toArgb()
                        setShadowLayer(3f * density, 0f, 0f, color)
                    }
                val alpha = scaled.extractAlpha()
                canvas.save()
                canvas.scale(1f / density, 1f / density)
                canvas.drawBitmap(alpha, 4f * density, 4f * density, glow)
                canvas.restore()
                canvas.drawBitmap(
                    source,
                    null,
                    RectF(4f, 4f, width + 4f, height + 4f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                alpha.recycle()
                if (scaled !== source) scaled.recycle()
            }
        }

    /**
     * 다녀온 정지점의 **발바닥 핀** — 번호 핀을 통째로 갈음한다 (MZ2AZ-478 E7, iOS `PinoPin.pawPin`). 28dp 하늘→보라 원 +
     * 흰 테두리 2 + 흰 발바닥(16dp, -12° 기울임). 자리 위에 얹는다(anchor 가운데).
     */
    fun pawPin(): OverlayImage =
        cached("paw") {
            image(34f, 34f) { canvas ->
                val center = 17f
                val fill =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(3f, 3f, 31f, 31f, IOS.pinLight.toArgb(), IOS.pinDeep.toArgb(), Shader.TileMode.CLAMP)
                        setShadowLayer(2f, 0f, 1f, Color.argb(64, 0, 0, 0))
                    }
                canvas.drawCircle(center, center, 14f, fill)
                val ring =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        color = Color.WHITE
                    }
                canvas.drawCircle(center, center, 13f, ring)
                val paw = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                canvas.save()
                canvas.translate(center - 8f, center - 8f)
                canvas.rotate(-12f, 8f, 8f)
                val s = 16f / 20f
                listOf(
                    floatArrayOf(5.2f, 10.2f, 9.6f, 7.6f),
                    floatArrayOf(3.4f, 5.4f, 4.4f, 4.6f),
                    floatArrayOf(7.9f, 3.2f, 4.4f, 4.6f),
                    floatArrayOf(12.4f, 5.4f, 4.4f, 4.6f),
                ).forEach { (left, top, w, h) -> canvas.drawOval(RectF(left * s, top * s, (left + w) * s, (top + h) * s), paw) }
                canvas.restore()
            }
        }

    /** 경로선의 점선 무늬 — 흰 점(6dp). iOS `RouteMapTrip.dash()`. */
    fun dash(): OverlayImage =
        cached("dash") {
            image(6f, 6f) { canvas -> canvas.drawCircle(3f, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }) }
        }

    private fun circle(
        canvas: Canvas,
        center: Float,
        radius: Float,
        color: Int,
        border: Float,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                setShadowLayer(1.8f, 0f, 0f, Color.argb(76, 0, 0, 0))
            }
        canvas.drawCircle(center, center, radius, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = border
        canvas.drawCircle(center, center, radius, paint)
    }

    private fun image(
        width: Float,
        height: Float,
        draw: (Canvas) -> Unit,
    ): OverlayImage {
        val bitmap = Bitmap.createBitmap((width * density).toInt(), (height * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        draw(canvas)
        return OverlayImage.fromBitmap(bitmap)
    }

    private fun cached(
        key: String,
        make: () -> OverlayImage,
    ): OverlayImage = cache.getOrPut("$density:$key", make)

    companion object {
        private val cache = mutableMapOf<String, OverlayImage>()
    }
}

@Composable
fun PoiGlyphIcon(
    group: PoiCategoryGroup,
    category: String?,
) {
    val color = RoutePoiTone.of(group.toRoutePoiGroup()).toArgb()
    ComposeCanvas(Modifier.size(18.dp)) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(size.width / 16f, size.height / 16f)
            PoiGlyph.draw(native, PoiGlyph.of(group, category), color)
            native.restore()
        }
    }
}

/** iOS와 같은 업종 판별 순서. 지도와 구성원 목록은 이 그림을 함께 쓴다. */
private object PoiGlyph {
    enum class Kind { CAFE, FOOD, BED, AIRPORT, BUS, TRAIN, TRAM, BEACH, VIEW, WHEEL, LEAF, LANDMARK }

    fun of(
        group: PoiCategoryGroup,
        category: String?,
    ): Kind {
        val text = category.orEmpty()

        fun has(vararg words: String) = words.any { text.contains(it) }
        return when (group) {
            PoiCategoryGroup.food -> {
                if (has("카페", "커피", "제과", "베이커리", "디저트")) Kind.CAFE else Kind.FOOD
            }

            PoiCategoryGroup.stay -> {
                Kind.BED
            }

            PoiCategoryGroup.transit -> {
                when {
                    has("공항") -> Kind.AIRPORT
                    has("버스") -> Kind.BUS
                    has("기차", "철도", "KTX") -> Kind.TRAIN
                    else -> Kind.TRAM
                }
            }

            PoiCategoryGroup.sight -> {
                when {
                    has("해수욕장", "해변") -> Kind.BEACH
                    has("전망대") -> Kind.VIEW
                    has("테마파크", "놀이") -> Kind.WHEEL
                    has("농원", "공원", "수목원", "정원") -> Kind.LEAF
                    else -> Kind.LANDMARK
                }
            }
        }
    }

    fun draw(
        canvas: Canvas,
        kind: Kind,
        color: Int,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.ROUND
            }
        when (kind) {
            Kind.CAFE -> {
                cup(canvas, paint)
            }

            Kind.FOOD -> {
                utensils(canvas, paint)
            }

            Kind.BED -> {
                bed(canvas, paint)
            }

            Kind.AIRPORT -> {
                plane(canvas, paint)
            }

            Kind.BUS, Kind.TRAIN, Kind.TRAM -> {
                vehicle(canvas, paint, kind)
            }

            Kind.BEACH -> {
                umbrella(canvas, paint)
            }

            Kind.VIEW -> {
                canvas.drawRoundRect(2f, 5f, 7f, 13f, 1.5f, 1.5f, paint)
                canvas.drawRoundRect(9f, 5f, 14f, 13f, 1.5f, 1.5f, paint)
                canvas.drawRect(6f, 7f, 10f, 9f, paint)
            }

            Kind.WHEEL -> {
                wheel(canvas, paint)
            }

            Kind.LEAF -> {
                canvas.drawOval(3f, 2f, 13f, 12f, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(3f, 14f, 10f, 5f, paint)
            }

            Kind.LANDMARK -> {
                landmark(canvas, paint)
            }
        }
    }

    private fun cup(
        c: Canvas,
        p: Paint,
    ) {
        c.drawRoundRect(3f, 4f, 11f, 12f, 2f, 2f, p)
        p.style = Paint.Style.STROKE
        c.drawOval(9f, 5f, 14f, 10f, p)
        c.drawLine(2f, 14f, 13f, 14f, p)
    }

    private fun utensils(
        c: Canvas,
        p: Paint,
    ) {
        p.style = Paint.Style.STROKE
        c.drawLine(5f, 2f, 5f, 14f, p)
        c.drawLine(2.5f, 2f, 2.5f, 6f, p)
        c.drawLine(7.5f, 2f, 7.5f, 6f, p)
        c.drawLine(2.5f, 6f, 7.5f, 6f, p)
        c.drawLine(12f, 2f, 12f, 14f, p)
        c.drawLine(10f, 2f, 10f, 8f, p)
        c.drawLine(10f, 8f, 12f, 8f, p)
    }

    private fun bed(
        c: Canvas,
        p: Paint,
    ) {
        c.drawRoundRect(2f, 7f, 14f, 12f, 1f, 1f, p)
        c.drawRoundRect(3f, 4f, 7f, 7f, 1f, 1f, p)
        c.drawRoundRect(9f, 4f, 13f, 7f, 1f, 1f, p)
        p.style = Paint.Style.STROKE
        c.drawLine(2f, 4f, 2f, 14f, p)
        c.drawLine(14f, 7f, 14f, 14f, p)
    }

    private fun plane(
        c: Canvas,
        p: Paint,
    ) {
        val path =
            android.graphics.Path().apply {
                moveTo(8f, 1f)
                lineTo(10f, 7f)
                lineTo(15f, 10f)
                lineTo(15f, 12f)
                lineTo(9f, 10f)
                lineTo(9f, 13f)
                lineTo(11f, 15f)
                lineTo(5f, 15f)
                lineTo(7f, 13f)
                lineTo(7f, 10f)
                lineTo(1f, 12f)
                lineTo(1f, 10f)
                lineTo(6f, 7f)
                close()
            }
        c.drawPath(path, p)
    }

    private fun vehicle(
        c: Canvas,
        p: Paint,
        kind: Kind,
    ) {
        p.style = Paint.Style.STROKE
        c.drawRoundRect(3f, 2f, 13f, 12f, 2f, 2f, p)
        c.drawRect(4.5f, 4f, 11.5f, 8f, p)
        c.drawLine(5f, 12f, 4f, 14f, p)
        c.drawLine(11f, 12f, 12f, 14f, p)
        c.drawPoint(5f, 10f, p)
        c.drawPoint(11f, 10f, p)
        if (kind == Kind.TRAM) c.drawLine(6f, 0.5f, 10f, 0.5f, p)
        if (kind == Kind.TRAIN) c.drawLine(8f, 4f, 8f, 8f, p)
    }

    private fun umbrella(
        c: Canvas,
        p: Paint,
    ) {
        c.drawArc(1f, 2f, 15f, 15f, 180f, 180f, true, p)
        p.style = Paint.Style.STROKE
        c.drawLine(8f, 8f, 8f, 14f, p)
        c.drawLine(5f, 14f, 11f, 14f, p)
    }

    private fun wheel(
        c: Canvas,
        p: Paint,
    ) {
        p.style = Paint.Style.STROKE
        c.drawCircle(8f, 7f, 5f, p)
        for (angle in 0..3) {
            c.save()
            c.rotate(angle * 45f, 8f, 7f)
            c.drawLine(3f, 7f, 13f, 7f, p)
            c.restore()
        }
        c.drawLine(8f, 7f, 4f, 15f, p)
        c.drawLine(8f, 7f, 12f, 15f, p)
    }

    private fun landmark(
        c: Canvas,
        p: Paint,
    ) {
        val roof =
            android.graphics.Path().apply {
                moveTo(1f, 5f)
                lineTo(8f, 1f)
                lineTo(15f, 5f)
                close()
            }
        c.drawPath(roof, p)
        for (x in listOf(3f, 7f, 11f)) c.drawRect(x, 6f, x + 2f, 12f, p)
        c.drawRect(1f, 13f, 15f, 15f, p)
    }
}
