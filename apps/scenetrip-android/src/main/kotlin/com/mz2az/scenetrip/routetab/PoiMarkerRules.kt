package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import kotlin.math.abs

/**
 * 편의시설을 **각각 실제 좌표에** 그린다 (MZ2AZ-482 C9, iOS 403 `PoiMarkerRules`). 같은 위치의 서로 다른 장소를 합치거나 옮기지 않는다 —
 * 앞서 Android 는 확대에 따라 묶음 핀과 구성원 목록 시트를 썼는데 iOS 가 걷어냈다.
 */
object PoiMarkerRules {
    /** 지도에 찍을 수 있는 것만, 들어온 순서 그대로. 좌표가 유한하지 않거나 지도 범위 밖이면 빼고, 같은 id 는 하나만 둔다. */
    fun validPlaces(places: List<GuidePlace>): List<GuidePlace> {
        val seen = mutableSetOf<Long>()
        return places.filter { place ->
            place.latitude.isFinite() &&
                place.longitude.isFinite() &&
                abs(place.latitude) <= MAX_LATITUDE &&
                abs(place.longitude) <= MAX_LONGITUDE &&
                seen.add(place.id)
        }
    }

    /** 웹 메르카토르가 그리는 위도의 끝. */
    private const val MAX_LATITUDE = 85.05112878
    private const val MAX_LONGITUDE = 180.0
}
