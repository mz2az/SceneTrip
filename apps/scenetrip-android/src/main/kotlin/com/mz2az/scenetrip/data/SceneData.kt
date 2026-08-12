package com.mz2az.scenetrip.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.SearchApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱이 서버를 부르는 주소.
 *
 * **에뮬레이터에서 `localhost` 는 에뮬레이터 자신이다.** 맥에서 도는 scene-api 는
 * 특수 주소 `10.0.2.2` 로 가리킨다 — iOS 시뮬레이터가 맥과 네트워크를 공유해
 * `localhost` 로 닿는 것과 다른 점이다 (iOS 는 `SceneTripApp.swift` 에서
 * `http://localhost:8081/v1`).
 *
 * 실기기에서는 둘 다 안 되므로 나중에 설정으로 뺀다.
 */
const val API_BASE = "http://10.0.2.2:8081/v1"

/**
 * 화면이 쓰는 데이터 저장소. **서버가 정본이다.**
 *
 * iOS `Models/SceneData.swift` 를 옮긴 것이다. 검색은 서버가 한다 —
 * `GET /contents` 와 `GET /places` 에 **같은 `q` 를 넣으면** 두 탭이 같이 채워진다.
 * 배우 이름으로도 장소가 걸리도록 서버가 이미 넓혀 뒀으므로(MZ2AZ-167) 프론트가
 * 우회할 것이 없다 — 그래서 이 타입에는 검색 로직이 없다.
 *
 * **손으로 쓴 API 클라이언트가 아니다.** 아래 `ContentsApi`·`PlacesApi` 는 계약에서
 * 생성한 것이다 (`//contracts/openapi:scene_api_kotlin_lib`).
 */
class SceneData(
    private val scope: CoroutineScope,
) {
    enum class Phase { LOADING, LOADED, FAILED }

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var contents by mutableStateOf<List<ContentSummary>>(emptyList())
        private set
    var places by mutableStateOf<List<PlaceSummary>>(emptyList())
        private set
    var failure by mutableStateOf<ApiFailure?>(null)
        private set

    private val contentsApi = ContentsApi(API_BASE)
    private val placesApi = PlacesApi(API_BASE)
    private val searchApi = SearchApi(API_BASE)

    // 앞선 요청이 늦게 도착해 최신 결과를 덮지 않도록 취소한다 — iOS 의 `inFlight`.
    private var inFlight: Job? = null

    /** 마지막으로 부른 것. 「다시 시도」가 같은 요청을 되풀이하는 데 쓴다. */
    private var lastCall: (() -> Unit)? = null

    /** 검색어 하나로 두 탭을 채운다. 빈 문자열이면 전체를 받는다. */
    fun search(query: String) {
        lastCall = { search(query) }
        inFlight?.cancel()
        phase = Phase.LOADING
        val keyword = query.trim().ifEmpty { null }
        inFlight =
            scope.launch {
                runCatching {
                    // 생성된 클라이언트는 동기(블로킹) 호출이라 IO 로 옮긴다.
                    withContext(Dispatchers.IO) {
                        val works = contentsApi.listContents(q = keyword, limit = 100)
                        val spots = placesApi.listPlaces(q = keyword, limit = 200)
                        works to spots
                    }
                }.onSuccess { (works, spots) ->
                    contents = works.items ?: emptyList()
                    places = spots.items ?: emptyList()
                    failure = null
                    phase = Phase.LOADED
                }.onFailure {
                    failure = ApiFailure.of(it)
                    phase = Phase.FAILED
                }
            }
    }

    /**
     * **지금 화면에 보이는 지도 범위** 안의 촬영지만.
     *
     * 반경이 아니라 뷰포트다. 사용자가 얼마나 확대했는지는 그때그때 다르고, 이
     * 기능은 **보이는 그대로**를 묻는 것이다. `q` 를 함께 보내지 않는다 — "이 화면
     * 안" 과 "이 단어" 는 서로 다른 질문이라 섞으면 결과를 설명할 수 없다.
     */
    fun searchInViewport(bbox: String) {
        lastCall = { searchInViewport(bbox) }
        inFlight?.cancel()
        phase = Phase.LOADING
        inFlight =
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { placesApi.listPlaces(bbox = bbox, limit = 200) }
                }.onSuccess {
                    places = it.items ?: emptyList()
                    failure = null
                    phase = Phase.LOADED
                }.onFailure {
                    failure = ApiFailure.of(it)
                    phase = Phase.FAILED
                }
            }
    }

    /** 고른 작품의 촬영지 (드릴다운 2단). */
    suspend fun placesOf(contentId: Long): List<PlaceSummary> =
        withContext(Dispatchers.IO) {
            runCatching { contentsApi.listContentPlaces(contentId, limit = 100).items ?: emptyList() }
                .getOrDefault(emptyList())
        }

    /** 마지막 요청을 되풀이한다. iOS `data.retry()` 와 같다. */
    fun retry() {
        lastCall?.invoke()
    }

    /** 작품 상세 — 줄거리·출연진은 여기에만 있다. */
    suspend fun contentDetail(id: Long) =
        withContext(Dispatchers.IO) {
            runCatching { contentsApi.getContent(id) }.getOrNull()
        }

    /** 촬영지 상세 — 작품별 장면은 여기에만 있다. */
    suspend fun placeDetail(id: Long) =
        withContext(Dispatchers.IO) {
            runCatching { placesApi.getPlace(id) }.getOrNull()
        }

    /** 자동완성. 글자마다 부르지만 결과가 늦게 오면 버린다 — 호출부가 판단한다. */
    suspend fun suggest(query: String): List<Suggestion> =
        withContext(Dispatchers.IO) {
            runCatching { searchApi.suggest(q = query, limit = 8).items ?: emptyList() }
                .getOrDefault(emptyList())
        }
}
