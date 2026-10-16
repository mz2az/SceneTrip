package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface SearchClient {
    suspend fun search(query: String?): Pair<ContentList, PlaceList>

    suspend fun contents(
        query: String?,
        offset: Int,
    ): ContentList

    /** [source] 는 [bbox] 가 기기 위치(GPS)에서 온 범위인지다 — 위치기반서비스 동의가 필요한지를 전송 계층이 가른다(MZ2AZ-472). */
    suspend fun places(
        query: String?,
        bbox: String?,
        offset: Int,
        source: CoordinateSource = CoordinateSource.DEVICE,
    ): PlaceList
}

/** 첫 목록은 두 API를 함께 받고, 이어 받기는 같은 조건에 offset만 바꾼다. */
class ServerSearchClient : SearchClient {
    private val contentApi = ContentsApi(API_BASE)
    private val placeApi = PlacesApi(API_BASE)

    override suspend fun search(query: String?): Pair<ContentList, PlaceList> =
        coroutineScope {
            val works = async { contents(query, 0) }
            val spots = async { places(query, null, 0) }
            works.await() to spots.await()
        }

    override suspend fun contents(
        query: String?,
        offset: Int,
    ): ContentList =
        withContext(Dispatchers.IO) {
            contentApi.listContents(q = query, limit = CONTENT_PAGE_SIZE, offset = offset)
        }

    override suspend fun places(
        query: String?,
        bbox: String?,
        offset: Int,
        source: CoordinateSource,
    ): PlaceList =
        withContext(Dispatchers.IO) {
            CoordinateSources.with(source) {
                placeApi.listPlaces(q = query, bbox = bbox, limit = PLACE_PAGE_SIZE, offset = offset)
            }
        }

    companion object {
        const val CONTENT_PAGE_SIZE = 50
        const val PLACE_PAGE_SIZE = 200
    }
}
