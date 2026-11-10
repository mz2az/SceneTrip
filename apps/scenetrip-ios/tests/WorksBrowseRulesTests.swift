import SceneApiClient
@testable import SceneTrip
import XCTest

/// 작품 고르기 단계의 작품 목록 — 쪽 넘김·검색·고른 것 유지·요청 제목·찜 먼저
/// (계획 docs/project/plans/motion-works-drawer-parity.md §1).
final class WorksBrowseRulesTests: XCTestCase {
    private func work(_ id: Int64) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품\(id)", placeCount: 3)
    }

    private func works(_ ids: [Int64]) -> [ContentSummary] {
        ids.map(work)
    }

    // MARK: 숫자

    func testPageSizeDebounceAndLimits() {
        XCTAssertEqual(WorksBrowseRules.pageSize, 30)
        XCTAssertEqual(WorksBrowseRules.debounce, .milliseconds(300))
        XCTAssertEqual(WorksBrowseRules.maxQueryLength, 100)
        XCTAssertEqual(WorksBrowseRules.popularFallbackCount, 3)
    }

    // MARK: 검색어

    func testBlankSearchHasNoQuery() {
        XCTAssertNil(WorksBrowseRules.query(""))
        XCTAssertNil(WorksBrowseRules.query("   "))
        XCTAssertNil(WorksBrowseRules.query("\n\t "))
        XCTAssertEqual(WorksBrowseRules.query("도깨비"), "도깨비")
    }

    func testQueryIsTrimmedAndOneCharacterIsEnough() {
        XCTAssertEqual(WorksBrowseRules.query("  도깨비 "), "도깨비")
        XCTAssertEqual(WorksBrowseRules.query("별"), "별")
    }

    func testQueryCutsAtContractMaximum() {
        let long = String(repeating: "가", count: 150)
        XCTAssertEqual(WorksBrowseRules.query(long), String(repeating: "가", count: 100))
        XCTAssertEqual(WorksBrowseRules.query(String(repeating: "가", count: 100))?.count, 100)
    }

    // MARK: 다음 쪽

    func testFirstPageStartsAtZero() {
        XCTAssertEqual(
            WorksBrowseRules.nextRequest(query: nil, paging: Paging()),
            WorksPageRequest(query: nil, limit: 30, offset: 0)
        )
    }

    func testOffsetAdvancesByReceivedRows() {
        var paging = Paging().advanced(received: 30, total: 128)
        XCTAssertEqual(
            WorksBrowseRules.nextRequest(query: nil, paging: paging),
            WorksPageRequest(query: nil, limit: 30, offset: 30)
        )
        paging = paging.advanced(received: 30, total: 128)
        XCTAssertEqual(WorksBrowseRules.nextRequest(query: nil, paging: paging)?.offset, 60)
        paging = paging.advanced(received: 30, total: 128).advanced(received: 30, total: 128)
        XCTAssertEqual(WorksBrowseRules.nextRequest(query: nil, paging: paging)?.offset, 120)
    }

    func testStopsWhenTotalReached() {
        var paging = Paging()
        for _ in 0 ..< 4 {
            paging = paging.advanced(received: 30, total: 128)
        }
        XCTAssertEqual(WorksBrowseRules.nextRequest(query: nil, paging: paging)?.offset, 120)
        paging = paging.advanced(received: 8, total: 128)
        XCTAssertNil(WorksBrowseRules.nextRequest(query: nil, paging: paging))
    }

    func testStopsOnEmptyPageEvenBelowTotal() {
        let paging = Paging().advanced(received: 30, total: 128)
        XCTAssertNotNil(WorksBrowseRules.nextRequest(query: nil, paging: paging))
        XCTAssertNil(WorksBrowseRules.nextRequest(query: nil, paging: paging.advanced(received: 0, total: 128)))
    }

    func testSearchPagesCarryTheQuery() {
        XCTAssertEqual(
            WorksBrowseRules.nextRequest(query: "사랑", paging: Paging()),
            WorksPageRequest(query: "사랑", limit: 30, offset: 0)
        )
        let paging = Paging().advanced(received: 30, total: 45)
        XCTAssertEqual(
            WorksBrowseRules.nextRequest(query: "사랑", paging: paging),
            WorksPageRequest(query: "사랑", limit: 30, offset: 30)
        )
        XCTAssertNil(WorksBrowseRules.nextRequest(query: "사랑", paging: paging.advanced(received: 15, total: 45)))
    }

    func testSearchWithFewerThanOnePageStopsAfterFirst() {
        XCTAssertNotNil(WorksBrowseRules.nextRequest(query: "도깨비", paging: Paging()))
        let paging = Paging().advanced(received: 2, total: 2)
        XCTAssertNil(WorksBrowseRules.nextRequest(query: "도깨비", paging: paging))
    }

    // MARK: 목록 끝에서 한 줄 안

    func testLoadMoreWhenLastRowAppears() {
        XCTAssertTrue(WorksBrowseRules.shouldLoadMore(appeared: 29, loaded: 30, columns: 3))
        XCTAssertTrue(WorksBrowseRules.shouldLoadMore(appeared: 27, loaded: 30, columns: 3))
        XCTAssertFalse(WorksBrowseRules.shouldLoadMore(appeared: 26, loaded: 30, columns: 3))
        XCTAssertFalse(WorksBrowseRules.shouldLoadMore(appeared: 0, loaded: 30, columns: 3))
    }

    func testLoadMoreWhenListIsShorterThanARow() {
        XCTAssertTrue(WorksBrowseRules.shouldLoadMore(appeared: 0, loaded: 2, columns: 3))
    }

    func testNoLoadMoreForEmptyList() {
        XCTAssertTrue(WorksBrowseRules.shouldLoadMore(appeared: 59, loaded: 60, columns: 3))
        XCTAssertFalse(WorksBrowseRules.shouldLoadMore(appeared: 0, loaded: 0, columns: 3))
    }

    // MARK: 찜 먼저

    func testLikedWorksFirstThenServerOrder() {
        let list = works([1, 2, 3, 4, 5, 6])
        XCTAssertEqual(WorksBrowseRules.ordered(list, liked: [5, 2, 99]).map(\.id), [2, 5, 1, 3, 4, 6])
    }

    func testNoLikesKeepsServerOrder() {
        let list = works([4, 1, 3])
        XCTAssertEqual(WorksBrowseRules.ordered(list, liked: []).map(\.id), [4, 1, 3])
    }

    func testLikedOutsideLoadedAreNotAdded() {
        let list = works([1, 2, 3])
        XCTAssertEqual(WorksBrowseRules.ordered(list, liked: [42]).map(\.id), [1, 2, 3])
        XCTAssertEqual(WorksBrowseRules.ordered(works([]), liked: [1]).map(\.id), [])
    }

    // MARK: 고른 것 유지

    func testPicksFromShownListAreKept() {
        let kept = WorksBrowseRules.keptPicks(selected: [3, 1], kept: [], shown: works([1, 2, 3, 4]))
        XCTAssertEqual(kept.map(\.id), [1, 3])
    }

    func testPicksAbsentFromSearchResultsStay() {
        // 원래 목록에서 7 을 고른 뒤 「사랑」 으로 찾았다 — 결과에 7 이 없어도 남고, 결과에서 고른 2 가 뒤에 붙는다.
        let kept = WorksBrowseRules.keptPicks(selected: [7, 2], kept: [work(7)], shown: works([1, 2, 3]))
        XCTAssertEqual(kept.map(\.id), [7, 2])
    }

    func testUnpickedWorksAreDropped() {
        let kept = WorksBrowseRules.keptPicks(selected: [2], kept: works([7, 2]), shown: [])
        XCTAssertEqual(kept.map(\.id), [2])
    }

    func testSameWorkKeptOnce() {
        let kept = WorksBrowseRules.keptPicks(selected: [2, 5], kept: works([2]), shown: works([5, 2]))
        XCTAssertEqual(kept.map(\.id), [2, 5])
    }

    func testSelectedButNeverSeenIsNotInvented() {
        let kept = WorksBrowseRules.keptPicks(selected: [1, 99], kept: [], shown: works([1, 2]))
        XCTAssertEqual(kept.map(\.id), [1])
    }

    // MARK: 요청 제목

    func testRequestUsesPickedTitles() {
        XCTAssertEqual(
            WorksBrowseRules.requestTitles(picked: works([3, 1]), popular: works([1, 2, 3, 4])),
            ["작품3", "작품1"]
        )
    }

    func testNoPicksFallsBackToFirstThreePopular() {
        XCTAssertEqual(
            WorksBrowseRules.requestTitles(picked: [], popular: works([9, 8, 7, 6, 5])),
            ["작품9", "작품8", "작품7"]
        )
    }

    func testFallbackWithFewerThanThreePopular() {
        XCTAssertEqual(WorksBrowseRules.requestTitles(picked: [], popular: works([4, 5])), ["작품4", "작품5"])
        XCTAssertEqual(WorksBrowseRules.requestTitles(picked: [], popular: works([4])), ["작품4"])
    }

    func testRequestTitlesCapAtMaxWorks() {
        XCTAssertEqual(
            WorksBrowseRules.requestTitles(picked: works([1, 2, 3, 4, 5, 6]), popular: []),
            ["작품1", "작품2", "작품3", "작품4", "작품5"]
        )
    }

    func testPickedWorkOutsideSearchStillTitlesRequest() {
        // 7 을 고르고 검색 결과([1,2])에는 7 이 없다 — 요청은 여전히 7 의 제목.
        let kept = WorksBrowseRules.keptPicks(selected: [7], kept: [work(7)], shown: works([1, 2]))
        XCTAssertEqual(WorksBrowseRules.requestTitles(picked: kept, popular: works([1, 2, 3])), ["작품7"])
    }

    // MARK: 최대 5개 규칙은 그대로

    func testMaxPicksStillFiveAcrossSearch() {
        XCTAssertEqual(WorkPickRules.maxWorks, 5)
        // 원래 목록에서 다섯을 골랐다.
        var selected: Set<Int64> = []
        for id in Int64(1) ... 5 {
            selected = WorkPickRules.toggle(selected, id).selected
        }
        var kept = WorksBrowseRules.keptPicks(selected: selected, kept: [], shown: works([1, 2, 3, 4, 5, 6]))
        XCTAssertEqual(kept.map(\.id), [1, 2, 3, 4, 5])
        // 검색 결과에서 여섯째를 누르면 경고, 고른 것은 그대로.
        let sixth = WorkPickRules.toggle(selected, 40)
        XCTAssertTrue(sixth.tooMany)
        XCTAssertEqual(sixth.selected, selected)
        kept = WorksBrowseRules.keptPicks(selected: sixth.selected, kept: kept, shown: works([40, 41]))
        XCTAssertEqual(kept.map(\.id), [1, 2, 3, 4, 5])
        XCTAssertEqual(WorksBrowseRules.requestTitles(picked: kept, popular: []).count, 5)
    }
}
