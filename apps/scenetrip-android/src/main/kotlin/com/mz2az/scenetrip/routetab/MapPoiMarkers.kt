package com.mz2az.scenetrip.routetab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.searchtab.regionSource
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.Projection
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import kotlin.math.floor

/** 배경 점의 이름표는 이만큼 확대했을 때부터 단다(`AmbientPoiPins` 와 같은 값). */
private const val SPREAD_CAPTION_MIN_ZOOM = 18.0

/**
 * 칩으로 받은 편의시설을 **골고루** 찍는다 (MZ2AZ-547 M4, iOS `PoiMarkerRenderer`+`MinorDotRenderer`, 웹 `mapOverlay.ts`).
 * 지도에 고정된 72dp 칸마다 받은 순서의 1등은 원래 점(분류 그림), 나머지는 작은 점(보이는 9 · 누르는 자리 16). 고른 곳은 빨간 해태.
 *
 * **점은 받은 목록이 바뀔 때만 만든다.** 확대·축소는 다시 받지 않고 다시 솎는다 — 이미 있는 점의 그림·이름표·높이만 바꾼다
 * (점 수백 개를 줌마다 지웠다 다시 만들면 지도가 깜빡인다).
 */
@Composable
internal fun SpreadPoiPins(
    map: NaverMap?,
    pois: List<PoiSummary>,
    zoom: Double,
    pickedId: Long?,
    onTap: (GuidePlace) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    val select by rememberUpdatedState(onTap)
    val places = remember(pois) { PoiMarkerRules.validPlaces(pois.map { it.asGuidePlace() }) }
    val drawable = remember(pois, places) { places.map { it.id }.toSet().let { ids -> pois.filter { it.id in ids } } }
    val level = floor(zoom)
    val majors = remember(drawable, level) { MapPoiSpread.majorIds(drawable, level) }
    var built by remember { mutableStateOf<List<Pair<GuidePlace, Marker>>>(emptyList()) }
    DisposableEffect(map, places, AppLanguage.current) {
        val markers =
            places.map { place ->
                place to
                    Marker().apply {
                        position = LatLng(place.latitude, place.longitude)
                        isHideCollidedSymbols = false
                        setOnClickListener {
                            select(place)
                            true
                        }
                    }
            }
        built = markers
        onDispose { markers.forEach { it.second.map = null } }
    }
    LaunchedEffect(map, built, majors, pickedId) {
        val images = PoiPinImage(context)
        built.forEach { (place, marker) ->
            val picked = place.id == pickedId
            val major = picked || place.id in majors
            marker.icon =
                when {
                    picked -> images.mascot(picked = true)
                    major -> images.dot(place)
                    else -> MapPoiImages.minorDot(context, place.categoryGroup)
                }
            marker.anchor = PointF(0.5f, if (picked) 1f else 0.5f)
            marker.captionText = if (major) place.label().title else ""
            marker.captionMinZoom = if (picked) 0.0 else SPREAD_CAPTION_MIN_ZOOM
            marker.isHideCollidedCaptions = !picked
            marker.zIndex =
                if (picked) {
                    30
                } else if (major) {
                    3
                } else {
                    2
                }
            if (marker.map == null) marker.map = map
        }
    }
}

/**
 * 지도 검색의 점 (M6·M7, iOS `MapSearchPinRenderer`) — 찾은 가게·장소를 갈래 색 점(16, 흰 테두리)으로. 엔터 전 목록이 떠 있는 동안에도,
 * 엔터 뒤 결과 지도에서도 찍는다. 같은 목록이면 다시 만들지 않는다.
 */
@Composable
internal fun MapSearchDots(
    map: NaverMap?,
    shops: List<PoiSummary>,
    onTap: (PoiSummary) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    val select by rememberUpdatedState(onTap)
    val ids = shops.map { it.id }
    DisposableEffect(map, ids) {
        val markers =
            shops.map { place ->
                Marker().apply {
                    position = LatLng(place.latitude, place.longitude)
                    icon = MapPoiImages.shopDot(context, place.categoryGroup)
                    anchor = PointF(0.5f, 0.5f)
                    zIndex = 40
                    isHideCollidedSymbols = false
                    setOnClickListener {
                        select(place)
                        true
                    }
                    this.map = map
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

/** 검색에서 고른 곳 — 칩 점·AI 핀에 없는 곳이면 여기서 빨간 해태와 이름표를 단다(정보 카드가 가리키는 자리). */
@Composable
internal fun PickedPoiPin(
    map: NaverMap?,
    place: GuidePlace?,
) {
    if (map == null || place == null) return
    val context = LocalContext.current
    DisposableEffect(map, place.id, AppLanguage.current) {
        val marker =
            Marker().apply {
                position = LatLng(place.latitude, place.longitude)
                icon = PoiPinImage(context).mascot(picked = true)
                anchor = PointF(0.5f, 1f)
                captionText = place.label().title
                zIndex = 45
                this.map = map
            }
        onDispose { marker.map = null }
    }
}

/**
 * 고른 편의시설·검색 결과로 — **덮이지 않은 지도의 가운데에** 오게 (2026-11-10 사용자 지적, iOS `Coordinator.move(to:)`).
 * 아래 시트·결과 서랍은 지도의 아래 여백(content padding)이 이미 비켜 주고, 정보 카드(적어도 300dp)가 그보다 높으면 그만큼 더 올린다
 * ([MapSearchRules.extraLift]). 확대는 지금과 16 중 큰 것 — 더 확대해 둔 화면을 풀지 않는다.
 */
internal fun NaverMap.focusOnPicked(
    latitude: Double,
    longitude: Double,
    density: Float,
) {
    regionSource.source = CoordinateSource.USER_SELECTED
    val zoom = MapSearchRules.focusZoom(cameraPosition.zoom)
    val insetDp = contentPadding[3] / density
    val target =
        MapSearchRules.focusTarget(
            latitude,
            longitude,
            MapSearchRules.extraLift(insetDp),
            Projection.getMetersPerDp(latitude, zoom),
        )
    moveCamera(
        CameraUpdate
            .scrollAndZoomTo(LatLng(target.first, target.second), zoom)
            .animate(CameraAnimation.Easing, 500L),
    )
}

/** 지도에 얹는 점 그림. 같은 갈래의 점은 한 번 굽고 다시 쓴다(밀도마다). */
internal object MapPoiImages {
    private val cache = mutableMapOf<String, OverlayImage>()

    fun minorDot(
        context: android.content.Context,
        group: PoiCategoryGroup,
    ): OverlayImage = dot(context, group, MapPoiSpread.MINOR_VISIBLE, MapPoiSpread.MINOR_HIT, 1.5f)

    fun shopDot(
        context: android.content.Context,
        group: PoiCategoryGroup,
    ): OverlayImage = dot(context, group, 16f, 20f, 2.5f)

    /** 갈래 색 원 + 흰 테두리 + 옅은 그림자. [canvas] 가 누르는 자리다. */
    private fun dot(
        context: android.content.Context,
        group: PoiCategoryGroup,
        visible: Float,
        canvas: Float,
        border: Float,
    ): OverlayImage {
        val density = context.resources.displayMetrics.density
        return cache.getOrPut("$density:$group:$visible:$canvas") {
            val size = (canvas * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val drawing = Canvas(bitmap)
            drawing.scale(density, density)
            val center = canvas / 2
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    setShadowLayer(1.5f, 0f, 0.5f, android.graphics.Color.argb(76, 0, 0, 0))
                }
            drawing.drawCircle(center, center, visible / 2, paint)
            paint.clearShadowLayer()
            paint.color = RoutePoiTone.of(group.toRoutePoiGroup()).toArgb()
            drawing.drawCircle(center, center, visible / 2 - border, paint)
            OverlayImage.fromBitmap(bitmap)
        }
    }
}
