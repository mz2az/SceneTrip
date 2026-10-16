package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.analytics.SearchUsageIntent
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.UsageEntityType
import com.mz2az.scenetrip.sceneapi.client.model.UsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class SearchPhase { LOADING, LOADED, FAILED }

data class SearchState(
    val phase: SearchPhase = SearchPhase.LOADING,
    val contents: List<ContentSummary> = emptyList(),
    val places: List<PlaceSummary> = emptyList(),
    val failure: ApiFailure? = null,
    val contentPaging: Paging = Paging(),
    val placePaging: Paging = Paging(),
    val loadingMoreContents: Boolean = false,
    val loadingMorePlaces: Boolean = false,
    val moreContentsFailed: Boolean = false,
    val morePlacesFailed: Boolean = false,
    val listSerial: Int = 0,
)

/** 취소된 검색·이전 페이지를 버리고, 검색 확정에만 이용 기록을 보낸다. */
class SearchSession(
    private val scope: CoroutineScope,
    private val client: SearchClient,
    private val recordUsage: (UsageEvent) -> Unit = {},
    private val onCommit: (String, String?) -> Unit = { _, _ -> },
    private val onChanged: (SearchState) -> Unit = {},
) {
    var state = SearchState()
        private set
    private var generation = 0
    private var request: Job? = null
    private var moreWorks: Job? = null
    private var moreSpots: Job? = null
    private var query: String? = null
    private var contentQuery: String? = null
    private var bbox: String? = null

    // 지도 범위가 GPS 에서 왔는지 — 이어 받기·다시 시도도 최초 출처를 그대로 쓴다(MZ2AZ-472).
    private var bboxSource = CoordinateSource.DEVICE
    private var lastCall: (() -> Unit)? = null

    fun commitSearch(
        query: String,
        kind: String?,
        target: UsageEntityType,
    ) {
        val term = query.trim()
        if (term.isNotEmpty()) onCommit(term, kind)
        search(query, SearchUsageIntent.make(query, target))
    }

    fun search(
        query: String,
        usage: SearchUsageIntent? = null,
    ) {
        lastCall = { search(query) }
        reset(false)
        this.query = query.trim().ifEmpty { null }
        contentQuery = this.query
        bbox = null
        val run = generation
        val keyword = this.query
        request =
            scope.launch {
                try {
                    val (works, spots) = client.search(keyword)
                    ensureActive()
                    if (run != generation) return@launch
                    publish(
                        state.copy(
                            phase = SearchPhase.LOADED,
                            contents = works.items,
                            places = spots.items,
                            contentPaging = Paging().advanced(works.items.size, works.total),
                            placePaging = Paging().advanced(spots.items.size, spots.total),
                            listSerial = state.listSerial + 1,
                        ),
                    )
                    usage?.event(works.total, spots.total)?.let(recordUsage)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (run == generation) publish(state.copy(phase = SearchPhase.FAILED, failure = ApiFailure.of(error)))
                }
            }
    }

    fun searchInViewport(
        bbox: String,
        source: CoordinateSource = CoordinateSource.DEVICE,
    ) {
        lastCall = { searchInViewport(bbox, source) }
        reset(true)
        this.bbox = bbox
        bboxSource = source
        query = null
        val run = generation
        request =
            scope.launch {
                try {
                    val spots = client.places(null, bbox, 0, source)
                    ensureActive()
                    if (run != generation) return@launch
                    publish(
                        state.copy(
                            phase = SearchPhase.LOADED,
                            places = spots.items,
                            placePaging = Paging().advanced(spots.items.size, spots.total),
                            listSerial = state.listSerial + 1,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (run == generation) publish(state.copy(phase = SearchPhase.FAILED, failure = ApiFailure.of(error)))
                }
            }
    }

    fun retry() {
        lastCall?.invoke()
    }

    fun loadMoreContents() {
        if (moreWorks?.isActive != true && !state.loadingMoreContents) moreWorks = scope.launch { loadNextContents() }
    }

    fun loadMorePlaces() {
        if (moreSpots?.isActive != true && !state.loadingMorePlaces) moreSpots = scope.launch { loadNextPlaces() }
    }

    suspend fun loadNextContents(): Boolean {
        if (state.phase != SearchPhase.LOADED || state.loadingMoreContents || !state.contentPaging.hasMore) return false
        val run = generation
        val keyword = contentQuery
        val offset = state.contentPaging.offset
        publish(state.copy(loadingMoreContents = true, moreContentsFailed = false))
        try {
            val page = client.contents(keyword, offset)
            coroutineContext.ensureActive()
            if (run != generation) return false
            publish(
                state.copy(
                    contents = Paging.merged(state.contents, page.items) { it.id },
                    contentPaging = state.contentPaging.advanced(page.items.size, page.total),
                ),
            )
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (run == generation) publish(state.copy(moreContentsFailed = true))
        } finally {
            if (run == generation) publish(state.copy(loadingMoreContents = false))
        }
        return false
    }

    suspend fun loadNextPlaces(): Boolean {
        if (state.phase != SearchPhase.LOADED || state.loadingMorePlaces || !state.placePaging.hasMore) return false
        val run = generation
        val keyword = query
        val viewport = bbox
        val offset = state.placePaging.offset
        publish(state.copy(loadingMorePlaces = true, morePlacesFailed = false))
        try {
            val page = client.places(keyword, viewport, offset, bboxSource)
            coroutineContext.ensureActive()
            if (run != generation) return false
            publish(
                state.copy(
                    places = Paging.merged(state.places, page.items) { it.id },
                    placePaging = state.placePaging.advanced(page.items.size, page.total),
                ),
            )
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (run == generation) publish(state.copy(morePlacesFailed = true))
        } finally {
            if (run == generation) publish(state.copy(loadingMorePlaces = false))
        }
        return false
    }

    private fun reset(viewportOnly: Boolean) {
        generation++
        request?.cancel()
        moreWorks?.cancel()
        moreSpots?.cancel()
        publish(
            state.copy(
                phase = SearchPhase.LOADING,
                failure = null,
                contentPaging = if (viewportOnly) state.contentPaging else Paging(),
                placePaging = Paging(),
                loadingMoreContents = false,
                loadingMorePlaces = false,
                moreContentsFailed = false,
                morePlacesFailed = false,
            ),
        )
    }

    private fun publish(next: SearchState) {
        state = next
        onChanged(next)
    }
}
