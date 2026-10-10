@testable import SceneTrip
import XCTest

final class PoiClustersTests: XCTestCase {
    func testNearbyPlacesSplitWhenZoomedIn() {
        let places = [place("a"), place("b", longitude: 126.9801)]
        XCTAssertEqual(PoiClusters.make(places, zoom: 13).map(\.places.count), [2])
        XCTAssertEqual(PoiClusters.make(places, zoom: 20).map(\.places.count), [1, 1])
    }

    func testSameCoordinatesRemainIndividuallySelectableAtMaximumZoom() {
        let places = [place("c"), place("a"), place("b")]
        let groups = PoiClusters.make(places, zoom: 21)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups[0].places.map(\.id), ["a", "b", "c"])
        XCTAssertTrue(groups[0].opensList(zoom: 21, maximumZoom: 21))
        XCTAssertTrue(groups[0].opensList(zoom: 13, maximumZoom: 21))
    }

    func testPickedPlaceStaysSeparateWithoutLosingOtherMembers() {
        let groups = PoiClusters.make([place("a"), place("b"), place("c")], zoom: 13, pickedId: "b")
        XCTAssertEqual(groups.flatMap(\.places).map(\.id).sorted(), ["a", "b", "c"])
        XCTAssertEqual(groups.first { $0.places.contains { $0.id == "b" } }?.places.count, 1)
        XCTAssertEqual(groups.first { $0.places.count == 2 }?.places.map(\.id), ["a", "c"])
    }

    func testInputOrderAndCellBoundaryDoNotChangeGrouping() {
        // 줌 13의 64pt 셀 경계를 양옆에서 걸친다. 두 점의 간격은 1pt 미만이다.
        let width = 256.0 * pow(2, 13.0)
        let boundary = 28000.0 * 64 / width * 360 - 180
        let places = [place("a", longitude: boundary - 0.00001), place("b", longitude: boundary + 0.00001)]
        XCTAssertEqual(PoiClusters.make(places, zoom: 13).map(\.places.count), [2])
        XCTAssertEqual(PoiClusters.make(places, zoom: 13), PoiClusters.make(places.reversed(), zoom: 13))
    }

    func testFilteredInputAloneDeterminesCounts() {
        let places = [place("food-1"), place("food-2"), place("stay-1")]
        let food = places.filter { $0.id.hasPrefix("food") }
        XCTAssertEqual(PoiClusters.make(food, zoom: 13).flatMap(\.places).map(\.id), ["food-1", "food-2"])
        XCTAssertEqual(PoiClusters.make([], zoom: 13), [])
    }

    func testInvalidCoordinatesAndDuplicateIdsDoNotCreatePhantomCounts() {
        let groups = PoiClusters.make([
            place("ok"), place("ok"), place("nan", latitude: .nan),
            place("infinite", longitude: .infinity), place("outside", latitude: 95),
        ], zoom: 13)
        XCTAssertEqual(groups.flatMap(\.places).map(\.id), ["ok"])
        XCTAssertEqual(PoiClusters.make([place("ok")], zoom: .nan), [])
    }

    func testDistinctNearPointsOfferZoomBeforeList() {
        let groups = PoiClusters.make([place("a"), place("b", longitude: 126.9801)], zoom: 13)
        XCTAssertFalse(groups[0].opensList(zoom: 13, maximumZoom: 21))
        XCTAssertTrue(groups[0].opensList(zoom: 21, maximumZoom: 21))
    }

    func testDenseResultKeepsEveryIdAcrossZoomChanges() {
        let places = (0 ..< 1000).map { index in
            place(String(index), latitude: 37.5663 + Double(index / 25) * 0.00002,
                  longitude: 126.98 + Double(index % 25) * 0.00002)
        }
        let ids = Set(places.map(\.id))
        for zoom in [13.0, 15, 17, 19, 21, 17, 13] {
            let members = PoiClusters.make(places, zoom: zoom).flatMap(\.places)
            XCTAssertEqual(members.count, places.count)
            XCTAssertEqual(Set(members.map(\.id)), ids)
        }
    }

    private func place(_ id: String, latitude: Double = 37.5663, longitude: Double = 126.98) -> RouteGuide.Place {
        RouteGuide.Place(id: id, name: id, category: "카페", latitude: latitude, longitude: longitude)
    }
}
