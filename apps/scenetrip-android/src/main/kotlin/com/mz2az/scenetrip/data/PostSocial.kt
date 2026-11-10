package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.sceneapi.client.api.PostsApi
import com.mz2az.scenetrip.sceneapi.client.model.TripPostComment
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCommentCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCommentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 좋아요·댓글 창구 (계약 1.24.0, MZ2AZ-538) — 생성 클라이언트(`PostsApi`)만 부른다. 시험에서 가짜로 바꾼다. iOS `CommunitySocialClient`. */
interface CommunitySocialClient {
    suspend fun like(
        postId: Long,
        on: Boolean,
    )

    suspend fun comments(postId: Long): TripPostCommentList

    suspend fun addComment(
        postId: Long,
        body: String,
    ): TripPostComment

    suspend fun deleteComment(
        postId: Long,
        commentId: Long,
    )
}

class CommunitySocialServerClient : CommunitySocialClient {
    private val api = PostsApi(API_BASE)

    override suspend fun like(
        postId: Long,
        on: Boolean,
    ) {
        withContext(Dispatchers.IO) { if (on) api.likePost(postId) else api.unlikePost(postId) }
    }

    override suspend fun comments(postId: Long): TripPostCommentList =
        withContext(Dispatchers.IO) { api.listPostComments(postId, COMMENT_PAGE, 0) }

    override suspend fun addComment(
        postId: Long,
        body: String,
    ): TripPostComment = withContext(Dispatchers.IO) { api.createPostComment(postId, TripPostCommentCreate(body)) }

    override suspend fun deleteComment(
        postId: Long,
        commentId: Long,
    ) {
        withContext(Dispatchers.IO) { api.deletePostComment(postId, commentId) }
    }

    private companion object {
        const val COMMENT_PAGE = 100
    }
}

/** 글 하나의 좋아요·댓글 화면 값. */
data class PostSocialState(
    val liked: Boolean = false,
    val likeCount: Int = 0,
    /** null 이면 아직 받지 않았다. */
    val comments: List<TripPostComment>? = null,
    val commentTotal: Int = 0,
    val commentsFailed: Boolean = false,
    val sending: Boolean = false,
    val likeBusy: Boolean = false,
)

/**
 * 글 하나의 좋아요·댓글 상태 (iOS `PostSocialModel`, 웹 `protoPostSocial.tsx`).
 * - 좋아요: 누르면 바로 바뀌고(낙관적) 실패하면 되돌린다. 로그인하지 않았으면 부르지 않고 [Outcome.NEEDS_SIGN_IN]. 보내는 중에 또 누르면 [Outcome.IGNORED].
 * - 댓글: 오래된 순(서버 순서 그대로). 쓰면 끝에 붙고 수 +1, 지우면 바로 빼고 수 −1(실패하면 다시 받는다).
 * - 바뀔 때마다 [onChange] (liked, likeCount, commentTotal) 로 목록 줄에 알린다.
 *
 * 화면 값은 [state] 하나이고 바뀔 때마다 [onState] 로 알린다 — 화면이 `mutableStateOf` 로 받는다(시험은 Compose 없이 돈다).
 */
class PostSocialModel(
    val postId: Long,
    liked: Boolean?,
    likeCount: Int?,
    commentCount: Int? = null,
    private val client: CommunitySocialClient = CommunitySocialServerClient(),
    private val signedIn: () -> Boolean = { AuthStore.signedIn },
    private val onState: (PostSocialState) -> Unit = {},
) {
    enum class Outcome {
        DONE,
        NEEDS_SIGN_IN,

        /** 보낼 것이 없거나 이미 보내는 중. */
        IGNORED,
        FAILED,
    }

    /** 처음 값은 목록 줄·상세의 값이다(null 이면 false·0). */
    var state: PostSocialState =
        PostSocialState(
            liked = liked ?: false,
            likeCount = maxOf(0, likeCount ?: 0),
            commentTotal = maxOf(0, commentCount ?: 0),
        )
        private set

    /** 좋아요·댓글 수가 바뀌면 목록 줄에도 알린다(liked, likeCount, commentTotal). */
    var onChange: ((Boolean, Int, Int) -> Unit)? = null

    /** 상세가 새로 왔다 — 서버 값으로 맞춘다(null 이면 false·0). 보내는 중이면 내 값이 이긴다. */
    fun sync(
        liked: Boolean?,
        likeCount: Int?,
    ) {
        if (state.likeBusy) return
        publish(state.copy(liked = liked ?: false, likeCount = maxOf(0, likeCount ?: 0)))
    }

    suspend fun toggleLike(): Outcome {
        if (!signedIn()) return Outcome.NEEDS_SIGN_IN
        if (state.likeBusy) return Outcome.IGNORED
        val before = state
        val next = CommunityFeedRules.toggledLike(state.liked, state.likeCount)
        publish(state.copy(liked = next.liked, likeCount = next.count, likeBusy = true))
        notifyChange()
        return try {
            client.like(postId, next.liked)
            Outcome.DONE
        } catch (cancelled: CancellationException) {
            publish(state.copy(liked = before.liked, likeCount = before.likeCount))
            notifyChange()
            throw cancelled
        } catch (error: Exception) {
            publish(state.copy(liked = before.liked, likeCount = before.likeCount))
            notifyChange()
            Outcome.FAILED
        } finally {
            publish(state.copy(likeBusy = false))
        }
    }

    /** 댓글을 받는다. 실패하면 `commentsFailed`(받아 둔 목록은 그대로). */
    suspend fun loadComments() {
        publish(state.copy(commentsFailed = false))
        try {
            val page = client.comments(postId)
            publish(state.copy(comments = page.items, commentTotal = page.total))
            notifyChange()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publish(state.copy(commentsFailed = true))
        }
    }

    /**
     * 댓글 쓰기([CommunityFeedRules.commentBody] 로 다듬어 보낸다). 빈 글이거나 보내는 중이면 [Outcome.IGNORED],
     * 로그인 안 했으면 [Outcome.NEEDS_SIGN_IN](부르지 않는다). 성공하면 [Outcome.DONE], 실패하면 [Outcome.FAILED].
     */
    suspend fun send(text: String): Outcome {
        val body = CommunityFeedRules.commentBody(text) ?: return Outcome.IGNORED
        if (state.sending) return Outcome.IGNORED
        if (!signedIn()) return Outcome.NEEDS_SIGN_IN
        publish(state.copy(sending = true))
        return try {
            val created = client.addComment(postId, body)
            publish(state.copy(comments = state.comments.orEmpty() + created, commentTotal = state.commentTotal + 1))
            notifyChange()
            Outcome.DONE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Outcome.FAILED
        } finally {
            publish(state.copy(sending = false))
        }
    }

    /** 내 댓글 지우기 — 바로 빼고 수 −1, 실패하면 다시 받는다. */
    suspend fun remove(comment: TripPostComment) {
        publish(
            state.copy(
                comments = state.comments.orEmpty().filterNot { it.id == comment.id },
                commentTotal =
                    maxOf(
                        0,
                        state.commentTotal - 1,
                    ),
            ),
        )
        notifyChange()
        try {
            client.deleteComment(postId, comment.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadComments()
        }
    }

    /** 댓글 작성자를 차단했다 — 서버가 그 사람의 댓글을 빼므로 다시 받는다. */
    suspend fun blocked() {
        loadComments()
    }

    private fun publish(next: PostSocialState) {
        state = next
        onState(next)
    }

    private fun notifyChange() {
        onChange?.invoke(state.liked, state.likeCount, state.commentTotal)
    }
}
