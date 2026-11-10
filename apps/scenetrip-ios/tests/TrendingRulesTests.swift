import SceneApiClient
@testable import SceneTrip
import XCTest

/// 「지금 뜨는 작품」 전체 보기 — 100편씩 이어 받기 (MZ2AZ-547 H6, 웹 `trendingModel.ts`).
final class TrendingRulesTests: XCTestCase {
    private func work(_ id: Int64) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품 \(id)", placeCount: 1)
    }

    private func works(_ range: ClosedRange<Int64>) -> [ContentSummary] {
        range.map(work)
    }

    func testConstants() {
        XCTAssertEqual(TrendingRules.pageLimit, 100)
        XCTAssertEqual(TrendingRules.maxPages, 5)
    }

    func testMergeDedupesKeepingFirst() {
        let merged = TrendingRules.merge([work(1), work(2), work(3)], page: [work(3), work(4), work(1), work(5)])
        XCTAssertEqual(merged.map(\.id), [1, 2, 3, 4, 5])
    }

    func testShouldContinueOnlyWhenPageFullAndBelowTotal() {
        XCTAssertTrue(TrendingRules.shouldContinue(pageCount: 100, collected: 100, total: 128))
        XCTAssertFalse(TrendingRules.shouldContinue(pageCount: 28, collected: 128, total: 300))
        XCTAssertFalse(TrendingRules.shouldContinue(pageCount: 100, collected: 200, total: 200))
        XCTAssertFalse(TrendingRules.shouldContinue(pageCount: 100, collected: 250, total: 200))
    }

    func testLoadFetchesPagesUntilTotalAndPinsFront() async throws {
        // 1~100, 101~128 (32 는 첫 쪽에 있다).
        var offsets: [Int] = []
        var detailAsked = 0
        let out = try await TrendingRules.load(
            fetchPage: { offset in
                offsets.append(offset)
                return offset == 0 ? (self.works(1 ... 100), 128) : (self.works(101 ... 128), 128)
            },
            fetchDetail: { _ in
                detailAsked += 1
                return nil
            }
        )
        XCTAssertEqual(offsets, [0, 100])
        XCTAssertEqual(out.count, 128)
        XCTAssertEqual(out.first?.id, 32)
        XCTAssertEqual(Set(out.map(\.id)).count, 128)
        XCTAssertEqual(detailAsked, 0)
    }

    func testLoadStopsAtMaxPages() async throws {
        var calls = 0
        let out = try await TrendingRules.load(
            fetchPage: { offset in
                calls += 1
                let start = Int64(offset + 1000)
                return (self.works(start ... start + 99), 100_000)
            },
            fetchDetail: { _ in nil }
        )
        XCTAssertEqual(calls, 5)
        XCTAssertEqual(out.count, 500)
    }

    func testLoadFetchesMissingPinnedDetail() async throws {
        var asked: [Int64] = []
        let out = try await TrendingRules.load(
            fetchPage: { _ in (self.works(1 ... 3), 3) },
            fetchDetail: { id in
                asked.append(id)
                return ContentDetail(id: id, category: .movie, title: "케이팝 데몬 헌터스", placeCount: 4)
            }
        )
        XCTAssertEqual(asked, [32])
        XCTAssertEqual(out.map(\.id), [32, 1, 2, 3])
    }

    func testLoadThrowsWhenPageFails() async {
        do {
            _ = try await TrendingRules.load(
                fetchPage: { _ in throw URLError(.notConnectedToInternet) },
                fetchDetail: { _ in nil }
            )
            XCTFail("쪽 받기 실패는 던져야 한다")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
        }
    }
}
