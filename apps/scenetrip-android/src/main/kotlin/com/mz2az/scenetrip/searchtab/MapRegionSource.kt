package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.CoordinateSource
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import java.util.Collections
import java.util.WeakHashMap

/**
 * 지도 범위가 GPS 에서 유래했는지 보존한다 (MZ2AZ-472). iOS `SearchTab/MapRegionSource.swift`.
 *
 * - 손으로 밀거나 확대하는 제스처·컨트롤은 사용자가 고른 영역이다.
 * - 위치 추적이 움직인 카메라는 기기 위치다.
 * - 코드가 옮긴 카메라(`REASON_DEVELOPER`)와 여백 변경(`REASON_CONTENT_PADDING`)은 출처를 바꾸지 않는다 —
 *   코드로 옮기는 쪽이 옮기기 전에 [source] 를 직접 정한다(검색 결과 맞춤·장소 확대는 사용자 선택, 내 위치로 날아가기는 기기).
 *
 * 처음 값은 사용자 선택이다(남한 전체). 이 값은 지도 범위로 장소·편의시설을 조회할 때 위치 동의가 필요한지를 가른다.
 */
class MapRegionSource {
    @Volatile var source: CoordinateSource = CoordinateSource.USER_SELECTED

    fun cameraWillChange(reason: Int) {
        if (reason == CameraUpdate.REASON_GESTURE || reason == CameraUpdate.REASON_CONTROL) {
            source = CoordinateSource.USER_SELECTED
        } else if (reason == CameraUpdate.REASON_LOCATION) {
            source = CoordinateSource.DEVICE
        }
    }
}

private val regions = Collections.synchronizedMap(WeakHashMap<NaverMap, MapRegionSource>())

/** 이 지도의 현재 범위 출처. iOS `MapCamera.region`. */
val NaverMap.regionSource: MapRegionSource
    get() = regions.getOrPut(this) { MapRegionSource() }
