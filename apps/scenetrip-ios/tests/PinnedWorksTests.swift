import SceneApiClient
@testable import SceneTrip
import XCTest

/// 「지금 뜨는 작품」 맨 앞 고정 작품 (MZ2AZ-547 H6, 웹 `pinnedWorks.ts`).
final class PinnedWorksTests: XCTestCase {
    private func work(_ id: Int64) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품 \(id)", placeCount: 1)
    }

    private func detail(_ id: Int64, delisted: Bool? = nil) -> ContentDetail {
        ContentDetail(id: id, category: .movie, title: "상세 \(id)", posterUrl: "https://img.example/\(id).jpg", placeCount: 7, delisted: delisted)
    }

    func testPinnedIdsIsKpopDemonHunters() {
        XCTAssertEqual(PinnedWorks.ids, [32])
    }

    func testMissingListsIdsNotInList() {
        XCTAssertEqual(PinnedWorks.missing(in: [work(1), work(2)], ids: [32, 2, 40]), [32, 40])
        XCTAssertEqual(PinnedWorks.missing(in: [work(32)], ids: [32]), [])
    }

    func testSummaryCopiesDetailFields() {
        let summary = PinnedWorks.summary(of: detail(32, delisted: false))
        XCTAssertEqual(summary?.id, 32)
        XCTAssertEqual(summary?.title, "상세 32")
        XCTAssertEqual(summary?.category, .movie)
        XCTAssertEqual(summary?.placeCount, 7)
        XCTAssertEqual(summary?.posterUrl, "https://img.example/32.jpg")
        XCTAssertNotNil(PinnedWorks.summary(of: detail(33)))
    }

    func testSummaryNilForDelisted() {
        XCTAssertNil(PinnedWorks.summary(of: detail(32, delisted: true)))
        // 대조: 숨기지 않은 작품은 목록 줄이 된다.
        XCTAssertEqual(PinnedWorks.summary(of: detail(32, delisted: false))?.id, 32)
    }

    func testArrangeMovesPinnedFrontWithoutDuplicate() {
        let out = PinnedWorks.arrange([work(1), work(2), work(32), work(3)], fetched: [:], ids: [32])
        XCTAssertEqual(out.map(\.id), [32, 1, 2, 3])
    }

    func testArrangeUsesFetchedWhenMissingAndSkipsUnknown() {
        let out = PinnedWorks.arrange([work(1), work(2)], fetched: [40: work(40)], ids: [32, 40])
        XCTAssertEqual(out.map(\.id), [40, 1, 2])
    }

    func testArrangeKeepsIdsOrder() {
        let out = PinnedWorks.arrange([work(1), work(5), work(9)], fetched: [:], ids: [9, 5])
        XCTAssertEqual(out.map(\.id), [9, 5, 1])
    }

    func testApplyFetchesOnlyMissing() async {
        var asked: [Int64] = []
        let out = await PinnedWorks.apply(to: [work(1), work(5)], ids: [5, 32]) { id in
            asked.append(id)
            return self.detail(id)
        }
        XCTAssertEqual(asked, [32])
        XCTAssertEqual(out.map(\.id), [5, 32, 1])
    }

    func testApplyToleratesFailedFetch() async {
        // 32 받기는 실패해도 목록에 있던 5 는 여전히 앞에 선다.
        let out = await PinnedWorks.apply(to: [work(1), work(5), work(2)], ids: [32, 5]) { _ in nil }
        XCTAssertEqual(out.map(\.id), [5, 1, 2])
    }

    func testApplySkipsDelistedFetch() async {
        // 숨긴 32 는 건너뛰고, 숨기지 않은 40 은 앞에 선다.
        let out = await PinnedWorks.apply(to: [work(1)], ids: [32, 40]) { id in self.detail(id, delisted: id == 32) }
        XCTAssertEqual(out.map(\.id), [40, 1])
    }

    func testApplyDoesNotFetchWhenPresent() async {
        var asked = 0
        let out = await PinnedWorks.apply(to: [work(1), work(32)], ids: [32]) { _ in
            asked += 1
            return nil
        }
        XCTAssertEqual(asked, 0)
        XCTAssertEqual(out.map(\.id), [32, 1])
    }
}
