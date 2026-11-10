package com.mz2az.scenetrip.routetab

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.searchtab.regionSource
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.Projection
import com.naver.maps.map.overlay.Marker
import kotlin.math.floor

/** 배경 점의 이름표는 이만큼 확대했을 때부터 단다(`AmbientPoiPins` 와 같은 값). */
private const val SPREAD_CAPTION_MIN_ZOOM = 18.0

/**
 * 칩으로 받은 편의시설을 **골고루** 찍는다 (MZ2AZ-547 M4, iOS `PoiMarkerRenderer`, 웹 `mapOverlay.ts`).
 * 지도에 고정된 72dp 칸마다 받은 순서의 1등만 갈래 아이콘으로, 나머지는 그리지 않는다(§7 구글처럼 — 예전의 작은 점을 뺐다.
 * 확대하면 칸이 작아져 나타난다). 고른 곳은 1등이 아니어도 같은 아이콘을 크게(26→40, §9) — 빨간 해태·고리는 뺐다.
 *
 * **마커는 받은 목록이 바뀔 때만 만든다.** 확대·축소는 다시 받지 않고 다시 솎는다 — 이미 있는 마커를 지도에 붙이거나 떼고
 * 그림·이름표·높이만 바꾼다(마커 수백 개를 줌마다 지웠다 다시 만들면 지도가 깜빡인다).
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
            if (!picked && place.id !in majors) {
                marker.map = null
                return@forEach
            }
            marker.icon = images.dot(place, picked)
            marker.anchor = PointF(0.5f, 0.5f)
            marker.captionText = place.label().title
            marker.captionMinZoom = if (picked) 0.0 else SPREAD_CAPTION_MIN_ZOOM
            marker.isHideCollidedCaptions = !picked
            marker.zIndex = if (picked) 30 else 3
            if (marker.map == null) marker.map = map
        }
    }
}

/**
 * 지도 검색 결과 핀 (M7, iOS `MapSearchPinRenderer`) — 찾은 가게·장소를 기본 지도 1등과 같은 갈래 아이콘으로. 점이 아니다(§7 구글처럼).
 * 엔터 뒤 결과 서랍이 떠 있을 때만 찍는다(글자 치는 동안은 찍지 않는다). 더 받으면 핀도 는다 — 앞 100개까지(§8-2).
 * 같은 목록이면 다시 만들지 않는다.
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
        val images = PoiPinImage(context)
        val markers =
            shops.map { place ->
                Marker().apply {
                    position = LatLng(place.latitude, place.longitude)
                    icon = images.dot(place.asGuidePlace())
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

/**
 * 검색에서 고른 곳 — 칩 아이콘·AI 핀에 없는 곳이면 여기서 큰 갈래 아이콘(40)과 이름표를 단다(정보 카드가 가리키는 자리, §9).
 * 검색 결과 100개 밖이어도 고른 곳은 여기서 그린다(§8-2).
 */
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
                icon = PoiPinImage(context).dot(place, picked = true)
                anchor = PointF(0.5f, 0.5f)
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
 * 가까우면(화면 너비 3배 안) 0.6초 동안 부드럽게, 멀면 한 번에(§10-3, [withMotion]).
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
    val spot = LatLng(target.first, target.second)
    moveCamera(CameraUpdate.scrollAndZoomTo(spot, zoom).withMotion(spot, this))
}
