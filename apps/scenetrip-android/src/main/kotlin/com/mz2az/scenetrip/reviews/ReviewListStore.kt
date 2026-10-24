package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewList
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 목록 화면의 큰 상태 (iOS `ReviewListStore.Phase`) — 첫 쪽을 기다리는 동안은 스피너만, 못 받으면 「다시 시도」, 받으면 목록. */
enum class ReviewListPhase { LOADING, LOADED, FAILED }

/** 리뷰 시트가 보여 주는 목록 한 벌. */
data class ReviewListUi(
    val reviews: List<Review> = emptyList(),
    val summary: RatingSummary? = null,
    val total: Int = 0,
    /** 서버에서 받은 줄 수 — 다음 쪽을 어디부터 받을지. 차단으로 줄을 바로 빼면 같이 줄어든다. */
    val offset: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val phase: ReviewListPhase = ReviewListPhase.LOADING,
    /** 다음 쪽을 못 받았다. 돌아가는 표시 대신 「더 불러오지 못했어요 · 다시 시도」 를 보인다. */
    val moreFailed: Boolean = false,
)

/**
 * 리뷰 시트의 목록 받기 (iOS `ReviewListStore`). 늦게 온 응답이 새 목록을 덮지 못하게 **세대**를 센다 —
 * 차단한 사람의 리뷰를 담고 나간 요청이 차단 뒤에 도착해도 그 줄을 되살리지 않는다(MZ2AZ-461).
 */
class ReviewListStore(
    private val fetch: suspend (sort: ReviewSort, offset: Int) -> ReviewList,
    private val accountEpoch: () -> Int,
) {
    private val state = MutableStateFlow(ReviewListUi())
    val ui: StateFlow<ReviewListUi> = state.asStateFlow()

    /** 통째로 다시 받거나 줄을 뺄 때마다 오른다 — 그 전에 떠난 응답을 버린다. */
    private var generation = 0

    /**
     * [reset] 이면 첫 쪽부터 다시 받는다 — 나가 있는 요청이 있어도 그것을 버리고 새로 받는다.
     * 아니면 다음 쪽이며, 이미 받는 중이면 아무것도 하지 않는다.
     */
    suspend fun load(
        sort: ReviewSort,
        reset: Boolean = false,
    ) {
        if (state.value.loading && !reset) return
        val epoch = accountEpoch()
        val request = ++generation
        val from = if (reset) 0 else state.value.offset
        state.update { if (reset) it.copy(loading = true, phase = ReviewListPhase.LOADING) else it.copy(loading = true) }
        try {
            apiResult { fetch(sort, from) }.fold(
                onSuccess = { page ->
                    if (epoch == accountEpoch() && request == generation) {
                        state.update {
                            val merged = ((if (reset) emptyList() else it.reviews) + page.items).distinctBy { row -> row.id }
                            val received = from + page.items.size
                            it.copy(
                                reviews = merged,
                                summary = page.summary,
                                offset = received,
                                total = if (page.items.isEmpty()) received else page.total,
                                error = null,
                                phase = ReviewListPhase.LOADED,
                                moreFailed = if (reset) false else it.moreFailed,
                            )
                        }
                    }
                },
                onFailure = {
                    if (epoch == accountEpoch() &&
                        request == generation
                    ) {
                        // 다음 쪽을 못 받은 것은 목록을 그대로 두고 푸터만 바꾼다 — 첫 쪽을 못 받은 것과 다르다(iOS `moreFailed`).
                        state.update { s ->
                            val message = NetworkFailure.of(it).message
                            if (reset) {
                                s.copy(error = message, phase = ReviewListPhase.FAILED)
                            } else {
                                s.copy(error = message, moreFailed = true)
                            }
                        }
                    }
                },
            )
        } finally {
            if (request == generation) state.update { it.copy(loading = false) }
        }
    }

    /** 다음 쪽 푸터의 「다시 시도」 — 실패 표시를 풀면 푸터가 다시 받는다. */
    fun retryMore() {
        state.update { it.copy(moreFailed = false) }
    }

    /** 첫 쪽 실패 화면의 「다시 시도」 — 스피너부터 다시 보인다. */
    fun prepareRetry() {
        state.update { it.copy(phase = ReviewListPhase.LOADING) }
    }

    /**
     * 정렬을 바꾼다 — 새 첫 쪽이 올 때까지 다음 쪽을 받지 않는다. 옛 목록 끝에 새 정렬의 둘째 쪽이 붙지 않게 나가 있던 받기를 버리고
     * 스피너만 보인다 (iOS `prepareSortChange`).
     */
    fun prepareSortChange() {
        generation++
        state.update { it.copy(total = it.reviews.size, phase = ReviewListPhase.LOADING, loading = false, moreFailed = false) }
    }

    /**
     * 차단한 사람의 리뷰를 다시 받기 전에 바로 뺀다(MZ2AZ-461). 앱은 작성자 번호를 몰라 그 사람의 다른 리뷰는 다시 받은 목록에서
     * 빠진다. 나가 있던 받기는 그 리뷰를 담고 있을 수 있어 버린다.
     */
    fun drop(reviewId: Long) {
        generation++
        state.update {
            if (it.reviews.none { row -> row.id == reviewId }) {
                it.copy(loading = false)
            } else {
                val rest = it.reviews.filterNot { row -> row.id == reviewId }
                it.copy(
                    reviews = rest,
                    total = maxOf(rest.size, it.total - 1),
                    offset = maxOf(0, it.offset - 1),
                    loading = false,
                )
            }
        }
    }

    /** 다른 화면에서 차단·풀기가 일어났다 — 나가 있는 받기를 버린다. 이어서 [load] 를 `reset` 으로 부른다. */
    fun invalidate() {
        generation++
        state.update { it.copy(loading = false) }
    }
}
