@testable import SceneTrip
import XCTest

/// 가이드 답의 장소 카드 「길찾기」(MZ2AZ-554) — 누르면 무엇을 어떤 순서로 하나.
///
/// - 로그인 안 했으면 길찾기 로그인 안내(이유 문구)만 띄운다. 코스에 담지도, 안내를 켜지도 않는다.
/// - 코스에 없는 곳이면 먼저 담는다(가이드의 기존 담기 길).
/// - 여행 중이 아니면 그곳을 목적지로 여행을 시작하고, 이미 여행 중이면 목적지만 바꾼다.
///
/// 웹 `route/guide/guideRules.ts` 의 `guideDirectionsSteps` 와 같은 값이다.
final class RouteGuideDirectionsTests: XCTestCase {
    private typealias Step = RouteGuideDirections.Step

    private func steps(signedIn: Bool, inCourse: Bool, tripRunning: Bool) -> [Step] {
        RouteGuideDirections.steps(signedIn: signedIn, inCourse: inCourse, tripRunning: tripRunning)
    }

    func testSignedOutOnlyAsksSignIn() {
        for inCourse in [false, true] {
            for running in [false, true] {
                XCTAssertEqual(steps(signedIn: false, inCourse: inCourse, tripRunning: running), [.signIn])
            }
        }
    }

    func testNotInCourseNotRunningAddsThenStartsTrip() {
        XCTAssertEqual(steps(signedIn: true, inCourse: false, tripRunning: false), [.add, .startTrip])
    }

    func testInCourseNotRunningStartsTripWithoutAdding() {
        XCTAssertEqual(steps(signedIn: true, inCourse: true, tripRunning: false), [.startTrip])
    }

    func testNotInCourseRunningAddsThenSwitchesTarget() {
        XCTAssertEqual(steps(signedIn: true, inCourse: false, tripRunning: true), [.add, .switchTarget])
    }

    func testInCourseRunningOnlySwitchesTarget() {
        XCTAssertEqual(steps(signedIn: true, inCourse: true, tripRunning: true), [.switchTarget])
    }

    func testRunningNeverRestartsTrip() {
        for inCourse in [false, true] {
            XCTAssertFalse(steps(signedIn: true, inCourse: inCourse, tripRunning: true).contains(.startTrip))
        }
    }
}
