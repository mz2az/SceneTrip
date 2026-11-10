package com.mz2az.scenetrip.routetab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 가게·장소 검색의 한 쪽 — 가게와 조건에 맞는 전체 개수(`PoiList.total`). */
data class MapShopPage(
    val shops: List<PoiSummary>,
    val total: Int?,
)

/**
 * 가게·장소 이름 검색 한 쪽(`GET /pois?q`) — 범위 없이(§10-2). 못 받으면 던진다. 촬영지는 찾지 않는다(2026-11-10).
 * [origin] 이 있으면 거기서 가까운 순(`lat`·`lng`·`sort=distance`, §8), 없으면 서버 기본 이름순. 좌표 출처는 기준점의 출처 —
 * 기기 위치로 맞춘 화면의 가운데면 위치 동의가 있어야 보낸다(MZ2AZ-472).
 */
typealias MapShopSearch = suspend (query: String, origin: MapSearchOrigin?, limit: Int, offset: Int) -> MapShopPage

/**
 * 🔍 를 눌러 펼친 검색칸의 목록 (M6, iOS `MapSearchModel`) — 2글자부터, 0.35초 멈추면 가게·장소를 묻는다.
 * 늦게 온 답이 새 답을 덮지 않는다 — 새로 물으면 앞의 것을 취소한다.
 * 글자 치는 동안은 목록만 — 지도에는 찍지 않는다(§7). [pinned] 는 규칙(`MapSearchRules.pinned`)을 따라 들고 있지만 지도가 그리지 않는다.
 */
internal class MapSearchModel(
    private val scope: CoroutineScope,
    private val search: MapShopSearch,
) {
    var phase by mutableStateOf<MapSearchPhase>(MapSearchPhase.Idle)
        private set

    /** 목록 결과 — 새 결과가 올 때까지는 앞의 것을 둔다. */
    var pinned by mutableStateOf<List<PoiSummary>>(emptyList())
        private set
    private var job: Job? = null

    private fun show(next: MapSearchPhase) {
        phase = next
        pinned = MapSearchRules.pinned(pinned, next)
    }

    /** 범위 없이 화면 가운데에서 가까운 순(§10-2). */
    fun update(
        text: String,
        viewport: MapViewport?,
    ) {
        job?.cancel()
        val query = MapSearchRules.query(text)
        if (query == null) {
            show(MapSearchPhase.Idle)
            return
        }
        show(MapSearchPhase.Loading)
        val origin = MapSearchPagingRules.origin(viewport)
        job =
            scope.launch {
                delay(MapSearchRules.DEBOUNCE_MILLIS)
                try {
                    val found = search(query, origin, MapSearchRules.LIST_LIMIT, 0).shops
                    show(MapSearchPhase.Done(found))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    show(MapSearchPhase.Failed)
                }
            }
    }

    /** 검색칸을 닫았다 — 목록을 다 지운다. */
    fun reset() {
        job?.cancel()
        phase = MapSearchPhase.Idle
        pinned = emptyList()
    }
}

/**
 * 엔터 뒤 결과 지도의 받기 (M7, iOS `MapSearchResultsModel`). 상태는 [MapSearchResults], 여기는 서버에 묻고 그 답을 넣는다.
 * 처음 쪽 20개, 목록 끝이 보이면 같은 기준점으로 다음 20개(§7-4, [MapSearchPagingRules]). 범위 없이 가까운 순(§10-2).
 */
internal class MapSearchResultsModel(
    private val scope: CoroutineScope,
    private val search: MapShopSearch,
) {
    var state by mutableStateOf<MapSearchResults?>(null)

    /** 처음 쪽에서 찾은 것이 있을 때마다 오른다 — 화면이 결과 전체에 지도를 맞춘다. 더 받을 때는 오르지 않는다. */
    var fitTick by mutableIntStateOf(0)
        private set
    private var job: Job? = null
    private var moreJob: Job? = null

    /** 처음 쪽을 물은 기준점 — 다음 쪽도 같은 것으로 묻는다(지도를 옮겨도 「다시 찾기」 전까지는 그대로). */
    private var pageOrigin: MapSearchOrigin? = null

    val isOpen: Boolean get() = state != null

    fun open(
        query: String,
        viewport: MapViewport?,
    ) {
        state = MapSearchResults(query)
        load(viewport)
    }

    fun close() {
        job?.cancel()
        moreJob?.cancel()
        state = null
    }

    fun searchHere(viewport: MapViewport?) {
        state = state?.searchHere()
        load(viewport)
    }

    fun update(change: (MapSearchResults) -> MapSearchResults) {
        state = state?.let(change)
    }

    /** 목록의 [index] 번 줄까지 보였다 — 끝에서 한 줄 안이면 다음 쪽을 묻는다. */
    fun rowVisible(index: Int) {
        val shops = state?.shops ?: return
        if (MapSearchPagingRules.shouldLoadMore(index, shops.size)) loadMore()
    }

    /** 「더 불러오지 못했어요 · 다시 시도」. */
    fun retryMore() {
        state = state?.retryMore()
        loadMore()
    }

    private fun loadMore() {
        val current = state ?: return
        val offset = current.nextOffset ?: return
        state = current.beginMore()
        val origin = pageOrigin
        val ask = current.ask
        moreJob =
            scope.launch {
                try {
                    val page = search(current.query, origin, MapSearchPagingRules.PAGE_SIZE, offset)
                    if (state?.ask == ask) state = state?.receivedMore(page.shops, page.total)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (state?.ask == ask) state = state?.moreFailedNow()
                }
            }
    }

    private fun load(viewport: MapViewport?) {
        val current = state ?: return
        job?.cancel()
        moreJob?.cancel()
        state = current.begin()
        val origin = MapSearchPagingRules.origin(viewport)
        pageOrigin = origin
        job =
            scope.launch {
                try {
                    val page = search(current.query, origin, MapSearchPagingRules.PAGE_SIZE, 0)
                    state = state?.received(page.shops, page.total)
                    if (page.shops.isNotEmpty() && MapSearchPagingRules.fitsMap(0)) fitTick += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state = state?.searchFailed()
                }
            }
    }
}
