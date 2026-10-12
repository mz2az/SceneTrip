package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.PlaceType
import com.mz2az.scenetrip.sceneapi.client.model.Lang

/** 저장한 편의시설도 목록·카드·지도에서 같은 이름을 표시한다. */
data class PoiText(
    val displayName: String?,
    val nameRoman: String?,
    val categoryLabel: String?,
    val displayAddress: String?,
)

fun RouteStop.label(korean: Boolean = AppLanguage.current == Lang.ko): PoiLabel =
    if (poiId == null) {
        PoiLabel(place.name, null, PlaceType.stopLabel(place.type, isPinned, korean), place.address)
    } else {
        PoiLabel.make(
            place.name,
            poiText?.displayName,
            poiText?.nameRoman,
            place.type,
            poiText?.categoryLabel,
            place.address,
            poiText?.displayAddress,
            korean,
        )
    }

fun com.mz2az.scenetrip.sceneapi.client.model.GuidePlace.label(korean: Boolean = AppLanguage.current == Lang.ko): PoiLabel =
    PoiLabel.make(name, displayName, nameRoman, category, categoryLabel, address, displayAddress, korean)
