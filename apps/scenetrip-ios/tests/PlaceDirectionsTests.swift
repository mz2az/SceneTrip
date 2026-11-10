import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 촬영지 상세 「길찾기」(MZ2AZ-532 → iOS MZ2AZ-547 G3) — 한 곳짜리 코스를 만들어 곧바로 여행 모드를 연다.
/// 비회원이면 로그인 뒤 이어서. 기준은 웹 `features/route/placeDirections.test.ts`. 서버·로그인 창은 전부 가짜다.
/// 웹의 `start_directions` 통계 이벤트는 웹 전용이라 옮기지 않았다.
@MainActor
final class PlaceDirectionsTests: XCTestCase {
    private let place = PlaceSummary(id: 42, name: "도깨비 방파제", address: "강릉시 주문진읍", latitude: 37.89, longitude: 128.83)

    // MARK: 제목

    func testTitleIsPlaceNamePlusGaneunGil() {
        XCTAssertEqual(PlaceDirectionsRules.title("도깨비 방파제"), "도깨비 방파제 가는 길")
    }

    func testTitleInEnglishIsTripTo() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .en
        XCTAssertEqual(PlaceDirectionsRules.title("도깨비 방파제"), "Trip to 도깨비 방파제")
    }

    func testTitleMaxIs50() {
        XCTAssertEqual(PlaceDirectionsRules.titleMax, 50)
    }

    /// 딱 50자면 자르지 않는다 — 「가」45 + 「 가는 길」5.
    func testTitleExactly50IsNotCut() {
        let name = String(repeating: "가", count: 45)
        let title = PlaceDirectionsRules.title(name)
        XCTAssertEqual(title, name + " 가는 길")
        XCTAssertEqual(title.count, 50)
    }

    /// 넘으면 장소 이름 끝을 말줄임(…)으로 잘라 50자 안에 둔다.
    func testLongKoreanTitleIsCutWithEllipsisWithin50() {
        let title = PlaceDirectionsRules.title(String(repeating: "가", count: 60))
        XCTAssertLessThanOrEqual(title.count, 50)
        XCTAssertTrue(title.hasSuffix("… 가는 길"), title)
        XCTAssertEqual(title, String(repeating: "가", count: 44) + "… 가는 길")
    }

    /// 잘린 끝의 빈칸은 말줄임 앞에서 지운다.
    func testCutTrimsTrailingSpaceBeforeEllipsis() {
        let name = String(repeating: "가", count: 43) + " " + String(repeating: "나", count: 10)
        XCTAssertEqual(PlaceDirectionsRules.title(name), String(repeating: "가", count: 43) + "… 가는 길")
    }

    func testLongEnglishTitleIsCutWithin50() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .en
        let title = PlaceDirectionsRules.title("Former Seoul Station Plaza and the Old Train Platform Café Street Market")
        XCTAssertLessThanOrEqual(title.count, 50)
        XCTAssertTrue(title.hasPrefix("Trip to Former Seoul Station"), title)
        XCTAssertTrue(title.hasSuffix("…"), title)
    }

    // MARK: 한 곳짜리 코스

    func testCourseIsOneDayOneStopOfThatPlace() {
        let course = PlaceDirectionsRules.course(for: place)
        XCTAssertNil(course.serverId)
        XCTAssertEqual(course.days.count, 1)
        XCTAssertEqual(course.stops.count, 1)
        XCTAssertEqual(course.stops.first?.place.id, 42)
        XCTAssertEqual(course.stops.first?.kind, .place)
        XCTAssertEqual(course.title, "도깨비 방파제 가는 길")
        XCTAssertFalse(course.madeByAI)
        XCTAssertFalse(course.isRunning)
    }

    // MARK: 다시 열어도 되는 코스인가

    func testIsDirectionsCourseOnlyForOneDayOneStopOfThatPlace() {
        XCTAssertTrue(PlaceDirectionsRules.isDirectionsCourse(saved(700, stops: [[42]]), of: 42))
        // 다른 장소
        XCTAssertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(701, stops: [[7]]), of: 42))
        // 사용자가 다른 곳을 더했다
        XCTAssertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(702, stops: [[42, 7]]), of: 42))
        // 일차가 둘
        XCTAssertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(703, stops: [[42], []]), of: 42))
        // 비었다
        XCTAssertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(704, stops: [[]]), of: 42))
    }

    // MARK: 목록(요약)에서 후보 찾기

    func testCandidatesAreRunningOneDayOnePlaceWithSameTitle() {
        let title = "도깨비 방파제 가는 길"
        let courses = [
            summary(1, title: title, days: 1, places: 1, running: true), // 후보
            summary(2, title: title, days: 1, places: 1, running: false), // 여행 중 아님
            summary(3, title: title, days: 2, places: 1, running: true), // 2일
            summary(4, title: title, days: 1, places: 2, running: true), // 2곳
            summary(5, title: "다른 코스", days: 1, places: 1, running: true), // 제목 다름
            summary(6, title: title, days: 1, places: 1, running: true), // 후보
        ]
        XCTAssertEqual(PlaceDirectionsRules.candidates(in: courses, for: place), [1, 6])
    }

    // MARK: 로그인한 사람

    func testCreatesStartsAndOpensTheCourse() async {
        let fake = FakeDirectionsDeps()
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 901))
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.created.first?.stops.map(\.place.id), [42])
        XCTAssertEqual(fake.created.first?.title, "도깨비 방파제 가는 길")
        XCTAssertEqual(fake.started.map(\.serverId), [901])
        XCTAssertEqual(fake.opened, [901])
        XCTAssertEqual(fake.signInAsked, 0)
    }

    /// 만든 코스를 장소 id 로 기억한다 — 상세를 닫았다 다시 열어도 같은 코스를 쓴다.
    func testRemembersMadeCourseAfterCreate() async {
        let fake = FakeDirectionsDeps()
        _ = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(fake.made[42], 901)
    }

    func testPressingTwiceWhileRunningCreatesOnceAndSecondIsBusy() async {
        let fake = FakeDirectionsDeps()
        let gate = DirectionsGate<Void>()
        fake.createGate = gate
        let directions = PlaceDirections(deps: fake)
        let first = Task { await directions.start(place) }
        await settle { gate.entered }
        let second = await directions.start(place)
        XCTAssertEqual(second, .busy)
        gate.open(())
        let firstOutcome = await first.value
        XCTAssertEqual(firstOutcome, .started(courseId: 901))
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.opened, [901])
    }

    func testCreateFailureReturnsItsMessageAndDoesNotStartOrOpen() async {
        let fake = FakeDirectionsDeps()
        fake.createFailure = "연결이 원활하지 않아요"
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .failed("연결이 원활하지 않아요"))
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
        XCTAssertNil(fake.made[42])
    }

    /// 만들었다는데 서버 id 가 없으면 열 코스가 없다 — 「코스를 불러오지 못했어요」.
    func testCreatedCourseWithoutServerIdFails() async {
        let fake = FakeDirectionsDeps()
        fake.createReturnsNoServerId = true
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .failed("코스를 불러오지 못했어요"))
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
        XCTAssertTrue(fake.made.isEmpty)
    }

    /// 시작이 실패하면 열지 않고, 다시 누르면 새로 만들지 않고 시작만 다시 한다.
    func testStartFailureThenRetryStartsOnlyWithoutCreatingAgain() async {
        let fake = FakeDirectionsDeps()
        fake.startFailures = ["시작하지 못했어요"]
        let directions = PlaceDirections(deps: fake)
        let firstOutcome = await directions.start(place)
        XCTAssertEqual(firstOutcome, .failed("시작하지 못했어요"))
        XCTAssertTrue(fake.opened.isEmpty)
        let secondOutcome = await directions.start(place)
        XCTAssertEqual(secondOutcome, .started(courseId: 901))
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.started.map(\.serverId), [901, 901])
        XCTAssertEqual(fake.opened, [901])
    }

    /// 시작이 실패한 코스를 다른 컨트롤러(상세를 다시 연 것)가 눌러도 만들지 않고 시작만 한다.
    func testStartFailureThenNewControllerStartsOnly() async {
        let fake = FakeDirectionsDeps()
        fake.startFailures = ["x"]
        _ = await PlaceDirections(deps: fake).start(place)
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 901))
        XCTAssertEqual(fake.created.count, 1)
    }

    // MARK: 이미 있는 「가는 길」 다시 쓰기

    /// 받아 둔 목록에 이 장소의 여행 중 1곳짜리 코스가 있으면 만들지도, 시작을 다시 하지도 않고 연다.
    func testExistingRunningCourseIsOpenedWithoutCreateOrStart() async {
        let fake = FakeDirectionsDeps()
        fake.server[700] = saved(700, stops: [[42]], running: true)
        fake.candidates = [700]
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 700))
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertEqual(fake.opened, [700])
    }

    /// 여행 중이 아닌 기존 「가는 길」은 시작만 하고 연다.
    func testExistingNotRunningCourseIsStartedThenOpened() async {
        let fake = FakeDirectionsDeps()
        fake.server[705] = saved(705, stops: [[42]], running: false)
        fake.candidates = [705]
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 705))
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertEqual(fake.started.map(\.serverId), [705])
        XCTAssertEqual(fake.opened, [705])
    }

    /// 후보 속을 읽어 보니 다른 장소의 코스면 새로 만든다.
    func testCandidateOfAnotherPlaceIsNotReused() async {
        let fake = FakeDirectionsDeps()
        fake.server[701] = saved(701, stops: [[7]], running: true)
        fake.candidates = [701]
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(fake.loaded, [701])
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.opened, [901])
        XCTAssertEqual(outcome, .started(courseId: 901))
    }

    /// 새 컨트롤러(상세를 닫았다 다시 연 것)라도 같은 장소면 새로 만들지 않는다 — 기억은 deps 의 `made`.
    func testNewControllerReusesRememberedCourse() async {
        let fake = FakeDirectionsDeps()
        _ = await PlaceDirections(deps: fake).start(place)
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 901))
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.opened, [901, 901])
    }

    /// 기억한 코스를 먼저 본다 — 목록 후보보다 앞.
    func testRememberedCourseIsCheckedBeforeListCandidates() async {
        let fake = FakeDirectionsDeps()
        fake.server[800] = saved(800, stops: [[42]], running: true)
        fake.server[700] = saved(700, stops: [[42]], running: true)
        fake.made[42] = 800
        fake.candidates = [700, 800]
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .started(courseId: 800))
        XCTAssertEqual(fake.loaded.first, 800)
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertEqual(fake.opened, [800])
    }

    /// 기억한 코스가 지워졌으면 기억을 버리고 새로 만든다(새 코스를 기억한다).
    func testDeletedRememberedCourseIsForgottenAndReplaced() async {
        let fake = FakeDirectionsDeps()
        fake.made[42] = 555 // 서버에 없다
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(fake.loaded, [555])
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(outcome, .started(courseId: 901))
        XCTAssertEqual(fake.made[42], 901)
    }

    /// 기억한 코스를 사용자가 고쳐 다른 곳을 더했으면 기억을 버린다 — 만들기가 실패해도 다시 기억하지 않는다.
    func testEditedRememberedCourseIsForgotten() async {
        let fake = FakeDirectionsDeps()
        fake.server[556] = saved(556, stops: [[42, 7]], running: true)
        fake.made[42] = 556
        fake.createFailure = "x"
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(outcome, .failed("x"))
        XCTAssertEqual(fake.loaded, [556])
        XCTAssertNil(fake.made[42])
        XCTAssertTrue(fake.opened.isEmpty)
    }

    // MARK: 비회원

    func testSignInClosedCancelsWithoutCreating() async {
        let fake = FakeDirectionsDeps(signedIn: false)
        fake.signInResult = false
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(fake.signInAsked, 1)
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
    }

    func testSignedInContinuesWithTheSamePlace() async {
        let fake = FakeDirectionsDeps(signedIn: false)
        fake.signInResult = true
        let outcome = await PlaceDirections(deps: fake).start(place)
        XCTAssertEqual(fake.signInAsked, 1)
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.created.first?.stops.first?.place.id, 42)
        XCTAssertEqual(fake.opened, [901])
        XCTAssertEqual(outcome, .started(courseId: 901))
    }

    /// 로그인 시트가 떠 있는 동안 또 눌러도 시트를 또 띄우지 않는다.
    func testPressWhileSignInSheetIsUpIsBusy() async {
        let fake = FakeDirectionsDeps(signedIn: false)
        let gate = DirectionsGate<Bool>()
        fake.signInGate = gate
        let directions = PlaceDirections(deps: fake)
        let first = Task { await directions.start(place) }
        await settle { gate.entered }
        let second = await directions.start(place)
        XCTAssertEqual(second, .busy)
        gate.open(true)
        _ = await first.value
        XCTAssertEqual(fake.signInAsked, 1)
        XCTAssertEqual(fake.created.count, 1)
    }

    /// 로그인을 기다리는 사이 화면을 떠났으면(dispose) 로그인돼도 만들지 않는다.
    func testDisposeWhileSignInPendingCancelsAfterSignIn() async {
        let fake = FakeDirectionsDeps(signedIn: false)
        let gate = DirectionsGate<Bool>()
        fake.signInGate = gate
        let directions = PlaceDirections(deps: fake)
        let pending = Task { await directions.start(place) }
        await settle { gate.entered }
        directions.dispose()
        gate.open(true)
        let outcome = await pending.value
        XCTAssertEqual(fake.signInAsked, 1)
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
    }

    // MARK: 요청 중에 화면을 떠나면

    /// 만들기 중에 떠났으면 여행 중으로 바꾸지 않고 열지 않는다(만든 코스는 기억해 둔다).
    func testDisposeDuringCreateDoesNotStartOrOpen() async {
        let fake = FakeDirectionsDeps()
        let gate = DirectionsGate<Void>()
        fake.createGate = gate
        let directions = PlaceDirections(deps: fake)
        let pending = Task { await directions.start(place) }
        await settle { gate.entered }
        directions.dispose()
        gate.open(())
        let outcome = await pending.value
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertEqual(fake.created.count, 1)
        XCTAssertEqual(fake.made[42], 901)
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
    }

    /// 기존 코스를 확인하는 중에 떠났어도 시작·열기를 하지 않는다.
    func testDisposeDuringExistingCheckDoesNotStartOrOpen() async {
        let fake = FakeDirectionsDeps()
        fake.server[702] = saved(702, stops: [[42]], running: false)
        fake.candidates = [702]
        let gate = DirectionsGate<Void>()
        fake.loadGate = gate
        let directions = PlaceDirections(deps: fake)
        let pending = Task { await directions.start(place) }
        await settle { gate.entered }
        directions.dispose()
        gate.open(())
        let outcome = await pending.value
        XCTAssertEqual(fake.loaded, [702])
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertTrue(fake.started.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
    }

    /// 떠난 뒤의 누름은 아무것도 하지 않는다.
    func testStartAfterDisposeDoesNothing() async {
        let fake = FakeDirectionsDeps()
        let directions = PlaceDirections(deps: fake)
        directions.dispose()
        let outcome = await directions.start(place)
        XCTAssertNotEqual(outcome, .started(courseId: 901))
        XCTAssertTrue(fake.created.isEmpty)
        XCTAssertTrue(fake.opened.isEmpty)
        XCTAssertEqual(fake.signInAsked, 0)
    }

    // MARK: 도우미

    /// 서버에 저장된(상세를 받은) 코스 — `stops` 는 일차마다 장소 id 목록.
    private func saved(_ id: Int64, stops: [[Int64]], running: Bool = false) -> RouteCourse {
        RouteCourse(
            serverId: id,
            title: "도깨비 방파제 가는 길",
            days: stops.map { ids in
                RouteDay(stops: ids.map { RouteStop(place: PlaceSummary(id: $0, name: "장소 \($0)", latitude: 37, longitude: 127)) })
            },
            isRunning: running,
            detailLoaded: true
        )
    }

    /// 코스 목록의 요약 — 일차 속이 비어 있고 장소 수는 서버 값(`RouteBridge.course(from:)` 모양).
    private func summary(_ id: Int64, title: String, days: Int, places: Int, running: Bool) -> RouteCourse {
        RouteCourse(
            serverId: id,
            title: title,
            days: Array(repeating: RouteDay(), count: days),
            isRunning: running,
            placeCountFromServer: places
        )
    }

    /// 가짜가 문 앞에 닿을 때까지 기다린다. 닿지 않으면(구현이 그 길로 가지 않으면) 잠시 뒤 그냥 넘어간다.
    private func settle(_ reached: () -> Bool) async {
        for _ in 0 ..< 1000 where !reached() {
            await Task.yield()
        }
    }
}

/// 늦게 끝나는 문 — 「진행 중에 또 누른다」·「도중에 떠난다」를 재기 위해.
@MainActor
final class DirectionsGate<T> {
    private var waiter: CheckedContinuation<T, Never>?
    private var value: T?
    private(set) var entered = false

    func wait() async -> T {
        entered = true
        if let value {
            return value
        }
        return await withCheckedContinuation { waiter = $0 }
    }

    func open(_ value: T) {
        self.value = value
        waiter?.resume(returning: value)
        waiter = nil
    }
}

/// `PlaceDirectionsDeps` 의 가짜. 만든 코스는 900+n 번이고, 시작하면 가짜 서버의 그 코스가 여행 중이 된다.
@MainActor
final class FakeDirectionsDeps: PlaceDirectionsDeps {
    var signedIn: Bool
    var signInResult = false
    var signInGate: DirectionsGate<Bool>?
    var signInAsked = 0

    var created: [RouteCourse] = []
    var createFailure: String?
    var createReturnsNoServerId = false
    var createGate: DirectionsGate<Void>?

    var started: [RouteCourse] = []
    /// 앞에서부터 하나씩 꺼내 실패로 돌려준다. 비면 성공.
    var startFailures: [String] = []

    var opened: [Int64] = []
    var made: [Int64: Int64] = [:]
    var candidates: [Int64] = []
    var server: [Int64: RouteCourse] = [:]
    var loaded: [Int64] = []
    var loadGate: DirectionsGate<Void>?

    init(signedIn: Bool = true) {
        self.signedIn = signedIn
    }

    func isSignedIn() -> Bool {
        signedIn
    }

    func askSignIn() async -> Bool {
        signInAsked += 1
        let ok: Bool = if let signInGate {
            await signInGate.wait()
        } else {
            signInResult
        }
        if ok {
            signedIn = true
        }
        return ok
    }

    func createCourse(_ course: RouteCourse) async -> Result<RouteCourse, PlaceDirectionsFailure> {
        created.append(course)
        if let createGate {
            await createGate.wait()
        }
        if let createFailure {
            return .failure(PlaceDirectionsFailure(message: createFailure))
        }
        var saved = course
        if createReturnsNoServerId {
            return .success(saved)
        }
        let id = Int64(900 + created.count)
        saved.serverId = id
        saved.detailLoaded = true
        server[id] = saved
        return .success(saved)
    }

    func startCourse(_ course: RouteCourse) async -> PlaceDirectionsStep {
        started.append(course)
        if !startFailures.isEmpty {
            return .failed(startFailures.removeFirst())
        }
        if let id = course.serverId, var now = server[id] {
            now.isRunning = true
            server[id] = now
        }
        return .ok
    }

    func openTrip(_ courseId: Int64) {
        opened.append(courseId)
    }

    func existingCandidates(_: PlaceSummary) -> [Int64] {
        candidates
    }

    func loadCourse(_ courseId: Int64) async -> RouteCourse? {
        loaded.append(courseId)
        if let loadGate {
            await loadGate.wait()
        }
        return server[courseId]
    }
}
