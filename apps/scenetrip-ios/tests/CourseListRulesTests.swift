import SceneApiClient
@testable import SceneTrip
import XCTest

/// 코스 목록(「내 여행」)의 규칙 — 계획 docs/project/plans/ios-web-parity.md L1–L5 (MZ2AZ-547 P7).
///
/// 웹 기준은 `features/proto/ProtoCourse.tsx`. 시험은 한국어로 돈다(`tr` 이 한국어 열쇠를 그대로 돌려준다).
final class CourseListRulesTests: XCTestCase {
    // MARK: 만들기 도우미

    private func place(_ id: Int64, image: String? = nil) -> PlaceSummary {
        PlaceSummary(id: id, name: "곳\(id)", latitude: 37.5, longitude: 127.0, imageUrl: image)
    }

    private func stop(_ id: Int64, image: String? = nil) -> RouteStop {
        RouteStop(place: place(id, image: image))
    }

    private func course(_ serverId: Int64?, _ title: String = "코스", running: Bool = false) -> RouteCourse {
        RouteCourse(serverId: serverId, title: title, days: [RouteDay()], isRunning: running)
    }

    // MARK: 여러 개 고르기 — 상태

    /// 처음에는 꺼져 있다 — 고른 것이 「빈 집합」이 아니라 nil 이다.
    func testPickingStartsOff() {
        let picking = CourseListPicking()
        XCTAssertFalse(picking.isOn)
        XCTAssertNil(picking.picked)
        XCTAssertEqual(picking.count, 0)
        XCTAssertFalse(picking.isPicked("s1"))
    }

    /// 꾹 누르면 켜지고 그 줄이 골라진다.
    func testHoldTurnsOnAndPicksThatRow() {
        var picking = CourseListPicking()
        picking.hold("s1")
        XCTAssertTrue(picking.isOn)
        XCTAssertEqual(picking.picked, ["s1"])
        XCTAssertEqual(picking.count, 1)
        XCTAssertTrue(picking.isPicked("s1"))
        XCTAssertFalse(picking.isPicked("s2"))
    }

    /// 이미 켜진 동안 다른 줄을 꾹 눌러도 그대로다.
    func testHoldWhileOnChangesNothing() {
        var picking = CourseListPicking()
        picking.hold("s1")
        picking.hold("s2")
        XCTAssertEqual(picking.picked, ["s1"])
        XCTAssertEqual(picking.count, 1)
    }

    /// 꺼져 있을 때 누르기는 아무 일도 없다(그때는 코스를 연다).
    func testToggleWhileOffDoesNothing() {
        var picking = CourseListPicking()
        picking.toggle("s1")
        XCTAssertFalse(picking.isOn)
        XCTAssertNil(picking.picked)
    }

    /// 켜진 동안 누르면 체크가 뒤집힌다.
    func testToggleWhileOnFlipsCheck() {
        var picking = CourseListPicking()
        picking.hold("s1")
        picking.toggle("s2")
        XCTAssertEqual(picking.picked, ["s1", "s2"])
        picking.toggle("s1")
        XCTAssertEqual(picking.picked, ["s2"])
        XCTAssertFalse(picking.isPicked("s1"))
        XCTAssertTrue(picking.isPicked("s2"))
    }

    /// 마지막 하나를 풀어도 꺼지지 않는다 — 켜진 채 0개.
    func testUntogglingLastKeepsModeOnWithZero() {
        var picking = CourseListPicking()
        picking.hold("s1")
        picking.toggle("s1")
        XCTAssertTrue(picking.isOn)
        XCTAssertEqual(picking.picked, [])
        XCTAssertEqual(picking.count, 0)
    }

    /// 꺼져 있거나 목록이 비면 「다 골랐다」가 아니다.
    func testAllPickedFalseWhenOffOrListEmpty() {
        var picking = CourseListPicking()
        XCTAssertFalse(picking.allPicked(["s1"]), "꺼져 있는데 다 골랐다고 했다")
        XCTAssertFalse(picking.allPicked([]), "꺼진 채 빈 목록")

        picking.hold("s1")
        XCTAssertFalse(picking.allPicked([]), "목록이 비었는데 다 골랐다고 했다")
    }

    func testAllPickedReflectsList() {
        var picking = CourseListPicking()
        picking.hold("s1")
        XCTAssertTrue(picking.allPicked(["s1"]))
        XCTAssertFalse(picking.allPicked(["s1", "s2"]))
        picking.toggle("s2")
        XCTAssertTrue(picking.allPicked(["s1", "s2"]))
    }

    /// [전체 선택] 은 목록 전부를, 다 고른 뒤 [선택 해제] 는 모두 풀되 켜진 채로 둔다.
    func testToggleAllPicksAllThenClearsButStaysOn() {
        let keys = ["s1", "s2", "s3"]
        var picking = CourseListPicking()
        picking.hold("s2")

        picking.toggleAll(keys)
        XCTAssertEqual(picking.picked, Set(keys))
        XCTAssertTrue(picking.allPicked(keys))

        picking.toggleAll(keys)
        XCTAssertTrue(picking.isOn, "선택 해제가 고르기를 껐다")
        XCTAssertEqual(picking.picked, [])
        XCTAssertEqual(picking.count, 0)
    }

    /// [취소] 는 끈다.
    func testCancelTurnsOff() {
        var picking = CourseListPicking()
        picking.hold("s1")
        picking.toggle("s2")
        picking.cancel()
        XCTAssertFalse(picking.isOn)
        XCTAssertNil(picking.picked)
        XCTAssertEqual(picking.count, 0)
    }

    /// 목록이 바뀌면 없어진 줄의 열쇠를 뺀다.
    func testKeepOnlyDropsMissingKeys() {
        var picking = CourseListPicking()
        picking.hold("s1")
        picking.toggle("s2")
        picking.toggle("s3")
        picking.keep(only: ["s2", "s9"])
        XCTAssertTrue(picking.isOn)
        XCTAssertEqual(picking.picked, ["s2"])
    }

    /// 꺼져 있으면 목록이 바뀌어도 꺼진 채다(빈 집합으로 켜지면 안 된다).
    func testKeepOnlyWhileOffStaysOff() {
        var picking = CourseListPicking()
        picking.keep(only: ["s1", "s2"])
        XCTAssertFalse(picking.isOn)
        XCTAssertNil(picking.picked)
    }

    // MARK: 글자

    func testHeaderTitle() {
        var picking = CourseListPicking()
        XCTAssertEqual(CourseListText.headerTitle(picking), "내 여행")

        picking.hold("s1")
        picking.toggle("s2")
        picking.toggle("s3")
        XCTAssertEqual(CourseListText.headerTitle(picking), "3개 선택")

        picking.toggleAll(["s1", "s2", "s3"])
        XCTAssertEqual(CourseListText.headerTitle(picking), "코스 선택", "켜진 채 0개")
    }

    func testAllButton() {
        XCTAssertEqual(CourseListText.allButton(allPicked: false), "전체 선택")
        XCTAssertEqual(CourseListText.allButton(allPicked: true), "선택 해제")
    }

    func testDeleteButton() {
        XCTAssertEqual(CourseListText.deleteButton(count: 2, removing: false), "2개 삭제")
        XCTAssertEqual(CourseListText.deleteButton(count: 2, removing: true), "지우는 중…")
    }

    func testBulkConfirmTitle() {
        XCTAssertEqual(CourseListText.bulkConfirmTitle(count: 2), "코스 2개를 지울까요?")
    }

    /// 여행 중이면 「N일차」 가 앞에 붙는다.
    func testSubtitleRunning() {
        XCTAssertEqual(
            CourseListText.subtitle(isRunning: true, currentDayNo: 2, dayCount: 2, placeCount: 5),
            "2일차 · 1박 2일 · 5곳"
        )
    }

    /// 여행 중인데 몇 일차인지 모르면 1일차.
    func testSubtitleRunningUnknownDayIsDayOne() {
        XCTAssertEqual(
            CourseListText.subtitle(isRunning: true, currentDayNo: nil, dayCount: 3, placeCount: 4),
            "1일차 · 2박 3일 · 4곳"
        )
    }

    /// 다가오는 여행에는 일차가 없다. 하루면 당일치기.
    func testSubtitleUpcomingHasNoDayPart() {
        XCTAssertEqual(
            CourseListText.subtitle(isRunning: false, currentDayNo: nil, dayCount: 1, placeCount: 3),
            "당일치기 · 3곳"
        )
        XCTAssertEqual(
            CourseListText.subtitle(isRunning: false, currentDayNo: 2, dayCount: 3, placeCount: 7),
            "2박 3일 · 7곳",
            "여행 중이 아닌데 일차가 붙었다"
        )
    }

    // MARK: 열쇠

    func testKeyUsesServerId() {
        XCTAssertEqual(CourseListRules.key(course(123)), "s123")
    }

    /// 서버 id 가 같으면 목록을 다시 받아 화면 id(UUID)가 바뀌어도 열쇠가 같다.
    func testKeySurvivesRefetch() {
        XCTAssertEqual(CourseListRules.key(course(7, "앞")), CourseListRules.key(course(7, "뒤")))
    }

    /// 저장 전 코스는 서로 다른 열쇠를 갖고, 같은 코스는 늘 같은 열쇠다.
    func testUnsavedKeysAreDistinctAndStable() {
        let first = course(nil, "가")
        let second = course(nil, "나")
        XCTAssertNotEqual(CourseListRules.key(first), CourseListRules.key(second))
        XCTAssertEqual(CourseListRules.key(first), CourseListRules.key(first))
        XCTAssertFalse(CourseListRules.key(first).isEmpty)
        XCTAssertNotEqual(CourseListRules.key(first), CourseListRules.key(course(1)))
    }

    // MARK: 묶음

    func testSectionsSplitAndKeepOrder() {
        let list = [
            course(1, "다가옴1"),
            course(2, "여행중1", running: true),
            course(3, "다가옴2"),
            course(4, "여행중2", running: true),
        ]
        let sections = CourseListRules.sections(list)
        XCTAssertEqual(sections.running.map(\.title), ["여행중1", "여행중2"])
        XCTAssertEqual(sections.upcoming.map(\.title), ["다가옴1", "다가옴2"])
    }

    // MARK: 지울 코스

    /// 고른 순서가 아니라 목록 순서대로, 고른 것만.
    func testDoomedInListOrderOnlyPicked() {
        let list = [course(1, "가"), course(2, "나"), course(3, "다"), course(4, "라")]
        var picking = CourseListPicking()
        picking.hold("s4")
        picking.toggle("s1")
        picking.toggle("s3")

        XCTAssertEqual(CourseListRules.doomed(list, picking: picking).map(\.title), ["가", "다", "라"])
    }

    func testDoomedEmptyWhenOff() {
        let list = [course(1), course(2)]
        XCTAssertTrue(CourseListRules.doomed(list, picking: CourseListPicking()).isEmpty)
    }

    // MARK: 작품 줄

    func testWorksFirstTitlePerStopDedupedMaxTwo() {
        let stops = [stop(1), stop(2), stop(3), stop(4)]
        let titles: [Int64: String] = [1: "도깨비", 2: "도깨비", 3: "눈물의 여왕", 4: "더 글로리"]
        let works = CourseListRules.works(stops) { titles[$0.place.id] }
        XCTAssertEqual(works, ["도깨비", "눈물의 여왕"])
    }

    func testWorksSkipsUnknownAndEmpty() {
        let stops = [stop(1), stop(2), stop(3)]
        let titles: [Int64: String] = [2: "", 3: "도깨비"]
        let works = CourseListRules.works(stops) { titles[$0.place.id] }
        XCTAssertEqual(works, ["도깨비"])
    }

    func testWorksEmptyWhenNoneKnown() {
        XCTAssertEqual(CourseListRules.works([stop(1), stop(2)]) { _ in nil }, [])
        XCTAssertEqual(CourseListRules.workLimit, 2)
    }

    // MARK: 줄 사진

    func testPhotoThumbnailWins() {
        let stops = [stop(1, image: "https://img.example/stop.jpg")]
        XCTAssertEqual(
            CourseListRules.photo(thumbnail: "https://img.example/thumb.jpg", stops: stops),
            "https://img.example/thumb.jpg"
        )
    }

    func testPhotoFallsBackToFirstStopWithImage() {
        let stops = [stop(1), stop(2, image: "https://img.example/b.jpg"), stop(3, image: "https://img.example/c.jpg")]
        XCTAssertEqual(CourseListRules.photo(thumbnail: nil, stops: stops), "https://img.example/b.jpg")
    }

    func testPhotoNilWhenNothing() {
        XCTAssertNil(CourseListRules.photo(thumbnail: nil, stops: [stop(1), stop(2)]))
        XCTAssertNil(CourseListRules.photo(thumbnail: nil, stops: []))
    }

    // MARK: 하나씩 지우기

    /// 앞의 지우기가 끝나야 다음을 부른다 — 동시에 둘이 돌면 안 된다.
    func testRemoveOneByOneIsSequentialInOrder() async {
        let recorder = DeleteRecorder()
        let list = [course(1, "가"), course(2, "나"), course(3, "다")]

        await CourseListRules.removeOneByOne(list) { doomed in
            await recorder.start(doomed.title)
            await Task.yield()
            await Task.yield()
            await recorder.end(doomed.title)
        }

        let events = await recorder.events
        XCTAssertEqual(events, ["시작 가", "끝 가", "시작 나", "끝 나", "시작 다", "끝 다"])
        let maxInFlight = await recorder.maxInFlight
        XCTAssertEqual(maxInFlight, 1, "지우기가 겹쳐 돌았다")
    }

    func testRemoveOneByOneWithEmptyListCallsNothing() async {
        let recorder = DeleteRecorder()
        await CourseListRules.removeOneByOne([]) { doomed in
            await recorder.start(doomed.title)
        }
        let events = await recorder.events
        XCTAssertEqual(events, [])
    }
}

/// 지우기 호출의 시작·끝을 차례로 적는다.
private actor DeleteRecorder {
    private(set) var events: [String] = []
    private(set) var maxInFlight = 0
    private var inFlight = 0

    func start(_ name: String) {
        events.append("시작 \(name)")
        inFlight += 1
        maxInFlight = max(maxInFlight, inFlight)
    }

    func end(_ name: String) {
        events.append("끝 \(name)")
        inFlight -= 1
    }
}
