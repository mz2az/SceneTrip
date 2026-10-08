import SceneApiClient
@testable import SceneTrip
import XCTest

/// 계약의 편의시설(`PoiSummary`) → 화면 장소(`RouteGuide.Place`) 매핑 (MZ2AZ-314 연결).
/// 갈래는 서버의 `categoryGroup` 을 그대로 믿고, 글리프는 업종 이름으로 고른다.
final class RouteGuidePlacePoiTests: XCTestCase {
    private func poi(_ id: Int64, _ name: String, _ category: String, _ group: PoiCategoryGroup) -> PoiSummary {
        PoiSummary(
            id: id, name: name, category: category, categoryGroup: group,
            address: "서울 종로구", latitude: 37.58, longitude: 126.98, distanceMeters: 120
        )
    }

    func testGroupComesFromContractNotFromCategoryText() {
        // 이름에 「역」이 들어도 서버가 food 라면 food 다.
        let place = RouteGuide.Place(poi: poi(7, "역전우동", "우동", .food))
        XCTAssertEqual(place.poiGroup, .food)
        XCTAssertEqual(place.id, "poi-7")
        XCTAssertEqual(place.poiId, 7)
        XCTAssertEqual(place.distanceMeters, 120)
    }

    /// 지도 범위 조회의 거리는 화면 중심에서 잰 값이다 — 카드에 적지 않는다 (MZ2AZ-367).
    func testDistanceFromTheMapCenterIsNotShown() {
        let place = RouteGuide.Place(poi: poi(7, "트로", "베이커리", .food))
        XCTAssertEqual(place.distanceBasis, .mapCenter)
        XCTAssertNil(place.shownMeters)
    }

    /// 가이드가 찾아 준 곳의 거리는 답이 밝힌 기준에서 잰 것이라 보인다. 기준을 모르면 숨긴다.
    func testDistanceIsShownOnlyWithAMeaningfulBasis() {
        XCTAssertEqual(RouteGuide.Place.shownMeters(88, basis: .guideAnchor), 88)
        XCTAssertNil(RouteGuide.Place.shownMeters(88, basis: .mapCenter))
        XCTAssertNil(RouteGuide.Place.shownMeters(88, basis: nil))
        XCTAssertNil(RouteGuide.Place.shownMeters(nil, basis: .guideAnchor))
    }

    func testGlyphFollowsCategoryWithinGroup() {
        XCTAssertEqual(RouteGuide.Place(poi: poi(1, "a", "카페", .food)).poiSymbol, "cup.and.saucer.fill")
        XCTAssertEqual(RouteGuide.Place(poi: poi(2, "b", "한식", .food)).poiSymbol, "fork.knife")
        XCTAssertEqual(RouteGuide.Place(poi: poi(3, "c", "지하철역", .transit)).poiSymbol, "tram.fill")
        XCTAssertEqual(RouteGuide.Place(poi: poi(4, "d", "공항", .transit)).poiSymbol, "airplane")
        XCTAssertEqual(RouteGuide.Place(poi: poi(5, "e", "호텔", .stay)).poiSymbol, "bed.double.fill")
    }

    /// 챗봇이 준 곳(id 가 이름)은 서버 id 가 없다 — 카드를 묻지 않는다.
    func testChatPlaceHasNoPoiId() {
        let place = RouteGuide.Place(id: "북촌손만두", name: "북촌손만두", category: "만두", latitude: 37.58, longitude: 126.98)
        XCTAssertNil(place.poiId)
    }
}
