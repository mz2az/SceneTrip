package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityFeedRules
import com.mz2az.scenetrip.data.CommunityFeedRules.EmptyState
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * 커뮤니티 목록의 순수 규칙 (MZ2AZ-547 P8, iOS `CommunityFeedRulesTests` 를 옮긴 것).
 * 글자 수는 코드 포인트로 센다 — iOS 의 「여러 스칼라로 된 그림 문자 하나는 한 글자」 경우는 Android 에서 뜻이 달라 옮기지 않았다.
 */
class CommunityFeedRulesParityTests {
    // ── 게시판 ──

    @Test
    fun boardTabsAreReviewThenFree() {
        assertEquals(listOf(TripPostBoard.review, TripPostBoard.free), CommunityFeedRules.boards)
    }

    @Test
    fun missingBoardMeansReview() {
        assertEquals(TripPostBoard.review, CommunityFeedRules.board(null))
        assertEquals(TripPostBoard.review, CommunityFeedRules.board(TripPostBoard.review))
        assertEquals(TripPostBoard.free, CommunityFeedRules.board(TripPostBoard.free))
    }

    @Test
    fun boardLabelKeys() {
        assertEquals("여행후기", CommunityFeedRules.boardLabelKey(TripPostBoard.review))
        assertEquals("자유게시판", CommunityFeedRules.boardLabelKey(TripPostBoard.free))
    }

    // ── 검색 ──

    @Test
    fun searchTermIsNullWhenNotSearchingOrTooShort() {
        assertNull(CommunityFeedRules.searchTerm(null))
        assertNull(CommunityFeedRules.searchTerm(""))
        assertNull(CommunityFeedRules.searchTerm("   "))
        assertNull(CommunityFeedRules.searchTerm(" a "))
        assertNull(CommunityFeedRules.searchTerm("한"))
        // 😀 는 UTF-16 두 칸이지만 코드 포인트 하나 — 한 글자다.
        assertNull(CommunityFeedRules.searchTerm("😀"))
    }

    @Test
    fun searchTermTrimsAndKeepsTwoCharacters() {
        assertEquals("ab", CommunityFeedRules.searchTerm("ab"))
        assertEquals("서울", CommunityFeedRules.searchTerm("  서울 "))
        assertEquals("😀😀", CommunityFeedRules.searchTerm("😀😀"))
        assertEquals("👨‍👩‍👧가", CommunityFeedRules.searchTerm("👨‍👩‍👧가"))
        assertEquals(2, CommunityFeedRules.SEARCH_MIN_LENGTH)
    }

    @Test
    fun searchWaitsThreeHundredFiftyMillisecondsOnlyWhenSearching() {
        assertEquals(350L, CommunityFeedRules.SEARCH_DELAY_MILLIS)
        assertEquals(0L, CommunityFeedRules.delayMillis(null))
        assertEquals(350L, CommunityFeedRules.delayMillis("서울"))
    }

    // ── 끊김 없는 목록 ──

    @Test
    fun firstPageHasNoCursorAndNoOffset() {
        assertEquals(CommunityFeedRules.Page(null, 0), CommunityFeedRules.page(more = false, nextCursor = "c1", loaded = 20))
    }

    @Test
    fun moreUsesCursorWhenServerGaveOne() {
        assertEquals(CommunityFeedRules.Page("c1", 0), CommunityFeedRules.page(more = true, nextCursor = "c1", loaded = 20))
    }

    @Test
    fun moreWithoutCursorSkipsLoadedRows() {
        assertEquals(CommunityFeedRules.Page(null, 20), CommunityFeedRules.page(more = true, nextCursor = null, loaded = 20))
    }

    @Test
    fun shouldLoadMoreNearTheEnd() {
        assertEquals(5, CommunityFeedRules.PREFETCH_DISTANCE)
        assertTrue(CommunityFeedRules.shouldLoadMore(15, loaded = 20, total = 50, loading = false, failed = false))
        assertTrue(CommunityFeedRules.shouldLoadMore(19, loaded = 20, total = 50, loading = false, failed = false))
        assertFalse(CommunityFeedRules.shouldLoadMore(14, loaded = 20, total = 50, loading = false, failed = false))
    }

    @Test
    fun shouldNotLoadMoreWhenDoneBusyOrFailed() {
        assertFalse(CommunityFeedRules.shouldLoadMore(19, loaded = 20, total = 20, loading = false, failed = false))
        assertFalse(CommunityFeedRules.shouldLoadMore(19, loaded = 20, total = 50, loading = true, failed = false))
        assertFalse(CommunityFeedRules.shouldLoadMore(19, loaded = 20, total = 50, loading = false, failed = true))
    }

    // ── 빈 화면 ──

    @Test
    fun noEmptyStateWhileLoadingFailedOrShowingPosts() {
        assertNull(CommunityFeedRules.emptyState(TripPostBoard.review, null, posts = 0, legacy = 0, loading = true, failed = false))
        assertNull(CommunityFeedRules.emptyState(TripPostBoard.review, null, posts = 0, legacy = 0, loading = false, failed = true))
        assertNull(CommunityFeedRules.emptyState(TripPostBoard.review, null, posts = 2, legacy = 0, loading = false, failed = false))
        assertNull(CommunityFeedRules.emptyState(TripPostBoard.review, "서울", posts = 2, legacy = 0, loading = false, failed = false))
    }

    @Test
    fun searchWithoutResultsIsNoMatchEvenWithLegacyPosts() {
        assertEquals(
            EmptyState.NoMatch("서울"),
            CommunityFeedRules.emptyState(TripPostBoard.review, "서울", posts = 0, legacy = 3, loading = false, failed = false),
        )
        assertEquals(
            EmptyState.NoMatch("바다"),
            CommunityFeedRules.emptyState(TripPostBoard.free, "바다", posts = 0, legacy = 0, loading = false, failed = false),
        )
    }

    @Test
    fun emptyBoardDependsOnBoard() {
        assertEquals(
            EmptyState.FreeBoard,
            CommunityFeedRules.emptyState(TripPostBoard.free, null, posts = 0, legacy = 0, loading = false, failed = false),
        )
        assertEquals(
            EmptyState.Reviews,
            CommunityFeedRules.emptyState(TripPostBoard.review, null, posts = 0, legacy = 0, loading = false, failed = false),
        )
    }

    @Test
    fun legacyPostsHideEmptyStateWhenNotSearching() {
        assertNull(CommunityFeedRules.emptyState(TripPostBoard.review, null, posts = 0, legacy = 1, loading = false, failed = false))
    }

    // ── 좋아요·댓글 수 ──

    @Test
    fun countsHiddenWhenServerGaveNeither() {
        assertNull(CommunityFeedRules.counts(null, null))
    }

    @Test
    fun countsFillMissingSideWithZero() {
        assertEquals(CommunityFeedRules.Counts(likes = 3, comments = 0), CommunityFeedRules.counts(3, null))
        assertEquals(CommunityFeedRules.Counts(likes = 0, comments = 2), CommunityFeedRules.counts(null, 2))
        assertEquals(CommunityFeedRules.Counts(likes = 7, comments = 4), CommunityFeedRules.counts(7, 4))
    }

    @Test
    fun toggledLikeFlipsAndCounts() {
        assertEquals(CommunityFeedRules.Like(liked = true, count = 4), CommunityFeedRules.toggledLike(liked = false, count = 3))
        assertEquals(CommunityFeedRules.Like(liked = false, count = 2), CommunityFeedRules.toggledLike(liked = true, count = 3))
    }

    @Test
    fun toggledLikeNeverGoesBelowZero() {
        assertEquals(CommunityFeedRules.Like(liked = false, count = 0), CommunityFeedRules.toggledLike(liked = true, count = 0))
    }

    // ── 댓글 ──

    @Test
    fun commentBodyTrimsAndRejectsEmpty() {
        assertEquals("좋은 글이에요", CommunityFeedRules.commentBody("  좋은 글이에요  "))
        assertNull(CommunityFeedRules.commentBody(""))
        assertNull(CommunityFeedRules.commentBody("   "))
    }

    @Test
    fun commentBodyIsCutToFiveHundredCharacters() {
        assertEquals(500, CommunityFeedRules.COMMENT_LIMIT)
        assertEquals("가".repeat(500), CommunityFeedRules.commentBody("가".repeat(600)))
        // 코드 포인트 단위 — 😀 하나(UTF-16 두 칸)는 한 글자다. 반으로 자르지 않는다.
        assertEquals("😀".repeat(500), CommunityFeedRules.commentBody("😀".repeat(501)))
        val exact = "a".repeat(500)
        assertEquals(exact, CommunityFeedRules.commentBody("  $exact  "))
    }

    @Test
    fun commentPlaceholderDependsOnWhetherCommentsExist() {
        assertEquals("첫 댓글을 남겨 보세요", CommunityFeedRules.commentPlaceholderKey(0))
        assertEquals("댓글을 남겨 보세요", CommunityFeedRules.commentPlaceholderKey(1))
        assertEquals("댓글을 남겨 보세요", CommunityFeedRules.commentPlaceholderKey(12))
    }

    // ── 글쓰기 ──

    @Test
    fun composeTapOpensComposeWhenSignedIn() {
        assertEquals(CommunityFeedRules.ComposeTap.Open, CommunityFeedRules.composeTap(signedIn = true))
    }

    @Test
    fun composeTapShowsSignInWhenSignedOut() {
        assertEquals(CommunityFeedRules.ComposeTap.SignIn, CommunityFeedRules.composeTap(signedIn = false))
    }

    // ── 새 영어 문구 ──

    @Test
    fun newCommunityStringsHaveEnglish() {
        val expected =
            mapOf(
                "여행후기" to "Trip reviews",
                "자유게시판" to "Free board",
                "「%s」 에 맞는 글이 없어요" to "No posts match “%s”",
                "좋아요 %d" to "Like %d",
                "댓글 %d" to "Comments %d",
                "첫 댓글을 남겨 보세요" to "Be the first to comment",
                "출처·AI 표시 더하기" to "Add source / AI label",
                "글쓰기" to "New post",
            )
        assertAll(expected.map { (korean, english) -> Executable { assertEquals(english, tr(korean, Lang.en), "「$korean」 의 영어") } })
    }
}
