package com.mz2az.scenetrip.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.analytics.UsageEvents
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.SearchApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import com.mz2az.scenetrip.sceneapi.client.model.UsageEntityType
import kotlinx.coroutines.CoroutineScope

/** 로컬 빌드는 에뮬레이터의 호스트 주소, 배포 빌드는 빌드 설정의 API 주소를 사용한다. */
const val API_BASE = ApiConfiguration.BASE_URL

/** 서버 검색·총수·이어 받기 상태를 화면에서 구독한다. */
class SceneData(
    scope: CoroutineScope,
) {
    enum class Phase { LOADING, LOADED, FAILED }

    var state by mutableStateOf(SearchState())
        private set
    private val session =
        SearchSession(
            scope,
            ServerSearchClient(),
            recordUsage = UsageEvents::record,
            onCommit = { query, kind -> AppAnalytics.log(AppEvent.Search(query.length, kind ?: "typed")) },
            onChanged = { state = it },
        )
    val phase get() = Phase.valueOf(state.phase.name)
    val contents get() = state.contents
    val places get() = state.places
    val failure get() = state.failure
    val contentPaging get() = state.contentPaging
    val placePaging get() = state.placePaging
    val listSerial get() = state.listSerial

    private val contentsApi = ContentsApi(API_BASE)
    private val placesApi = PlacesApi(API_BASE)
    private val searchApi = SearchApi(API_BASE)
    private val contentPlaces =
        ContentPlacePages { id, limit, offset ->
            contentsApi.listContentPlaces(id, limit = limit, offset = offset)
        }

    fun search(query: String) = session.search(query)

    fun commitSearch(
        query: String,
        kind: String?,
        target: UsageEntityType,
    ) = session.commitSearch(query, kind, target)

    fun searchInViewport(bbox: String) = session.searchInViewport(bbox)

    fun retry() = session.retry()

    fun loadMoreContents() = session.loadMoreContents()

    fun loadMorePlaces() = session.loadMorePlaces()

    suspend fun loadNextPlaces() = session.loadNextPlaces()

    /** 작품에 연결된 촬영지를 끝까지 받는다. 취소는 API 실패로 바꾸지 않는다. */
    suspend fun placesOf(contentId: Long): Result<List<PlaceSummary>> = contentPlaces.load(contentId)

    suspend fun contentDetail(id: Long) = apiResult { contentsApi.getContent(id) }.getOrNull()

    suspend fun placeDetail(id: Long) = apiResult { placesApi.getPlace(id) }

    suspend fun suggest(query: String): List<Suggestion> =
        apiResult { searchApi.suggest(q = query, limit = 8).items }.getOrDefault(emptyList())
}
