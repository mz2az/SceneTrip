package com.mz2az.scenetrip.routetab

/**
 * 코스 항목 유무와 무관한 챗봇·주변 촬영지 검색 기준 (MZ2AZ-444). iOS `RouteGuideAnchor`를 옮긴 것이다.
 *
 * 기준은 둘이다. 기기 위치(`isMapCenter = false`)와 지금 보이는 지도 중심(`isMapCenter = true`). 위치를
 * 못 받았거나 거절했어도 지도 중심이 있으면 가이드를 쓸 수 있다.
 *
 * iOS 는 좌표 출처(`device`/`userSelected`)도 함께 든다 — 위치기반서비스 동의 때문이다. Android 는 아직
 * 위치 동의가 없어 출처를 두지 않는다.
 * TODO(MZ2AZ-472): 위치기반서비스 동의가 Android 에 들어오면 출처(기기 GPS 에서 맞춘 지도인가)를 여기 싣는다.
 */
data class RouteGuideAnchor(
    val latitude: Double,
    val longitude: Double,
    val isMapCenter: Boolean,
) {
    companion object {
        /**
         * [preferMap]이 참이고 지도 중심이 있으면 지도 중심, 아니면 기기 위치, 그것도 없으면 지도 중심.
         * 범위를 벗어난 좌표는 없는 것으로 본다.
         */
        fun resolve(
            current: Pair<Double, Double>?,
            mapCenter: Pair<Double, Double>?,
            preferMap: Boolean,
        ): RouteGuideAnchor? {
            val here = current?.takeIf { isValid(it) }
            val center = mapCenter?.takeIf { isValid(it) }
            if (preferMap && center != null) return RouteGuideAnchor(center.first, center.second, isMapCenter = true)
            if (here != null) return RouteGuideAnchor(here.first, here.second, isMapCenter = false)
            return center?.let { RouteGuideAnchor(it.first, it.second, isMapCenter = true) }
        }

        fun isValid(point: Pair<Double, Double>): Boolean =
            point.first.isFinite() && point.second.isFinite() && point.first in -90.0..90.0 && point.second in -180.0..180.0
    }
}
