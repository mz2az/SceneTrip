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
    )
}

fun GuidePlace.matches(stop: RouteStop): Boolean =
    when {
        targetPlaceId != null -> stop.savablePlaceId == targetPlaceId
        stop.poiId != null -> source == GuidePlaceSource.poi && stop.poiId == id
        else -> RouteDedupe.key(stop.place) == RouteDedupe.key(courseStop().place)
    }

fun RouteStop.sameTarget(other: RouteStop): Boolean =
    when {
        savablePlaceId != null && other.savablePlaceId != null -> savablePlaceId == other.savablePlaceId
        poiId != null && other.poiId != null -> poiId == other.poiId
        else -> RouteDedupe.key(place) == RouteDedupe.key(other.place)
    }

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
