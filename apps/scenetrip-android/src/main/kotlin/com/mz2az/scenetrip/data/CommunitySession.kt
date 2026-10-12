package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import kotlinx.coroutines.CancellationException

data class CommunityState(
    val posts: List<CommunityPost> = emptyList(),
    val mine: List<CommunityPost> = emptyList(),
    val loading: Boolean = false,
    val mineLoading: Boolean = false,
    val message: String? = null,
    val total: Int = 0,
    val mineTotal: Int = 0,
)

/** 늦은 응답이 다른 계정의 목록을 덮지 않고, 새로고침 실패도 기존 글을 유지한다. */
class CommunitySession(
    private val client: CommunityClient,
    private val accountEpoch: () -> Int = { 0 },
    private val onChanged: (CommunityState) -> Unit = {},
) {
    var state = CommunityState()
        private set
    private var loadedEpoch: Int? = null
    private var revision = 0

    fun syncAccount() {
        val epoch = accountEpoch()
        if (loadedEpoch == epoch) return
        loadedEpoch = epoch
        revision++
        publish(CommunityState())
    }

    suspend fun refresh(
        mine: Boolean = false,
        more: Boolean = false,
    ) {
        syncAccount()
        if (if (mine) state.mineLoading else state.loading) return
        val epoch = accountEpoch()
        val generation = revision
        val old = if (mine) state.mine else state.posts
        publish(if (mine) state.copy(mineLoading = true, message = null) else state.copy(loading = true, message = null))
        try {
            val page = client.list(mine, CommunityRules.PAGE_SIZE, if (more) old.size else 0)
            if (!current(epoch, generation)) return
            val merged = if (more) old.toMutableList() else mutableListOf()
            for (summary in page.items) {
                val post = CommunityPost.from(summary)
                val index = merged.indexOfFirst { it.id == post.id }
                if (index < 0) merged.add(post) else merged[index] = post
            }
            publish(
                if (mine) {
                    state.copy(mine = merged.toList(), mineTotal = page.total)
                } else {
                    state.copy(posts = merged.toList(), total = page.total)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (current(epoch, generation)) publish(state.copy(message = CommunityRules.failureText(error)))
        } finally {
            if (current(epoch, generation)) publish(if (mine) state.copy(mineLoading = false) else state.copy(loading = false))
        }
    }

    fun accept(detail: TripPostDetail) {
        syncAccount()
        val post = CommunityPost.from(detail)
        val known = state.posts.any { it.id == post.id }
        val knownMine = state.mine.any { it.id == post.id }
        publish(
            state.copy(
                posts = listOf(post) + state.posts.filterNot { it.id == post.id },
                mine = if (post.isMine) listOf(post) + state.mine.filterNot { it.id == post.id } else state.mine,
                total = state.total + if (known) 0 else 1,
                mineTotal = state.mineTotal + if (post.isMine && !knownMine) 1 else 0,
            ),
        )
    }

    suspend fun detail(post: CommunityPost): CommunityPost {
        val id = post.serverId ?: return post
        val epoch = accountEpoch()
        val result = client.detail(id)
        if (epoch != accountEpoch()) throw CancellationException("계정이 바뀌었다")
        return CommunityPost.from(result)
    }

    suspend fun remove(post: CommunityPost) {
        if (!post.isMine) return
        val id = post.serverId ?: return
        val epoch = accountEpoch()
        try {
            client.delete(id)
            if (epoch != accountEpoch()) return
            publish(
                state.copy(
                    posts = state.posts.filterNot { it.serverId == id },
                    mine = state.mine.filterNot { it.serverId == id },
                    total = (state.total - 1).coerceAtLeast(0),
                    mineTotal = (state.mineTotal - 1).coerceAtLeast(0),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (epoch == accountEpoch()) publish(state.copy(message = CommunityRules.failureText(error)))
        }
    }

    suspend fun saveCourse(post: CommunityPost): CourseDetail {
        val id = requireNotNull(post.serverId)
        val epoch = accountEpoch()
        val course = client.saveCourse(id)
        if (epoch != accountEpoch()) throw CancellationException("계정이 바뀌었다")
        return course
    }

    fun clearMine() {
        publish(state.copy(mine = emptyList(), mineTotal = 0))
    }

    private fun current(
        epoch: Int,
        generation: Int,
    ): Boolean = epoch == accountEpoch() && generation == revision

    private fun publish(value: CommunityState) {
        state = value
        onChanged(value)
    }
}
