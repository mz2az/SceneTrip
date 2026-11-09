import SceneApiClient
@testable import SceneTrip
import XCTest

// 지도 편의시설 칩·골고루 찍기·지도 검색 규칙 (MZ2AZ-547 P5, 계획서 `docs/project/plans/ios-web-parity.md` M1–M7).
//
// 웹 `proto/protoPoi.tsx`(`fetchSpread`·`spreadPois`·`MapPoiBar`·`SearchPanel`·`ResultsView`)와
// `route/editor/tripExtensions.tsx`(칩 켜고 끄기 → `poiAsk`), `route/poi/useAmbientPois.ts`(받는 중·다시 찾기)를 명세로 삼는다.

// MARK: - 도움

/// 편의시설 하나 — 갈래와 좌표만 바꿔 쓴다.
private func poi(
    _ id: Int64, _ group: PoiCategoryGroup = .food, lat: Double = 37.5, lng: Double = 127.0
) -> RouteGuide.Place {
    RouteGuide.Place(poi: PoiSummary(
        id: id, name: "가게\(id)", category: "한식", categoryGroup: group, latitude: lat, longitude: lng
    ))
}

private func spot(_ id: Int64) -> PlaceSummary {
    PlaceSummary(id: id, name: "촬영지\(id)", latitude: 37.5 + Double(id) * 0.001, longitude: 127.0)
}

private func ids(_ places: [RouteGuide.Place]) -> [String] {
    places.map(\.id)
}

// MARK: - M1·M2 칩 줄

// MARK: - M6 지도 검색

final class MapSearchRulesTests: XCTestCase {
    private let viewport = MapViewport(
        south: 37.4, west: 126.9, north: 37.6, east: 127.1, centerLat: 37.5, centerLng: 127.0, zoom: 13
    )

    func testConstants() {
        XCTAssertEqual(MapSearchRules.minChars, 2)
        XCTAssertEqual(MapSearchRules.debounce, .milliseconds(350))
        XCTAssertEqual(MapSearchRules.listLimit, 20)
        XCTAssertEqual(MapSearchRules.resultsLimit, 40)
        XCTAssertEqual(MapSearchRules.maxLength, 100)
    }

    /// 앞뒤 공백을 떼고 2글자부터.
    func testQueryTrimsAndNeedsTwoCharacters() {
        XCTAssertEqual(MapSearchRules.query("  남산  "), "남산")
        XCTAssertEqual(MapSearchRules.query("ab"), "ab")
        XCTAssertNil(MapSearchRules.query("남"))
        XCTAssertNil(MapSearchRules.query("  남 \n"))
        XCTAssertNil(MapSearchRules.query(""))
    }

    /// 글자 수는 보이는 글자로 센다 — 풀어 쓴 한글 「가」(ᄀ+ᅡ)는 한 글자.
    func testQueryCountsCharactersNotScalars() {
        XCTAssertNil(MapSearchRules.query("\u{1100}\u{1161}"))
        XCTAssertEqual(MapSearchRules.query("\u{1100}\u{1161}\u{1102}\u{1161}"), "\u{1100}\u{1161}\u{1102}\u{1161}")
    }

    /// 100자에서 자른다.
    func testQueryTruncatesToMaxLength() {
        let long = String(repeating: "가", count: 150)
        XCTAssertEqual(MapSearchRules.query(long), String(repeating: "가", count: 100))
        XCTAssertEqual(MapSearchRules.query(" " + long + " ")?.count, 100)
    }

    func testCanCommitNeedsTwoCharacters() {
        XCTAssertTrue(MapSearchRules.canCommit("남산"))
        XCTAssertTrue(MapSearchRules.canCommit("  남산 "))
        XCTAssertFalse(MapSearchRules.canCommit("남"))
        XCTAssertFalse(MapSearchRules.canCommit("   "))
    }

    /// 범위는 서쪽,남쪽,동쪽,북쪽.
    func testViewportBboxOrder() {
        XCTAssertEqual(viewport.bbox, "126.9,37.4,127.1,37.6")
    }

    /// 「이 지역 밖에서도」 면 범위 없음, 아니면 지금 지도.
    func testBboxForWideOrHere() {
        XCTAssertEqual(MapSearchRules.bbox(wide: false, viewport: viewport), "126.9,37.4,127.1,37.6")
        XCTAssertNil(MapSearchRules.bbox(wide: true, viewport: viewport))
        XCTAssertNil(MapSearchRules.bbox(wide: false, viewport: nil))
    }

    func testRowsForIdleLoadingFailed() {
        XCTAssertEqual(MapSearchRules.rows(.idle), [])
        XCTAssertEqual(MapSearchRules.rows(.loading), [.loading])
        XCTAssertEqual(MapSearchRules.rows(.failed), [.failed])
    }

    /// 이 지역 — 촬영지 → 「이 지역 밖에서도 찾기」 → 가게.
    func testRowsHereWithResults() {
        let shop = poi(9)
        let rows = MapSearchRules.rows(.done(spots: [spot(1), spot(2)], shops: [shop], wide: false))
        XCTAssertEqual(rows, [
            .title(.spotsHere), .spot(spot(1)), .spot(spot(2)),
            .searchEverywhere,
            .title(.shopsHere), .shop(shop),
        ])
    }

    /// 이 지역에 없으면 「이 지역엔 없어요」·「찾는 가게가 없어요」, 그래도 「이 지역 밖에서도 찾기」.
    func testRowsHereEmpty() {
        let rows = MapSearchRules.rows(.done(spots: [], shops: [], wide: false))
        XCTAssertEqual(rows, [
            .title(.spotsHere), .empty(.noneHere),
            .searchEverywhere,
            .title(.shopsHere), .empty(.noShops),
        ])
    }

    /// 전체에서 찾았으면 「이 지역 밖에서도 찾기」 가 없다.
    func testRowsWideWithResults() {
        let shop = poi(7, .stay)
        let rows = MapSearchRules.rows(.done(spots: [spot(3)], shops: [shop], wide: true))
        XCTAssertEqual(rows, [
            .title(.spotsEverywhere), .spot(spot(3)),
            .title(.shopsEverywhere), .shop(shop),
        ])
    }

    func testRowsWideEmpty() {
        let rows = MapSearchRules.rows(.done(spots: [], shops: [], wide: true))
        XCTAssertEqual(rows, [
            .title(.spotsEverywhere), .empty(.noSpots),
            .title(.shopsEverywhere), .empty(.noShops),
        ])
    }
}

// MARK: - M7 엔터 뒤 결과 지도

final class MapSearchResultsTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    func testConstants() {
        XCTAssertEqual(MapSearchResults.shortShare, 0.42)
        XCTAssertEqual(MapSearchResults.tallShare, 0.78)
        XCTAssertEqual(MapSearchResults.pinSmall, 34)
        XCTAssertEqual(MapSearchResults.pinBig, 46)
        XCTAssertEqual(MapSearchResults.selfMoveGrace, 0.9, accuracy: 0.0001)
    }

    /// 서랍은 42% 에서 시작해 손잡이로 78% ↔ 42%.
    func testDrawerToggles() {
        var results = MapSearchResults(query: "남산", wide: false)
        XCTAssertEqual(results.drawerShare, 0.42, accuracy: 0.0001)
        results.toggleDrawer()
        XCTAssertTrue(results.tall)
        XCTAssertEqual(results.drawerShare, 0.78, accuracy: 0.0001)
        results.toggleDrawer()
        XCTAssertEqual(results.drawerShare, 0.42, accuracy: 0.0001)
    }

    /// 탭 수 — 촬영지는 받기 전 nil, 가게는 늘 수.
    func testTabCounts() {
        var results = MapSearchResults(query: "남산", wide: false)
        XCTAssertNil(results.count(of: .spots))
        XCTAssertEqual(results.count(of: .shops), 0)
        results.received(spots: [spot(1), spot(2)])
        results.received(shops: [poi(1), poi(2), poi(3)])
        XCTAssertEqual(results.count(of: .spots), 2)
        XCTAssertEqual(results.count(of: .shops), 3)
    }

    /// 새로 받기 시작하면 목록을 비운다.
    func testBeginClearsLists() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.received(spots: [spot(1)])
        results.received(shops: [poi(1)])
        results.spotsFailed()
        XCTAssertNotNil(results.spots)
        XCTAssertTrue(results.failed)
        results.begin()
        XCTAssertNil(results.spots)
        XCTAssertEqual(results.shops, [])
        XCTAssertFalse(results.failed)
    }

    /// 「이 지역에서 다시 찾기」 — 범위를 지금 지도로, 다시 받는다.
    func testSearchHere() {
        var results = MapSearchResults(query: "남산", wide: true)
        let before = results.ask
        results.searchHere()
        XCTAssertFalse(results.wide)
        XCTAssertEqual(results.ask, before + 1)
    }

    /// 「이 지역 밖에서도 찾기」 — 범위 없이, 다시 받는다.
    func testSearchEverywhere() {
        var results = MapSearchResults(query: "남산", wide: false)
        let before = results.ask
        results.searchEverywhere()
        XCTAssertTrue(results.wide)
        XCTAssertEqual(results.ask, before + 1)
    }

    func testSelect() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.select(5)
        XCTAssertEqual(results.selected, 5)
        results.select(nil)
        XCTAssertNil(results.selected)
    }

    // MARK: 「다시 찾기」

    /// 받기 전에는 지도를 옮겨도 없다. 받은 뒤 사람이 지도를 옮기면 「다시 찾기」, 새로 받으면 거둔다.
    func testCameraIdleAfterReceivedShowsRefresh() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.cameraIdle(at: start)
        XCTAssertFalse(results.stale)
        XCTAssertFalse(results.showsRefresh)
        results.received(spots: [spot(1)])
        results.cameraIdle(at: start)
        XCTAssertTrue(results.stale)
        XCTAssertTrue(results.showsRefresh)
        results.received(spots: [spot(2)])
        XCTAssertFalse(results.stale)
        XCTAssertFalse(results.showsRefresh)
    }

    /// 앱이 카메라를 옮긴 직후(0.9초 안)의 멈춤은 사람이 옮긴 것이 아니다.
    func testSelfMoveWithinGraceIsIgnored() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.received(spots: [spot(1)])
        results.markSelfMove(at: start)
        results.cameraIdle(at: start.addingTimeInterval(0.5))
        XCTAssertFalse(results.stale)
        results.cameraIdle(at: start.addingTimeInterval(1.0))
        XCTAssertTrue(results.stale)
    }

    /// 새로 받는 동안(촬영지 nil)에는 「다시 찾기」 를 보이지 않는다.
    func testNoRefreshWhileSpotsNotReceived() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.received(spots: [spot(1)])
        results.cameraIdle(at: start)
        XCTAssertTrue(results.showsRefresh)
        results.begin()
        XCTAssertFalse(results.showsRefresh)
    }

    // MARK: 서랍 본문

    func testSpotsBodyLoadingThenSpots() {
        var results = MapSearchResults(query: "남산", wide: false)
        XCTAssertEqual(results.body, .loading)
        results.received(spots: [spot(1), spot(2)])
        XCTAssertEqual(results.body, .spots([spot(1), spot(2)]))
    }

    func testSpotsBodyFailed() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.spotsFailed()
        XCTAssertTrue(results.failed)
        XCTAssertEqual(results.body, .failed)
    }

    /// 이 지역에 없으면 「이 지역엔 없어요」 + 「이 지역 밖에서도 찾기」 단추.
    func testSpotsBodyEmptyHereOffersEverywhere() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.received(spots: [])
        XCTAssertEqual(results.body, .empty(.noneHere, offerEverywhere: true))
    }

    /// 전체에서도 없으면 「찾는 촬영지가 없어요」, 단추 없음.
    func testSpotsBodyEmptyWide() {
        var results = MapSearchResults(query: "남산", wide: true)
        results.received(spots: [])
        XCTAssertEqual(results.body, .empty(.noSpots, offerEverywhere: false))
    }

    func testShopsBody() {
        var results = MapSearchResults(query: "남산", wide: false)
        results.tab = .shops
        XCTAssertEqual(results.body, .empty(.noneHere, offerEverywhere: false))
        results.received(shops: [poi(1), poi(2)])
        XCTAssertEqual(results.body, .shops([poi(1), poi(2)]))
    }

    /// 가게 탭은 촬영지 실패와 상관없다. 전체에서 없으면 「찾는 가게가 없어요」.
    func testShopsBodyWideEmptyIgnoresSpotFailure() {
        var results = MapSearchResults(query: "남산", wide: true)
        results.spotsFailed()
        results.tab = .shops
        XCTAssertEqual(results.body, .empty(.noShops, offerEverywhere: false))
    }
}
