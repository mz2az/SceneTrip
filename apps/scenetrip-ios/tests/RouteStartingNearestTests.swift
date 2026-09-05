import SceneApiClient
@testable import SceneTrip
import XCTest

/// 「지금 선 자리에서 가장 가까운 곳이 출발」 — 현재 위치를 아는 상태의 동선 최적화 첫 걸음
/// (2026-09-04 사용자 결정). 나머지 순서는 건드리지 않는다.
final class RouteStartingNearestTests: XCTestCase {
    private func stop(_ name: String, _ lat: Double, _ lng: Double) -> RouteStop {
        RouteStop(place: PlaceSummary(id: 0, name: name, latitude: lat, longitude: lng))
    }

    func testNearestMovesToFrontAndOthersKeepOrder() {
        let stops = [
            stop("덕수궁", 37.5658, 126.9751),
            stop("인사동", 37.5744, 126.9856),
            stop("북촌", 37.5826, 126.9831), // 여기서 가장 가깝다
            stop("동대문", 37.5665, 127.0092),
        ]
        let here = PlaceSummary(id: 0, name: "여기", latitude: 37.5830, longitude: 126.9835)
        let ordered = RouteGeometry.startingNearest(stops, to: here)
        XCTAssertEqual(ordered.map(\.place.name), ["북촌", "덕수궁", "인사동", "동대문"])
    }

    func testSingleStopIsUntouched() {
        let only = [stop("덕수궁", 37.5658, 126.9751)]
        let here = PlaceSummary(id: 0, name: "여기", latitude: 37.5, longitude: 127.0)
        XCTAssertEqual(RouteGeometry.startingNearest(only, to: here).map(\.place.name), ["덕수궁"])
    }
}
