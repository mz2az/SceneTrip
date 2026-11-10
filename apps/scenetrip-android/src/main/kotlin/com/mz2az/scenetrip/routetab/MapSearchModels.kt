package com.mz2az.scenetrip.routetab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 가게·장소 이름 검색 하나(`GET /pois?q`) — `bbox` 가 없으면 범위 없이. 못 받으면 던진다. 촬영지는 찾지 않는다(2026-11-10).
 * `source` 는 그 범위의 좌표 출처 — 기기 위치로 맞춘 지도의 범위면 위치 동의가 있어야 보낸다(MZ2AZ-472).
 */
typealias MapShopSearch = suspend (query: String, bbox: String?, limit: Int, source: CoordinateSource) -> List<PoiSummary>

/** 범위로 찾으면 그 지도의 출처, 범위 없이(「이 지역 밖에서도」) 찾으면 사용자가 고른 것. */
private fun sourceOf(
    bbox: String?,
    viewport: MapViewport?,
): CoordinateSource = if (bbox == null) CoordinateSource.USER_SELECTED else viewport?.source ?: CoordinateSource.USER_SELECTED

/**
 * 🔍 를 눌러 펼친 검색칸의 목록 (M6, iOS `MapSearchModel`) — 2글자부터, 0.35초 멈추면 가게·장소를 묻는다.
 * 늦게 온 답이 새 답을 덮지 않는다 — 새로 물으면 앞의 것을 취소한다.
 * 목록이 떠 있는 동안 지도에 찍을 곳([pinned])도 여기서 들고 있다 — 계획 화면 지도가 그린다.
 */
internal class MapSearchModel(
    private val scope: CoroutineScope,
    private val search: MapShopSearch,
) {
    var phase by mutableStateOf<MapSearchPhase>(MapSearchPhase.Idle)
        private set

    /** 지도에 찍어 둔 목록 결과 — 새 결과가 올 때까지는 앞의 것을 둔다. */
    var pinned by mutableStateOf<List<PoiSummary>>(emptyList())
        private set
    private var job: Job? = null

    private fun show(next: MapSearchPhase) {
        phase = next
        pinned = MapSearchRules.pinned(pinned, next)
    }

    fun update(
        text: String,
        wide: Boolean,
        viewport: MapViewport?,
    ) {
        job?.cancel()
        val query = MapSearchRules.query(text)
        if (query == null) {
            show(MapSearchPhase.Idle)
            return
        }
        show(MapSearchPhase.Loading)
        val bbox = MapSearchRules.bbox(wide, viewport)
        val source = sourceOf(bbox, viewport)
        job =
            scope.launch {
                delay(MapSearchRules.DEBOUNCE_MILLIS)
                try {
                    val found = search(query, bbox, MapSearchRules.LIST_LIMIT, source)
                    show(MapSearchPhase.Done(found, wide))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    show(MapSearchPhase.Failed)
                }
            }
    }

    /** 검색칸을 닫았다 — 목록과 지도의 점을 다 지운다. */
    fun reset() {
        job?.cancel()
        phase = MapSearchPhase.Idle
        pinned = emptyList()
    }
}

/** 엔터 뒤 결과 지도의 받기 (M7, iOS `MapSearchResultsModel`). 상태는 [MapSearchResults], 여기는 서버에 묻고 그 답을 넣는다. */
internal class MapSearchResultsModel(
    private val scope: CoroutineScope,
    private val search: MapShopSearch,
) {
    var state by mutableStateOf<MapSearchResults?>(null)

    /** 찾은 것이 있을 때마다 오른다 — 화면이 결과 전체에 지도를 맞춘다. */
    var fitTick by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    val isOpen: Boolean get() = state != null

    fun open(
        query: String,
        wide: Boolean,
        viewport: MapViewport?,
    ) {
        state = MapSearchResults(query, wide)
        load(viewport)
    }

    fun close() {
        job?.cancel()
        state = null
    }

    fun searchHere(viewport: MapViewport?) {
        state = state?.searchHere()
        load(viewport)
    }

    fun searchEverywhere() {
        state = state?.searchEverywhere()
        load(null)
    }

    fun update(change: (MapSearchResults) -> MapSearchResults) {
        state = state?.let(change)
    }

    private fun load(viewport: MapViewport?) {
        val current = state ?: return
        job?.cancel()
        state = current.begin()
        val bbox = MapSearchRules.bbox(current.wide, viewport)
        val source = sourceOf(bbox, viewport)
        job =
            scope.launch {
                try {
                    val found = search(current.query, bbox, MapSearchRules.RESULTS_LIMIT, source)
                    state = state?.received(found)
                    if (found.isNotEmpty()) fitTick += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state = state?.searchFailed()
                }
            }
    }
}
