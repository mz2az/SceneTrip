package com.mz2az.scenetrip.routetab

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.RouteLeg
import com.mz2az.scenetrip.searchtab.PinImage
import com.mz2az.scenetrip.ui.IOS
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import java.util.UUID

// 편집 지도의 **여행 안내 그리기** — 정지점 핀, 미리보기 핀, 경로선, 내 자리 파문 (MZ2AZ-478 E6·E7).
// iOS `RouteMapPins.swift` · `RouteMapTrip.swift` · `RadarPulse.swift` 의 Android 대응이다.

/**
 * 코스의 정지점 핀 (iOS `RouteMapView.Coordinator.drawPins`). 상태가 핀 모양을 고른다.
 *
 * | 정지점 | 핀 |
 * | --- | --- |
 * | 다녀온 곳 | **발바닥 핀** — 번호 핀을 통째로 갈음한다. 자리 위에 얹는다 |
 * | 고른 곳 · 안내 중인 목적지 | **해태 핀**(다른 핀에 가리지 않게 위로) |
 * | 그 밖 | 번호 핀 |
 */
@Composable
fun CourseStopPins(
    map: NaverMap?,
    stops: List<RouteStop>,
    focusedId: UUID?,
    targetId: UUID?,
    onTap: (RouteStop) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    val select by rememberUpdatedState(onTap)
    DisposableEffect(map, stops, focusedId, targetId, AppLanguage.current) {
        val metrics = context.resources.displayMetrics
        val images = PoiPinImage(context)
        val markers =
            stops.mapIndexed { index, stop ->
                Marker().apply {
                    position = LatLng(stop.place.latitude, stop.place.longitude)
                    when {
                        stop.visited -> {
                            // 다녀온 곳은 번호 핀이 발바닥으로 바뀐다(2026-09-03 사용자 요청 — 「도착한 표시로 그 핀이 발바닥으로」).
                            icon = images.pawPin()
                            anchor = PointF(0.5f, 0.5f)
                            zIndex = 8
                        }

                        stop.id == focusedId || stop.id == targetId -> {
                            // 고른 곳과 **지금 안내 중인 목적지**는 해태다.
                            icon = images.focusMascot()
                            anchor = PointF(0.5f, 1f)
                            zIndex = 10
                        }

                        else -> {
                            icon = PinImage.numbered(index + 1, metrics)
                            // 꼬리 끝이 좌표를 가리켜야 한다(iOS 는 꼬리를 45/50 지점에 둔다).
                            anchor = PointF(0.5f, 45f / 50f)
                        }
                    }
                    captionText = stop.label().title
                    // 줌 12 이상에서 이름표를 띄운다(iOS `captionMinZoom = 12`).
                    captionMinZoom = 12.0
                    setOnClickListener {
                        select(stop)
                        true
                    }
                    this.map = map
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

/**
 * 담을까 보는 곳(검색·장바구니에서 체크한 것) — **빨간 해태.** 코스 핀보다 위에 올려 가리지 않게 한다 (iOS `previews`).
 * 눌러야 한다 — 손잡이가 없으면 카드를 볼 길이 없다.
 */
@Composable
fun PreviewPins(
    map: NaverMap?,
    places: List<PlaceSummary>,
    onTap: (PlaceSummary) -> Unit,
) {
    if (map == null) return
    val context = LocalContext.current
    val select by rememberUpdatedState(onTap)
    DisposableEffect(map, places) {
        val image = PoiPinImage(context).mascot(picked = true)
        val markers =
            places.map { place ->
                Marker().apply {
                    position = LatLng(place.latitude, place.longitude)
                    icon = image
                    anchor = PointF(0.5f, 1f)
                    captionText = place.name
                    captionMinZoom = 10.0
                    zIndex = 20
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

/** 점선의 흰 점 무늬를 얹은 [PathOverlay]. 간격은 px. */
private fun PathOverlay.dashed(
    images: PoiPinImage,
    interval: Int,
) {
    patternImage = images.dash()
    patternInterval = interval
}

/**
 * 안내 경로 — **API 가 준 실제 길 좌표를 그대로 그린다.** 구간마다 따로 그어야 도보(점선)와 대중교통(실선)이 갈린다. 계획선(직선)
 * 위에 굵게 얹는다 (iOS `renderLegs`).
 *
 * - [here] 를 주면 **지나온 구간을 지우고** 남은 길만 그린다([RouteTrail]). 걸으면 선이 줄어야 하므로 자리도 열쇠에 넣는다 — 소수 넷째
 *   자리 ≈ 11 m, 그보다 잘게 넣으면 GPS 가 떨 때마다 선을 다시 그린다.
 * - 카카오 경로는 목적지에서 가장 가까운 **도로 접점**에서 끝난다 — 건물 안·캠퍼스 안의 핀까지 마지막 몇십 m 는 길이 없다. 그 사이를
 *   얇은 회색 점선으로 이어 「여기서부터 걸어 들어간다」가 보이게 한다(2026-09-04 사용자 결정).
 */
@Composable
fun TripLegLines(
    map: NaverMap?,
    legs: List<RouteLeg>,
    target: RouteStop?,
    here: Pair<Double, Double>?,
) {
    if (map == null) return
    val context = LocalContext.current
    val hereKey = here?.let { "${(it.first * 10_000).toInt()},${(it.second * 10_000).toInt()}" }
    val legKey = legs.joinToString(",") { "${it.mode}${it.path.coordinates.size}${it.path.coordinates.lastOrNull()}" }
    DisposableEffect(map, legKey, target?.id, hereKey) {
        val density = context.resources.displayMetrics.density
        val images = PoiPinImage(context)
        val overlays = mutableListOf<PathOverlay>()
        val lastPair =
            legs
                .lastOrNull { it.path.coordinates.size > 1 }
                ?.path
                ?.coordinates
                ?.lastOrNull()
        if (target != null && lastPair != null && lastPair.size >= 2) {
            overlays +=
                PathOverlay().apply {
                    coords = listOf(LatLng(lastPair[1], lastPair[0]), LatLng(target.place.latitude, target.place.longitude))
                    width = (4 * density).toInt()
                    color = android.graphics.Color.argb(217, 138, 148, 166)
                    outlineWidth = 0
                    dashed(images, (8 * density).toInt())
                    zIndex = 5
                    this.map = map
                }
        }
        val remaining = RouteTrail.remaining(legs.map { it.path.coordinates }, here)
        legs.forEachIndexed { index, leg ->
            val points =
                remaining[index].filter { it.size >= 2 }.map { LatLng(it[1], it[0]) } // [경도, 위도]
            if (points.size < 2) return@forEachIndexed
            val walking = RouteLegMode.from(leg.mode, leg.vehicleType, leg.vehicleName) == RouteLegMode.WALK
            overlays +=
                PathOverlay().apply {
                    coords = points
                    width = ((if (walking) 7 else 9) * density).toInt()
                    color = IOS.pinDeep.toArgb()
                    outlineWidth = 0
                    if (walking) dashed(images, (14 * density).toInt())
                    zIndex = 5 // 계획선 위
                    this.map = map
                }
        }
        onDispose { overlays.forEach { it.map = null } }
    }
}

/**
 * 「내 자리 → 다음 곳」 직선 미리보기. 실제 경로가 오면 부르는 쪽이 `target = null` 을 줘 지운다. 계획선과 같은 색이되 **점선**이다 —
 * 아직 길이 아니라 방향이라는 뜻 (iOS `renderPreview`).
 */
@Composable
fun TripPreviewLine(
    map: NaverMap?,
    here: Pair<Double, Double>?,
    target: RouteStop?,
) {
    if (map == null || here == null || target == null) return
    val context = LocalContext.current
    val hereKey = "${(here.first * 10_000).toInt()},${(here.second * 10_000).toInt()}"
    DisposableEffect(map, target.id, hereKey) {
        val density = context.resources.displayMetrics.density
        val overlay =
            PathOverlay().apply {
                coords = listOf(LatLng(here.first, here.second), LatLng(target.place.latitude, target.place.longitude))
                width = (5 * density).toInt()
                color = IOS.pinDeep.copy(alpha = 0.55f).toArgb()
                outlineWidth = 0
                dashed(PoiPinImage(context), (12 * density).toInt())
                zIndex = 4
                this.map = map
            }
        onDispose { overlay.map = null }
    }
}
