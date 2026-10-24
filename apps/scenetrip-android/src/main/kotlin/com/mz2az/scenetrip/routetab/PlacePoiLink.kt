package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/** 촬영지가 같은 곳인 편의시설을 대표한다. 상세·리뷰·코스 모두 이 규칙을 쓴다. */
val GuidePlace.targetPlaceId: Long?
    get() = if (source == GuidePlaceSource.place) id else placeId

fun GuidePlace.courseStop(): RouteStop {
    val asPlace = targetPlaceId
    return RouteStop(
        place =
            PlaceSummary(
                id = asPlace ?: 0,
                name = name,
                type = category,
                address = address,
                latitude = latitude,
                longitude = longitude,
            ),
        poiId = id.takeIf { asPlace == null && source == GuidePlaceSource.poi },
        poiText =
            if (asPlace == null &&
                source == GuidePlaceSource.poi
            ) {
                PoiText(displayName, nameRoman, categoryLabel, displayAddress)
            } else {
                null
            },
    )
}

fun GuidePlace.matches(stop: RouteStop): Boolean =
    when {
        targetPlaceId != null -> stop.savablePlaceId == targetPlaceId
        stop.poiId != null -> source == GuidePlaceSource.poi && stop.poiId == id
        else -> RouteDedupe.key(stop.place) == RouteDedupe.key(courseStop().place)
    }

fun RouteStop.sameTarget(other: RouteStop): Boolean =
    (savablePlaceId != null && savablePlaceId == other.savablePlaceId) ||
        (poiId != null && poiId == other.poiId) ||
        RouteDedupe.key(place) == RouteDedupe.key(other.place)

fun RouteStop.cardPlace(): GuidePlace? {
    if (isPinned || placeMissing) return null
    val group =
        com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup.entries
            .firstOrNull { it.value == place.type }
            ?: com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup.sight
    return GuidePlace(
        id = poiId ?: savablePlaceId ?: return null,
        name = place.name,
        category = place.type.orEmpty(),
        categoryGroup = group,
        latitude = place.latitude,
        longitude = place.longitude,
        address = place.address,
        displayName = poiText?.displayName,
        nameRoman = poiText?.nameRoman,
        categoryLabel = poiText?.categoryLabel,
        displayAddress = poiText?.displayAddress,
        source =
            if (poiId ==
                null
            ) {
                GuidePlaceSource.place
            } else {
                GuidePlaceSource.poi
            },
    )
}

/**
 * 저장하고 서버가 돌려준 줄 중에서 [this] 와 같은 곳 (MZ2AZ-478 B7, iOS `PlacePoiLink.row(of:in:)`).
 *
 * 편의시설 이름·좌표로 담긴 줄(`entry`)은 서버가 촬영지의 이름·좌표로 돌려주므로 이름 + 좌표로는 못 찾는다. **촬영지 줄은 촬영지
 * id 로, 편의시설 줄은 편의시설 id 로** 찾는다. 그 밖(개인 핀 — id 가 저장마다 바뀐다)과 편의시설 id 로 못 찾은 줄(서버가 촬영지로
 * 바꿔 저장했다)은 이름 + 좌표로 찾는다.
 */
fun RouteStop.rowIn(saved: List<RouteStop>): RouteStop? {
    savablePlaceId?.let { placeId -> return saved.firstOrNull { it.savablePlaceId == placeId } }
    poiId?.let { poi -> saved.firstOrNull { it.poiId == poi }?.let { return it } }
    val key = RouteDedupe.key(place)
    return saved.firstOrNull { RouteDedupe.key(it.place) == key }
}
