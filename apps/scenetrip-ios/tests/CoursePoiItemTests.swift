import SceneApiClient
@testable import SceneTrip
import XCTest

/// 코스에 편의시설을 **편의시설로** 담는다 (MZ2AZ-380, 계획 `course-poi-item.md` §6-1).
///
/// 예시는 수원 화홍문 근처다 — 촬영지 「화홍문」(51), 연결 안 된 편의시설 「행궁당」(900001),
/// 촬영지와 같은 곳인 편의시설 「몽테드」(277819 ↔ 촬영지 45).
final class CoursePoiItemTests: XCTestCase {
    private func poi(_ id: Int64 = 900_001, _ name: String = "행궁당", placeId: Int64? = nil) -> PoiSummary {
        PoiSummary(
            id: id, placeId: placeId, name: name, category: "카페", categoryGroup: .food,
            address: "경기 수원시 팔달구 정조로 886", latitude: 37.28712345, longitude: 127.01598765
        )
    }

    private func guide(_ id: Int64, source: GuidePlaceSource, placeId: Int64? = nil) -> GuidePlace {
        GuidePlace(
            id: id, placeId: placeId, name: "화홍문", category: "명소", categoryGroup: .sight,
            latitude: 37.2875, longitude: 127.0178, source: source
        )
    }

    private func item(
        _ id: Int64, source: CourseItemSource, placeId: Int64? = nil, poiId: Int64? = nil,
        name: String = "행궁당", category: String? = "카페"
    ) -> CourseItem {
        CourseItem(
            id: id, source: source, placeId: placeId, poiId: poiId, name: name,
            address: "경기 수원시 팔달구 정조로 886", category: category,
            latitude: 37.28712345, longitude: 127.01598765, dwellMinutes: 60
        )
    }

    private func place(_ id: Int64, _ name: String, _ lat: Double = 37.2875, _ lng: Double = 127.0178) -> PlaceSummary {
        PlaceSummary(id: id, name: name, latitude: lat, longitude: lng)
    }

    private func course(_ stops: [RouteStop]) -> RouteCourse {
        RouteCourse(serverId: 1, title: "수원", days: [RouteDay(stops: stops)])
    }

    /// 나가는 항목이 가리키는 것의 수 — 계약은 **정확히 하나**다.
    private func targets(_ item: CourseItemInput) -> Int {
        [item.placeId != nil, item.poiId != nil, item.customPin != nil].filter { $0 }.count
    }

    // MARK: 1. 담기 — poiId 로

    /// 주변 점(연결 없음)은 편의시설로 담긴다. 이름·분류·주소·좌표는 편의시설의 것이다.
    func testAmbientPoiIsAddedAsPoi() {
        let stop = RouteGuide.Place(poi: poi()).courseEntry.stop
        XCTAssertEqual(stop.kind, .poi(900_001))
        XCTAssertEqual(stop.poiId, 900_001)
        XCTAssertNil(stop.placeId)
        XCTAssertFalse(stop.isPinned)
        XCTAssertEqual(stop.place.name, "행궁당")
        XCTAssertEqual(stop.place.type, "카페")
        XCTAssertEqual(stop.place.address, "경기 수원시 팔달구 정조로 886")
    }

    /// 챗봇이 찾아 준 편의시설도 같다.
    func testGuidePoiIsAddedAsPoi() {
        let entry = RouteGuide.Place(guide: guide(900_001, source: .poi)).courseEntry
        XCTAssertEqual(entry.kind, .poi(900_001))
    }

    /// 편의시설 줄은 서버에 `poiId` 로만 간다.
    func testPoiStopIsSavedWithPoiIdOnly() {
        let stop = RouteGuide.Place(poi: poi()).courseEntry.stop
        let sent = RouteBridge.replace(from: course([stop])).days[0].items[0]
        XCTAssertEqual(sent.poiId, 900_001)
        XCTAssertNil(sent.placeId)
        XCTAssertNil(sent.customPin)
        XCTAssertNil(sent.id, "새로 담은 줄은 항목 id 가 없다")
    }

    /// 촬영지와 같은 곳인 편의시설은 여전히 촬영지로 담긴다(MZ2AZ-378).
    func testLinkedPoiIsStillAddedAsThePlace() {
        let entry = RouteGuide.Place(poi: poi(277_819, "몽테드", placeId: 45)).courseEntry
        XCTAssertEqual(entry.kind, .place)
        XCTAssertEqual(entry.stop.placeId, 45)
        XCTAssertNil(entry.stop.poiId)
        let sent = RouteBridge.replace(from: course([entry.stop])).days[0].items[0]
        XCTAssertEqual(sent.placeId, 45)
        XCTAssertNil(sent.poiId)
    }

    // MARK: 저장 — 셋 중 정확히 하나

    /// 세 갈래 어느 줄이든 나가는 항목은 **하나만** 가리킨다. 둘이거나 없으면 서버가 저장 전체를 400 으로 물린다.
    func testEveryKindSendsExactlyOneTarget() {
        let stops = [
            RouteStop(place: place(51, "화홍문")),
            RouteStop.poi(900_001, name: "행궁당", category: "카페", address: nil, latitude: 37.2871, longitude: 127.0159),
            RouteStop(place: place(-7, "우리 숙소", 37.28, 127.01), kind: .pin),
        ]
        let sent = RouteBridge.replace(from: course(stops)).days[0].items
        XCTAssertEqual(sent.map(targets), [1, 1, 1])
        XCTAssertEqual(sent.map(\.placeId), [51, nil, nil])
        XCTAssertEqual(sent.map(\.poiId), [nil, 900_001, nil])
        XCTAssertEqual(sent.map { $0.customPin?.name }, [nil, nil, "우리 숙소"])
    }

    func testTargetFollowsTheKindNotTheSignOfTheId() {
        // 편의시설 id 가 촬영지 id 와 숫자가 같아도 촬영지로 가지 않는다.
        let cafe = RouteStop.poi(51, name: "행궁당", category: "카페", address: nil, latitude: 37.28, longitude: 127.01)
        XCTAssertEqual(RouteBridge.target(of: cafe), .poi(51))
        XCTAssertEqual(RouteBridge.target(of: RouteStop(place: place(51, "화홍문"))), .place(51))
        guard case let .pin(pin) = RouteBridge.target(of: RouteStop(place: place(-3, "숙소"), kind: .pin)) else {
            return XCTFail("개인 핀은 customPin 으로 간다")
        }
        XCTAssertEqual(pin.name, "숙소")
    }

    // MARK: 2. 불러오기

    /// `source: poi` 항목은 편의시설 줄이 된다 — 이름·분류·주소는 서버가 준 것, 항목 id 와 방문 체크는 따라온다.
    func testPoiItemBecomesAPoiStop() {
        var loaded = item(700, source: .poi, poiId: 900_001)
        loaded.visitedAt = Date(timeIntervalSince1970: 0)
        let stop = RouteBridge.stop(from: loaded)
        XCTAssertEqual(stop.kind, .poi(900_001))
        XCTAssertEqual(stop.serverItemId, 700)
        XCTAssertNil(stop.placeId)
        XCTAssertEqual(stop.place.name, "행궁당")
        XCTAssertEqual(stop.place.type, "카페")
        XCTAssertEqual(stop.place.address, "경기 수원시 팔달구 정조로 886")
        XCTAssertEqual(stop.stayMinutes, 60)
        XCTAssertTrue(stop.visited)
    }

    /// **그대로 다시 저장하면 그대로 편의시설이다.** 전에는 `placeId` 에 음수가 실렸다(378 이 넘긴 것).
    func testReloadedCourseSavesBackUnchanged() {
        let day = CourseDay(
            dayNumber: 1,
            items: [
                item(701, source: .place, placeId: 51, name: "화홍문", category: "gate"),
                item(702, source: .poi, poiId: 900_001),
                item(703, source: .custompin, name: "우리 숙소", category: "lodging"),
            ],
            dwellMinutes: 180, travelMinutes: 0, totalMinutes: 180, travelBasis: .straightLine
        )
        let detail = CourseDetail(
            id: 1, title: "수원", dayCount: 1, status: .upcoming, origin: ._self, placeCount: 3,
            createdAt: Date(), updatedAt: Date(), days: [day]
        )
        let sent = RouteBridge.replace(from: RouteBridge.course(from: detail)).days[0].items
        XCTAssertEqual(sent.map(\.id), [701, 702, 703])
        XCTAssertEqual(sent.map(\.placeId), [51, nil, nil])
        XCTAssertEqual(sent.map(\.poiId), [nil, 900_001, nil])
        XCTAssertEqual(sent.map { $0.customPin?.category }, [nil, nil, .lodging])
        XCTAssertEqual(sent.map(targets), [1, 1, 1])
    }

    /// 계약에 없는 모양(`source: poi` 인데 `poiId` 가 없다)이 와도 줄은 선다 — 이름·좌표만 가진 개인 핀으로.
    func testPoiItemWithoutPoiIdFallsBackToAPin() {
        let stop = RouteBridge.stop(from: item(704, source: .poi))
        XCTAssertEqual(stop.kind, .pin)
        XCTAssertEqual(targets(RouteBridge.item(from: stop)), 1)
    }

    /// 이미 개인 핀으로 저장된 편의시설은 그대로 개인 핀이다 — 앱이 바꾸지 않는다(계획 §5 의 명령이 한다).
    func testOldPinnedPoiStaysAPin() {
        let stop = RouteBridge.stop(from: item(705, source: .custompin, name: "행궁당", category: "food"))
        XCTAssertTrue(stop.isPinned)
        XCTAssertNil(stop.poiId)
        XCTAssertEqual(RouteBridge.item(from: stop).customPin?.name, "행궁당")
    }

    // MARK: 줄의 분류

    /// 편의시설 줄은 분류를 늘 적는다 — 유형 표에 있으면 그 표시말, 없으면 서버가 준 그대로.
    func testPoiRowAlwaysShowsItsCategory() {
        let same: (String) -> String = { $0 }
        XCTAssertEqual(PlaceType.stopLabel("카페", kind: .poi(1), korean: true, translate: same), "카페")
        XCTAssertEqual(
            PlaceType.stopLabel("카페", kind: .poi(1), korean: false, translate: same),
            PlaceType.label("카페", korean: false)
        )
        XCTAssertEqual(PlaceType.stopLabel("닭갈비", kind: .poi(1), korean: true, translate: same), "닭갈비")
        XCTAssertNil(PlaceType.stopLabel(" ", kind: .poi(1), korean: true, translate: same))
        XCTAssertNil(PlaceType.stopLabel(nil, kind: .poi(1), korean: true, translate: same))
    }

    /// 촬영지와 개인 핀은 전과 같다.
    func testOtherKindsKeepTheirLabel() {
        let same: (String) -> String = { $0 }
        XCTAssertNil(PlaceType.stopLabel("닭갈비", kind: .place, korean: true, translate: same))
        XCTAssertEqual(PlaceType.stopLabel("food", kind: .pin, korean: true, translate: same), "음식점·카페")
    }

    // MARK: 번호 핀을 누를 때 — 편의시설 카드

    /// 편의시설 줄은 편의시설 카드의 장소가 된다 — 상세·리뷰가 편의시설을 가리킨다.
    func testPoiStopOpensThePoiCard() throws {
        let stop = RouteBridge.stop(from: item(700, source: .poi, poiId: 900_001))
        let shown = try XCTUnwrap(RouteGuide.Place(poiStop: stop))
        XCTAssertEqual(shown.id, "poi-900001")
        XCTAssertEqual(shown.target, .poi(900_001))
        XCTAssertEqual(shown.name, "행궁당")
        XCTAssertEqual(shown.category, "카페")
        XCTAssertEqual(shown.address, "경기 수원시 팔달구 정조로 886")
        XCTAssertNil(shown.shownMeters)
        // 상세를 못 받아도 카드는 줄이 가진 것으로 선다.
        let card = RouteGuide.card(poi: nil, listed: shown)
        XCTAssertEqual(card.address, "경기 수원시 팔달구 정조로 886")
        XCTAssertFalse(card.detailed)
    }

    /// 촬영지와 개인 핀은 성지 카드다.
    func testOtherStopsDoNotOpenThePoiCard() {
        XCTAssertNil(RouteGuide.Place(poiStop: RouteStop(place: place(51, "화홍문"))))
        XCTAssertNil(RouteGuide.Place(poiStop: RouteStop(place: place(-3, "숙소"), kind: .pin)))
    }

    // MARK: 지도 — 번호 핀 밑의 점

    /// 코스에 편의시설로 담긴 곳의 주변 점은 그리지 않는다 — 편의시설 id 로.
    func testDotOfATakenPoiIsHidden() {
        let places = [RouteGuide.Place(poi: poi()), RouteGuide.Place(poi: poi(900_002, "슬로우써니사이드"))]
        XCTAssertEqual(places.withoutDotsUnderPins([], takenPoiIds: [900_001]).map(\.id), ["poi-900002"])
        XCTAssertEqual(places.withoutDotsUnderPins([]).count, 2)
    }

    /// 촬영지 id 와 편의시설 id 는 다른 번호다 — 숫자가 같다고 숨기지 않는다.
    func testPlaceIdDoesNotHideAPoiWithTheSameNumber() {
        let places = [RouteGuide.Place(poi: poi(51, "행궁당"))]
        XCTAssertEqual(places.withoutDotsUnderPins([51]).count, 1)
        XCTAssertTrue(PlacePoiLink.drawsDot(linkedPlaceId: nil, drawnPlaceIds: [51], poiId: 51, takenPoiIds: []))
    }

    /// 편의시설 줄은 「그려진 촬영지 핀」 에 세지 않는다.
    func testPoiStopIsNotADrawnPlacePin() {
        let stop = RouteStop.poi(51, name: "행궁당", category: nil, address: nil, latitude: 37.28, longitude: 127.01)
        XCTAssertTrue(PlacePoiLink.drawnPlaceIds(stops: [stop.place]).isEmpty)
    }

    // MARK: 중복

    private var hwahongmun: RouteStop {
        RouteStop(place: place(51, "화홍문"))
    }

    /// 같은 편의시설은 한 번만 — 이미 담긴 것과도, 이번에 담는 것끼리도.
    func testSamePoiIsAddedOnce() {
        let cafe = RouteGuide.Place(poi: poi()).courseEntry.stop
        XCTAssertTrue(RouteDedupe.fresh([cafe], among: [hwahongmun, cafe]).isEmpty)
        XCTAssertEqual(RouteDedupe.fresh([cafe, cafe], among: [hwahongmun]).count, 1)
    }

    /// 편의시설 자료의 이름이 바뀌어도 같은 편의시설이다 — 편의시설 id 로 본다.
    func testSamePoiIsCaughtByIdEvenWhenTheNameChanged() {
        let saved = RouteBridge.stop(from: item(700, source: .poi, poiId: 900_001, name: "행궁당 본점"))
        let again = RouteGuide.Place(poi: poi()).courseEntry.stop
        XCTAssertTrue(RouteDedupe.fresh([again], among: [saved]).isEmpty)
    }

    /// 전에 **개인 핀으로** 담긴 같은 가게(이름·좌표가 같다)는 다시 담기지 않는다.
    func testPoiAlreadyPinnedIsNotAddedAgain() {
        let pinned = RouteBridge.stop(from: item(705, source: .custompin, name: "행궁당", category: "food"))
        let again = RouteGuide.Place(poi: poi()).courseEntry.stop
        XCTAssertTrue(RouteDedupe.fresh([again], among: [pinned]).isEmpty)
        XCTAssertTrue(RouteGuide.Place(poi: poi()).isSameSpot(as: pinned), "카드는 「경로에 있음」 을 보인다")
    }

    /// 숫자가 같은 촬영지와 편의시설은 서로 다른 곳이다.
    func testPlaceAndPoiWithTheSameNumberAreDifferent() {
        let cafe = RouteStop.poi(51, name: "행궁당", category: nil, address: nil, latitude: 37.2871, longitude: 127.0159)
        XCTAssertEqual(RouteDedupe.fresh([cafe], among: [hwahongmun]).count, 1)
        XCTAssertEqual(RouteDedupe.fresh([hwahongmun], among: [cafe]).count, 1)
    }

    /// 연결된 편의시설은 촬영지로 담기므로, 그 촬영지가 이미 있으면 담기지 않는다(MZ2AZ-378).
    func testLinkedPoiIsCaughtByItsPlace() {
        let montead = RouteStop(place: place(45, "수원 카페 몽테드", 37.284771, 127.013614))
        let linked = RouteGuide.Place(poi: poi(277_819, "몽테드", placeId: 45)).courseEntry.stop
        XCTAssertTrue(RouteDedupe.fresh([linked], among: [montead]).isEmpty)
    }

    /// 「경로에 있음」 — 편의시설로 담긴 줄은 편의시설 id 로 알아본다.
    func testPoiCountsAsAddedByItsId() {
        let listed = RouteGuide.Place(poi: poi())
        let saved = RouteBridge.stop(from: item(700, source: .poi, poiId: 900_001, name: "행궁당 본점"))
        XCTAssertTrue(listed.isSameSpot(as: saved))
        XCTAssertFalse(RouteGuide.Place(poi: poi(900_002, "슬로우써니사이드")).isSameSpot(as: saved))
        XCTAssertFalse(listed.isSameSpot(as: hwahongmun))
    }

    /// 한 코스의 **서로 다른 편의시설 줄 둘**은 섞이지 않는다 — 둘 다 `place.id` 가 0 이지만 줄을 가르는 것은
    /// 편의시설 id 다. 담기(`fresh`), 저장 뒤 줄 찾기(`row`), 「경로에 있음」(`holds`) 셋 다.
    func testTwoDifferentPoiStopsDoNotMix() {
        let mochi = RouteStop.poi(
            273_134, name: "모찌고", category: "제과점", address: nil, latitude: 37.285897, longitude: 127.015294
        )
        let sunny = RouteStop.poi(
            459_990, name: "슬로우써니사이드", category: "음식점", address: nil, latitude: 37.284774, longitude: 127.013565
        )
        XCTAssertEqual(mochi.place.id, 0)
        XCTAssertEqual(sunny.place.id, 0)

        // 담기 — 하나가 있어도 다른 하나는 담긴다. 둘을 한 번에 담아도 둘 다.
        XCTAssertEqual(RouteDedupe.fresh([sunny], among: [mochi]).map(\.poiId), [459_990])
        XCTAssertEqual(RouteDedupe.fresh([mochi, sunny], among: []).map(\.poiId), [273_134, 459_990])
        XCTAssertTrue(RouteDedupe.fresh([mochi, sunny], among: [sunny, mochi]).isEmpty)

        // 저장 뒤 줄 찾기 — 제 편의시설 id 의 줄을 찾는다(순서가 뒤바뀌어 있어도).
        var savedMochi = mochi
        savedMochi.serverItemId = 4
        var savedSunny = sunny
        savedSunny.serverItemId = 6
        let saved = [savedSunny, savedMochi]
        XCTAssertEqual(PlacePoiLink.row(of: mochi, in: saved)?.serverItemId, 4)
        XCTAssertEqual(PlacePoiLink.row(of: sunny, in: saved)?.serverItemId, 6)
        XCTAssertNil(PlacePoiLink.row(of: mochi, in: [savedSunny]))

        // 「경로에 있음」 — 다른 편의시설 줄을 제 것으로 알지 않는다.
        let mochiKey = RouteDedupe.key(mochi.place)
        XCTAssertTrue(PlacePoiLink.holds(stop: savedMochi, poiId: 273_134, linkedPlaceId: nil, pinKey: mochiKey))
        XCTAssertFalse(PlacePoiLink.holds(stop: savedSunny, poiId: 273_134, linkedPlaceId: nil, pinKey: mochiKey))
        // 촬영지 id 0 을 가진 것으로 읽히지도 않는다.
        XCTAssertFalse(PlacePoiLink.holds(stop: savedSunny, placeId: 0, linkedPlaceId: nil, pinKey: mochiKey))
    }

    // MARK: 3. 챗봇이 준 촬영지 — placeId 로

    /// 챗봇이 찾아 준 **촬영지**는 촬영지로 담긴다. 전에는 개인 핀(음수 id)이었다.
    func testGuideFilmingPlaceIsAddedAsThePlace() {
        let entry = RouteGuide.Place(guide: guide(51, source: .place)).courseEntry
        XCTAssertEqual(entry.kind, .place)
        XCTAssertEqual(entry.stop.placeId, 51)
        let sent = RouteBridge.replace(from: course([entry.stop])).days[0].items[0]
        XCTAssertEqual(sent.placeId, 51)
        XCTAssertNil(sent.customPin)
        XCTAssertNil(sent.poiId)
    }

    /// 그렇게 담긴 줄은 「그려진 촬영지 핀」 에 잡힌다 — 같은 곳인 편의시설 점이 번호 핀 밑에 안 남는다.
    func testGuideFilmingPlaceCountsAsADrawnPin() {
        let entry = RouteGuide.Place(guide: guide(45, source: .place)).courseEntry
        let drawn = PlacePoiLink.drawnPlaceIds(stops: [entry.place])
        XCTAssertEqual(drawn, [45])
        let dots = [RouteGuide.Place(poi: poi(277_819, "몽테드", placeId: 45))]
        XCTAssertTrue(dots.withoutDotsUnderPins(drawn).isEmpty)
    }

    /// 이미 검색으로 담은 촬영지를 챗봇이 또 찾아 줘도 한 번만 — 촬영지 id 로 걸린다. 카드도 「경로에 있음」.
    func testGuideFilmingPlaceIsCaughtByThePlaceId() {
        let listed = RouteGuide.Place(guide: guide(51, source: .place))
        // 촬영지 상세로 이름·좌표가 바뀐 줄이다 — 이름 + 좌표로는 못 알아본다.
        let saved = RouteStop(place: place(51, "수원 화홍문", 37.28761, 127.01792))
        XCTAssertTrue(RouteDedupe.fresh([listed.courseEntry.stop], among: [saved]).isEmpty)
        XCTAssertTrue(listed.isSameSpot(as: saved))
    }

    // MARK: 저장 뒤 줄 찾기 (담은 직후 길찾기)

    /// 편의시설 줄은 편의시설 id 로 찾는다 — 서버가 이름을 다르게 돌려줘도.
    func testPoiRowIsFoundByPoiIdAfterSaving() {
        let added = RouteGuide.Place(poi: poi()).courseEntry.stop
        let saved = [
            RouteBridge.stop(from: item(701, source: .place, placeId: 51, name: "화홍문")),
            RouteBridge.stop(from: item(702, source: .poi, poiId: 900_001, name: "행궁당 본점")),
        ]
        XCTAssertEqual(PlacePoiLink.row(of: added, in: saved)?.serverItemId, 702)
    }

    // MARK: 4. 커뮤니티 후기의 코스 사본

    /// 사본은 갈래를 지킨다 — 「내 코스로 담기」 로 만든 코스가 편의시설을 편의시설로 저장한다.
    func testPostCourseKeepsThePoi() throws {
        let original = course([
            RouteStop(place: place(51, "화홍문")),
            RouteGuide.Place(poi: poi()).courseEntry.stop,
            RouteStop(place: place(-7, "우리 숙소", 37.28, 127.01), kind: .pin),
        ])
        let copy = PostCourse(from: original)
        XCTAssertEqual(copy.days[0].map(\.placeId), [51, nil, nil])
        XCTAssertEqual(copy.days[0].map(\.poiId), [nil, 900_001, nil])

        // 기기에 저장됐다 다시 읽혀도 같다.
        let stored = try JSONDecoder().decode(PostCourse.self, from: JSONEncoder().encode(copy))
        XCTAssertEqual(stored, copy)

        let mine = stored.asNewCourse()
        XCTAssertNil(mine.serverId)
        XCTAssertEqual(mine.days[0].stops.map(\.kind), [.place, .poi(900_001), .pin])
        let sent = RouteBridge.replace(from: mine).days[0].items
        XCTAssertEqual(sent.map(targets), [1, 1, 1])
        XCTAssertEqual(sent.map(\.placeId), [51, nil, nil])
        XCTAssertEqual(sent.map(\.poiId), [nil, 900_001, nil])
        XCTAssertNil(sent[1].id, "남의 코스의 항목 id 를 들고 오지 않는다")
    }

    /// **옛 사본**(편의시설 칸이 생기기 전에 기기에 저장된 글)은 그대로 읽힌다 — 촬영지는 촬영지, 나머지는 개인 핀.
    func testOldPostCourseStillDecodes() throws {
        let json = """
        {"title":"수원 하루","days":[[
          {"placeId":51,"name":"화홍문","latitude":37.2875,"longitude":127.0178},
          {"name":"행궁당","type":"음식점·카페","latitude":37.2871,"longitude":127.0159}
        ]]}
        """
        let old = try JSONDecoder().decode(PostCourse.self, from: Data(json.utf8))
        XCTAssertEqual(old.days[0].map(\.poiId), [nil, nil])
        let stops = old.asNewCourse().days[0].stops
        XCTAssertEqual(stops.map(\.kind), [.place, .pin])
        XCTAssertEqual(stops[0].placeId, 51)
        XCTAssertEqual(RouteBridge.item(from: stops[1]).customPin?.category, .food)
    }

    // MARK: 생성 클라이언트 — 모르는 `source`

    /// **지금 동작을 박아 둔다**: 생성된 `CourseItemSource` 는 모르는 값을 못 읽고, 그 항목이 든 코스 상세
    /// 전체가 디코딩에 실패한다(계획 §3 — 낡은 앱이 `source: poi` 를 못 여는 이유). 생성기에
    /// `enumUnknownDefaultCase` 를 켜면 이 시험이 깨진다 — 그때 `RouteBridge.kind(of:)` 의 갈래를 다시 본다.
    func testUnknownSourceStillFailsToDecode() {
        XCTAssertEqual(try JSONDecoder().decode(CourseItemSource.self, from: Data(#""poi""#.utf8)), .poi)
        XCTAssertThrowsError(try JSONDecoder().decode(CourseItemSource.self, from: Data(#""hotel""#.utf8)))
        let json = """
        {"id":1,"source":"hotel","name":"x","latitude":37.0,"longitude":127.0,"dwellMinutes":30}
        """
        XCTAssertThrowsError(try JSONDecoder().decode(CourseItem.self, from: Data(json.utf8)))
    }
}
