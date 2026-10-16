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

/** 리뷰 시트가 보여 주는 목록 한 벌. */
data class ReviewListUi(
    val reviews: List<Review> = emptyList(),
    val summary: RatingSummary? = null,
    val total: Int = 0,
    /** 서버에서 받은 줄 수 — 다음 쪽을 어디부터 받을지. 차단으로 줄을 바로 빼면 같이 줄어든다. */
    val offset: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
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
        state.update { it.copy(loading = true) }
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
                            )
                        }
                    }
                },
                onFailure = {
                    if (epoch == accountEpoch() &&
                        request == generation
                    ) {
                        state.update { s -> s.copy(error = NetworkFailure.of(it).message) }
                    }
                },
            )
        } finally {
            if (request == generation) state.update { it.copy(loading = false) }
        }
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
