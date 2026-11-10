package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
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
    /** 모두의 목록 조건(1.24.0) — 게시판·검색어. 탭을 떠나면 기본(여행후기, 검색 없음)으로 돌린다. */
    val board: TripPostBoard = TripPostBoard.review,
    val query: String? = null,
    /** 모두의 목록 다음 쪽 커서. 서버가 주지 않았으면 null — 그때 다음 쪽은 옛 길(받은 줄 수만큼 건너뛰기)이다. */
    val nextCursor: String? = null,
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

    // 피드와 내 글은 따로 센다 — 차단은 피드의 나가 있는 받기만 버리고 내 글 받기는 건드리지 않는다(iOS `feedGeneration`·`mineGeneration`).
    private var feedGeneration = 0
    private var mineGeneration = 0

    fun syncAccount() {
        val epoch = accountEpoch()
        if (loadedEpoch == epoch) return
        loadedEpoch = epoch
        feedGeneration++
        mineGeneration++
        // 게시판·검색어는 계정과 무관하다 — 그대로 두고 목록만 비운다(iOS `resetForAccount`).
        publish(CommunityState(board = state.board, query = state.query))
    }

    suspend fun refresh(
        mine: Boolean = false,
        more: Boolean = false,
    ) {
        syncAccount()
        if (if (mine) state.mineLoading else state.loading) return
        val epoch = accountEpoch()
        val generation = generationOf(mine)
        val old = if (mine) state.mine else state.posts
        publish(if (mine) state.copy(mineLoading = true, message = null) else state.copy(loading = true, message = null))
        try {
            val page =
                if (mine) {
                    client.list(true, CommunityRules.PAGE_SIZE, if (more) old.size else 0)
                } else {
                    // 모두의 목록은 게시판·검색어(1.24.0)를 붙이고, 다음 쪽은 서버가 준 커서로 — 없으면 옛 길(받은 줄 수만큼 건너뛰기).
                    val next = CommunityFeedRules.page(more, state.nextCursor, old.size)
                    client.feed(CommunityRules.PAGE_SIZE, next.offset, state.board, state.query, next.cursor)
                }
            if (!current(epoch, mine, generation)) return
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
                    state.copy(posts = merged.toList(), total = page.total, nextCursor = page.nextCursor)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (current(epoch, mine, generation)) publish(state.copy(message = CommunityRules.failureText(error)))
        } finally {
            if (current(epoch, mine, generation)) publish(if (mine) state.copy(mineLoading = false) else state.copy(loading = false))
        }
    }

    /** 게시판·검색어를 바꾼다 — 나가 있던 받기를 버리고 목록을 비운 뒤 새 조건의 첫 쪽부터 받는다. */
    suspend fun setFilter(
        board: TripPostBoard,
        query: String?,
    ) {
        syncAccount()
        invalidateFeed()
        if (board != state.board || query != state.query) {
            publish(state.copy(board = board, query = query, posts = emptyList(), total = 0, nextCursor = null))
        }
        refresh()
    }

    /** 글 화면에서 좋아요·댓글이 바뀌었다 — 목록·내 글의 같은 글 줄에도 맞춘다. */
    fun updateSocial(
        postId: Long,
        liked: Boolean,
        likeCount: Int,
        commentCount: Int,
    ) {
        fun apply(list: List<CommunityPost>) =
            list.map { if (it.serverId == postId) it.copy(liked = liked, likeCount = likeCount, commentCount = commentCount) else it }
        publish(state.copy(posts = apply(state.posts), mine = apply(state.mine)))
    }

    fun accept(detail: TripPostDetail) {
        syncAccount()
        val post = CommunityPost.from(detail)
        val known = state.posts.any { it.id == post.id }
        val knownMine = state.mine.any { it.id == post.id }
        // 보고 있는 게시판의 글이고 검색 중이 아닐 때만 모두의 목록 맨 앞에 넣는다 — 다른 게시판에 올린 글이 섞이지 않게.
        val fits = CommunityFeedRules.board(post.serverBoard) == state.board && state.query == null
        val rest = state.posts.filterNot { it.id == post.id }
        publish(
            state.copy(
                posts = if (fits) listOf(post) + rest else rest,
                mine = if (post.isMine) listOf(post) + state.mine.filterNot { it.id == post.id } else state.mine,
                total =
                    when {
                        fits && !known -> state.total + 1
                        !fits && known -> maxOf(rest.size, state.total - 1)
                        else -> state.total
                    },
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

    /**
     * 차단한 사람의 글을 다시 받기 전에 바로 뺀다(MZ2AZ-461). 그 사람의 다른 글은 다시 받은 목록에서 빠진다 — 앱은 작성자 번호를 모른다.
     *
     * **나가 있던 받기를 무효로 한다** — 차단 전에 떠난 새로고침·더 보기 응답은 그 글을 담고 있어 늦게 오면 되살린다.
     */
    fun drop(postId: Long) {
        invalidateFeed()
        if (state.posts.any { it.serverId == postId }) {
            val rest = state.posts.filterNot { it.serverId == postId }
            publish(state.copy(posts = rest, total = maxOf(rest.size, state.total - 1)))
        }
    }

    /** 그 글의 작성자를 차단했다 — 바로 빼고, 첫 쪽부터 새로 받는다(서버가 그 사람의 다른 글도 뺀다). */
    suspend fun blocked(postId: Long) {
        drop(postId)
        invalidateAndRefresh()
    }

    /** 차단·풀기 뒤에는 진행 중인 피드 요청을 버리고 첫 쪽을 반드시 다시 받는다. */
    suspend fun invalidateAndRefresh() {
        invalidateFeed()
        refresh()
    }

    private fun invalidateFeed() {
        feedGeneration++
        if (state.loading) publish(state.copy(loading = false))
    }

    private fun generationOf(mine: Boolean) = if (mine) mineGeneration else feedGeneration

    private fun current(
        epoch: Int,
        mine: Boolean,
        generation: Int,
    ): Boolean = epoch == accountEpoch() && generation == generationOf(mine)

    private fun publish(value: CommunityState) {
        state = value
        onChanged(value)
    }
}
