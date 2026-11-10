@testable import SceneTrip
import XCTest

/// 홈의 새 코스 입구와 전체 보기 덮개 (MZ2AZ-547 H1·H4·H6).
@MainActor
final class NewCourseHookTests: XCTestCase {
    private var savedCover: TabRouter.Cover?
    private var savedPending: TabRouter.NewCourseRequest?

    override func setUp() async throws {
        savedCover = TabRouter.shared.cover
        savedPending = TabRouter.shared.pendingNewCourse
        TabRouter.shared.cover = nil
        TabRouter.shared.pendingNewCourse = nil
    }

    override func tearDown() async throws {
        TabRouter.shared.cover = savedCover
        TabRouter.shared.pendingNewCourse = savedPending
    }

    func testCityChipLeavesCityAndOpensRoute() {
        TabRouter.shared.startNewCourse(city: "부산")
        XCTAssertEqual(TabRouter.shared.pendingNewCourse, TabRouter.NewCourseRequest(city: "부산"))
        XCTAssertEqual(TabRouter.shared.cover, .route)
    }

    func testPlusNewCourseLeavesNilCity() {
        TabRouter.shared.startNewCourse(city: nil)
        XCTAssertEqual(TabRouter.shared.pendingNewCourse, TabRouter.NewCourseRequest(city: nil))
        XCTAssertEqual(TabRouter.shared.cover, .route)
    }

    func testOpenTrendingOpensTrendingCover() {
        TabRouter.shared.openTrending()
        XCTAssertEqual(TabRouter.shared.cover, .trending)
    }
}
