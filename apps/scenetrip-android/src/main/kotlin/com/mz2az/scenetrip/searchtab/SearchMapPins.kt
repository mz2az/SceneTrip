package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/** 지도에 꽂을 핀 하나 — 장소, 번호(없으면 번호 없는 흰 점 핀), 고른 핀인가(1.2배). */
data class SearchPinMark(
    val place: PlaceSummary,
    val number: Int?,
    val selected: Boolean,
)

/**
 * 검색 탭 지도에 **어느 장소를** 번호 핀으로 꽂는가 (MZ2AZ-547 G1, iOS `SearchTab/SearchMapPins.swift`, 웹 `useSearchTab.ts:100-108`).
 *
 * 첫 화면(검색어·반경·고른 작품이 없는 상태)의 지도에는 **보관함에 담은 곳만** 꽂는다 — 번호는 보관함 목록 순서다.
 * 작품을 골랐으면 그 작품의 촬영지, 검색했으면 검색 결과(목록과 같은 배열)다.
 */
object SearchMapPins {
    /** 보관함 항목을 지도 핀으로. 좌표가 없는 곳과 목록에서 빠진 곳(`delisted`)은 뺀다. 순서는 보관함 그대로. */
    fun saved(items: List<CartItem>): List<PlaceSummary> =
        items.mapNotNull { item ->
            val latitude = item.latitude
            val longitude = item.longitude
            if (item.delisted == true || latitude == null || longitude == null) return@mapNotNull null
            PlaceSummary(
                id = item.placeId,
                name = item.name,
                latitude = latitude,
                longitude = longitude,
                address = item.address,
                imageUrl = item.imageUrl,
            )
        }

    /** 지도에 꽂을 장소들 — 고른 작품 > 첫 화면(보관함) > 검색 결과. */
    fun places(
        contentSelected: Boolean,
        contentPlaces: List<PlaceSummary>,
        initial: Boolean,
        saved: List<PlaceSummary>,
        visible: List<PlaceSummary>,
    ): List<PlaceSummary> =
        when {
            contentSelected -> contentPlaces
            initial -> saved
            else -> visible
        }

    /**
     * 핀에 번호를 찍을지. 첫 화면은 보관함 순서 번호라 어느 탭이든 찍는다. 그 밖에는 목록 행 번호와 짝이 있을 때만 —
     * 첫 화면이 아닌 한 늘 짝이 있다. 인자는 규칙이 다시 갈릴 때를 위해 남긴다(iOS·웹 `numbered` 와 같은 모양).
     */
    fun numbered(
        initial: Boolean,
        tab: ListTab,
        contentSelected: Boolean,
    ): Boolean = true

    /**
     * 꽂을 핀들 — 번호는 1부터(끄면 null). 열어 둔 장소([selected])가 목록에 있으면 **그 핀만** 고른 핀이 되고(번호 유지),
     * 없으면 번호 없는 고른 핀을 맨 뒤에 하나 더 꽂는다(MZ2AZ-519, iOS `NaverMapView` 의 강조 핀).
     */
    fun marks(
        places: List<PlaceSummary>,
        numbered: Boolean,
        selected: PlaceSummary?,
    ): List<SearchPinMark> {
        val marks =
            places.mapIndexed { index, place ->
                SearchPinMark(place, if (numbered) index + 1 else null, selected = selected != null && place.id == selected.id)
            }
        if (selected == null || marks.any { it.selected }) return marks
        return marks + SearchPinMark(selected, number = null, selected = true)
    }
}
