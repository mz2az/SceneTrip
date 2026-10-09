import SceneApiClient
@testable import SceneTrip
import XCTest

/// 코스 편집 화면의 「바꾼 내용을 버릴까요?」 — 무엇이 「바뀐 것」인가 (MZ2AZ-369).
///
/// 기준은 **저장하면 서버에 갈 모양**이다(`RouteBridge.outgoing`). 화면을 열 때 떠 둔 것과
/// 「취소」 때의 것을 견준다. 여기서 잡아 두는 것은 두 방향이다 — 잃을 것이 있는데 안 묻는 것,
/// 그리고 잃을 것이 없는데(스탬프·코스 시작·되돌린 편집) 묻는 것.
final class RouteEditChangeTests: XCTestCase {
    private func stop(_ name: String, id: Int64, item: Int64? = nil, stay: Int = 30) -> RouteStop {
        RouteStop(
            place: PlaceSummary(id: id, name: name, latitude: 37.5, longitude: 127.0),
            serverItemId: item,
            stayMinutes: stay
        )
    }

    /// 저장된 코스 — 1일차에 두 곳, 서버 항목 id 가 붙어 있다.
    private func savedCourse() -> RouteCourse {
        RouteCourse(
            serverId: 1,
            title: "서울 하루",
            days: [RouteDay(stops: [stop("병원", id: 10, item: 100), stop("마트", id: 11, item: 101)])]
        )
    }

    private func changed(_ course: RouteCourse, since opened: RouteCourse) -> Bool {
        RouteBridge.changed(from: RouteBridge.outgoing(from: opened), to: course)
    }

    // MARK: 안 바뀐 것

    func testUntouchedCourseIsNotChanged() {
        let course = savedCourse()
        XCTAssertFalse(changed(course, since: course))
    }

    /// 바꿨다가 원래대로 되돌리면 묻지 않는다 — 건드렸는지가 아니라 **지금 다른지**를 본다.
    func testRevertedEditIsNotChanged() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops[0].stayMinutes = 90
        XCTAssertTrue(changed(course, since: opened))
        course.days[0].stops[0].stayMinutes = 30
        course.days[0].stops.swapAt(0, 1)
        course.days[0].stops.swapAt(0, 1)
        course.days.append(RouteDay())
        course.days.removeLast()
        XCTAssertFalse(changed(course, since: opened))
    }

    /// 방문 스탬프와 「코스 시작」·「여행 종료」는 화면 안에서 이미 서버에 갔다(다른 요청으로).
    /// 편집 완료 몸통에는 없으므로 「바꾼 내용」이 아니다.
    func testVisitStampAndRunningStateAreNotChanges() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops[0].visited = true
        course.isRunning = true
        XCTAssertFalse(changed(course, since: opened))
    }

    /// 그런 뒤에도 **편집한 것은 여전히 바뀐 것으로 남는다** — 「코스 시작」은 편집 내용을
    /// 저장하지 않는다.
    func testEditSurvivesStartingTheCourse() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops[1].stayMinutes = 60
        course.isRunning = true
        XCTAssertTrue(changed(course, since: opened))
    }

    /// 이름은 저장할 때 다듬는 모양으로 견준다 — 뒤에 공백만 붙인 것은 저장해도 같은 이름이다.
    func testTitleWhitespaceOnlyIsNotChanged() {
        let opened = savedCourse()
        var course = opened
        course.title = "  서울 하루 \n"
        XCTAssertFalse(changed(course, since: opened))
    }

    /// 저장에서 빠지는 초안 줄(`placeMissing`)은 있든 없든 서버 값이 같다.
    func testUnsavableDraftLineIsNotChanged() {
        let opened = savedCourse()
        var course = opened
        var ghost = stop("이름만 있는 곳", id: -1)
        ghost.placeMissing = true
        course.days[0].stops.append(ghost)
        XCTAssertFalse(changed(course, since: opened))
    }

    // MARK: 바뀐 것

    func testTitleEditIsChanged() {
        let opened = savedCourse()
        var course = opened
        course.title = "서울 이틀"
        XCTAssertTrue(changed(course, since: opened))
    }

    /// 이름을 비우면 저장할 때 기본 이름이 된다 — 원래 이름과 다르므로 바뀐 것이다.
    func testEmptiedTitleIsChanged() {
        let opened = savedCourse()
        var course = opened
        course.title = "   "
        XCTAssertEqual(RouteBridge.outgoing(from: course).title, RouteBridge.savedTitle(""))
        XCTAssertTrue(changed(course, since: opened))
    }

    func testReorderIsChanged() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops.swapAt(0, 1)
        XCTAssertTrue(changed(course, since: opened))
    }

    func testAddAndRemoveAreChanged() {
        let opened = savedCourse()
        var added = opened
        added.days[0].stops.append(stop("공원", id: 12))
        XCTAssertTrue(changed(added, since: opened))
        var removed = opened
        removed.days[0].stops.removeLast()
        XCTAssertTrue(changed(removed, since: opened))
    }

    /// 빈 일차도 기간을 바꾼다 — `days` 배열 길이가 곧 기간이다.
    func testAddedEmptyDayIsChanged() {
        let opened = savedCourse()
        var course = opened
        course.days.append(RouteDay())
        XCTAssertTrue(changed(course, since: opened))
    }

    /// 뺐다가 **같은 곳을 다시 담으면** 바뀐 것이다 — 서버 항목 id 가 떨어져 저장하면 새
    /// 장소로 들어가고 방문 체크가 날아간다. 화면에는 같아 보여도 서버에서는 다르다.
    func testRemovedThenReAddedPlaceIsChanged() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops.removeLast()
        course.days[0].stops.append(stop("마트", id: 11))
        XCTAssertTrue(changed(course, since: opened))
    }

    /// 직접 찍은 핀도 장소다.
    func testCustomPinIsChanged() {
        let opened = savedCourse()
        var course = opened
        var pin = stop("숙소", id: -5)
        pin.kind = .pin
        course.days[0].stops.append(pin)
        XCTAssertTrue(changed(course, since: opened))
    }

    // MARK: 새 코스

    //
    // 새 코스는 열 때의 모습이 아니라 **빈 틀인가**로 본다(`RouteBridge.isBlank`) — 서버에 아직
    // 아무것도 없으니 견줄 것은 빈 코스다.

    /// 「직접 짜기」가 넘기는 틀 — 기본 이름, 빈 일차들.
    private func frame(days: Int) -> RouteCourse {
        RouteCourse(title: RouteBridge.savedTitle(""), days: (0 ..< days).map { _ in RouteDay() })
    }

    /// 아무것도 담지 않았으면 바로 닫힌다. 이름을 비워도 저장하면 기본 이름 그대로다.
    func testNewEmptyFrameIsBlank() {
        var course = frame(days: 1)
        XCTAssertTrue(RouteBridge.isBlank(course))
        course.title = "  "
        XCTAssertTrue(RouteBridge.isBlank(course))
    }

    /// 일차 수·떠나는 날만 정한 틀(2박 3일)은 잃어도 되는 선택이다 — 일차를 더해도 마찬가지.
    func testNewMultiDayFrameIsBlank() {
        var course = frame(days: 3)
        course.startDate = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(RouteBridge.isBlank(course))
        course.days.append(RouteDay())
        XCTAssertTrue(RouteBridge.isBlank(course))
    }

    func testNewFrameWithOnePlaceIsNotBlank() {
        var course = frame(days: 3)
        course.days[1].stops.append(stop("병원", id: 10))
        XCTAssertFalse(RouteBridge.isBlank(course))
    }

    func testNewFrameWithNameIsNotBlank() {
        var course = frame(days: 1)
        course.title = "부산 하루"
        XCTAssertFalse(RouteBridge.isBlank(course))
    }

    /// **AI 초안은 손대지 않았어도 빈 것이 아니다** — 「취소」로 사라지는 것은 마법사의 답과
    /// 초안 전부다. 열 때의 모습과 견주면 이것을 묻지 않고 닫는다.
    func testUntouchedAIDraftIsNotBlank() {
        var draft = RouteCourse(
            title: "도깨비 1박 2일",
            days: [RouteDay(stops: [stop("병원", id: 10)]), RouteDay(stops: [stop("마트", id: 11)])]
        )
        draft.madeByAI = true
        XCTAssertFalse(RouteBridge.isBlank(draft))
        XCTAssertFalse(changed(draft, since: draft), "열 때와는 같다 — 그래서 새 코스는 이 기준을 쓰지 않는다")
    }

    // MARK: 화면 안에서 저장된 뒤

    /// 담은 곳으로 곧장 길찾기를 누르면 화면이 먼저 저장한다(`startTrip`). 그때 기준을 저장된
    /// 모습으로 옮기므로, 그 뒤로는 새로 바꾼 것만 바뀐 것이다.
    func testBaselineMovesAfterInScreenSave() {
        let opened = savedCourse()
        var course = opened
        course.days[0].stops.append(stop("공원", id: 12))
        XCTAssertTrue(changed(course, since: opened))
        // 서버가 돌려준 모습 — 새 항목에 id 가 붙었다.
        var saved = course
        saved.days[0].stops[2].serverItemId = 102
        XCTAssertFalse(changed(saved, since: saved))
        var later = saved
        later.days[0].stops[2].stayMinutes = 45
        XCTAssertTrue(changed(later, since: saved))
    }
}
