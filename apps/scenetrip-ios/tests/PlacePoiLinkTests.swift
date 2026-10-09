import SceneApiClient
@testable import SceneTrip
import XCTest

/// 촬영지와 같은 곳인 편의시설 — 어디서든 촬영지가 대표한다 (MZ2AZ-378, 계획 `place-poi-link.md` §5·§7).
///
/// 예시는 계약의 것이다 — 편의시설 「몽테드」(277819) ↔ 촬영지 「수원 카페 몽테드」(45).
final class PlacePoiLinkTests: XCTestCase {
    private func poi(_ id: Int64 = 277_819, _ name: String = "몽테드", placeId: Int64? = 45) -> PoiSummary {
        PoiSummary(
            id: id, placeId: placeId, name: name, category: "카페", categoryGroup: .food,
            address: "경기 수원시 팔달구 신풍로23번길 62", latitude: 37.28476789, longitude: 127.01361679
        )
    }

    private func guide(_ id: Int64, source: GuidePlaceSource, placeId: Int64? = nil) -> GuidePlace {
        GuidePlace(
            id: id, placeId: placeId, name: "몽테드", category: "카페", categoryGroup: .food,
            latitude: 37.28476789, longitude: 127.01361679, source: source
        )
    }

    private func stop(_ id: Int64, _ name: String, _ lat: Double = 37.284771, _ lng: Double = 127.013614) -> PlaceSummary {
        PlaceSummary(id: id, name: name, latitude: lat, longitude: lng)
    }

    // MARK: 계약 → 화면

    /// 목록이 준 `placeId` 를 들고 온다. 고른 줄을 찾는 열쇠(`id`)와 편의시설 번호는 그대로다.
    func testPoiCarriesItsLinkedPlace() {
        let place = RouteGuide.Place(poi: poi())
        XCTAssertEqual(place.linkedPlaceId, 45)
        XCTAssertEqual(place.id, "poi-277819")
        XCTAssertEqual(place.poiId, 277_819)
        XCTAssertNil(RouteGuide.Place(poi: poi(placeId: nil)).linkedPlaceId)
    }

    /// 챗봇 결과도 같다. `source: place` 는 `id` 가 곧 촬영지라 연결이 없다(계약 `GuidePlaceSource`).
    func testGuidePlaceCarriesTheLinkOnlyForPois() {
        XCTAssertEqual(RouteGuide.Place(guide: guide(277_819, source: .poi, placeId: 45)).linkedPlaceId, 45)
        XCTAssertNil(RouteGuide.Place(guide: guide(277_819, source: .poi)).linkedPlaceId)
        XCTAssertNil(RouteGuide.Place(guide: guide(45, source: .place, placeId: 45)).linkedPlaceId)
    }

    // MARK: 1. 누를 때

    /// 연결된 편의시설을 누르면 촬영지 상세 — 리뷰도 촬영지의 것이다.
    func testLinkedPoiOpensThePlace() {
        XCTAssertEqual(RouteGuide.Place(poi: poi()).target, .place(45))
        XCTAssertEqual(RouteGuide.Place(guide: guide(277_819, source: .poi, placeId: 45)).target, .place(45))
    }

    /// 연결이 없으면 전과 같다 — 편의시설은 편의시설, 촬영지는 촬영지, 코스에서 옮긴 것은 대상이 없다.
    func testUnlinkedPlacesKeepTheirOwnTarget() {
        XCTAssertEqual(RouteGuide.Place(poi: poi(placeId: nil)).target, .poi(277_819))
        XCTAssertEqual(RouteGuide.Place(guide: guide(45, source: .place)).target, .place(45))
        let moved = RouteGuide.Place(id: "stop-1", name: "몽테드", category: nil, latitude: 37.28, longitude: 127.01)
        XCTAssertNil(moved.target)
    }

    // MARK: 2. 지도

    /// 그 촬영지 핀이 그려져 있으면 점을 숨긴다.
    func testDotIsHiddenUnderItsPlacePin() {
        XCTAssertFalse(PlacePoiLink.drawsDot(linkedPlaceId: 45, drawnPlaceIds: [45, 51]))
    }

    /// 핀이 없으면 점은 남는다 — 숨기면 그 가게가 지도에서 사라진다.
    func testDotStaysWhenThePlacePinIsNotDrawn() {
        XCTAssertTrue(PlacePoiLink.drawsDot(linkedPlaceId: 45, drawnPlaceIds: [51]))
        XCTAssertTrue(PlacePoiLink.drawsDot(linkedPlaceId: 45, drawnPlaceIds: []))
    }

    /// 연결이 없는 편의시설은 촬영지 핀과 무관하다.
    func testUnlinkedDotIsAlwaysDrawn() {
        XCTAssertTrue(PlacePoiLink.drawsDot(linkedPlaceId: nil, drawnPlaceIds: [45]))
    }

    /// 그려진 촬영지는 번호 핀·미리보기 핀·챗봇이 찾아 준 촬영지다. 직접 찍은 핀(id 음수)은 아니다.
    func testDrawnPlacesAreStopsPreviewsAndGuidePlaces() {
        let drawn = PlacePoiLink.drawnPlaceIds(
            stops: [stop(45, "수원 카페 몽테드"), stop(-77, "숙소")],
            previews: [stop(51, "행리단길")],
            guidePlaceIds: [54]
        )
        XCTAssertEqual(drawn, [45, 51, 54])
    }

    /// 목록에서 거르면 숨길 것만 빠진다 — 순서는 그대로.
    func testFilteringKeepsEverythingElse() {
        let places = [
            RouteGuide.Place(poi: poi(1, "가", placeId: nil)),
            RouteGuide.Place(poi: poi()),
            RouteGuide.Place(poi: poi(3, "다", placeId: 51)),
        ]
        XCTAssertEqual(places.withoutDotsUnderPins([45]).map(\.id), ["poi-1", "poi-3"])
        XCTAssertEqual(places.withoutDotsUnderPins([]).map(\.id), ["poi-1", "poi-277819", "poi-3"])
    }

    // MARK: 3. 코스에 담기

    /// 연결된 편의시설은 **촬영지로** 담긴다 — id 가 촬영지 id 이고 핀이 아니다.
    func testLinkedPoiIsAddedAsThePlace() {
        let entry = RouteGuide.Place(poi: poi()).courseEntry
        XCTAssertFalse(entry.pinned)
        XCTAssertEqual(entry.place.id, 45)
        XCTAssertEqual(entry.place.name, "몽테드")
    }

    /// 그렇게 담은 줄은 서버에 `placeId` 로 간다 — `customPin` 이 아니다(`RouteBridge`).
    func testLinkedEntryIsSavedWithPlaceId() {
        let entry = RouteGuide.Place(poi: poi()).courseEntry
        var course = RouteCourse(title: "수원", startDate: Date(), pace: .tight, days: [RouteDay()])
        course.days[0].stops = [RouteStop(place: entry.place, isPinned: entry.pinned)]
        let item = RouteBridge.replace(from: course).days[0].items[0]
        XCTAssertEqual(item.placeId, 45)
        XCTAssertNil(item.customPin)
        XCTAssertNil(item.poiId)
    }

    /// 연결이 없으면 전처럼 개인 핀이다(음수 id).
    func testUnlinkedPoiIsStillAddedAsAPin() {
        let entry = RouteGuide.Place(poi: poi(placeId: nil)).courseEntry
        XCTAssertTrue(entry.pinned)
        XCTAssertLessThan(entry.place.id, 0)
        XCTAssertEqual(entry.place.name, "몽테드")
    }

    /// 같은 곳을 두 번 담으면 기존 중복 검사(촬영지 id)에 걸린다 — 촬영지를 먼저 검색으로 담았어도.
    func testAddingTheSamePlaceTwiceIsCaughtByTheIdCheck() {
        let entry = RouteGuide.Place(poi: poi()).courseEntry
        let taken = stop(45, "수원 카페 몽테드")
        let fresh = RouteDedupe.fresh([entry.place], takenIds: [45], takenKeys: [RouteDedupe.key(taken)])
        XCTAssertTrue(fresh.isEmpty, "이름·좌표가 달라도 촬영지 id 로 걸려야 한다")
        XCTAssertEqual(RouteDedupe.fresh([entry.place], takenIds: [], takenKeys: []).count, 1)
    }

    /// 「경로에 있음」 — 그 촬영지가 담겨 있으면 담긴 것이다. 이름·좌표가 달라도.
    func testLinkedPoiCountsAsAddedWhenItsPlaceIsInTheCourse() {
        let place = RouteGuide.Place(poi: poi())
        XCTAssertTrue(place.isSameSpot(as: stop(45, "수원 카페 몽테드")))
        XCTAssertFalse(place.isSameSpot(as: stop(51, "행리단길", 37.2853, 127.01298)))
    }

    /// 연결이 없는 편의시설은 전처럼 이름 + 좌표로 본다 — 숫자가 같은 촬영지 id 와 헷갈리지 않는다.
    func testUnlinkedPoiIsMatchedByNameAndSpotOnly() {
        let place = RouteGuide.Place(poi: poi(45, "몽테드", placeId: nil))
        XCTAssertTrue(place.isSameSpot(as: stop(-9, "몽테드", 37.28476789, 127.01361679)))
        XCTAssertFalse(place.isSameSpot(as: stop(45, "수원 카페 몽테드")))
    }

    /// 촬영지 상세가 오면 담아 둔 줄을 촬영지의 이름·유형·주소·좌표로 바꾼다. 다른 곳의 상세면 그대로.
    func testAddedRowTakesThePlaceNameOnceTheDetailArrives() {
        let entry = RouteGuide.Place(poi: poi()).courseEntry
        let detail = PlaceDetail(
            id: 45, name: "수원 카페 몽테드", type: "cafe", address: "경기 수원시 팔달구 신풍로23번길 62",
            latitude: 37.284771, longitude: 127.013614
        )
        let adopted = PlacePoiLink.adopting(detail, over: entry.place)
        XCTAssertEqual(adopted.id, 45)
        XCTAssertEqual(adopted.name, "수원 카페 몽테드")
        XCTAssertEqual(adopted.type, "cafe")
        XCTAssertEqual(adopted.latitude, 37.284771)

        let other = stop(51, "행리단길")
        XCTAssertEqual(PlacePoiLink.adopting(detail, over: other), other)
    }

    // MARK: 촬영지 상세를 못 받았을 때

    private var placeDetail: PlaceDetail {
        PlaceDetail(
            id: 45, name: "수원 카페 몽테드", type: "cafe", address: "경기도 수원시 팔달구 화서문로48번길 14 1층",
            latitude: 37.284771, longitude: 127.013614
        )
    }

    /// 연결된 편의시설은 촬영지 상세가 실패해도 **목록이 준 것으로 카드가 선다** — 연결 전처럼.
    func testLinkedPoiCardStandsOnListedInfoWhenThePlaceDetailFails() throws {
        let card = try XCTUnwrap(RouteGuide.card(place: nil, listed: RouteGuide.Place(poi: poi())))
        XCTAssertEqual(card.title, "몽테드")
        XCTAssertEqual(card.address, "경기 수원시 팔달구 신풍로23번길 62")
        XCTAssertNotNil(card.category)
        XCTAssertFalse(card.detailed, "목록만으로 지은 카드다 — 다시 읽기가 성공하면 갈아 끼운다")
        XCTAssertNil(card.naverUrl)
    }

    /// 그 카드에서 담아도 촬영지로 담긴다 — `placeId` 는 목록이 이미 줬다.
    func testLinkedPoiIsStillAddedAsThePlaceWithoutTheDetail() {
        let place = RouteGuide.Place(poi: poi())
        XCTAssertNotNil(RouteGuide.card(place: nil, listed: place))
        XCTAssertEqual(place.courseEntry.place.id, 45)
        XCTAssertFalse(place.courseEntry.pinned)
    }

    /// 촬영지 자신의 카드는 전과 같다 — 상세를 못 받으면 카드가 없다.
    func testPlainPlaceCardIsStillMissingWhenTheDetailFails() {
        XCTAssertNil(RouteGuide.card(place: nil, listed: RouteGuide.Place(guide: guide(45, source: .place))))
    }

    /// 상세가 오면 연결된 편의시설의 카드는 촬영지 이름·유형 표시말·주소다.
    func testLinkedPoiCardShowsThePlaceWhenTheDetailArrives() throws {
        let card = try XCTUnwrap(RouteGuide.card(place: placeDetail, listed: RouteGuide.Place(poi: poi())))
        XCTAssertEqual(card.title, "수원 카페 몽테드")
        XCTAssertEqual(card.category, PlaceType.label("cafe"))
        XCTAssertEqual(card.address, "경기도 수원시 팔달구 화서문로48번길 14 1층")
        XCTAssertTrue(card.detailed)
    }

    // MARK: 저장 뒤 줄 찾기 (담은 직후 길찾기)

    /// 편의시설 이름·좌표로 담긴 촬영지 줄은 서버가 촬영지 이름·좌표로 돌려준다 — **촬영지 id 로 찾는다.**
    func testPlaceRowIsFoundByPlaceIdAfterSaving() {
        let entry = RouteGuide.Place(poi: poi()).courseEntry
        let added = RouteStop(place: entry.place, isPinned: entry.pinned)
        let saved = [
            RouteStop(place: stop(49, "화홍마트", 37.28726, 127.01651), serverItemId: 1),
            RouteStop(place: stop(45, "수원 카페 몽테드"), serverItemId: 2),
        ]
        XCTAssertEqual(PlacePoiLink.row(of: added, in: saved)?.serverItemId, 2)
    }

    /// 개인 핀은 전처럼 이름 + 좌표로 찾는다 — id 는 저장마다 바뀐다. 숫자가 같은 촬영지 줄과 헷갈리지 않는다.
    func testPinRowIsFoundByNameAndSpot() {
        let pin = RouteStop(place: stop(-45, "숙소", 37.5, 127.0), isPinned: true)
        let saved = [
            RouteStop(place: stop(45, "수원 카페 몽테드"), serverItemId: 2),
            RouteStop(place: stop(-9, "숙소", 37.5, 127.0), serverItemId: 3, isPinned: true),
        ]
        XCTAssertEqual(PlacePoiLink.row(of: pin, in: saved)?.serverItemId, 3)
    }

    /// 저장된 줄에 없으면 못 찾는다(부른 쪽이 원래 줄로 간다).
    func testRowIsNotFoundWhenTheSavedCourseLacksIt() {
        let added = RouteStop(place: stop(45, "몽테드"))
        XCTAssertNil(PlacePoiLink.row(of: added, in: [RouteStop(place: stop(49, "화홍마트", 37.28726, 127.01651))]))
    }
}
