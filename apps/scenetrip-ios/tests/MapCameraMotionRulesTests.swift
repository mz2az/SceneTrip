@testable import SceneTrip
import XCTest

/// 지도를 고른 곳·코스 장소로 옮기는 움직임 — 지금 화면 가운데에서 그 곳까지가 화면 너비의 3배 이하면
/// 0.6초 감속 곡선으로 부드럽게, 그보다 멀면 한 번에(계획 docs/project/plans/motion-works-drawer-parity.md §10-3).
final class MapCameraMotionRulesTests: XCTestCase {
    /// 지금 화면 너비 1km 로 잡는다 — 3배는 3km.
    private let width = 1000.0

    // MARK: 숫자

    func testConstants() {
        XCTAssertEqual(MapCameraMotionRules.smoothWithinScreens, 3)
        XCTAssertEqual(MapCameraMotionRules.smoothDuration, 0.6, accuracy: 0.0001)
    }

    // MARK: 부드럽게 / 한 번에

    /// 화면 너비 3배 안 — 0.6초 동안 부드럽게.
    func testWithinThreeScreensIsSmooth() {
        XCTAssertEqual(
            MapCameraMotionRules.motion(distanceMeters: 1500, visibleWidthMeters: width),
            .smooth(duration: 0.6)
        )
        XCTAssertEqual(
            MapCameraMotionRules.motion(distanceMeters: 200, visibleWidthMeters: width),
            .smooth(duration: 0.6)
        )
    }

    /// 딱 3배(「3배 이하」)도 부드럽게.
    func testExactlyThreeScreensIsSmooth() {
        XCTAssertEqual(
            MapCameraMotionRules.motion(distanceMeters: 3000, visibleWidthMeters: width),
            .smooth(duration: 0.6)
        )
    }

    /// 3배를 넘으면 움직임 없이 한 번에.
    func testBeyondThreeScreensJumps() {
        XCTAssertEqual(MapCameraMotionRules.motion(distanceMeters: 3001, visibleWidthMeters: width), .jump)
        XCTAssertEqual(MapCameraMotionRules.motion(distanceMeters: 50000, visibleWidthMeters: width), .jump)
    }

    /// 기준은 화면 너비에 따라 바뀐다 — 같은 5km 도 넓게 보는 화면(2km)에선 부드럽게, 좁게 보는 화면(1km)에선 한 번에.
    func testLimitScalesWithVisibleWidth() {
        XCTAssertEqual(
            MapCameraMotionRules.motion(distanceMeters: 5000, visibleWidthMeters: 2000),
            .smooth(duration: 0.6)
        )
        XCTAssertEqual(MapCameraMotionRules.motion(distanceMeters: 5000, visibleWidthMeters: 1000), .jump)
    }

    /// 이미 그 자리(거리 0)면 3배 이하이므로 부드럽게.
    func testZeroDistanceIsSmooth() {
        XCTAssertEqual(
            MapCameraMotionRules.motion(distanceMeters: 0, visibleWidthMeters: width),
            .smooth(duration: 0.6)
        )
    }

    /// 부드럽게 옮길 때의 시간은 `smoothDuration` 과 같다.
    func testSmoothUsesDurationConstant() {
        guard case let .smooth(duration) = MapCameraMotionRules.motion(distanceMeters: 1000, visibleWidthMeters: width)
        else {
            return XCTFail("화면 너비 1배 거리는 부드럽게 옮겨야 한다")
        }
        XCTAssertEqual(duration, MapCameraMotionRules.smoothDuration, accuracy: 0.0001)
        XCTAssertEqual(duration, 0.6, accuracy: 0.0001)
    }
}
