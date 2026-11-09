@testable import SceneTrip
import XCTest

/// 홈 떠 있는 「여행 코스 짜기」 단추 (MZ2AZ-547 H4, 웹 `ProtoScreens.tsx` `TripFloatBar`).
final class HomeTripFloatRulesTests: XCTestCase {
    private func course(_ id: Int64?, running: Bool = false) -> RouteCourse {
        RouteCourse(serverId: id, title: "코스 \(id.map(String.init) ?? "new")", days: [], isRunning: running)
    }

    func testConstants() {
        XCTAssertEqual(HomeTripFloatRules.maxCourses, 5)
        XCTAssertEqual(HomeTripFloatRules.stagger, 0.045, accuracy: 0.00001)
    }

    func testCoursesOnlySavedRunningFirstMaxFive() {
        let all = [
            course(1), course(nil), course(2), course(3, running: true), course(4), course(5),
            course(6, running: true), course(7),
        ]
        XCTAssertEqual(HomeTripFloatRules.courses(all).map(\.serverId), [3, 6, 1, 2, 4])
    }

    func testCoursesDropUnsaved() {
        XCTAssertTrue(HomeTripFloatRules.courses([course(nil), course(nil, running: true)]).isEmpty)
    }

    func testCollapsedIsPlanWithoutRunning() {
        XCTAssertEqual(HomeTripFloatRules.collapsed([course(1), course(2)]), .plan)
        XCTAssertEqual(HomeTripFloatRules.collapsed([]), .plan)
        // 대조: 여행 중 코스가 끼면 `.plan` 이 아니다.
        let running = course(9, running: true)
        XCTAssertEqual(HomeTripFloatRules.collapsed([course(1), running]), .running(running))
    }

    func testCollapsedIsFirstRunningCourse() {
        let first = course(3, running: true)
        let second = course(4, running: true)
        XCTAssertEqual(HomeTripFloatRules.collapsed([course(1), first, second]), .running(first))
    }

    func testPlanTapNewCourseOnlyWhenLoadedAndEmpty() {
        XCTAssertEqual(HomeTripFloatRules.planTap(courseList: .loaded, courseCount: 0), .newCourse)
        XCTAssertEqual(HomeTripFloatRules.planTap(courseList: .loaded, courseCount: 2), .expand)
        XCTAssertEqual(HomeTripFloatRules.planTap(courseList: .unknown, courseCount: 0), .expand)
        XCTAssertEqual(HomeTripFloatRules.planTap(courseList: .failed, courseCount: 0), .expand)
    }

    func testItemsOrder() {
        let first = course(1)
        let second = course(2, running: true)
        XCTAssertEqual(HomeTripFloatRules.items([first, second]), [.close, .all, .newCourse, .course(first), .course(second)])
        XCTAssertEqual(HomeTripFloatRules.items([]), [.close, .all, .newCourse])
    }

    func testDelayStaggersOnOpenZeroOnClose() {
        XCTAssertEqual(HomeTripFloatRules.delay(index: 0, open: true), 0, accuracy: 0.00001)
        XCTAssertEqual(HomeTripFloatRules.delay(index: 1, open: true), 0.045, accuracy: 0.00001)
        XCTAssertEqual(HomeTripFloatRules.delay(index: 4, open: true), 0.18, accuracy: 0.00001)
        XCTAssertEqual(HomeTripFloatRules.delay(index: 4, open: false), 0, accuracy: 0.00001)
    }
}
