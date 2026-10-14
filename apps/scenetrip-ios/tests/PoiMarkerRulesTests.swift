@testable import SceneTrip
import XCTest

final class PoiMarkerRulesTests: XCTestCase {
    func testCoincidentPlacesRemainSeparateAtTheirOriginalCoordinates() {
        let places = (0 ..< 12).map { place(String($0)) }
        let result = PoiMarkerRules.validPlaces(places)
        XCTAssertEqual(result, places)
        XCTAssertEqual(Set(result.map(\.id)).count, 12)
    }

    func testNearbyPlacesKeepTheirOriginalCoordinatesAndOrder() {
        let places = [place("b"), place("a", longitude: 126.980001)]
        XCTAssertEqual(PoiMarkerRules.validPlaces(places), places)
    }

    func testInvalidCoordinatesAndDuplicateIdsAreExcluded() {
        let valid = place("ok")
        XCTAssertEqual(PoiMarkerRules.validPlaces([
            valid, valid, place("nan", latitude: .nan),
            place("inf", longitude: .infinity), place("outside", latitude: 95),
        ]), [valid])
        XCTAssertEqual(PoiMarkerRules.validPlaces([]), [])
    }

    private func place(_ id: String, latitude: Double = 37.5663, longitude: Double = 126.98) -> RouteGuide.Place {
        RouteGuide.Place(id: id, name: id, category: "카페", latitude: latitude, longitude: longitude)
    }
}
