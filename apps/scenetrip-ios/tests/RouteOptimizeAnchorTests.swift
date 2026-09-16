import SceneApiClient
@testable import SceneTrip
import XCTest

/// 동선 최적화의 **기준점 고르기** — 지금 자리를 쓸 것인가 말 것인가 (2026-09-16 사용자 결정).
///
/// 규칙 둘. **한국 안**이어야 하고, 가장 가까운 정지점이 **같은 지역**(기본 100 km)이어야 한다.
/// 둘 중 하나라도 어긋나면 기준점 없이 순서만 최적화한다 — 도쿄에서 짜는 서울 코스의 1 번이
/// 「도쿄에서 가장 가까운 곳」이 되면 안 되고, 서울에 앉아 손보는 부산 코스도 마찬가지다.
final class RouteOptimizeAnchorTests: XCTestCase {
    private func stop(_ name: String, _ lat: Double, _ lng: Double) -> RouteStop {
        RouteStop(place: PlaceSummary(id: 0, name: name, latitude: lat, longitude: lng))
    }

    private func at(_ lat: Double, _ lng: Double) -> PlaceSummary {
        PlaceSummary(id: 0, name: "여기", latitude: lat, longitude: lng)
    }

    /// 서울 코스 + 서울시청. 같은 지역이므로 기준점으로 쓴다.
    private let seoulCourse = [
        RouteStop(place: PlaceSummary(id: 0, name: "인천 월미도", latitude: 37.4751, longitude: 126.5966)),
        RouteStop(place: PlaceSummary(id: 0, name: "서울 덕수궁", latitude: 37.5658, longitude: 126.9751)),
    ]

    func testSeoulCityHallIsUsedForSeoulCourse() {
        let anchor = RouteGeometry.usableAnchor(at(37.5663, 126.9779), for: seoulCourse)
        XCTAssertNotNil(anchor)
    }

    func testTokyoIsRejected() {
        // 한국 밖 — 출국 전에 일정을 짜는 사람.
        XCTAssertNil(RouteGeometry.usableAnchor(at(35.6762, 139.6503), for: seoulCourse))
    }

    func testBusanIsRejectedForSeoulCourse() {
        // 한국 안이지만 325 km 떨어져 있다 — 같은 지역이 아니다.
        XCTAssertNil(RouteGeometry.usableAnchor(at(35.1796, 129.0756), for: seoulCourse))
    }

    func testNoLocationIsRejected() {
        XCTAssertNil(RouteGeometry.usableAnchor(nil, for: seoulCourse))
    }

    func testEmptyCourseIsRejected() {
        XCTAssertNil(RouteGeometry.usableAnchor(at(37.5663, 126.9779), for: []))
    }

    func testKoreaBox() {
        XCTAssertTrue(RouteGeometry.isInKorea(at(33.2, 126.5))) // 제주
        XCTAssertTrue(RouteGeometry.isInKorea(at(38.6, 128.4))) // 고성
        XCTAssertFalse(RouteGeometry.isInKorea(at(39.2, 125.7))) // 평양 — 위도 밖
        XCTAssertFalse(RouteGeometry.isInKorea(at(37.5, 121.4))) // 옌타이 — 경도 밖
    }

    /// 기준점이 있으면 **그 자리에서 가장 가까운 곳이 1 번**이 된다. 시연 리허설에서
    /// 서울시청에 서 있는데 인천이 1 번이던 것이 이 자리다.
    func testNearestBecomesFirst() {
        let ordered = RouteGeometry.startingNearest(seoulCourse, to: at(37.5663, 126.9779))
        XCTAssertEqual(ordered.first?.place.name, "서울 덕수궁")
    }
}
