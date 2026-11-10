import SceneApiClient
@testable import SceneTrip
import XCTest

/// 지도 검색 결과 서랍의 「더 받기」 — 20개씩, 끝에서 한 줄 안이 보이면 다음 쪽, total·빈 쪽에서 멈춤,
/// 같은 id 는 다시 넣지 않음, 지도 맞추기는 처음 쪽만 (계획 docs/project/plans/motion-works-drawer-parity.md §7-4).
final class MapSearchPagingRulesTests: XCTestCase {
    private struct Shop: Identifiable, Equatable {
        let id: String
        let name: String
    }

    private func shops(_ ids: [String]) -> [Shop] {
        ids.map { Shop(id: $0, name: "가게 \($0)") }
    }

    private func poi(_ id: Int64) -> PoiSummary {
        PoiSummary(id: id, name: "가게 \(id)", category: "카페", categoryGroup: .food, latitude: 37.28, longitude: 127.01)
    }

    // MARK: 쪽 크기

    func testPageSizeIsTwenty() {
        XCTAssertEqual(MapSearchPagingRules.pageSize, 20)
    }

    // MARK: 다음 offset

    func testFirstPageAsksFromZero() {
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 0, total: nil, lastPageEmpty: false), 0)
    }

    func testNextOffsetIsLoadedCountWhenTotalUnknown() {
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 20, total: nil, lastPageEmpty: false), 20)
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 37, total: nil, lastPageEmpty: false), 37)
    }

    func testNextOffsetIsLoadedCountBelowTotal() {
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 20, total: 55, lastPageEmpty: false), 20)
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 40, total: 55, lastPageEmpty: false), 40)
    }

    func testStopsWhenTotalIsZero() {
        XCTAssertNil(MapSearchPagingRules.nextOffset(loaded: 0, total: 0, lastPageEmpty: false))
    }

    func testStopsAtExactMultipleOfPageSize() {
        // total 40 — 20·20 을 받았으면 끝. 빈 쪽을 한 번 더 묻지 않는다.
        XCTAssertNil(MapSearchPagingRules.nextOffset(loaded: 40, total: 40, lastPageEmpty: false))
        XCTAssertEqual(MapSearchPagingRules.nextOffset(loaded: 20, total: 40, lastPageEmpty: false), 20)
    }

    func testStopsWhenLoadedPassesTotal() {
        // 받는 사이 서버 쪽 개수가 줄었을 수 있다.
        XCTAssertNil(MapSearchPagingRules.nextOffset(loaded: 45, total: 40, lastPageEmpty: false))
    }

    func testStopsAfterEmptyPage() {
        XCTAssertNil(MapSearchPagingRules.nextOffset(loaded: 20, total: nil, lastPageEmpty: true))
        // total 이 더 남았다고 해도 빈 쪽이 오면 멈춘다.
        XCTAssertNil(MapSearchPagingRules.nextOffset(loaded: 20, total: 60, lastPageEmpty: true))
    }

    // MARK: 끝 가까이 보이면

    func testLoadsMoreWhenRowBeforeLastIsVisible() {
        // 20개 받음 → 18번(끝에서 두 번째) 줄이 보이면 묻는다.
        XCTAssertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 18, loaded: 20))
        XCTAssertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 19, loaded: 20))
    }

    func testDoesNotLoadMoreFarFromEnd() {
        XCTAssertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 17, loaded: 20))
        XCTAssertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 5, loaded: 20))
    }

    func testShortListLoadsMoreFromFirstRow() {
        // 1개뿐 → 0번이 곧 끝 줄. 2개 → 0번이 끝에서 두 번째.
        XCTAssertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 0, loaded: 1))
        XCTAssertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 0, loaded: 2))
        // 빈 목록이면 볼 줄이 없으니 묻지 않는다.
        XCTAssertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: 0, loaded: 0))
    }

    // MARK: 합치기

    func testMergeAppendsInServerOrder() {
        let merged = MapSearchPagingRules.merge(loaded: shops(["a", "b"]), page: shops(["c", "d"]))
        XCTAssertEqual(merged.map(\.id), ["a", "b", "c", "d"])
    }

    func testMergeSkipsAlreadyLoadedIds() {
        let merged = MapSearchPagingRules.merge(loaded: shops(["a", "b", "c"]), page: shops(["c", "d", "a", "e"]))
        XCTAssertEqual(merged.map(\.id), ["a", "b", "c", "d", "e"])
    }

    func testMergeKeepsExistingItemOverNewCopy() {
        let loaded = [Shop(id: "a", name: "먼저 받은 이름")]
        let merged = MapSearchPagingRules.merge(loaded: loaded, page: [Shop(id: "a", name: "나중 이름"), Shop(id: "b", name: "새 가게")])
        XCTAssertEqual(merged, [Shop(id: "a", name: "먼저 받은 이름"), Shop(id: "b", name: "새 가게")])
    }

    func testMergeDropsDuplicatesInsideOnePage() {
        let merged = MapSearchPagingRules.merge(loaded: shops(["a"]), page: shops(["b", "b", "c"]))
        XCTAssertEqual(merged.map(\.id), ["a", "b", "c"])
    }

    func testMergeWithEmptySides() {
        XCTAssertEqual(MapSearchPagingRules.merge(loaded: [Shop](), page: shops(["a", "b"])).map(\.id), ["a", "b"])
        XCTAssertEqual(MapSearchPagingRules.merge(loaded: shops(["a", "b"]), page: []).map(\.id), ["a", "b"])
    }

    func testMergeWorksWithRoutePlaces() {
        // 지도 검색이 실제로 쓰는 줄 모양(`RouteGuide.Place`, id 는 "poi-<번호>" 글자).
        let first = RouteGuide.Place(poi: poi(1))
        let second = RouteGuide.Place(poi: poi(2))
        let merged = MapSearchPagingRules.merge(loaded: [first], page: [first, second])
        XCTAssertEqual(merged.map(\.id), ["poi-1", "poi-2"])
    }

    // MARK: 지도 맞추기

    func testOnlyFirstPageFitsMap() {
        XCTAssertTrue(MapSearchPagingRules.fitsMap(offset: 0))
        XCTAssertFalse(MapSearchPagingRules.fitsMap(offset: 20))
        XCTAssertFalse(MapSearchPagingRules.fitsMap(offset: 40))
    }
}
