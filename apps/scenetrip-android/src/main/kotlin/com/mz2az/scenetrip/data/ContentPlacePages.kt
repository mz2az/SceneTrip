package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/** 작품별 촬영지는 전체 장소 API와 다른 조회 상한을 사용한다. */
class ContentPlacePages(
    private val fetch: suspend (Long, Int, Int) -> PlaceList,
) {
    suspend fun load(contentId: Long): Result<List<PlaceSummary>> =
        apiResult {
            require(contentId > 0)
            var paging = Paging()
            var places = emptyList<PlaceSummary>()
            while (paging.hasMore) {
                val page = fetch(contentId, PAGE_SIZE, paging.offset)
                places = Paging.merged(places, page.items) { it.id }
                paging = paging.advanced(page.items.size, page.total)
            }
            places
        }

    companion object {
        const val PAGE_SIZE = 100
    }
}
