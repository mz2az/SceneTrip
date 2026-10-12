import SceneApiClient
@testable import SceneTrip
import XCTest

/// 홈 선반은 서버 인기도 순서를 그대로 유지한다 (MZ2AZ-411).
final class HomeShelfTests: XCTestCase {
    private func work(_ id: Int64, places: Int) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품 \(id)", placeCount: places)
    }

    func testOnlyFirstTwentyPopularWorksAppearEvenIfLaterWorkHasMorePlaces() {
        var works = (1 ... 127).map { work(Int64($0), places: 3) }
        works.append(work(128, places: 19))
        let shelf = HomeTabModel.shelf(from: works)
        XCTAssertEqual(shelf.map(\.id), Array(1 ... 20).map(Int64.init))
        XCTAssertEqual(shelf.count, HomeTabModel.shelfCount)
    }

    func testServerOrderWinsOverPlaceCount() {
        let works = [work(5, places: 4), work(2, places: 9), work(9, places: 4), work(1, places: 4)]
        let shelf = HomeTabModel.shelf(from: works)
        XCTAssertEqual(shelf.map(\.id), [5, 2, 9, 1])
    }

    func testSmallCatalogShowsAll() {
        XCTAssertEqual(HomeTabModel.shelf(from: [work(1, places: 1), work(2, places: 2)]).map(\.id), [1, 2])
        XCTAssertTrue(HomeTabModel.shelf(from: []).isEmpty)
    }

    @MainActor func testShelfLoadsOnceWithTwentyItemLimit() async {
        var limits: [Int] = []
        let expected = [work(5, places: 1), work(2, places: 19)]
        let model = HomeTabModel { limit in
            limits.append(limit)
            return expected
        }
        await model.loadShelf()
        XCTAssertEqual(limits, [20])
        XCTAssertEqual(model.works.map(\.id), [5, 2])
        XCTAssertFalse(model.failed)
    }

    @MainActor func testRefreshFailurePreservesLoadedShelf() async {
        var shouldFail = false
        let model = HomeTabModel { _ in
            if shouldFail {
                throw URLError(.notConnectedToInternet)
            }
            return [self.work(5, places: 1)]
        }
        await model.loadShelf()
        shouldFail = true
        await model.loadShelf()
        XCTAssertEqual(model.works.map(\.id), [5])
        XCTAssertFalse(model.failed)
        let empty = HomeTabModel { _ in throw URLError(.notConnectedToInternet) }
        await empty.loadShelf()
        XCTAssertTrue(empty.failed)
    }
}
