import SceneApiClient
@testable import SceneTrip
import XCTest

/// 길찾기 한도 안내의 **규칙** (MZ2AZ-366 D, 계획 `app-retry.md` §12). 서버는 1 분 창과 하루 창을 같은 `code`
/// 로 알린다 — 앱은 「언제 풀리나」 만으로 말을 고른다. 시험은 한국어로 돈다.
final class RouteNavLimitRulesTests: XCTestCase {
    private let now: TimeInterval = 5000

    func testWindowIsToldByHowSoonItLifts() {
        XCTAssertEqual(NavLimit.window(seconds: 1), .short)
        XCTAssertEqual(NavLimit.window(seconds: 60), .short, "1 분 창은 길어야 60초다")
        XCTAssertEqual(NavLimit.window(seconds: 61), .day)
        XCTAssertEqual(NavLimit.window(seconds: 30000), .day)
        XCTAssertEqual(NavLimit.window(seconds: nil), .unknown, "서버가 말하지 않았으면 「오늘」 이라 지어내지 않는다")
    }

    func testBlockStopsAskingUntilItLifts() {
        let block = NavLimit.Block(retryAfter: 40, now: now)
        XCTAssertEqual(block.window, .short)
        XCTAssertTrue(block.stopsAsking(at: now))
        XCTAssertTrue(block.stopsAsking(at: now + 39.9))
        XCTAssertFalse(block.stopsAsking(at: now + 40))
        XCTAssertTrue(block.isOver(at: now + 40))
        XCTAssertEqual(block.secondsLeft(at: now + 9.5), 31)
        XCTAssertNil(block.secondsLeft(at: now + 40))
    }

    /// `Retry-After` 가 없으면 언제 풀리는지 모른다 — 막지 않고(풀 길이 없다), 풀렸다고도 하지 않는다.
    func testUnknownRetryAfterDoesNotLock() {
        let block = NavLimit.Block(retryAfter: nil, now: now)
        XCTAssertEqual(block.window, .unknown)
        XCTAssertFalse(block.stopsAsking(at: now))
        XCTAssertFalse(block.isOver(at: now + 100_000))
        XCTAssertNil(block.secondsLeft(at: now))
    }

    /// 세 문구가 서로 다르고, 전부 지도 앱을 말한다. 모를 때는 「오늘」 을 말하지 않는다.
    func testMessages() {
        XCTAssertEqual(
            NavLimit.message(.short),
            "길찾기를 잠시 많이 썼어요. 1분 안에 다시 찾을 수 있어요. 지도 앱에서 이어서 볼 수도 있어요"
        )
        XCTAssertEqual(NavLimit.message(.day), "오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요")
        XCTAssertEqual(NavLimit.message(.unknown), "지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요")
        XCTAssertFalse(NavLimit.message(.unknown).contains("오늘"))
        for window in [NavLimit.Window.short, .day, .unknown] {
            XCTAssertTrue(NavLimit.message(window).contains("지도 앱"))
        }
    }

    // MARK: 응답 분류

    private func response(_ status: Int, code: String?, headers: [String: String] = [:]) -> Error {
        let url = URL(string: "http://localhost:8081/v1/navigation/next-leg")!
        let http = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
        let data = code.map { Data(#"{"code":"\#($0)","message":"x"}"#.utf8) }
        return ErrorResponse.error(status, data, http, URLError(.badServerResponse))
    }

    func testNavigationLimitCarriesRetryAfter() {
        XCTAssertEqual(
            RouteNavFailure(response(429, code: "NAVIGATION_LIMIT_REACHED", headers: ["Retry-After": "42"])),
            .limitReached(retryAfter: 42)
        )
        XCTAssertEqual(
            RouteNavFailure(response(429, code: "NAVIGATION_LIMIT_REACHED", headers: ["retry-after": "30000"])),
            .limitReached(retryAfter: 30000)
        )
        XCTAssertEqual(
            RouteNavFailure(response(429, code: "NAVIGATION_LIMIT_REACHED")), .limitReached(retryAfter: nil)
        )
        // 숫자가 아니면(HTTP 날짜 꼴) 모르는 것으로.
        XCTAssertEqual(
            RouteNavFailure(response(
                429, code: "NAVIGATION_LIMIT_REACHED", headers: ["Retry-After": "Fri, 09 Oct 2026 15:00:00 GMT"]
            )),
            .limitReached(retryAfter: nil)
        )
    }

    /// 분당 한도와 게이트웨이의 429 는 길찾기 한도가 아니다 — 넘기는 단추가 아니라 「다시 시도」.
    func testOther429sAreNotTheNavigationLimit() {
        XCTAssertEqual(
            RouteNavFailure(response(429, code: "RATE_LIMITED", headers: ["Retry-After": "3"])), .rateLimited
        )
        XCTAssertEqual(RouteNavFailure(response(429, code: nil)), .rateLimited)
        XCTAssertEqual(RouteNavFailure(response(429, code: "GUIDE_LIMIT_REACHED")), .other(status: 429))
        XCTAssertTrue(RouteNavFailure.rateLimited.canRetry)
        XCTAssertFalse(RouteNavFailure.rateLimited.isLimit)
    }

    /// 한도는 「다시 시도」 를 스스로 내지 않는다 — 풀린 뒤에만, 한도 안내가 정한다.
    func testLimitIsNotPlainlyRetryable() {
        XCTAssertFalse(RouteNavFailure.limitReached(retryAfter: 20).canRetry)
        XCTAssertTrue(RouteNavFailure.limitReached(retryAfter: nil).isLimit)
        XCTAssertEqual(RouteNavFailure.limitReached(retryAfter: 20).message, NavLimit.message(.short))
        XCTAssertEqual(RouteNavFailure.limitReached(retryAfter: 3000).message, NavLimit.message(.day))
        XCTAssertEqual(RouteNavFailure.limitReached(retryAfter: nil).message, NavLimit.message(.unknown))
    }
}

/// 한도의 **상태**와 여행 안내가 그것을 어떻게 타는가 — 걸리면 서버에 묻지 않고, 풀려도 저절로 묻지 않는다.
@MainActor
final class RouteNavLimitSessionTests: XCTestCase {
    /// 연속 시계(초).
    private var now: TimeInterval = 9000
    private var sleeps: [TimeInterval] = []
    private var wake = false
    /// 서버에 나간 길찾기 요청과, 차례로 돌려줄 답.
    private var sent: [NextLegRequest] = []
    private var replies: [Result<NextLeg, Error>] = []

    private func makeStore() -> NavLimitStore {
        NavLimitStore(environment: .init(
            now: { [unowned self] in now },
            sleep: { [unowned self] seconds in
                sleeps.append(seconds)
                while !wake, !Task.isCancelled {
                    await Task.yield()
                }
                wake = false
            }
        ))
    }

    private func makeTrip(_ store: NavLimitStore) -> TripSession {
        TripSession(limits: store) { [unowned self] request in
            sent.append(request)
            await Task.yield() // 진짜 요청처럼 한 번 쉰다
            return try replies.removeFirst().get()
        }
    }

    private func limited(retryAfter: Int?) -> Result<NextLeg, Error> {
        let url = URL(string: "http://localhost:8081/v1/navigation/next-leg")!
        let headers = retryAfter.map { ["Retry-After": String($0)] } ?? [:]
        let response = HTTPURLResponse(url: url, statusCode: 429, httpVersion: "HTTP/1.1", headerFields: headers)
        let data = Data(#"{"code":"NAVIGATION_LIMIT_REACHED","message":"x"}"#.utf8)
        return .failure(ErrorResponse.error(429, data, response, URLError(.badServerResponse)))
    }

    private var route: Result<NextLeg, Error> {
        .success(NextLeg(totalMinutes: 12, transfers: 0, guidanceLang: .ko, legs: []))
    }

    /// 가상 위치(서울시청)에서 먼 곳 — 시험 도중 도착 판정이 나지 않게.
    private func stop(_ name: String, item: Int64) -> RouteStop {
        var stop = RouteStop(place: PlaceSummary(id: item, name: name, latitude: 35.1587, longitude: 129.1604))
        stop.serverItemId = item
        return stop
    }

    private func settle(until done: () -> Bool) async {
        for _ in 0 ..< 5000 where !done() {
            await Task.yield()
        }
    }

    /// 요청이 끝나(또는 막혀) 실패·결과가 설 때까지.
    private func answered(_ trip: TripSession) async {
        await settle { !trip.asking && (trip.failure != nil || trip.result != nil) }
    }

    private func pass(_ seconds: TimeInterval, _ store: NavLimitStore) async {
        await settle { !self.sleeps.isEmpty }
        now += seconds
        wake = true
        await settle { !self.wake && store.state == .lifted }
    }

    // MARK: 상태

    func testStoreLocksThenLiftsOnItsOwn() async {
        let store = makeStore()
        XCTAssertFalse(store.stopsAsking)
        store.reach(retryAfter: 40)
        XCTAssertEqual(store.state, .reached(NavLimit.Block(retryAfter: 40, now: now)))
        XCTAssertTrue(store.stopsAsking)
        XCTAssertEqual(store.secondsLeft, 40)

        await pass(40, store)
        XCTAssertEqual(sleeps, [40])
        XCTAssertEqual(store.state, .lifted)
        XCTAssertFalse(store.stopsAsking)
        XCTAssertNil(store.secondsLeft)
    }

    /// 타이머가 늦어도(앱이 뒤에 있었다) 묻기 직전에 시계를 다시 본다.
    func testStoreLiftsWhenAskedAfterTheTime() {
        let store = makeStore()
        store.reach(retryAfter: 30000)
        now += 30000
        XCTAssertFalse(store.stopsAsking)
        XCTAssertEqual(store.state, .lifted)
    }

    func testUnknownRetryAfterNeverLocksTheStore() {
        let store = makeStore()
        store.reach(retryAfter: nil)
        XCTAssertFalse(store.stopsAsking)
        now += 100_000
        store.refresh()
        XCTAssertEqual(store.state, .reached(NavLimit.Block(retryAfter: nil, now: 9000)), "풀렸다고 지어내지 않는다")
    }

    func testForgetClearsAndCountsTheAccountChange() {
        let store = makeStore()
        store.reach(retryAfter: 30000)
        let before = store.epoch
        store.forget()
        XCTAssertEqual(store.state, .clear)
        XCTAssertEqual(store.epoch, before + 1)
        XCTAssertFalse(store.stopsAsking)
    }

    // MARK: 여행 안내

    /// 하루 한도 — 자동으로 다시 부르지 않고, 안내는 켜진 채이고, **다음 성지로 넘어가도 서버에 묻지 않는다.**
    func testDayLimitKeepsTheTripAndStopsAsking() async {
        let store = makeStore()
        let trip = makeTrip(store)
        defer { trip.end() }
        replies = [limited(retryAfter: 30000)]
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(trip)

        XCTAssertEqual(trip.failure, .limitReached(retryAfter: 30000))
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent.first?.itemId, 11)
        XCTAssertEqual(trip.phase, .guiding, "여행은 멈추지 않는다")
        XCTAssertEqual(trip.target?.place.name, "해운대")
        XCTAssertNil(trip.result)
        XCTAssertTrue(store.stopsAsking)

        // 「다시 시도」 — 막혀 있는 동안은 나가지 않는다.
        trip.retry()
        await answered(trip)
        XCTAssertEqual(sent.count, 1)
        XCTAssertTrue(trip.failure?.isLimit == true)

        // 「여기 도착함」 → 「다음 · 2번으로」 — 도착 처리는 되고, 다음 구간도 서버에 묻지 않고 한도 안내로 간다.
        var arrived: [String] = []
        trip.onArrived = { arrived.append($0.place.name) }
        trip.arriveNow()
        XCTAssertEqual(trip.phase, .arrived)
        XCTAssertEqual(arrived, ["해운대"])
        trip.stampDone()
        trip.start(to: stop("광안리", item: 12), number: 2, courseId: 7)
        await answered(trip)
        XCTAssertEqual(sent.count, 1, "뻔히 429 인 요청을 보내지 않는다")
        XCTAssertTrue(trip.failure?.isLimit == true)
        XCTAssertEqual(trip.phase, .guiding)
        XCTAssertEqual(trip.target?.place.name, "광안리")
    }

    /// 한도는 계정의 것 — 다른 코스의 편집 화면(새 `TripSession`)도 같은 한도를 본다.
    func testAnotherTripSeesTheSameLimit() async {
        let store = makeStore()
        let first = makeTrip(store)
        replies = [limited(retryAfter: 30000)]
        first.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(first)
        first.end()

        let second = makeTrip(store)
        defer { second.end() }
        second.start(to: stop("감천", item: 21), number: 1, courseId: 8)
        await answered(second)
        XCTAssertTrue(second.failure?.isLimit == true)
        XCTAssertEqual(sent.count, 1)
    }

    /// 1 분 창 — 풀려도 **저절로 다시 부르지 않는다.** 「다시 시도」 를 눌러야 나가고, 되면 한도가 걷힌다.
    func testShortLimitLiftsButWaitsForTheTap() async {
        let store = makeStore()
        let trip = makeTrip(store)
        defer { trip.end() }
        replies = [limited(retryAfter: 25), route]
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(trip)
        XCTAssertEqual(store.state, .reached(NavLimit.Block(retryAfter: 25, now: now)))

        await pass(25, store)
        XCTAssertEqual(store.state, .lifted)
        XCTAssertEqual(sent.count, 1, "풀렸다고 유료 요청을 저절로 내지 않는다")
        XCTAssertTrue(trip.failure?.isLimit == true)

        trip.retry()
        await settle { trip.result != nil }
        XCTAssertEqual(sent.count, 2)
        XCTAssertNil(trip.failure)
        XCTAssertEqual(trip.result?.totalMinutes, 12)
        XCTAssertEqual(store.state, .clear)
    }

    /// 풀리는 때를 모르면 막지 않는다 — 「다시 시도」 가 바로 서버에 묻는다.
    func testUnknownWindowAsksAgainRightAway() async {
        let store = makeStore()
        let trip = makeTrip(store)
        defer { trip.end() }
        replies = [limited(retryAfter: nil), route]
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(trip)
        XCTAssertEqual(trip.failure, .limitReached(retryAfter: nil))

        trip.retry()
        await settle { trip.result != nil }
        XCTAssertEqual(sent.count, 2)
        XCTAssertEqual(store.state, .clear)
    }

    // MARK: 한도가 아닌 실패

    private var providerDown: Result<NextLeg, Error> {
        .failure(ErrorResponse.error(503, nil, nil, URLError(.badServerResponse)))
    }

    /// 잠시 장애(503) — 「다시 시도」 는 요청을 한 번 더 낸다. 한도의 문이 다른 실패를 막지 않는다.
    func testProviderDownRetrySendsOnceMore() async {
        let store = makeStore()
        let trip = makeTrip(store)
        defer { trip.end() }
        replies = [providerDown, route]
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(trip)
        XCTAssertEqual(trip.failure, .providerDown)
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(store.state, .clear, "503 은 한도가 아니다")

        trip.retry()
        await settle { trip.result != nil }
        XCTAssertEqual(sent.count, 2)
        XCTAssertNil(trip.failure)
    }

    /// 위치가 연달아 와 길찾기가 두 번 예약돼도 요청은 한 번 — 앞의 것이 실패로 끝난 직후 뒤의 것이 또 내지 않는다.
    func testDoubleScheduledLoadAsksOnce() async {
        let store = makeStore()
        // 쉬지 않고 곧장 실패하는 요청 — 앞의 예약이 통째로 끝난 뒤에 뒤의 예약이 돈다(`asking` 이 막아 주지 않는 순서).
        let trip = TripSession(limits: store) { [unowned self] request in
            sent.append(request)
            return try providerDown.get()
        }
        defer { trip.end() }
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await answered(trip)
        // 같은 자리가 두 번 더 들어온다 — 실패가 서 있어 예약조차 되지 않는다.
        trip.locator.inject(latitude: 37.5665, longitude: 126.9780)
        trip.locator.inject(latitude: 37.5666, longitude: 126.9781)
        // 「다음」 을 눌러 실패를 비운 직후 위치가 또 온다 — `start` 와 `observe` 가 저마다 예약한다.
        trip.start(to: stop("광안리", item: 12), number: 2, courseId: 7)
        trip.locator.inject(latitude: 37.5667, longitude: 126.9782)
        await answered(trip)
        for _ in 0 ..< 200 {
            await Task.yield()
        }

        XCTAssertEqual(sent.map(\.itemId), [11, 12], "목적지마다 한 번씩만")
        XCTAssertEqual(trip.failure, .providerDown)
    }

    /// 기다리는 사이에 계정이 바뀌었다 — 앞 계정의 429 가 새 계정을 막지 않는다.
    func testLateLimitAfterAccountChangeIsDropped() async {
        let store = makeStore()
        var release = false
        let trip = TripSession(limits: store) { [unowned self] request in
            sent.append(request)
            while !release {
                await Task.yield()
            }
            return try limited(retryAfter: 30000).get()
        }
        defer { trip.end() }
        trip.start(to: stop("해운대", item: 11), number: 1, courseId: 7)
        await settle { self.sent.count == 1 }
        store.forget() // 로그아웃·다른 계정으로 로그인
        release = true
        await settle { !trip.asking }

        XCTAssertEqual(store.state, .clear)
        XCTAssertFalse(store.stopsAsking)
        XCTAssertNil(trip.failure)
    }
}
