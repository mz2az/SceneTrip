@testable import SceneTrip
import XCTest

/// **지나온 길은 지우고 남은 길만 그린다** (2026-09-17 사용자 요청).
///
/// 프로토타입은 안내 중 지나온 자리를 발자국으로 남기고 경로선은 앞쪽만 그렸다. main 으로
/// 옮길 때 발자국만 따라오고 선을 자르는 쪽이 빠져서, 걸어온 길 위에 보라색 선이 남았다.
final class RouteTrailTests: XCTestCase {
    /// 서울시청에서 종로 쪽으로 동쪽으로 뻗는 한 줄. `(경도, 위도)` 순서다.
    private let straight: [[[Double]]] = [[
        [126.9779, 37.5663],
        [126.9789, 37.5663],
        [126.9799, 37.5663],
        [126.9809, 37.5663],
    ]]

    func testNoPositionKeepsWholePath() {
        XCTAssertEqual(RouteTrail.remaining(paths: straight, from: nil), straight)
    }

    /// 세 번째 점 바로 옆에 서 있으면 앞의 둘은 사라지고, 선은 **발밑에서** 시작한다.
    func testWalkedPartIsDropped() {
        let remaining = RouteTrail.remaining(
            paths: straight, from: (latitude: 37.5663, longitude: 126.9799)
        )
        XCTAssertEqual(remaining.count, 1)
        XCTAssertEqual(remaining[0].count, 3) // 지금 자리 + 남은 점 둘
        XCTAssertEqual(remaining[0].first ?? [], [126.9799, 37.5663])
        XCTAssertEqual(remaining[0].last ?? [], [126.9809, 37.5663])
    }

    /// 아직 출발점이면 길이 그대로 남는다(맨 앞에 지금 자리가 한 점 붙을 뿐이다).
    func testAtStartKeepsEverything() {
        let remaining = RouteTrail.remaining(
            paths: straight, from: (latitude: 37.5663, longitude: 126.9779)
        )
        XCTAssertEqual(remaining[0].count, 5)
        XCTAssertEqual(remaining[0].last ?? [], [126.9809, 37.5663])
    }

    /// **길에서 멀면 자르지 않는다.** 잘못 잘라 길을 통째로 지우는 것보다 낫다.
    func testOffRouteKeepsWholePath() {
        // 약 2 km 북쪽.
        let remaining = RouteTrail.remaining(
            paths: straight, from: (latitude: 37.5843, longitude: 126.9799)
        )
        XCTAssertEqual(remaining, straight)
    }

    /// 구간이 여럿이면 **지나온 구간은 통째로 빈다.** 그리는 쪽이 점 2개 미만을 건너뛴다.
    func testEarlierLegsBecomeEmpty() {
        let legs: [[[Double]]] = [
            [[126.9779, 37.5663], [126.9789, 37.5663]], // 지나온 구간
            [[126.9799, 37.5663], [126.9809, 37.5663]], // 지금 걷는 구간
            [[126.9819, 37.5663], [126.9829, 37.5663]], // 아직 안 온 구간
        ]
        let remaining = RouteTrail.remaining(
            paths: legs, from: (latitude: 37.5663, longitude: 126.9799)
        )
        XCTAssertEqual(remaining[0], [])
        XCTAssertEqual(remaining[1].count, 3) // 지금 자리 + 점 둘
        XCTAssertEqual(remaining[2], legs[2])
    }

    func testEmptyInputIsSafe() {
        XCTAssertEqual(
            RouteTrail.remaining(paths: [], from: (latitude: 37.5663, longitude: 126.9779)), []
        )
    }
}
