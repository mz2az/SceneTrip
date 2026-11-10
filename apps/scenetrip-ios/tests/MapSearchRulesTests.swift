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

    func testRowsForIdleLoadingFailed() {
        XCTAssertEqual(MapSearchRules.rows(.idle), [])
        XCTAssertEqual(MapSearchRules.rows(.loading), [.loading])
        XCTAssertEqual(MapSearchRules.rows(.failed), [.failed])
    }

    /// 고른 곳 카드 높이·옮길 때의 최소 확대.
    func testFocusConstants() {
        XCTAssertEqual(MapSearchRules.cardCover, 300)
        XCTAssertEqual(MapSearchRules.focusMinZoom, 16)
    }

    // MARK: 목록 줄 — 가게·장소만(촬영지는 찾지 않는다)

    /// 범위 없이 가까운 순(§10-2) — 「가게·장소 · 가까운 순」 제목 → 가게(받은 순서 그대로). 「이 지역 밖에서도 찾기」 줄은 없다.
    func testRowsDoneWithShops() {
        let rows = MapSearchRules.rows(.done(shops: [poi(1), poi(2)]))
        XCTAssertEqual(rows, [.title(.shopsNearby), .shop(poi(1)), .shop(poi(2))])
    }

    /// 갈래가 달라도 가게·장소 줄로 그대로 나온다.
    func testRowsDoneWithOneStay() {
        let shop = poi(7, .stay)
        let rows = MapSearchRules.rows(.done(shops: [shop]))
        XCTAssertEqual(rows, [.title(.shopsNearby), .shop(shop)])
    }

    /// 하나도 없으면 제목 → 「찾는 곳이 없어요」, 그 밖의 줄은 없다(§10-2).
    func testRowsDoneEmpty() {
        let rows = MapSearchRules.rows(.done(shops: []))
        XCTAssertEqual(rows, [.title(.shopsNearby), .empty(.noPlaces)])
    }

    // MARK: 검색하는 동안 지도에 남길 점

    /// 결과가 오면 그것으로 바꾼다.
    func testPinnedReplacedByDoneShops() {
        let pinned = MapSearchRules.pinned(previous: [poi(1)], phase: .done(shops: [poi(2), poi(3)]))
        XCTAssertEqual(ids(pinned), ids([poi(2), poi(3)]))
    }

    /// 받는 동안은 앞의 점을 그대로 둔다 — 글자마다 깜빡이지 않게.
    func testPinnedKeepsPreviousWhileLoading() {
        let pinned = MapSearchRules.pinned(previous: [poi(1), poi(2)], phase: .loading)
        XCTAssertEqual(ids(pinned), ids([poi(1), poi(2)]))
    }

    /// 2글자 아래로 지웠으면 비운다.
    func testPinnedClearedWhenIdle() {
        XCTAssertEqual(MapSearchRules.pinned(previous: [poi(1)], phase: .idle), [])
    }

    /// 못 찾았으면 비운다.
    func testPinnedClearedWhenFailed() {
        XCTAssertEqual(MapSearchRules.pinned(previous: [poi(1)], phase: .failed), [])
    }

    /// 결과 지도가 떠 있으면 그 결과(검색칸 여부와 상관없이).
    func testMapDotsPreferResults() {
        let dots = MapSearchRules.mapDots(searching: true, listed: [poi(1)], results: [poi(2), poi(3)], picked: nil)
        XCTAssertEqual(ids(dots), ids([poi(2), poi(3)]))
        let closed = MapSearchRules.mapDots(searching: false, listed: [], results: [poi(4)], picked: nil)
        XCTAssertEqual(ids(closed), ids([poi(4)]))
    }

    /// 결과 지도는 없고 검색칸이 열려 있으면 목록의 것.
    func testMapDotsUseListedWhileSearching() {
        let dots = MapSearchRules.mapDots(searching: true, listed: [poi(1), poi(2)], results: nil, picked: nil)
        XCTAssertEqual(ids(dots), ids([poi(1), poi(2)]))
    }

    /// 둘 다 아니면 점이 없다.
    func testMapDotsEmptyWhenNotSearching() {
        XCTAssertEqual(MapSearchRules.mapDots(searching: false, listed: [poi(1)], results: nil, picked: nil), [])
    }

    /// 정보 카드가 떠 있는 곳은 고른 핀이 그리므로 뺀다 — 순서는 그대로.
    func testMapDotsDropPicked() {
        let picked = poi(2).id
        let fromList = MapSearchRules.mapDots(
            searching: true, listed: [poi(1), poi(2), poi(3)], results: nil, picked: picked
        )
        XCTAssertEqual(ids(fromList), ids([poi(1), poi(3)]))
        let fromResults = MapSearchRules.mapDots(
            searching: false, listed: [], results: [poi(3), poi(2), poi(1)], picked: picked
        )
        XCTAssertEqual(ids(fromResults), ids([poi(3), poi(1)]))
    }

    // MARK: 고른 곳을 보이는 지도 가운데로

    /// 덮인 높이의 반만큼 올린다 — 카드는 적어도 300 을 덮는다.
    func testCenterShift() {
        XCTAssertEqual(MapSearchRules.centerShift(covered: 0), 150)
        XCTAssertEqual(MapSearchRules.centerShift(covered: 200), 150)
        XCTAssertEqual(MapSearchRules.centerShift(covered: 300), 150)
        XCTAssertEqual(MapSearchRules.centerShift(covered: 500), 250)
    }

    /// 지도 아래 여백이 이미 반만큼 올려 주니 그 위로 더 — 음수는 없다.
    func testExtraLift() {
        XCTAssertEqual(MapSearchRules.extraLift(contentInset: 0), 150)
        XCTAssertEqual(MapSearchRules.extraLift(contentInset: 200), 50)
        XCTAssertEqual(MapSearchRules.extraLift(contentInset: 300), 0)
        XCTAssertEqual(MapSearchRules.extraLift(contentInset: 500), 0)
    }

    /// 지금과 16 중 큰 것 — 더 확대해 둔 화면을 풀지 않는다.
    func testFocusZoom() {
        XCTAssertEqual(MapSearchRules.focusZoom(current: 12), 16)
        XCTAssertEqual(MapSearchRules.focusZoom(current: 16), 16)
        XCTAssertEqual(MapSearchRules.focusZoom(current: 17.5), 17.5)
    }

    /// 화면에서 위로 올리려면 카메라는 그만큼 남쪽을 가리킨다 — 경도는 그대로.
    func testFocusTargetMovesSouth() {
        let target = MapSearchRules.focusTarget(latitude: 37.5, longitude: 127.0, lift: 150, metersPerPoint: 2)
        XCTAssertEqual(target.latitude, 37.5 - 150 * 2 / 111_320, accuracy: 1e-9)
        XCTAssertEqual(target.longitude, 127.0, accuracy: 1e-9)
    }

    func testFocusTargetWithoutLiftIsUnchanged() {
        let target = MapSearchRules.focusTarget(latitude: 37.5, longitude: 127.0, lift: 0, metersPerPoint: 2)
        XCTAssertEqual(target.latitude, 37.5, accuracy: 1e-9)
        XCTAssertEqual(target.longitude, 127.0, accuracy: 1e-9)
    }
}

// MARK: - M7 엔터 뒤 결과 지도

final class MapSearchResultsTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    func testConstants() {
        XCTAssertEqual(MapSearchResults.shortShare, 0.42)
        XCTAssertEqual(MapSearchResults.tallShare, 0.78)
        XCTAssertEqual(MapSearchResults.selfMoveGrace, 0.9, accuracy: 0.0001)
    }

    /// 서랍은 42% 에서 시작해 손잡이로 78% ↔ 42%.
    func testDrawerToggles() {
        var results = MapSearchResults(query: "남산")
        XCTAssertEqual(results.drawerShare, 0.42, accuracy: 0.0001)
        results.toggleDrawer()
        XCTAssertTrue(results.tall)
        XCTAssertEqual(results.drawerShare, 0.78, accuracy: 0.0001)
        results.toggleDrawer()
        XCTAssertFalse(results.tall)
        XCTAssertEqual(results.drawerShare, 0.42, accuracy: 0.0001)
    }

    /// 검색어 알약의 「N곳」 — 받기 전 nil.
    func testCount() {
        var results = MapSearchResults(query: "남산")
        XCTAssertNil(results.shops)
        XCTAssertNil(results.count)
        results.received(shops: [poi(1), poi(2), poi(3)])
        XCTAssertEqual(results.count, 3)
        results.received(shops: [])
        XCTAssertEqual(results.count, 0)
    }

    /// 받으면 목록을 두고 실패를 거둔다.
    func testReceivedStoresShopsAndClearsFailed() {
        var results = MapSearchResults(query: "남산")
        results.searchFailed()
        XCTAssertTrue(results.failed)
        results.received(shops: [poi(1)])
        XCTAssertEqual(results.shops, [poi(1)])
        XCTAssertFalse(results.failed)
    }

    /// 새로 받기 시작하면 목록을 비우고 실패·「다시 찾기」 를 거둔다.
    func testBeginClearsState() {
        var results = MapSearchResults(query: "남산")
        results.received(shops: [poi(1)])
        results.cameraIdle(at: start)
        results.searchFailed()
        XCTAssertNotNil(results.shops)
        XCTAssertTrue(results.failed)
        XCTAssertTrue(results.stale)
        results.begin()
        XCTAssertNil(results.shops)
        XCTAssertFalse(results.failed)
        XCTAssertFalse(results.stale)
    }

    /// 「↻ 이 지역에서 다시 찾기」 — 새 화면 가운데를 기준으로 처음 쪽부터 다시 묻는다(§10-2). 누를 때마다 새 물음.
    func testSearchHereStartsNewAsk() {
        var results = MapSearchResults(query: "남산")
        let before = results.ask
        results.searchHere()
        XCTAssertEqual(results.ask, before + 1)
        results.searchHere()
        XCTAssertEqual(results.ask, before + 2)
    }

    // MARK: 「다시 찾기」

    /// 받기 전에는 지도를 옮겨도 없다. 받은 뒤 사람이 지도를 옮기면 「다시 찾기」, 새로 받으면 거둔다.
    func testCameraIdleAfterReceivedShowsRefresh() {
        var results = MapSearchResults(query: "남산")
        results.cameraIdle(at: start)
        XCTAssertFalse(results.stale)
        XCTAssertFalse(results.showsRefresh)
        results.received(shops: [poi(1)])
        results.cameraIdle(at: start)
        XCTAssertTrue(results.stale)
        XCTAssertTrue(results.showsRefresh)
        results.received(shops: [poi(2)])
        XCTAssertFalse(results.stale)
        XCTAssertFalse(results.showsRefresh)
    }

    /// 앱이 카메라를 옮긴 직후(0.9초 안)의 멈춤은 사람이 옮긴 것이 아니다.
    func testSelfMoveWithinGraceIsIgnored() {
        var results = MapSearchResults(query: "남산")
        results.received(shops: [poi(1)])
        results.markSelfMove(at: start)
        results.cameraIdle(at: start.addingTimeInterval(0.5))
        XCTAssertFalse(results.stale)
        results.cameraIdle(at: start.addingTimeInterval(1.0))
        XCTAssertTrue(results.stale)
    }

    /// 새로 받는 동안(가게 nil)에는 「다시 찾기」 를 보이지 않는다.
    func testNoRefreshWhileShopsNotReceived() {
        var results = MapSearchResults(query: "남산")
        results.received(shops: [poi(1)])
        results.cameraIdle(at: start)
        XCTAssertTrue(results.showsRefresh)
        results.begin()
        XCTAssertFalse(results.showsRefresh)
    }

    // MARK: 서랍 본문

    func testBodyLoadingThenShops() {
        var results = MapSearchResults(query: "남산")
        XCTAssertEqual(results.body, .loading)
        results.received(shops: [poi(1), poi(2)])
        XCTAssertEqual(results.body, .shops([poi(1), poi(2)]))
    }

    func testBodyFailed() {
        var results = MapSearchResults(query: "남산")
        results.searchFailed()
        XCTAssertTrue(results.failed)
        XCTAssertEqual(results.body, .failed)
    }

    /// 하나도 없으면 「찾는 곳이 없어요」 — 「이 지역 밖에서도 찾기」 단추는 없다(§10-2).
    func testBodyEmptyShowsNoPlaces() {
        var results = MapSearchResults(query: "남산")
        results.received(shops: [])
        XCTAssertEqual(results.body, .empty(.noPlaces))
    }

    /// 받는 동안은 받는 중, 다 받았는데 비었으면 「찾는 곳이 없어요」.
    func testBodyLoadingThenEmpty() {
        var results = MapSearchResults(query: "남산")
        XCTAssertEqual(results.body, .loading)
        results.received(shops: [])
        XCTAssertEqual(results.body, .empty(.noPlaces))
    }
}
