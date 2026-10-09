import SceneApiClient
@testable import SceneTrip
import XCTest

/// 홈 「지금 뜨는 작품」 — 촬영지 많은 순 (MZ2AZ-372).
///
/// 전에는 서버 순서의 앞 20편만 받아 그 안에서 정렬해, 촬영지가 가장 많은 작품이 그 20편 밖이면 선반에 없었다.
final class HomeShelfTests: XCTestCase {
    private func work(_ id: Int64, places: Int) -> ContentSummary {
        ContentSummary(id: id, category: .drama, title: "작품 \(id)", placeCount: places)
    }

    /// 훑은 것 전체에서 고른다 — 맨 뒤에 있던 19곳짜리가 맨 앞에 온다.
    func testMostLocationsComeFirstWhereverTheyWere() {
        var works = (1 ... 127).map { work(Int64($0), places: 3) }
        works.append(work(128, places: 19))
        let shelf = HomeTabModel.shelf(from: works)
        XCTAssertEqual(shelf.first?.id, 128)
        XCTAssertEqual(shelf.count, HomeTabModel.shelfCount)
    }

    /// 촬영지 수가 같으면 서버가 준 순서를 지킨다 — 화면을 다시 그릴 때마다 자리가 바뀌면 안 된다.
    func testTiesKeepServerOrder() {
        let works = [work(5, places: 4), work(2, places: 9), work(9, places: 4), work(1, places: 4)]
        let shelf = HomeTabModel.shelf(from: works)
        XCTAssertEqual(shelf.map(\.id), [2, 5, 9, 1])
    }

    func testSmallCatalogShowsAll() {
        XCTAssertEqual(HomeTabModel.shelf(from: [work(1, places: 1), work(2, places: 2)]).map(\.id), [2, 1])
        XCTAssertTrue(HomeTabModel.shelf(from: []).isEmpty)
    }
}
