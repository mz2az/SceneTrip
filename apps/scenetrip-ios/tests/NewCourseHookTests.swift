@testable import SceneTrip
import XCTest

/// 새 코스 만들기의 입구와 방금 만든 코스 표시, 전체 보기 덮개 (MZ2AZ-547 H1·H4·H6·F1·Q2·R5·R6).
@MainActor
final class NewCourseHookTests: XCTestCase {
    private var savedCover: TabRouter.Cover?
    private var savedSheet = false
    private var savedJustCreated: TabRouter.JustCreated?
    private var savedPendingCourseId: Int64?

    override func setUp() async throws {
        savedCover = TabRouter.shared.cover
        savedSheet = TabRouter.shared.newCourseSheet
        savedJustCreated = TabRouter.shared.justCreated
        savedPendingCourseId = TabRouter.shared.pendingCourseId
        TabRouter.shared.cover = nil
        TabRouter.shared.newCourseSheet = false
        TabRouter.shared.justCreated = nil
        TabRouter.shared.pendingCourseId = nil
        TabRouter.shared.clearFreshCourse()
    }

    override func tearDown() async throws {
        TabRouter.shared.cover = savedCover
        TabRouter.shared.newCourseSheet = savedSheet
        TabRouter.shared.justCreated = savedJustCreated
        TabRouter.shared.pendingCourseId = savedPendingCourseId
        TabRouter.shared.clearFreshCourse()
    }

    /// 홈 도시 칩 — 시트 없이 바로 「며칠?」(Q2)부터인 도시 질문 덮개.
    func testCityChipOpensCityQuestionCover() {
        TabRouter.shared.startNewCourse(city: "부산")
        XCTAssertEqual(TabRouter.shared.cover, .newCourse(.city("부산")))
        XCTAssertFalse(TabRouter.shared.newCourseSheet)
    }

    /// 「+ 새 코스」 — 세 갈래 시트(F1)만 띄우고 덮개는 열지 않는다.
    func testPlusNewCourseOpensSheet() {
        TabRouter.shared.startNewCourse(city: nil)
        XCTAssertTrue(TabRouter.shared.newCourseSheet)
        XCTAssertNil(TabRouter.shared.cover)
    }

    func testChooseClosesSheetAndOpensFlow() {
        TabRouter.shared.startNewCourse(city: nil)
        TabRouter.shared.chooseNewCourse(.works)
        XCTAssertFalse(TabRouter.shared.newCourseSheet)
        XCTAssertEqual(TabRouter.shared.cover, .newCourse(.works))
    }

    func testChooseCityAndSaved() {
        TabRouter.shared.newCourseSheet = true
        TabRouter.shared.chooseNewCourse(.city(nil))
        XCTAssertFalse(TabRouter.shared.newCourseSheet)
        XCTAssertEqual(TabRouter.shared.cover, .newCourse(.city(nil)))

        TabRouter.shared.newCourseSheet = true
        TabRouter.shared.chooseNewCourse(.saved)
        XCTAssertFalse(TabRouter.shared.newCourseSheet)
        XCTAssertEqual(TabRouter.shared.cover, .newCourse(.saved))
    }

    /// 「이 코스로 만들기」 직후 — 방금 만든 코스 표시, 안내 띠, 그 코스를 열 쪽지, 경로여정 덮개.
    func testCourseCreatedMarksFreshAndOpensRoute() {
        TabRouter.shared.cover = .newCourse(.city("부산"))
        TabRouter.shared.courseCreated(id: 42, city: "부산", days: 2)
        XCTAssertTrue(TabRouter.shared.isFreshCourse(42))
        XCTAssertFalse(TabRouter.shared.isFreshCourse(7))
        XCTAssertFalse(TabRouter.shared.isFreshCourse(nil))
        XCTAssertEqual(TabRouter.shared.justCreated, TabRouter.JustCreated(city: "부산", days: 2))
        XCTAssertEqual(TabRouter.shared.pendingCourseId, 42)
        XCTAssertEqual(TabRouter.shared.cover, .route)
    }

    func testClearFreshCourse() {
        XCTAssertFalse(TabRouter.shared.isFreshCourse(42))
        TabRouter.shared.courseCreated(id: 42, city: "부산", days: 2)
        XCTAssertTrue(TabRouter.shared.isFreshCourse(42))
        TabRouter.shared.clearFreshCourse()
        XCTAssertFalse(TabRouter.shared.isFreshCourse(42))
    }

    func testOpenTrendingOpensTrendingCover() {
        TabRouter.shared.openTrending()
        XCTAssertEqual(TabRouter.shared.cover, .trending)
    }
}
