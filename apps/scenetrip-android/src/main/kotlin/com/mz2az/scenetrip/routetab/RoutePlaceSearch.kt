package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.Paging
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 코스 편집의 「장소 검색」 이 서버에 묻는 것 (MZ2AZ-372, MZ2AZ-482 E10). iOS `RoutePlaceSearch`.
 *
 * 전에는 [com.mz2az.scenetrip.data.RouteStore] 가 받아 둔 촬영지(인기 상위 200곳) 안에서 글자를 걸렀다. 촬영지가 486곳이 되자
 * 「Gyeongbok」 을 쳐도 결과가 없었다 — 경복궁이 그 200곳 밖이었다. 이제 **서버 검색**(`GET /places?q=`)이다. 검색 탭의 장소 탭과 같은
 * 창구라 같은 말에 같은 곳이 나온다.
 *
 * - **글자마다 부르지 않는다.** 입력이 멎고 [DEBOUNCE_MS] 뒤에 한 번. 그 사이 또 치면 앞의 것을 버린다.
 * - **늦게 온 답이 새 답을 덮지 않는다.** 새로 물으면 앞 요청을 취소하고, 취소된 것은 화면에 닿지 않는다.
 * - 재시도는 공통 계층이 한다 — 조회라서 끊김·502·503·504 에 세 번. 여기서 또 돌리지 않는다.
 * - 빈 검색어면 인기순 첫 쪽을 보인다 — 빈 화면보다 무엇이든 있는 편이 다음 행동을 부른다.
 *
 * 서버 검색은 **이름·별칭·설명·작품·출연진**을 본다(계약 `GET /places` 의 `q`). 주소는 보지 않는다 — 전에는 주소로도 걸렸다.
 */
class RoutePlaceSearch(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val fetch: suspend (query: String?, limit: Int, offset: Int) -> PlaceList = { query, limit, offset ->
        PlacesApi(API_BASE).listPlaces(q = query, limit = limit, offset = offset)
    },
) {
    enum class Phase { LOADING, LOADED, FAILED }

    @Volatile var phase: Phase = Phase.LOADING
        private set

    @Volatile var failure: ApiFailure? = null
        private set

    @Volatile var results: List<PlaceSummary> = emptyList()
        private set

    @Volatile var paging: Paging = Paging()
        private set

    @Volatile var loadingMore: Boolean = false
        private set

    @Volatile var moreFailed: Boolean = false
        private set

    /** 상태가 바뀔 때마다 불린다 — 화면이 다시 그리도록 한다(Compose 상태를 들지 않아 JVM 시험이 그대로 돌린다). */
    var onChange: (() -> Unit)? = null

    private var job: Job? = null
    private var keyword: String? = null

    /** 지금까지 결과에 나온 곳. 검색어를 바꿔 목록에서 사라진 뒤에도 **고른 것**을 되짚는다. */
    private val seen = mutableMapOf<Long, PlaceSummary>()

    /** 입력이 바뀔 때마다 부른다. */
    fun search(text: String) {
        val wanted = keyword(text)
        job?.cancel()
        phase = Phase.LOADING
        loadingMore = false
        moreFailed = false
        onChange?.invoke()
        job =
            scope.launch {
                val wait = delayMillis(wanted, debounceMs)
                if (wait > 0) delay(wait)
                apiResult { fetch(wanted, PAGE_SIZE, 0) }
                    .onSuccess { page ->
                        keyword = wanted
                        results = page.items
                        paging = Paging().advanced(received = page.items.size, total = page.total)
                        remember(page.items)
                        failure = null
                        phase = Phase.LOADED
                        onChange?.invoke()
                    }.onFailure {
                        failure = ApiFailure.of(it)
                        phase = Phase.FAILED
                        onChange?.invoke()
                    }
            }
    }

    /** 목록 끝이 보이면 다음 쪽. */
    fun loadMore() {
        if (phase != Phase.LOADED || loadingMore || !paging.hasMore) return
        loadingMore = true
        moreFailed = false
        onChange?.invoke()
        val asked = keyword
        val offset = paging.offset
        val running = job
        scope.launch {
            val answer = apiResult { fetch(asked, PAGE_SIZE, offset) }
            // 받는 사이 검색어가 바뀌었으면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
            if (job != running || phase != Phase.LOADED) return@launch
            loadingMore = false
            answer
                .onSuccess { page ->
                    results = Paging.merged(results, page.items) { it.id }
                    paging = paging.advanced(received = page.items.size, total = page.total)
                    remember(page.items)
                }.onFailure { moreFailed = true }
            onChange?.invoke()
        }
    }

    fun place(id: Long): PlaceSummary? = seen[id]

    private fun remember(places: List<PlaceSummary>) {
        places.forEach { seen[it.id] = it }
    }

    companion object {
        /** 입력이 멎고 서버에 묻기까지. */
        const val DEBOUNCE_MS = 350L
        const val PAGE_SIZE = 40

        /** 계약의 `q` 최대 길이. */
        const val MAX_LENGTH = 100

        /** 입력을 서버에 보낼 검색어로. 앞뒤 공백을 떼고, 비었으면 null(전체), 길면 계약의 상한에서 자른다. */
        fun keyword(text: String): String? = text.trim().takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)

        /** 기다렸다 물을 것인가. 빈 검색어(처음 열 때·지웠을 때)는 바로 보인다 — 기다릴 타자가 없다. */
        fun delayMillis(
            keyword: String?,
            debounceMs: Long = DEBOUNCE_MS,
        ): Long = if (keyword == null) 0 else debounceMs
    }
}
