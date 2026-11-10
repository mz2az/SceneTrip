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

final class MapPoiChipsTests: XCTestCase {
    /// 처음엔 모두 꺼져 있고 받지 않는다. 칩 하나를 켜면 켜지고 받는다.
    func testStartsAllOffAndToggleOnFetches() {
        var chips = MapPoiChips()
        XCTAssertTrue(chips.groupsOn.isEmpty)
        XCTAssertEqual(chips.ask, 0)
        XCTAssertFalse(chips.anyOn)
        XCTAssertFalse(chips.allOn)
        XCTAssertNil(chips.request(zoom: 15))
        chips.toggle(.food)
        XCTAssertNotNil(chips.request(zoom: 15))
        XCTAssertTrue(chips.isOn(.food))
        XCTAssertFalse(chips.isOn(.stay))
        XCTAssertTrue(chips.anyOn)
        XCTAssertFalse(chips.allOn)
        XCTAssertEqual(chips.ask, 1)
    }

    /// 켜진 것이 남아 있으면 끌 때도 다시 받는다. 다 끄면 받지 않는다(ask 0).
    func testToggleOffRefetchesWhileAnyRemainAndStopsWhenAllOff() {
        var chips = MapPoiChips()
        chips.toggle(.food)
        chips.toggle(.sight)
        XCTAssertEqual(chips.ask, 2)
        chips.toggle(.food)
        XCTAssertFalse(chips.isOn(.food))
        XCTAssertTrue(chips.isOn(.sight))
        XCTAssertEqual(chips.ask, 3)
        chips.toggle(.sight)
        XCTAssertFalse(chips.anyOn)
        XCTAssertEqual(chips.ask, 0)
    }

    /// 「전체」 — 다 꺼져 있으면 다 켜고 받는다.
    func testAllFromNoneTurnsAllOnAndFetches() {
        var chips = MapPoiChips()
        chips.toggleAll()
        XCTAssertEqual(chips.groupsOn, Set(RoutePoiGroup.allCases))
        XCTAssertTrue(chips.allOn)
        XCTAssertEqual(chips.ask, 1)
    }

    /// 「전체」 — 일부만 켜져 있어도 다 켜고 다시 받는다.
    func testAllFromSomeTurnsAllOnAndRefetches() {
        var chips = MapPoiChips()
        chips.toggle(.stay)
        chips.toggleAll()
        XCTAssertTrue(chips.allOn)
        XCTAssertEqual(chips.ask, 2)
    }

    /// 「전체」 — 넷이 다 켜져 있으면 다 끄고 받지 않는다.
    func testAllWhenAllOnTurnsAllOff() {
        var chips = MapPoiChips()
        chips.toggleAll()
        XCTAssertTrue(chips.allOn)
        chips.toggleAll()
        XCTAssertTrue(chips.groupsOn.isEmpty)
        XCTAssertFalse(chips.allOn)
        XCTAssertEqual(chips.ask, 0)
    }

    /// 칩을 하나씩 넷 다 켜도 「전체」 가 켜진 모양이다.
    func testAllOnWhenEachTurnedOnOneByOne() {
        var chips = MapPoiChips()
        for group in RoutePoiGroup.allCases {
            chips.toggle(group)
        }
        XCTAssertTrue(chips.allOn)
        XCTAssertEqual(chips.ask, 4)
    }

    /// 「다시 찾기」 — 켜진 것이 있을 때만 받는다.
    func testRefreshOnlyWhenAnyOn() {
        var chips = MapPoiChips()
        chips.refresh()
        XCTAssertEqual(chips.ask, 0)
        chips.toggle(.transit)
        chips.refresh()
        XCTAssertEqual(chips.ask, 2)
    }

    /// 보기로 돌아가거나 여행을 시작하면 다 끄고 받지 않는다.
    func testResetTurnsAllOff() {
        var chips = MapPoiChips()
        chips.toggleAll()
        chips.refresh()
        XCTAssertEqual(chips.ask, 2)
        chips.reset()
        XCTAssertTrue(chips.groupsOn.isEmpty)
        XCTAssertEqual(chips.ask, 0)
        XCTAssertNil(chips.request(zoom: 14))
    }

    // MARK: 요청 (M2)

    /// 하나만 켜면 서버가 거른다 — sort=spread, spreadZoom=내림, limit=200.
    func testRequestWithOneGroupFiltersOnServer() {
        var chips = MapPoiChips()
        chips.toggle(.food)
        XCTAssertEqual(
            chips.request(zoom: 12.7),
            MapPoiRequest(sort: .spread, spreadZoom: 12, limit: 200, categoryGroup: .food)
        )
        XCTAssertEqual(MapPoiRequest.limit, 200)
    }

    /// 갈래마다 계약의 같은 이름으로 간다.
    func testRequestMapsEachGroupToContract() {
        let pairs: [(RoutePoiGroup, PoiCategoryGroup)] = [
            (.food, .food), (.stay, .stay), (.sight, .sight), (.transit, .transit),
        ]
        for (group, contract) in pairs {
            var chips = MapPoiChips()
            chips.toggle(group)
            XCTAssertEqual(chips.request(zoom: 10)?.categoryGroup, contract, "\(group)")
        }
    }

    /// 여럿 켜면 갈래를 보내지 않는다(다 받아 앱이 거른다).
    func testRequestWithSeveralGroupsSendsNoGroup() {
        var chips = MapPoiChips()
        chips.toggle(.food)
        chips.toggle(.stay)
        let request = chips.request(zoom: 9.2)
        XCTAssertEqual(request, MapPoiRequest(sort: .spread, spreadZoom: 9, limit: 200, categoryGroup: nil))
        chips.toggleAll()
        XCTAssertNil(chips.request(zoom: 9.2)?.categoryGroup)
        XCTAssertNotNil(chips.request(zoom: 9.2))
    }

    /// spreadZoom 은 0…22 로 자른다. 확대 제한은 없다 — 아주 넓어도 묻는다.
    func testSpreadZoomClampsAndHasNoMinimum() {
        var chips = MapPoiChips()
        chips.toggle(.sight)
        XCTAssertEqual(chips.request(zoom: -1.5)?.spreadZoom, 0)
        XCTAssertEqual(chips.request(zoom: 3.99)?.spreadZoom, 3)
        XCTAssertEqual(chips.request(zoom: 25.3)?.spreadZoom, 22)
        XCTAssertEqual(chips.request(zoom: 22)?.spreadZoom, 22)
    }

    // MARK: 거르기·개수

    /// 켜진 갈래만 남기고 순서는 그대로.
    func testKeepOnlyGroupsOn() {
        var chips = MapPoiChips()
        chips.toggle(.food)
        chips.toggle(.stay)
        let places = [poi(1, .food), poi(2, .sight), poi(3, .stay), poi(4, .transit), poi(5, .food)]
        XCTAssertEqual(ids(chips.keep(places)), ["poi-1", "poi-3", "poi-5"])
    }

    /// 다 꺼져 있으면 아무것도 남지 않는다.
    func testKeepNothingWhenAllOff() {
        XCTAssertTrue(MapPoiChips().keep([poi(1, .food), poi(2, .stay)]).isEmpty)
    }

    // MARK: 칩 줄이 보이나 (M1)

    func testBarShowsOnlyWhenEditingWithMapOpenAndNotTripping() {
        XCTAssertTrue(MapPoiChips.showsBar(mode: .editing, mapOpen: true, tripActive: false))
        XCTAssertFalse(MapPoiChips.showsBar(mode: .viewing, mapOpen: true, tripActive: false))
        XCTAssertFalse(MapPoiChips.showsBar(mode: .editing, mapOpen: false, tripActive: false))
        XCTAssertFalse(MapPoiChips.showsBar(mode: .editing, mapOpen: true, tripActive: true))
        XCTAssertFalse(MapPoiChips.showsBar(mode: .viewing, mapOpen: false, tripActive: false))
    }
}

// MARK: - M3 상태 알약

final class MapPoiFetchTests: XCTestCase {
    /// 다 꺼져 있으면 알약이 없다 — 받는 중이어도.
    func testNoPillWhenNothingOn() {
        var fetch = MapPoiFetch()
        XCTAssertEqual(fetch.pill(anyOn: false, zoom: 15), MapPoiPill.none)
        fetch.begin()
        XCTAssertEqual(fetch.pill(anyOn: false, zoom: 15), MapPoiPill.none)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .searching, "켜진 것이 있으면 보인다")
    }

    /// 받는 중이면 「찾는 중」.
    func testSearchingWhileLoading() {
        var fetch = MapPoiFetch()
        fetch.begin()
        XCTAssertTrue(fetch.loading)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .searching)
    }

    /// 다 받고 지도가 그대로면 알약이 없다.
    func testNoPillAfterFinishWithoutMove() {
        var fetch = MapPoiFetch()
        fetch.begin()
        XCTAssertTrue(fetch.loading)
        fetch.finish()
        XCTAssertFalse(fetch.loading)
        XCTAssertFalse(fetch.stale)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), MapPoiPill.none)
    }

    /// 받은 뒤 지도가 움직이면 「다시 찾기」, 다시 받기 시작하면 거둔다.
    func testRefreshAfterMoveAndBeginClearsIt() {
        var fetch = MapPoiFetch()
        fetch.begin()
        fetch.finish()
        fetch.cameraIdle()
        XCTAssertTrue(fetch.stale)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .refresh)
        fetch.begin()
        XCTAssertFalse(fetch.stale)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .searching)
    }

    /// 받는 사이 지도가 움직였으면 다 받은 뒤에도 「다시 찾기」 가 남는다.
    func testMoveDuringLoadingSurvivesFinish() {
        var fetch = MapPoiFetch()
        fetch.begin()
        fetch.cameraIdle()
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .searching, "받는 중이 먼저")
        fetch.finish()
        XCTAssertTrue(fetch.stale)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), .refresh)
    }

    /// 다 끄면 알약도 상태도 없다.
    func testClearResets() {
        var fetch = MapPoiFetch()
        fetch.begin()
        fetch.cameraIdle()
        XCTAssertTrue(fetch.loading)
        XCTAssertTrue(fetch.stale)
        fetch.clear()
        XCTAssertFalse(fetch.loading)
        XCTAssertFalse(fetch.stale)
        XCTAssertEqual(fetch.pill(anyOn: true, zoom: 15), MapPoiPill.none)
    }

    /// 확대 제한이 없다 — 넓게 봐도 「조금 더 확대하면 보여요」 가 뜨지 않는다.
    func testNoZoomInPillAtOrdinaryZooms() {
        var fetch = MapPoiFetch()
        for zoom in [3.0, 8.0, 12.0] {
            XCTAssertEqual(fetch.pill(anyOn: true, zoom: zoom), MapPoiPill.none, "zoom \(zoom)")
        }
        fetch.begin()
        fetch.finish()
        fetch.cameraIdle()
        for zoom in [3.0, 8.0, 12.0] {
            XCTAssertEqual(fetch.pill(anyOn: true, zoom: zoom), .refresh, "zoom \(zoom)")
        }
    }
}

// MARK: - M4 골고루 찍기

final class MapPoiSpreadTests: XCTestCase {
    /// 줌 10 에서 a·b·c 는 한 칸, d 는 다른 칸. 줌 16 에서는 c 가 따로 떨어진다(웹 메르카토르, 256·2^z/72 칸).
    private let places = [
        poi(1, lat: 37.5, lng: 127.0),
        poi(2, lat: 37.5001, lng: 127.0001),
        poi(3, lat: 37.5, lng: 127.002),
        poi(4, lat: 37.6, lng: 127.3),
    ]

    /// 받은 순서가 순위 — 칸마다 먼저 온 것이 큰 점, 나머지는 작은 점. 각 목록의 순서는 그대로.
    /// 칸 72pt, 작은 점은 보이는 9·누르는 16. 빈 입력은 빈 답.
    func testFirstInCellIsMajorRestMinor() {
        XCTAssertEqual(MapPoiSpread.cell, 72)
        XCTAssertEqual(MapPoiSpread.minorVisible, 9)
        XCTAssertEqual(MapPoiSpread.minorHit, 16)
        let none = MapPoiSpread.split([], zoom: 12)
        XCTAssertTrue(none.major.isEmpty && none.minor.isEmpty)
        let split = MapPoiSpread.split(places, zoom: 10)
        XCTAssertEqual(ids(split.major), ["poi-1", "poi-4"])
        XCTAssertEqual(ids(split.minor), ["poi-2", "poi-3"])
    }

    /// 받은 순서를 바꾸면 그 칸의 큰 점도 바뀐다(좌표가 아니라 순서가 순위).
    func testOrderDecidesMajor() {
        let reordered = [places[2], places[0], places[1], places[3]]
        let split = MapPoiSpread.split(reordered, zoom: 10)
        XCTAssertEqual(ids(split.major), ["poi-3", "poi-4"])
        XCTAssertEqual(ids(split.minor), ["poi-1", "poi-2"])
    }

    /// 칸은 줌의 내림으로 정한다 — 10.9 는 10 과 같다.
    func testUsesFloorOfZoom() {
        let atTen = MapPoiSpread.split(places, zoom: 10)
        let atTenNine = MapPoiSpread.split(places, zoom: 10.9)
        XCTAssertEqual(ids(atTenNine.major), ["poi-1", "poi-4"])
        XCTAssertEqual(ids(atTenNine.major), ids(atTen.major))
        XCTAssertEqual(ids(atTenNine.minor), ids(atTen.minor))
    }

    /// 확대하면 다시 받지 않고 다시 솎는다 — 큰 점은 늘기만 한다.
    func testZoomingInOnlyGrowsMajor() {
        let wide = MapPoiSpread.split(places, zoom: 10)
        let close = MapPoiSpread.split(places, zoom: 16)
        XCTAssertEqual(ids(close.major), ["poi-1", "poi-3", "poi-4"])
        XCTAssertEqual(ids(close.minor), ["poi-2"])
        XCTAssertTrue(Set(ids(wide.major)).isSubset(of: Set(ids(close.major))))
    }

    /// 칸은 지도에 고정 — 아주 가까워도 칸 경계(줌 10 의 경도 127.012939453125)를 넘으면 다른 칸이다.
    func testCellsAreFixedToTheMap() {
        let boundary = 127.012939453125
        let pair = [poi(1, lat: 37.5, lng: boundary - 0.0001), poi(2, lat: 37.5, lng: boundary + 0.0001)]
        let split = MapPoiSpread.split(pair, zoom: 10)
        XCTAssertEqual(ids(split.major), ["poi-1", "poi-2"])
        XCTAssertTrue(split.minor.isEmpty)
    }

    /// tileSize 를 두 배로 하면 한 줌 더 확대한 것과 같다.
    func testTileSizeScalesWorld() {
        let doubled = MapPoiSpread.split(places, zoom: 15, tileSize: 512)
        let next = MapPoiSpread.split(places, zoom: 16)
        XCTAssertEqual(ids(doubled.major), ["poi-1", "poi-3", "poi-4"])
        XCTAssertEqual(ids(doubled.major), ids(next.major))
        XCTAssertEqual(ids(doubled.minor), ids(next.minor))
    }
}
