import SceneApiClient
@testable import SceneTrip
import XCTest

/// AI 초안의 촬영지 이름·주소는 앱 언어로 보인다 (MZ2AZ-530).
///
/// 에이전트는 `Accept-Language` 와 무관하게 한국어 이름을 준다. 앱이 이미 받아 둔 장소 목록(앱 언어)에서
/// 같은 `placeId` 의 이름·주소로 바꾼다. 목록에 없으면 초안 그대로, 편의시설 줄은 건드리지 않는다.
final class RouteDraftNamesTests: XCTestCase {
    private let englishPlaces = [
        PlaceSummary(id: 100, name: "Seoul Central High School", address: "Jongno-gu, Seoul",
                     latitude: 37.5, longitude: 127),
        PlaceSummary(id: 101, name: "Gaebul Cafe", address: "Mapo-gu, Seoul", latitude: 37.6, longitude: 127),
    ]

    private func lookup() -> [Int64: PlaceSummary] {
        RouteGuidePlan.placeLookup(englishPlaces)
    }

    private func koreanPlan() -> GuidePlan {
        GuidePlan(days: [GuidePlanDay(day: 1, stops: [
            GuidePlanStop(order: 1, placeId: 100, name: "서울중앙고등학교", address: "서울 종로구",
                          latitude: 37.5, longitude: 127, arriveMinute: 540),
            GuidePlanStop(order: 2, placeId: 999, name: "목록에 없는 곳", address: "서울 어딘가",
                          latitude: 37.55, longitude: 127, arriveMinute: 600),
            GuidePlanStop(order: 3, poiId: 9002, name: "카페 한글", address: "서울 중구",
                          latitude: 37.56, longitude: 127, arriveMinute: 660),
        ])])
    }

    func testDraftFilmingSpotsUseAppLanguageNamesAndAddresses() {
        let course = RouteGuidePlan.course(
            from: koreanPlan(), title: "t", startDate: nil, pace: .loose, places: lookup()
        )
        let stops = course.days[0].stops
        XCTAssertEqual(stops[0].place.name, "Seoul Central High School")
        XCTAssertEqual(stops[0].place.address, "Jongno-gu, Seoul")
    }

    func testDraftKeepsNameWhenPlaceIsNotInList() {
        let course = RouteGuidePlan.course(
            from: koreanPlan(), title: "t", startDate: nil, pace: .loose, places: lookup()
        )
        let stops = course.days[0].stops
        XCTAssertEqual(stops[1].place.name, "목록에 없는 곳")
        XCTAssertEqual(stops[1].place.address, "서울 어딘가")
    }

    func testDraftAmenityRowIsUnchanged() {
        let course = RouteGuidePlan.course(
            from: koreanPlan(), title: "t", startDate: nil, pace: .loose, places: lookup()
        )
        let amenity = course.days[0].stops[2]
        XCTAssertEqual(amenity.kind, .poi(9002))
        XCTAssertEqual(amenity.place.name, "카페 한글")
        XCTAssertEqual(amenity.place.address, "서울 중구")
    }

    /// 같은 촬영지로 대응한 줄도 목록(앱 언어)이 먼저다 — 앞 줄 이름이 한국어 초안이었을 수 있다.
    /// 목록에 없을 때만 앞서 보이던 이름·주소를 두고, 새 촬영지는 목록에서, 그것도 없으면 초안대로.
    func testRevisingPrefersListThenPreviousThenDraft() {
        var koreanPrevious = RouteStop(place: PlaceSummary(
            id: 100, name: "서울중앙고등학교", address: "서울 종로구", latitude: 37.5, longitude: 127
        ))
        koreanPrevious.serverItemId = 31
        var savedPrevious = RouteStop(place: PlaceSummary(
            id: 200, name: "Saved place", address: "Saved address", latitude: 37.7, longitude: 127
        ))
        savedPrevious.serverItemId = 32
        let original = RouteCourse(serverId: 4, title: "t", days: [RouteDay(stops: [koreanPrevious, savedPrevious])])
        let plan = GuidePlan(days: [GuidePlanDay(day: 1, stops: [
            GuidePlanStop(order: 1, placeId: 100, name: "서울중앙고등학교", address: "서울 종로구",
                          latitude: 37.5, longitude: 127, arriveMinute: 540),
            GuidePlanStop(order: 2, placeId: 200, name: "저장된 곳", address: "서울 강남구",
                          latitude: 37.7, longitude: 127, arriveMinute: 570),
            GuidePlanStop(order: 3, placeId: 101, name: "개뿔", address: "서울 마포구",
                          latitude: 37.6, longitude: 127, arriveMinute: 600),
            GuidePlanStop(order: 4, placeId: 999, name: "목록에 없는 곳", address: "서울 어딘가",
                          latitude: 37.55, longitude: 127, arriveMinute: 660),
        ])])
        let revised = RouteGuidePlan.revising(original, with: plan, places: lookup())
        let stops = revised.days[0].stops
        XCTAssertEqual(stops[0].serverItemId, 31)
        XCTAssertEqual(stops[0].place.name, "Seoul Central High School")
        XCTAssertEqual(stops[0].place.address, "Jongno-gu, Seoul")
        XCTAssertEqual(stops[1].serverItemId, 32)
        XCTAssertEqual(stops[1].place.name, "Saved place")
        XCTAssertEqual(stops[1].place.address, "Saved address")
        XCTAssertEqual(stops[2].place.name, "Gaebul Cafe")
        XCTAssertEqual(stops[2].place.address, "Mapo-gu, Seoul")
        XCTAssertEqual(stops[3].place.name, "목록에 없는 곳")
    }
}
