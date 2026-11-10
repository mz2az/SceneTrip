package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityClient
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunitySession
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostList
import com.mz2az.scenetrip.sceneapi.client.model.TripPostSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * 모두의 목록 — 게시판·검색어·커서, 좋아요·댓글 수 맞추기, 서버 값 → 글, 댓글 신고 대상, 읽는 화면 블록
 * (MZ2AZ-547 P8 K2·K3·K4·K5·K7·K9, iOS `CommunityFeedStoreTests`·`CommunityParityExtrasTests` 를 옮긴 것).
 */
class CommunityFeedSessionTests {
    private data class FeedCall(
        val limit: Int,
        val offset: Int,
        val board: TripPostBoard?,
        val query: String?,
        val cursor: String?,
    )

    private class FeedFake : CommunityClient {
        val feedCalls = mutableListOf<FeedCall>()
        var listCalls = 0

        /** 요청마다 돌려줄 쪽. */
        var respond: (FeedCall) -> TripPostList = { TripPostList(emptyList(), 0) }

        /** 켜 두면 받은 요청을 붙잡아 둔다 — 늦게 오는 응답을 흉내 낸다. */
        var hold = false
        val held = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun list(
            mine: Boolean,
            limit: Int,
            offset: Int,
        ): TripPostList {
            listCalls++
            return TripPostList(emptyList(), 0)
        }

        override suspend fun feed(
            limit: Int,
            offset: Int,
            board: TripPostBoard?,
            query: String?,
            cursor: String?,
        ): TripPostList {
            val call = FeedCall(limit, offset, board, query, cursor)
            feedCalls += call
            if (hold) {
                val gate = CompletableDeferred<Unit>()
                held += gate
                gate.await()
            }
            return respond(call)
        }

        fun releaseAll() {
            val waiting = held.toList()
            held.clear()
            waiting.forEach { it.complete(Unit) }
        }

        override suspend fun detail(id: Long): TripPostDetail = TripPostDetail(id, "t", "b", AT, emptyList(), false)

        override suspend fun create(input: TripPostCreate): TripPostDetail = error("이 시험은 게시를 요청하지 않는다")

        override suspend fun delete(id: Long) = error("이 시험은 삭제를 요청하지 않는다")

        override suspend fun saveCourse(id: Long): CourseDetail = error("이 시험은 코스 복사를 요청하지 않는다")
    }

    private fun page(
        ids: List<Long>,
        total: Int,
        cursor: String? = null,
        board: TripPostBoard? = null,
    ) = TripPostList(ids.map { summary(it, board = board) }, total, cursor)

    private fun ids(session: CommunitySession) = session.state.posts.mapNotNull { it.serverId }

    private val pageSize = CommunityRules.PAGE_SIZE

    // ── 게시판·검색어·커서 ──

    @Test
    fun refreshAsksFeedWithDefaultBoardAndNoQuery() =
        runBlocking {
            val api = FeedFake()
            api.respond = { page(listOf(1, 2), 2) }
            val session = CommunitySession(api)
            session.refresh()
            assertEquals(TripPostBoard.review, session.state.board)
            assertNull(session.state.query)
            assertEquals(listOf(FeedCall(pageSize, 0, TripPostBoard.review, null, null)), api.feedCalls)
            assertEquals(0, api.listCalls, "모두의 목록은 feed 로 받는다")
            assertEquals(listOf(1L, 2L), ids(session))
        }

    @Test
    fun setFilterAsksFirstPageWithBoardAndQuery() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call ->
                if (call.board == TripPostBoard.free) {
                    page(listOf(100), 1, board = TripPostBoard.free)
                } else {
                    page(listOf(1, 2, 3), 30, cursor = "old")
                }
            }
            val session = CommunitySession(api)
            session.refresh()
            session.setFilter(TripPostBoard.free, "서울")
            assertEquals(TripPostBoard.free, session.state.board)
            assertEquals("서울", session.state.query)
            assertEquals(FeedCall(pageSize, 0, TripPostBoard.free, "서울", null), api.feedCalls.lastOrNull())
            assertEquals(listOf(100L), ids(session))
            assertEquals(1, session.state.total)
            assertNull(session.state.nextCursor)
        }

    @Test
    fun moreUsesServerCursorWithZeroOffset() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call ->
                if (call.cursor == "c1") page(listOf(4, 5, 6), 10, cursor = "c2") else page(listOf(1, 2, 3), 10, cursor = "c1")
            }
            val session = CommunitySession(api)
            session.refresh()
            assertEquals("c1", session.state.nextCursor)
            session.refresh(more = true)
            assertEquals(2, api.feedCalls.size)
            assertEquals(FeedCall(pageSize, 0, TripPostBoard.review, null, "c1"), api.feedCalls.lastOrNull())
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), ids(session))
            assertEquals("c2", session.state.nextCursor)
        }

    @Test
    fun moreWithoutCursorSkipsLoadedRows() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call -> if (call.offset == 0) page(listOf(1, 2, 3), 10) else page(listOf(4, 5), 10) }
            val session = CommunitySession(api)
            session.refresh()
            assertNull(session.state.nextCursor)
            session.refresh(more = true)
            assertEquals(FeedCall(pageSize, 3, TripPostBoard.review, null, null), api.feedCalls.lastOrNull())
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(session))
        }

    @Test
    fun moreKeepsCurrentFilter() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call -> if (call.cursor == null) page(listOf(1), 5, cursor = "c1") else page(listOf(2), 5) }
            val session = CommunitySession(api)
            session.setFilter(TripPostBoard.free, "바다")
            session.refresh(more = true)
            assertEquals(FeedCall(pageSize, 0, TripPostBoard.free, "바다", "c1"), api.feedCalls.lastOrNull())
        }

    @Test
    fun myPostsStillUseList() =
        runBlocking {
            val api = FeedFake()
            val session = CommunitySession(api)
            session.refresh(mine = true)
            assertEquals(1, api.listCalls)
            assertTrue(api.feedCalls.isEmpty(), "내 글은 feed 로 받지 않는다")
        }

    @Test
    fun setFilterClearsOldPostsBeforeNewPageArrives() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call -> if (call.board == TripPostBoard.free) page(listOf(100), 1) else page(listOf(1, 2, 3), 3) }
            val session = CommunitySession(api)
            session.refresh()
            assertEquals(3, session.state.posts.size)
            api.hold = true
            val task = launch { session.setFilter(TripPostBoard.free, null) }
            try {
                assertTrue(waitUntil { api.held.isNotEmpty() }, "새 조건으로 받지 않았다")
                assertTrue(session.state.posts.isEmpty(), "옛 조건의 글이 남아 있다")
            } finally {
                api.hold = false
                api.releaseAll()
            }
            task.join()
            assertEquals(listOf(100L), ids(session))
        }

    @Test
    fun lateResponseOfOldFilterDoesNotOverwriteNewList() =
        runBlocking {
            val api = FeedFake()
            api.respond = { call ->
                if (call.board == TripPostBoard.free) page(listOf(100, 101), 2) else page(listOf(1, 2, 3), 3, cursor = "late")
            }
            val session = CommunitySession(api)
            api.hold = true
            val old = launch { session.refresh() }
            try {
                assertTrue(waitUntil { api.held.size == 1 }, "첫 받기가 feed 로 가지 않았다")
                api.hold = false
                session.setFilter(TripPostBoard.free, null)
                assertEquals(listOf(100L, 101L), ids(session))
            } finally {
                api.hold = false
                api.releaseAll()
            }
            old.join()
            assertEquals(listOf(100L, 101L), ids(session), "늦게 온 옛 응답이 새 목록을 덮었다")
            assertEquals(2, session.state.total)
            assertNull(session.state.nextCursor)
            assertFalse(session.state.loading)
        }

    // ── 좋아요·댓글 수 맞추기 ──

    @Test
    fun updateSocialChangesMatchingPostOnly() =
        runBlocking {
            val api = FeedFake()
            api.respond = {
                TripPostList(
                    listOf(
                        summary(1, likeCount = 0, commentCount = 0, liked = false),
                        summary(2, likeCount = 9, commentCount = 1, liked = false),
                    ),
                    2,
                )
            }
            val session = CommunitySession(api)
            session.refresh()
            session.updateSocial(1, liked = true, likeCount = 5, commentCount = 2)
            val first = session.state.posts.firstOrNull { it.serverId == 1L }
            assertEquals(true, first?.liked)
            assertEquals(5, first?.likeCount)
            assertEquals(2, first?.commentCount)
            val second = session.state.posts.firstOrNull { it.serverId == 2L }
            assertEquals(false, second?.liked)
            assertEquals(9, second?.likeCount)
            assertEquals(1, second?.commentCount)
        }

    @Test
    fun updateSocialAlsoChangesMyPosts() {
        val session = CommunitySession(FeedFake())
        session.accept(detail(7, isMine = true, likeCount = 1, commentCount = 0, liked = false))
        session.updateSocial(7, liked = true, likeCount = 2, commentCount = 3)
        assertEquals(
            2,
            session.state.posts
                .firstOrNull()
                ?.likeCount,
        )
        val mine = session.state.mine.firstOrNull()
        assertEquals(true, mine?.liked)
        assertEquals(2, mine?.likeCount)
        assertEquals(3, mine?.commentCount)
    }

    // ── 새 글 받아들이기 — 보고 있는 게시판·검색 ──

    @Test
    fun acceptPutsPostOfCurrentBoardOnTop() {
        val session = CommunitySession(FeedFake())
        session.accept(detail(7, isMine = true))
        assertEquals(listOf(7L), ids(session))
        assertEquals(1, session.state.total)
    }

    @Test
    fun acceptSkipsFeedForOtherBoardButKeepsMyPosts() =
        runBlocking {
            val api = FeedFake()
            api.respond = { page(listOf(1, 2), 2) }
            val session = CommunitySession(api)
            session.refresh()
            session.accept(detail(7, isMine = true, board = TripPostBoard.free))
            assertEquals(listOf(1L, 2L), ids(session), "다른 게시판의 글이 목록에 섞였다")
            assertEquals(2, session.state.total)
            assertEquals(listOf(7L), session.state.mine.mapNotNull { it.serverId })
        }

    @Test
    fun acceptSkipsFeedWhileSearching() =
        runBlocking {
            val api = FeedFake()
            api.respond = { page(listOf(1), 1) }
            val session = CommunitySession(api)
            session.setFilter(TripPostBoard.review, "서울")
            session.accept(detail(7, isMine = true))
            assertEquals(listOf(1L), ids(session), "검색 중인데 새 글이 맨 앞에 들어갔다")
            assertEquals(listOf(7L), session.state.mine.mapNotNull { it.serverId })
        }

    // ── 서버 값 → 글 ──

    @Test
    fun summaryCarriesBoardAndSocialCounts() {
        val post = CommunityPost.from(summary(1, board = TripPostBoard.free, likeCount = 3, commentCount = 4, liked = true))
        assertEquals(TripPostBoard.free, post.serverBoard)
        assertEquals(3, post.likeCount)
        assertEquals(4, post.commentCount)
        assertEquals(true, post.liked)
    }

    @Test
    fun summaryWithoutSocialFieldsLeavesThemNull() {
        val post = CommunityPost.from(summary(1))
        assertNull(post.serverBoard)
        assertNull(post.likeCount)
        assertNull(post.commentCount)
        assertNull(post.liked)
    }

    @Test
    fun detailCarriesBoardAndSocialCounts() {
        val post = CommunityPost.from(detail(3, board = TripPostBoard.review, likeCount = 8, commentCount = 2, liked = false))
        assertEquals(TripPostBoard.review, post.serverBoard)
        assertEquals(8, post.likeCount)
        assertEquals(2, post.commentCount)
        assertEquals(false, post.liked)
    }

    // ── 댓글 신고·차단 대상 (K9) ──

    @Test
    fun commentModerationTarget() {
        val target = ModerationTarget.comment(5)
        assertEquals(ReportTargetType.comment, target.type)
        assertEquals(5L, target.targetId)
        assertEquals("comment-5", target.key)
        assertNotEquals(ModerationTarget.post(5).key, target.key)
    }

    // ── 읽는 화면 블록 (K7) ──

    @Test
    fun readingBlocksWithoutServerBlocksIsLegacy() {
        val body = "첫 문단\n\n둘째 문단\n\n셋째 문단"
        assertEquals(CommunityArticle.legacy(body, 2), CommunityArticle.readingBlocks(null, body, 2))
    }

    @Test
    fun readingBlocksWithEmptyServerBlocksIsLegacy() {
        val body = "첫 문단\n\n둘째 문단"
        assertEquals(CommunityArticle.legacy(body, 1), CommunityArticle.readingBlocks(emptyList(), body, 1))
    }

    @Test
    fun readingBlocksKeepsCompleteServerBlocks() {
        val blocks =
            listOf(
                TripPostBlock(TripPostBlockKind.text, text = "가"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "바다"),
                TripPostBlock(TripPostBlockKind.text, text = "나"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 1),
            )
        assertEquals(blocks, CommunityArticle.readingBlocks(blocks, "가\n\n나", 2))
    }

    @Test
    fun readingBlocksShowsEachPhotoOnce() {
        val blocks =
            listOf(
                TripPostBlock(TripPostBlockKind.text, text = "가"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "첫"),
                TripPostBlock(TripPostBlockKind.text, text = "나"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "중복"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 5),
            )
        val result = CommunityArticle.readingBlocks(blocks, "가\n\n나", 3)
        // 중복·범위 밖은 빠지고, 어디에도 없던 1·2 번 사진은 끝에 붙는다.
        assertEquals(
            listOf(
                TripPostBlockKind.text,
                TripPostBlockKind.photo,
                TripPostBlockKind.text,
                TripPostBlockKind.photo,
                TripPostBlockKind.photo,
            ),
            result.map { it.kind },
        )
        assertEquals(listOf("가", "나"), result.mapNotNull { it.text })
        assertEquals(listOf(0, 1, 2), result.filter { it.kind == TripPostBlockKind.photo }.mapNotNull { it.photoIndex })
        assertEquals("첫", result.getOrNull(1)?.caption)
    }

    @Test
    fun readingBlocksAppendsUnreferencedPhotos() {
        val result = CommunityArticle.readingBlocks(listOf(TripPostBlock(TripPostBlockKind.text, text = "가")), "가", 2)
        assertEquals(listOf(TripPostBlockKind.text, TripPostBlockKind.photo, TripPostBlockKind.photo), result.map { it.kind })
        assertEquals(listOf(0, 1), result.mapNotNull { it.photoIndex })
    }

    private companion object {
        private val AT = OffsetDateTime.parse("2026-11-01T00:00:00Z")

        fun summary(
            id: Long,
            board: TripPostBoard? = null,
            likeCount: Int? = null,
            commentCount: Int? = null,
            liked: Boolean? = null,
        ) = TripPostSummary(
            id = id,
            title = "글$id",
            excerpt = "본문",
            createdAt = AT.plusSeconds(id),
            photoCount = 0,
            isMine = false,
            board = board,
            likeCount = likeCount,
            commentCount = commentCount,
            liked = liked,
        )

        fun detail(
            id: Long,
            isMine: Boolean = false,
            board: TripPostBoard? = null,
            likeCount: Int? = null,
            commentCount: Int? = null,
            liked: Boolean? = null,
        ) = TripPostDetail(
            id = id,
            title = "t",
            body = "b",
            createdAt = AT,
            photoUrls = emptyList(),
            isMine = isMine,
            board = board,
            likeCount = likeCount,
            commentCount = commentCount,
            liked = liked,
        )

        /** 조건이 설 때까지 다른 코루틴에 차례를 넘긴다. 끝내 서지 않으면 false(무한히 기다리지 않는다). */
        suspend fun waitUntil(condition: () -> Boolean): Boolean =
            withTimeoutOrNull(2_000) {
                while (!condition()) delay(1)
                true
            } ?: false
    }
}
