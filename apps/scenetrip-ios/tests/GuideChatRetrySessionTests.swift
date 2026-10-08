import SceneApiClient
@testable import SceneTrip
import XCTest

/// 멱등 키를 실은 챗봇 턴이 **실제로 무엇을 몇 번, 몇 초 뒤에 보내는가** (MZ2AZ-366 PR B).
///
/// 가짜 안쪽 세션과 가짜 시계로 순서 전체를 본다 — 같은 키·같은 본문으로 다시 나가는지(바이트 비교), 자동
/// 한 번 뒤 멈추는지, `409 처리 중` 을 3초마다 되묻다 상한·벽에서 멈추는지, 서버가 키를 모르면 한 번만인지.
final class GuideChatRetrySessionTests: XCTestCase {
    private let clock = RetryFakeClock()
    private let results = RetryResults()
    private let ledger = RateLimitLedger()
    private let keyClock = IdempotencyClock()
    private let key = "0A1B2C3D-0000-4000-8000-000000000001"
    private let inProgress = FakeReply.status(409, code: "IDEMPOTENCY_IN_PROGRESS")

    /// 진짜 요청 모양 — 위치와 화면 상태가 든 본문.
    private lazy var body: Data = {
        let request = GuideChatRequest(
            sessionId: UUID(), latitude: 37.5663, longitude: 126.9779,
            messages: [GuideMessage(role: .user, content: "근처 카페 알려줘")],
            context: GuideContext(stops: [
                GuideStop(number: 1, name: "덕수궁", latitude: 37.5658, longitude: 126.9752, visited: false),
            ])
        )
        return (try? CodableHelper.encode(request).get()) ?? Data()
    }()

    private func chat(key: String?) -> URLRequest {
        var request = URLRequest(url: URL(string: "http://localhost:8081/v1/guide/chat")!)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("en", forHTTPHeaderField: "Accept-Language")
        if let key {
            request.setValue(key, forHTTPHeaderField: "Idempotency-Key")
        }
        return request
    }

    /// 서버가 요청 한도를 안다(= 멱등 키도 안다) — 응답에서 `RateLimit-*` 를 본 것으로 해 둔다.
    private func serverKnowsKeys() {
        let response = HTTPURLResponse(
            url: URL(string: "http://localhost:8081/v1/contents")!, statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["RateLimit-Limit": "120", "RateLimit-Remaining": "119", "RateLimit-Reset": "30"]
        )!
        ledger.record(path: "/v1/contents", response: response, now: clock.now)
    }

    @discardableResult
    private func run(
        _ inner: RetryFakeSession, key: String? = "0A1B2C3D-0000-4000-8000-000000000001",
        fault: @escaping (URLRequest) -> NetFault.Injection? = { _ in nil }
    ) -> URLSessionDataTaskProtocol {
        inner.clock = clock
        let session = RetryingSession(
            inner: inner, environment: clock.environment(fault: fault), ledger: ledger, keyClock: keyClock
        )
        let results = results
        let task = session.dataTaskFromProtocol(with: chat(key: key)) { _, response, error in
            results.statuses.append((response as? HTTPURLResponse)?.statusCode)
            results.errors.append((error as? URLError)?.code)
        }
        task.resume()
        return task
    }

    /// 나간 요청이 전부 **같은 키, 같은 본문(바이트)**인가.
    private func assertSameTurn(_ inner: RetryFakeSession, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(body.isEmpty, file: file, line: line)
        for sent in inner.requests {
            XCTAssertEqual(sent.value(forHTTPHeaderField: "Idempotency-Key"), key, file: file, line: line)
            XCTAssertEqual(sent.httpBody, body, "본문이 한 바이트도 달라지면 서버는 422 다", file: file, line: line)
            XCTAssertEqual(sent.value(forHTTPHeaderField: "Accept-Language"), "en", file: file, line: line)
            XCTAssertEqual(sent.url, inner.requests[0].url, file: file, line: line)
        }
    }

    // MARK: 자동 한 번

    func testLostTurnIsResentOnceWithTheSameKeyAndBytes() {
        serverKnowsKeys()
        let inner = RetryFakeSession([.error(.networkConnectionLost), .status(200)])
        run(inner)
        XCTAssertEqual(inner.requests.count, 2)
        XCTAssertEqual(clock.delays, [1])
        XCTAssertEqual(results.statuses, [200])
        assertSameTurn(inner)
    }

    func testItStopsAfterOneAutomaticRetry() {
        serverKnowsKeys()
        for reply in [FakeReply.status(503), .status(500), .status(502), .error(.timedOut)] {
            let inner = RetryFakeSession([reply, reply, .status(200)])
            run(inner)
            XCTAssertEqual(inner.requests.count, 2, "\(reply)")
            assertSameTurn(inner)
        }
        XCTAssertEqual(clock.delays, [1, 1, 1, 1])
        XCTAssertEqual(results.statuses, [503, 500, 502, nil], "마지막 실패가 그대로 올라간다 — 화면이 단추를 낸다")
    }

    func testEachAttemptWaitsFiftySeconds() {
        serverKnowsKeys()
        let inner = RetryFakeSession([.status(200)])
        run(inner)
        XCTAssertEqual(inner.requests[0].timeoutInterval, 50)
    }

    func testClientErrorsAndThePaidLimitAreSentOnce() {
        serverKnowsKeys()
        let replies: [FakeReply] = [
            .status(400, code: "INVALID_PARAMETER"), .status(401, code: "SIGN_IN_REQUIRED"),
            .status(422, code: "IDEMPOTENCY_KEY_REUSED"),
            .status(429, code: "GUIDE_LIMIT_REACHED", headers: ["Retry-After": "1800"]),
            .error(.notConnectedToInternet),
        ]
        for reply in replies {
            let inner = RetryFakeSession([reply, .status(200)])
            run(inner)
            XCTAssertEqual(inner.requests.count, 1, "\(reply)")
        }
        XCTAssertEqual(clock.delays, [])
        XCTAssertEqual(results.statuses, [400, 401, 422, 429, nil])
    }

    func testRateLimitIsWaitedForThenSentWithTheSameKey() {
        serverKnowsKeys()
        let inner = RetryFakeSession([
            .status(429, code: "RATE_LIMITED", headers: ["Retry-After": "4"]), .status(200),
        ])
        run(inner)
        XCTAssertEqual(clock.delays, [4])
        XCTAssertEqual(results.statuses, [200])
        assertSameTurn(inner)
    }

    // MARK: 스위치

    /// 서버가 키를 아는지 확인되지 않았다 — 키는 실려 나가지만 **한 번만** 나간다(전과 같다).
    func testWithoutProofTheServerKeepsKeysTheTurnIsSentOnce() {
        for reply in [FakeReply.error(.networkConnectionLost), .status(503), .status(500), inProgress] {
            let inner = RetryFakeSession([reply, .status(200)])
            run(inner)
            XCTAssertEqual(inner.requests.count, 1, "\(reply)")
            XCTAssertEqual(inner.requests[0].value(forHTTPHeaderField: "Idempotency-Key"), key)
        }
        XCTAssertEqual(clock.delays, [])
    }

    /// 서버는 키를 아는데 요청에 키가 없다 — 다시 보내면 두 번 처리된다.
    func testAChatWithoutAKeyIsSentOnce() {
        serverKnowsKeys()
        let inner = RetryFakeSession([.error(.networkConnectionLost), .status(200)])
        run(inner, key: nil)
        XCTAssertEqual(inner.requests.count, 1)
    }

    // MARK: 409 처리 중

    /// 응답을 잃고 다시 보냈더니 서버가 아직 처리 중 — 3초마다 같은 키로 묻다 저장된 답을 받는다.
    func testLostThenInProgressIsPolledUntilTheStoredAnswer() {
        serverKnowsKeys()
        let inner = RetryFakeSession([.error(.timedOut), inProgress, inProgress, inProgress, .status(200)])
        run(inner)
        XCTAssertEqual(clock.delays, [1, 3, 3, 3])
        XCTAssertEqual(inner.requests.count, 5)
        XCTAssertEqual(results.statuses, [200], "completion 은 한 번")
        assertSameTurn(inner)
    }

    func testPollingStopsBeforeTheServerForgetsTheKey() {
        serverKnowsKeys()
        let inner = RetryFakeSession(Array(repeating: inProgress, count: 40))
        let started = clock.now
        run(inner)
        // 3·6·…·54초에 묻고 멈춘다 — 서버가 처리 중인 키를 죽은 것으로 보는 1 분 전이다.
        XCTAssertEqual(inner.requests.count, 19, "처음 한 번 + 되묻기 열여덟 번")
        XCTAssertEqual(clock.delays, Array(repeating: 3, count: 18))
        XCTAssertEqual(clock.now.timeIntervalSince(started), 54)
        XCTAssertEqual(results.statuses, [409], "화면은 「오래 걸려 멈췄어요」 와 「다시 시도」")
        assertSameTurn(inner)
    }

    /// 요청 하나가 오래 걸려도 마찬가지다 — 횟수가 아니라 **키를 처음 보낸 때부터의 시각**으로 끊는다.
    func testSlowPollsStopAtTheSameDeadline() {
        serverKnowsKeys()
        let inner = RetryFakeSession(Array(repeating: inProgress, count: 40))
        inner.seconds = 10 // 요청 하나에 10초 — 10초 + 3초씩 흐른다
        run(inner)
        XCTAssertEqual(results.statuses, [409])
        XCTAssertEqual(inner.requests.count, 5, "0·13·26·39·52초에 나가고, 다음은 65초라 묻지 않는다")
        XCTAssertEqual(clock.delays, [3, 3, 3, 3])
    }

    /// **「다시 시도」 는 새 요청이지만 같은 키다 — 시각은 이어서 센다.** 요청마다 새로 세면, 55초에 멈춘 뒤 누른
    /// 단추가 다시 55초를 되묻다 서버의 1 분 경계를 넘긴다(그때 서버가 새로 처리한다 — 모델 두 번).
    func testARetryOfTheSameKeyDoesNotPollPastTheDeadline() {
        serverKnowsKeys()
        run(RetryFakeSession(Array(repeating: inProgress, count: 40))) // 54초에 멈췄다
        clock.now.addTimeInterval(4) // 58초 — 사람이 「다시 시도」 를 눌렀다
        clock.delays = []

        let again = RetryFakeSession([inProgress, .status(200)])
        run(again)
        XCTAssertEqual(again.requests.count, 1, "한 번만 나간다 — 되묻지 않는다")
        XCTAssertEqual(clock.delays, [])
        XCTAssertEqual(results.statuses, [409, 409])
    }

    /// 단추를 일찍 눌렀으면 남은 시간만큼만 묻는다.
    func testAnEarlyRetryPollsOnlyForWhatIsLeft() {
        serverKnowsKeys()
        let first = RetryFakeSession([.status(503), .status(503)])
        first.seconds = 20
        run(first) // 20초에 503 → 1초 쉬고 → 41초에 503. 단추가 뜬다
        clock.delays = []

        run(RetryFakeSession(Array(repeating: inProgress, count: 40)))
        XCTAssertEqual(clock.delays, [3, 3, 3, 3], "44·47·50·53초 — 그다음은 56초라 묻지 않는다")
        XCTAssertEqual(results.statuses, [503, 409])
    }

    /// 새 키는 처음부터 센다.
    func testAnotherKeyHasItsOwnClock() {
        serverKnowsKeys()
        run(RetryFakeSession(Array(repeating: inProgress, count: 40)))
        clock.delays = []
        run(RetryFakeSession([inProgress, inProgress, .status(200)]), key: "0A1B2C3D-0000-4000-8000-000000000002")
        XCTAssertEqual(clock.delays, [3, 3])
        XCTAssertEqual(results.statuses, [409, 200])
    }

    /// 분당 한도로 거절된 요청은 서버에 키를 남기지 않는다 — 기다린 시간은 키의 나이에 들지 않는다.
    func testARateLimitedSendDoesNotAgeTheKey() {
        serverKnowsKeys()
        let inner = RetryFakeSession([
            .status(429, code: "RATE_LIMITED", headers: ["Retry-After": "50"]), inProgress, inProgress, .status(200),
        ])
        run(inner)
        XCTAssertEqual(clock.delays, [50, 3, 3])
        XCTAssertEqual(results.statuses, [200])
    }

    /// 되묻던 턴이 실패로 끝났다(서버가 키를 지웠다) — 자동 한 번을 이미 썼으면 그대로 올린다.
    func testFailureWhilePollingAfterTheRetryGoesUp() {
        serverKnowsKeys()
        let inner = RetryFakeSession([.error(.timedOut), inProgress, .status(503), .status(200)])
        run(inner)
        XCTAssertEqual(inner.requests.count, 3)
        XCTAssertEqual(results.statuses, [503])
    }

    // MARK: 취소

    /// 화면을 떠났다 — 되묻기를 기다리는 중이어도 다음 요청이 나가지 않는다.
    func testCancelWhileWaitingToPollSendsNothingMore() {
        serverKnowsKeys()
        clock.manual = true
        let inner = RetryFakeSession([inProgress, .status(200)])
        let task = run(inner)
        XCTAssertEqual(clock.delays, [3])
        XCTAssertEqual(results.count, 0)

        task.cancel()
        XCTAssertEqual(results.errors, [.cancelled])
        clock.sleeping.forEach { $0() }
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.count, 1)
    }

    // MARK: 알림

    /// 다시 보내려고 쉴 때마다 화면에 알린다 — 「다시 연결하는 중」 과, 창이 닫혀 있으면 끊는 데 쓴다.
    func testResendsAreAnnouncedWithTheKey() {
        serverKnowsKeys()
        var seen: [RetryingSession.Resend] = []
        let token = NotificationCenter.default.addObserver(
            forName: RetryingSession.willResend, object: nil, queue: nil
        ) { note in
            if let resend = note.object as? RetryingSession.Resend {
                seen.append(resend)
            }
        }
        defer { NotificationCenter.default.removeObserver(token) }

        run(RetryFakeSession([.status(503), inProgress, .status(200)]))
        // 한도를 기다리는 것은 「다시 연결」 이 아니다 — 화면이 다른 말을 한다.
        let other = "0A1B2C3D-0000-4000-8000-000000000003"
        run(
            RetryFakeSession([.status(429, code: "RATE_LIMITED", headers: ["Retry-After": "40"]), .status(200)]),
            key: other
        )
        XCTAssertEqual(seen.filter { $0.key == key }, [
            .init(key: key, reason: .failed), .init(key: key, reason: .inProgress),
        ])
        XCTAssertEqual(seen.filter { $0.key == other }, [.init(key: other, reason: .rateLimited)])
    }

    // MARK: 뒷문 — 보낸 뒤 끊기

    /// `cut:2` — 보내고 2초 뒤에 끊는다. 서버는 처리 중이라 다시 보낸 것이 409 를 받고, 되묻다 답을 받는다.
    func testCutLosesTheConnectionWhileTheServerStillWorks() {
        serverKnowsKeys()
        clock.manual = true
        var rules = NetFault.parse("guide/chat:cut:2")
        let inner = RetryFakeSession([.hang, inProgress, .status(200)])
        run(inner, fault: { NetFault.take(for: $0, from: &rules) })
        XCTAssertEqual(inner.requests.count, 1, "진짜로 나갔다")
        XCTAssertEqual(clock.delays, [2])

        clock.sleeping[0]() // 2초 — 끊는다
        XCTAssertEqual(clock.delays, [2, 1], "끊긴 것은 응답 없음 — 한 번 다시 보낸다")
        clock.sleeping[1]()
        XCTAssertEqual(clock.delays, [2, 1, 3])
        clock.sleeping[2]()
        XCTAssertEqual(results.statuses, [200])
        assertSameTurn(inner)
    }

    /// 끊기 전에 답이 와도 잃은 것으로 친다 — 「서버는 처리했는데 응답을 잃음」.
    func testCutDropsAnAnswerThatCameEarly() {
        serverKnowsKeys()
        var rules = NetFault.parse("guide/chat:cut:30")
        let inner = RetryFakeSession([.status(200), .status(200)])
        run(inner, fault: { NetFault.take(for: $0, from: &rules) })
        XCTAssertEqual(inner.requests.count, 2)
        XCTAssertEqual(results.statuses, [200])
    }

    /// 끊기를 기다리는 중에 화면을 떠났다 — 우리가 끊은 것이 아니라 취소다. 다시 보내지 않는다.
    func testLeavingDuringACutIsACancelNotARetry() {
        serverKnowsKeys()
        clock.manual = true
        var rules = NetFault.parse("guide/chat:cut:2")
        let inner = RetryFakeSession([.hang, .status(200)])
        let task = run(inner, fault: { NetFault.take(for: $0, from: &rules) })
        task.cancel()
        XCTAssertEqual(results.errors, [.cancelled])
        clock.sleeping.forEach { $0() }
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.count, 1)
    }

    func testCutSpecIsParsed() {
        XCTAssertEqual(NetFault.parse("guide/chat:cut:2, POST@guide/chat:cut:0.5:3"), [
            .init(match: "guide/chat", injection: .cut(2), remaining: 1),
            .init(method: "POST", match: "guide/chat", injection: .cut(0.5), remaining: 3),
        ])
        XCTAssertEqual(NetFault.parse("guide/chat:cut,guide/chat:cut:soon"), [], "초가 없으면 버린다")
    }

    /// 지어낸 409·422 는 진짜와 같은 `code` 를 싣는다 — 되묻기와 「키 재사용」 을 서버 없이 본다.
    func testInjectedIdempotencyErrorsCarryTheContractCodes() {
        let url = URL(string: "http://x/v1/guide/chat")
        XCTAssertEqual(AuthRules.apiCode(from: NetFault.response(status: 409, url: url).0), "IDEMPOTENCY_IN_PROGRESS")
        XCTAssertEqual(AuthRules.apiCode(from: NetFault.response(status: 422, url: url).0), "IDEMPOTENCY_KEY_REUSED")
        XCTAssertEqual(AuthRules.apiCode(from: NetFault.response(status: 503, url: url).0), "NET_FAULT")
    }
}
