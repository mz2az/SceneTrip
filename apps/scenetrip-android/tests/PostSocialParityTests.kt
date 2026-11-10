package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunitySocialClient
import com.mz2az.scenetrip.data.PostSocialModel
import com.mz2az.scenetrip.data.PostSocialModel.Outcome
import com.mz2az.scenetrip.data.PostSocialState
import com.mz2az.scenetrip.sceneapi.client.model.TripPostAuthor
import com.mz2az.scenetrip.sceneapi.client.model.TripPostComment
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCommentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

/** 글 하나의 좋아요·댓글 (MZ2AZ-547 P8 K8·K9, iOS `PostSocialTests` 를 옮긴 것). */
class PostSocialParityTests {
    private data class Change(
        val liked: Boolean,
        val likes: Int,
        val comments: Int,
    )

    private class FakeSocialClient : CommunitySocialClient {
        data class LikeCall(
            val postId: Long,
            val on: Boolean,
        )

        data class AddCall(
            val postId: Long,
            val body: String,
        )

        data class DeleteCall(
            val postId: Long,
            val commentId: Long,
        )

        val likeCalls = mutableListOf<LikeCall>()
        var likeError: Exception? = null

        /** 있으면 좋아요 응답을 이것이 풀릴 때까지 붙잡아 둔다 — 보내는 중의 상태를 본다. */
        var likeGate: CompletableDeferred<Unit>? = null
        var likeWaiting = false

        val commentsCalls = mutableListOf<Long>()
        var commentsResult = TripPostCommentList(emptyList(), 0)
        var commentsError: Exception? = null

        val addCalls = mutableListOf<AddCall>()
        var addError: Exception? = null
        var nextCommentId = 500L

        val deleteCalls = mutableListOf<DeleteCall>()
        var deleteError: Exception? = null

        override suspend fun like(
            postId: Long,
            on: Boolean,
        ) {
            likeCalls += LikeCall(postId, on)
            likeGate?.let {
                likeWaiting = true
                it.await()
                likeWaiting = false
            }
            likeError?.let { throw it }
        }

        override suspend fun comments(postId: Long): TripPostCommentList {
            commentsCalls += postId
            commentsError?.let { throw it }
            return commentsResult
        }

        override suspend fun addComment(
            postId: Long,
            body: String,
        ): TripPostComment {
            addCalls += AddCall(postId, body)
            addError?.let { throw it }
            return comment(nextCommentId++, body, mine = true)
        }

        override suspend fun deleteComment(
            postId: Long,
            commentId: Long,
        ) {
            deleteCalls += DeleteCall(postId, commentId)
            deleteError?.let { throw it }
        }
    }

    private fun model(
        client: FakeSocialClient,
        liked: Boolean? = false,
        likeCount: Int? = 3,
        commentCount: Int? = 0,
        signedIn: Boolean = true,
        onState: (PostSocialState) -> Unit = {},
    ) = PostSocialModel(42, liked, likeCount, commentCount, client = client, signedIn = { signedIn }, onState = onState)

    // ── 처음 값·맞추기 ──

    @Test
    fun initialValuesComeFromRow() {
        val social = model(FakeSocialClient(), liked = true, likeCount = 5, commentCount = 2)
        assertTrue(social.state.liked)
        assertEquals(5, social.state.likeCount)
        assertEquals(2, social.state.commentTotal)
        assertNull(social.state.comments)
    }

    @Test
    fun syncTakesServerValuesAndNullMeansZero() {
        val social = model(FakeSocialClient(), liked = false, likeCount = 0)
        social.sync(true, 7)
        assertTrue(social.state.liked)
        assertEquals(7, social.state.likeCount)
        social.sync(null, null)
        assertFalse(social.state.liked)
        assertEquals(0, social.state.likeCount)
    }

    // ── 좋아요 ──

    @Test
    fun likeIsOptimisticAndSent() =
        runBlocking {
            val client = FakeSocialClient()
            val gate = CompletableDeferred<Unit>()
            client.likeGate = gate
            val social = model(client, liked = false, likeCount = 3)
            val task = async { social.toggleLike() }
            try {
                assertTrue(waitUntil { client.likeWaiting }, "좋아요를 보내지 않았다")
                assertTrue(social.state.liked)
                assertEquals(4, social.state.likeCount)
                assertTrue(social.state.likeBusy)
            } finally {
                gate.complete(Unit)
            }
            assertEquals(Outcome.DONE, task.await())
            assertTrue(social.state.liked)
            assertEquals(4, social.state.likeCount)
            assertFalse(social.state.likeBusy)
            assertEquals(listOf(FakeSocialClient.LikeCall(42, true)), client.likeCalls)
        }

    @Test
    fun unlikeSendsOff() =
        runBlocking {
            val client = FakeSocialClient()
            val social = model(client, liked = true, likeCount = 3)
            assertEquals(Outcome.DONE, social.toggleLike())
            assertFalse(social.state.liked)
            assertEquals(2, social.state.likeCount)
            assertEquals(listOf(FakeSocialClient.LikeCall(42, false)), client.likeCalls)
        }

    @Test
    fun likeFailureRollsBack() =
        runBlocking {
            val client = FakeSocialClient()
            client.likeError = IOException("인터넷 없음")
            val social = model(client, liked = false, likeCount = 3)
            assertEquals(Outcome.FAILED, social.toggleLike())
            assertFalse(social.state.liked)
            assertEquals(3, social.state.likeCount)
            assertFalse(social.state.likeBusy)
            assertEquals(1, client.likeCalls.size)
        }

    @Test
    fun likeNeedsSignInAndDoesNotCall() =
        runBlocking {
            val client = FakeSocialClient()
            val social = model(client, liked = false, likeCount = 3, signedIn = false)
            assertEquals(Outcome.NEEDS_SIGN_IN, social.toggleLike())
            assertFalse(social.state.liked)
            assertEquals(3, social.state.likeCount)
            assertTrue(client.likeCalls.isEmpty())
        }

    @Test
    fun secondTapWhileSendingIsIgnored() =
        runBlocking {
            val client = FakeSocialClient()
            val gate = CompletableDeferred<Unit>()
            client.likeGate = gate
            val social = model(client, liked = false, likeCount = 3)
            val first = async { social.toggleLike() }
            try {
                assertTrue(waitUntil { client.likeWaiting }, "좋아요를 보내지 않았다")
                assertEquals(Outcome.IGNORED, social.toggleLike())
                assertTrue(social.state.liked)
                assertEquals(4, social.state.likeCount)
            } finally {
                gate.complete(Unit)
            }
            first.await()
            assertEquals(1, client.likeCalls.size)
        }

    // ── 댓글 받기 ──

    @Test
    fun loadCommentsKeepsServerOrderAndTotal() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1), comment(2)), 2)
            val social = model(client)
            social.loadComments()
            assertEquals(listOf(1L, 2L), social.state.comments?.map { it.id })
            assertEquals(2, social.state.commentTotal)
            assertFalse(social.state.commentsFailed)
            assertEquals(listOf(42L), client.commentsCalls)
        }

    @Test
    fun loadCommentsFailureKeepsListAndMarksFailed() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1)), 1)
            val social = model(client)
            social.loadComments()
            client.commentsError = IOException("시간 초과")
            social.loadComments()
            assertTrue(social.state.commentsFailed)
            assertEquals(listOf(1L), social.state.comments?.map { it.id })
            assertEquals(1, social.state.commentTotal)
            client.commentsError = null
            social.loadComments()
            assertFalse(social.state.commentsFailed)
        }

    @Test
    fun firstLoadFailureLeavesCommentsUnloaded() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsError = IOException("시간 초과")
            val social = model(client)
            social.loadComments()
            assertTrue(social.state.commentsFailed)
            assertNull(social.state.comments)
        }

    // ── 댓글 쓰기 ──

    @Test
    fun sendTrimsAppendsAndCounts() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1)), 1)
            val social = model(client)
            social.loadComments()
            assertEquals(Outcome.DONE, social.send("  안녕하세요  "))
            assertEquals(listOf(FakeSocialClient.AddCall(42, "안녕하세요")), client.addCalls)
            assertEquals(listOf(1L, 500L), social.state.comments?.map { it.id })
            assertEquals(
                "안녕하세요",
                social.state.comments
                    ?.last()
                    ?.body,
            )
            assertEquals(2, social.state.commentTotal)
            assertFalse(social.state.sending)
        }

    @Test
    fun sendCutsLongBodyToFiveHundredCharacters() =
        runBlocking {
            val client = FakeSocialClient()
            val social = model(client)
            social.loadComments()
            assertEquals(Outcome.DONE, social.send("가".repeat(600)))
            assertEquals("가".repeat(500), client.addCalls.firstOrNull()?.body)
        }

    @Test
    fun sendEmptyIsIgnored() =
        runBlocking {
            val client = FakeSocialClient()
            val social = model(client)
            assertEquals(Outcome.IGNORED, social.send("   \n "))
            assertEquals(Outcome.IGNORED, social.send(""))
            assertTrue(client.addCalls.isEmpty())
        }

    @Test
    fun sendNeedsSignInAndDoesNotCall() =
        runBlocking {
            val client = FakeSocialClient()
            val social = model(client, signedIn = false)
            assertEquals(Outcome.NEEDS_SIGN_IN, social.send("좋아요"))
            assertTrue(client.addCalls.isEmpty())
        }

    @Test
    fun sendFailureLeavesListAlone() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1)), 1)
            val social = model(client)
            social.loadComments()
            client.addError = IOException("연결 끊김")
            assertEquals(Outcome.FAILED, social.send("좋아요"))
            assertEquals(listOf(1L), social.state.comments?.map { it.id })
            assertEquals(1, social.state.commentTotal)
            assertFalse(social.state.sending)
            assertEquals(1, client.addCalls.size)
        }

    // ── 댓글 지우기·차단 ──

    @Test
    fun removeDropsCommentAndCounts() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1, mine = true), comment(2)), 2)
            val social = model(client)
            social.loadComments()
            social.remove(comment(1, mine = true))
            assertEquals(listOf(2L), social.state.comments?.map { it.id })
            assertEquals(1, social.state.commentTotal)
            assertEquals(listOf(FakeSocialClient.DeleteCall(42, 1)), client.deleteCalls)
            assertEquals(1, client.commentsCalls.size)
        }

    @Test
    fun removeFailureReloads() =
        runBlocking {
            val client = FakeSocialClient()
            val mine = comment(1, mine = true)
            client.commentsResult = TripPostCommentList(listOf(mine, comment(2)), 2)
            val social = model(client)
            social.loadComments()
            client.deleteError = IOException("연결 끊김")
            social.remove(mine)
            waitUntil { client.commentsCalls.size >= 2 && social.state.comments?.size == 2 }
            assertEquals(1, client.deleteCalls.size)
            assertEquals(2, client.commentsCalls.size, "실패한 뒤 다시 받지 않았다")
            assertEquals(listOf(1L, 2L), social.state.comments?.map { it.id })
            assertEquals(2, social.state.commentTotal)
        }

    @Test
    fun blockedReloadsComments() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1), comment(2)), 2)
            val social = model(client)
            social.loadComments()
            client.commentsResult = TripPostCommentList(listOf(comment(1)), 1)
            social.blocked()
            assertEquals(2, client.commentsCalls.size)
            assertEquals(listOf(1L), social.state.comments?.map { it.id })
            assertEquals(1, social.state.commentTotal)
        }

    // ── 목록 줄·화면에 알리기 ──

    @Test
    fun onChangeReportsLikeAndCommentChanges() =
        runBlocking {
            val client = FakeSocialClient()
            client.commentsResult = TripPostCommentList(listOf(comment(1)), 1)
            val social = model(client, liked = false, likeCount = 3, commentCount = 1)
            val changes = mutableListOf<Change>()
            social.onChange = { liked, likes, comments -> changes += Change(liked, likes, comments) }
            social.toggleLike()
            assertEquals(Change(true, 4, 1), changes.lastOrNull())
            social.loadComments()
            social.send("좋아요")
            assertEquals(Change(true, 4, 2), changes.lastOrNull())
            social.remove(comment(500, "좋아요", mine = true))
            assertEquals(Change(true, 4, 1), changes.lastOrNull())
        }

    @Test
    fun onChangeReportsRollback() =
        runBlocking {
            val client = FakeSocialClient()
            client.likeError = IOException("시간 초과")
            val social = model(client, liked = false, likeCount = 3, commentCount = 0)
            val changes = mutableListOf<Change>()
            social.onChange = { liked, likes, comments -> changes += Change(liked, likes, comments) }
            social.toggleLike()
            assertTrue(changes.isNotEmpty())
            assertEquals(Change(false, 3, 0), changes.lastOrNull())
        }

    @Test
    fun onStateReceivesEveryNewState() =
        runBlocking {
            val client = FakeSocialClient()
            val seen = mutableListOf<PostSocialState>()
            val social = model(client, liked = false, likeCount = 3, onState = { seen += it })
            social.toggleLike()
            assertTrue(seen.isNotEmpty(), "onState 가 불리지 않았다")
            assertEquals(social.state, seen.last())
            assertTrue(seen.last().liked)
        }

    private companion object {
        private val AT = OffsetDateTime.parse("2026-11-01T00:00:00Z")

        fun comment(
            id: Long,
            body: String = "댓글",
            mine: Boolean = false,
        ) = TripPostComment(id, body, AT.plusSeconds(id), mine, TripPostAuthor("사람$id"))

        /** 조건이 설 때까지 다른 코루틴에 차례를 넘긴다. 끝내 서지 않으면 false(무한히 기다리지 않는다). */
        suspend fun waitUntil(condition: () -> Boolean): Boolean =
            withTimeoutOrNull(2_000) {
                while (!condition()) delay(1)
                true
            } ?: false
    }
}
