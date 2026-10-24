package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.haversineKm

/**
 * 코스 지도에서 정지점(번호 핀·목록 행)을 골랐을 때 카메라를 어떻게 옮길지의 규칙 (MZ2AZ-509).
 * iOS `RouteMapView.Coordinator.move(to:)` (PR #326) 과 같은 값이다.
 */
object RouteStopFocus {
    /** 고른 곳 둘레의 동네가 보이는 크기. 15 는 골목까지 들어가 주변 가게 핀이 화면을 덮었다. */
    const val ZOOM = 14.0

    /**
     * 고른 곳과 나를 한 화면에 담는 거리의 한계, 단위 m. 이보다 멀면 둘을 담은 화면이 너무 넓어
     * 고른 곳이 어디인지 보이지 않는다(서울시청↔인천).
     */
    const val WITH_ME_RADIUS_METERS = 3_000.0

    /**
     * 「내 위치」 토글이 켜져 있고 내 자리를 알며 **그곳이 가까울 때만** 나와 그곳을 같이 담는다.
     * 아니면 그곳만 확대한다.
     */
    fun showsWithMe(
        showingMe: Boolean,
        here: Pair<Double, Double>?,
        there: Pair<Double, Double>,
    ): Boolean {
        if (!showingMe || here == null) return false
        return haversineKm(here.first, here.second, there.first, there.second) * 1000 <= WITH_ME_RADIUS_METERS
    }
}
