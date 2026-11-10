import SceneApiClient
@testable import SceneTrip
import XCTest

/// 커뮤니티 목록의 순수 규칙 (MZ2AZ-547 K2·K3·K4·K5·K8·K9, 웹 `ProtoCommunity.tsx`·`CommunityTab.tsx`·`protoPostSocial.tsx`).
final class CommunityFeedRulesTests: XCTestCase {
    // MARK: 게시판

    func testBoardTabsAreReviewThenFree() {
        XCTAssertEqual(CommunityFeedRules.boards, [.review, .free])
    }

    func testMissingBoardMeansReview() {
        XCTAssertEqual(CommunityFeedRules.board(nil), .review)
        XCTAssertEqual(CommunityFeedRules.board(.review), .review)
        XCTAssertEqual(CommunityFeedRules.board(.free), .free)
    }

    func testBoardLabelKeys() {
        XCTAssertEqual(CommunityFeedRules.boardLabelKey(.review), "여행후기")
        XCTAssertEqual(CommunityFeedRules.boardLabelKey(.free), "자유게시판")
    }

    // MARK: 검색

    func testSearchTermIsNilWhenNotSearchingOrTooShort() {
        XCTAssertNil(CommunityFeedRules.searchTerm(nil))
        XCTAssertNil(CommunityFeedRules.searchTerm(""))
        XCTAssertNil(CommunityFeedRules.searchTerm("   "))
        XCTAssertNil(CommunityFeedRules.searchTerm(" a "))
        XCTAssertNil(CommunityFeedRules.searchTerm("한"))
        XCTAssertNil(CommunityFeedRules.searchTerm("😀"))
        // 여러 스칼라로 된 그림 문자도 한 글자다.
        XCTAssertNil(CommunityFeedRules.searchTerm("👨‍👩‍👧"))
    }

    func testSearchTermTrimsAndKeepsTwoCharacters() {
        XCTAssertEqual(CommunityFeedRules.searchTerm("ab"), "ab")
        XCTAssertEqual(CommunityFeedRules.searchTerm("  서울 "), "서울")
        XCTAssertEqual(CommunityFeedRules.searchTerm("😀😀"), "😀😀")
        XCTAssertEqual(CommunityFeedRules.searchTerm("👨‍👩‍👧가"), "👨‍👩‍👧가")
        XCTAssertEqual(CommunityFeedRules.searchMinLength, 2)
    }

    func testSearchWaitsThreeHundredFiftyMillisecondsOnlyWhenSearching() {
        XCTAssertEqual(CommunityFeedRules.searchDelay, .milliseconds(350))
        XCTAssertEqual(CommunityFeedRules.delay(for: nil), .zero)
        XCTAssertEqual(CommunityFeedRules.delay(for: "서울"), .milliseconds(350))
    }

    // MARK: 끊김 없는 목록

    func testFirstPageHasNoCursorAndNoOffset() {
        let page = CommunityFeedRules.page(more: false, nextCursor: "c1", loaded: 20)
        XCTAssertNil(page.cursor)
        XCTAssertEqual(page.offset, 0)
    }

    func testMoreUsesCursorWhenServerGaveOne() {
        let page = CommunityFeedRules.page(more: true, nextCursor: "c1", loaded: 20)
        XCTAssertEqual(page.cursor, "c1")
        XCTAssertEqual(page.offset, 0)
    }

    func testMoreWithoutCursorSkipsLoadedRows() {
        let page = CommunityFeedRules.page(more: true, nextCursor: nil, loaded: 20)
        XCTAssertNil(page.cursor)
        XCTAssertEqual(page.offset, 20)
    }

    func testShouldLoadMoreNearTheEnd() {
        XCTAssertEqual(CommunityFeedRules.prefetchDistance, 5)
        XCTAssertTrue(CommunityFeedRules.shouldLoadMore(appearedIndex: 15, loaded: 20, total: 50, loading: false, failed: false))
        XCTAssertTrue(CommunityFeedRules.shouldLoadMore(appearedIndex: 19, loaded: 20, total: 50, loading: false, failed: false))
        XCTAssertFalse(CommunityFeedRules.shouldLoadMore(appearedIndex: 14, loaded: 20, total: 50, loading: false, failed: false))
    }

    func testShouldNotLoadMoreWhenDoneBusyOrFailed() {
        XCTAssertFalse(CommunityFeedRules.shouldLoadMore(appearedIndex: 19, loaded: 20, total: 20, loading: false, failed: false))
        XCTAssertFalse(CommunityFeedRules.shouldLoadMore(appearedIndex: 19, loaded: 20, total: 50, loading: true, failed: false))
        XCTAssertFalse(CommunityFeedRules.shouldLoadMore(appearedIndex: 19, loaded: 20, total: 50, loading: false, failed: true))
    }

    // MARK: 빈 화면

    func testNoEmptyStateWhileLoadingFailedOrShowingPosts() {
        XCTAssertNil(CommunityFeedRules.emptyState(board: .review, term: nil, posts: 0, legacy: 0, loading: true, failed: false))
        XCTAssertNil(CommunityFeedRules.emptyState(board: .review, term: nil, posts: 0, legacy: 0, loading: false, failed: true))
        XCTAssertNil(CommunityFeedRules.emptyState(board: .review, term: nil, posts: 2, legacy: 0, loading: false, failed: false))
        XCTAssertNil(CommunityFeedRules.emptyState(board: .review, term: "서울", posts: 2, legacy: 0, loading: false, failed: false))
    }

    func testSearchWithoutResultsIsNoMatchEvenWithLegacyPosts() {
        XCTAssertEqual(CommunityFeedRules.emptyState(board: .review, term: "서울", posts: 0, legacy: 3, loading: false, failed: false),
                       .noMatch("서울"))
        XCTAssertEqual(CommunityFeedRules.emptyState(board: .free, term: "바다", posts: 0, legacy: 0, loading: false, failed: false),
                       .noMatch("바다"))
    }

    func testEmptyBoardDependsOnBoard() {
        XCTAssertEqual(CommunityFeedRules.emptyState(board: .free, term: nil, posts: 0, legacy: 0, loading: false, failed: false), .freeBoard)
        XCTAssertEqual(CommunityFeedRules.emptyState(board: .review, term: nil, posts: 0, legacy: 0, loading: false, failed: false), .reviews)
    }

    func testLegacyPostsHideEmptyStateWhenNotSearching() {
        XCTAssertNil(CommunityFeedRules.emptyState(board: .review, term: nil, posts: 0, legacy: 1, loading: false, failed: false))
    }

    // MARK: 좋아요·댓글 수

    func testCountsHiddenWhenServerGaveNeither() {
        XCTAssertNil(CommunityFeedRules.counts(likeCount: nil, commentCount: nil))
    }

    func testCountsFillMissingSideWithZero() {
        let likesOnly = CommunityFeedRules.counts(likeCount: 3, commentCount: nil)
        XCTAssertEqual(likesOnly?.likes, 3)
        XCTAssertEqual(likesOnly?.comments, 0)
        let commentsOnly = CommunityFeedRules.counts(likeCount: nil, commentCount: 2)
        XCTAssertEqual(commentsOnly?.likes, 0)
        XCTAssertEqual(commentsOnly?.comments, 2)
        let both = CommunityFeedRules.counts(likeCount: 7, commentCount: 4)
        XCTAssertEqual(both?.likes, 7)
        XCTAssertEqual(both?.comments, 4)
    }

    func testToggledLikeFlipsAndCounts() {
        let on = CommunityFeedRules.toggledLike(liked: false, count: 3)
        XCTAssertTrue(on.liked)
        XCTAssertEqual(on.count, 4)
        let off = CommunityFeedRules.toggledLike(liked: true, count: 3)
        XCTAssertFalse(off.liked)
        XCTAssertEqual(off.count, 2)
    }

    func testToggledLikeNeverGoesBelowZero() {
        let off = CommunityFeedRules.toggledLike(liked: true, count: 0)
        XCTAssertFalse(off.liked)
        XCTAssertEqual(off.count, 0)
    }

    // MARK: 댓글

    func testCommentBodyTrimsAndRejectsEmpty() {
        XCTAssertEqual(CommunityFeedRules.commentBody("  좋은 글이에요  "), "좋은 글이에요")
        XCTAssertNil(CommunityFeedRules.commentBody(""))
        XCTAssertNil(CommunityFeedRules.commentBody("   "))
    }

    func testCommentBodyIsCutToFiveHundredCharacters() {
        XCTAssertEqual(CommunityFeedRules.commentLimit, 500)
        XCTAssertEqual(CommunityFeedRules.commentBody(String(repeating: "가", count: 600)), String(repeating: "가", count: 500))
        // 문자 단위 — 그림 문자 하나는 한 글자다.
        XCTAssertEqual(CommunityFeedRules.commentBody(String(repeating: "😀", count: 501)), String(repeating: "😀", count: 500))
        let exact = String(repeating: "a", count: 500)
        XCTAssertEqual(CommunityFeedRules.commentBody("  " + exact + "  "), exact)
    }

    func testCommentPlaceholderDependsOnWhetherCommentsExist() {
        XCTAssertEqual(CommunityFeedRules.commentPlaceholderKey(total: 0), "첫 댓글을 남겨 보세요")
        XCTAssertEqual(CommunityFeedRules.commentPlaceholderKey(total: 1), "댓글을 남겨 보세요")
        XCTAssertEqual(CommunityFeedRules.commentPlaceholderKey(total: 12), "댓글을 남겨 보세요")
    }
}
