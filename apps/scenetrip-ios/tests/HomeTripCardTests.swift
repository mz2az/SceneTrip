@testable import SceneTrip
import XCTest

/// 홈 코스 카드의 모습 (MZ2AZ-372). 코스가 있는지 모르는 동안 「코스 만들기」 를 권하면 안 된다 —
/// 그 단추는 일정짜기(유료)의 입구이고, 코스가 있는 사람에게 「첫 여행」 이라고 말하는 것이다.
final class HomeTripCardTests: XCTestCase {
    private func card(
        hasTrip: Bool = false, _ list: RouteStore.CourseListState, hasCourses: Bool = false,
        tripsLoading: Bool = false
    ) -> HomeTabModel.TripCard {
        HomeTabModel.tripCard(
            hasTrip: hasTrip, courseList: list, hasCourses: hasCourses, tripsLoading: tripsLoading
        )
    }

    /// 앱을 막 켰다 — 코스 목록이 아직 안 왔다. 다른 조회가 얼마나 느리든 「받는 중」 이다.
    func testUnknownCourseListIsLoadingNotCreate() {
        XCTAssertEqual(card(.unknown), .loading)
        XCTAssertEqual(card(.unknown, tripsLoading: true), .loading)
    }

    /// 목록은 왔고 코스가 있다 — 그 코스의 상세를 받는 동안도 「받는 중」.
    func testCoursesKnownButDetailStillLoading() {
        XCTAssertEqual(card(.loaded, hasCourses: true, tripsLoading: true), .loading)
    }

    /// 목록을 받아 봤고 **정말 없을 때만** 「코스 만들기」.
    func testCreateOnlyWhenTheListSaysThereAreNone() {
        XCTAssertEqual(card(.loaded), .create)
        XCTAssertEqual(card(.loaded, tripsLoading: true), .create)
    }

    /// 못 받았으면 있는지 모른다 — 「코스 만들기」 가 아니라 못 받았다고 적는다.
    func testFailedListIsUnavailable() {
        XCTAssertEqual(card(.failed), .unavailable)
    }

    /// 카드에 넣을 코스가 있으면 언제나 그것을 그린다 — 다시 받는 중에도 깜빡이지 않는다.
    func testExistingTripStaysWhileReloading() {
        XCTAssertEqual(card(hasTrip: true, .loaded, hasCourses: true, tripsLoading: true), .filled)
        XCTAssertEqual(card(hasTrip: true, .unknown), .filled)
        XCTAssertEqual(card(hasTrip: true, .failed), .filled)
    }
}
