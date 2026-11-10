package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.haversineKm
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap

/**
 * 지금 화면에서 [target] 까지의 거리로 움직임을 고른다(§10-3, [MapCameraMotionRules], iOS `MapCameraMotionNaver.swift`) —
 * 가까우면 0.6초 감속, 멀면 한 번에. 화면 너비는 지금 가운데 위도에서 보이는 범위(`contentBounds`)의 동서 길이다.
 *
 * 안드로이드 지도 SDK 에는 iOS 의 `easeOut` 이 없다 — 가장 가까운 [CameraAnimation.Easing](천천히 시작해 천천히 멈춤)을 쓴다.
 */
internal fun CameraUpdate.withMotion(
    target: LatLng,
    map: NaverMap,
): CameraUpdate {
    val center = map.cameraPosition.target
    val visible = map.contentBounds
    val width = meters(center.latitude, visible.westLongitude, center.latitude, visible.eastLongitude)
    return when (
        val motion =
            MapCameraMotionRules.motion(
                meters(center.latitude, center.longitude, target.latitude, target.longitude),
                width,
            )
    ) {
        is MapCameraMotion.Smooth -> animate(CameraAnimation.Easing, motion.durationMillis)
        MapCameraMotion.Jump -> animate(CameraAnimation.None)
    }
}

private fun meters(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
): Double = haversineKm(lat1, lng1, lat2, lng2) * 1000.0
