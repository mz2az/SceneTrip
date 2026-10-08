import SceneApiClient
@testable import SceneTrip
import XCTest

/// 재시도 세션이 **실제로 무엇을 몇 번, 몇 초 뒤에 보내는가** (MZ2AZ-366).
///
/// 규칙의 칸은 `RetryRulesTests` 가 본다. 여기는 그 규칙이 요청으로 옮겨지는 것 — 같은 요청을 다시 보내는지,
/// completion 이 한 번뿐인지, 화면을 떠나면 멈추는지, **쓰기가 두 번 나가지 않는지** — 를 가짜 세션과 가짜
/// 시계로 본다. 네트워크도 기다림도 없다.
final class RetryingSessionTests: XCTestCase {
    let clock = RetryFakeClock()
    let results = RetryResults()
    let ledger = RateLimitLedger()

    func request(
        _ method: String, _ path: String, headers: [String: String] = [:], body: String? = nil
    ) -> URLRequest {
        var request = URLRequest(url: URL(string: "http://localhost:8081/v1\(path)?q=1")!)
        request.httpMethod = method
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        request.httpBody = body.map { Data($0.utf8) }
        return request
    }

    @discardableResult
    func run(
        _ inner: RetryFakeSession, _ request: URLRequest,
        fault: @escaping (URLRequest) -> NetFault.Injection? = { _ in nil }, resume: Bool = true
    ) -> URLSessionDataTaskProtocol {
        inner.clock = clock
        let session = RetryingSession(inner: inner, environment: clock.environment(fault: fault), ledger: ledger)
        let results = results
        let task = session.dataTaskFromProtocol(with: request) { _, response, error in
            results.statuses.append((response as? HTTPURLResponse)?.statusCode)
            results.errors.append((error as? URLError)?.code)
        }
        if resume {
            task.resume()
        }
        return task
    }

    // MARK: 조회

    func testReadIsResentUntilItSucceeds() {
        let inner = RetryFakeSession([.error(.networkConnectionLost), .status(503), .status(200)])
        run(inner, request("GET", "/places", headers: ["Authorization": "Bearer t", "Accept-Language": "en"]))

        XCTAssertEqual(inner.requests.count, 3)
        XCTAssertEqual(clock.delays, [1, 2])
        XCTAssertEqual(results.statuses, [200], "completion 은 한 번, 마지막 결과로")
        // 다시 보낸 것은 **같은 요청**이다 — 주소·헤더가 그대로.
        for sent in inner.requests {
            XCTAssertEqual(sent.url, inner.requests[0].url)
            XCTAssertEqual(sent.value(forHTTPHeaderField: "Authorization"), "Bearer t")
            XCTAssertEqual(sent.value(forHTTPHeaderField: "Accept-Language"), "en")
        }
    }

    func testReadGivesUpAfterThreeRetriesAndReportsTheLastFailure() {
        let inner = RetryFakeSession([.status(503), .status(503), .status(503), .status(503), .status(200)])
        run(inner, request("GET", "/contents"))

        XCTAssertEqual(inner.requests.count, 4, "처음 한 번 + 재시도 세 번")
        XCTAssertEqual(clock.delays, [1, 2, 4])
        XCTAssertEqual(results.statuses, [503])
    }

    func testSuccessAndClientErrorsGoStraightThrough() {
        for status in [200, 204, 400, 404, 409, 422] {
            let inner = RetryFakeSession([.status(status), .status(200)])
            run(inner, request("GET", "/places/1"))
            XCTAssertEqual(inner.requests.count, 1, "\(status)")
        }
        XCTAssertEqual(clock.delays, [])
    }

    func testOfflineIsReportedAtOnce() {
        let inner = RetryFakeSession([.error(.notConnectedToInternet), .status(200)])
        run(inner, request("GET", "/places"))
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.errors, [.notConnectedToInternet])
    }

    // MARK: 시간 벽

    func testEachAttemptCarriesItsTimeWall() {
        let walls: [String: TimeInterval] = [
            "GET /places": 15, "PUT /courses/3": 30, "POST /courses": 30,
            "POST /guide/chat": 60, "POST /guide/plan": 60, "POST /navigation/next-leg": 30,
        ]
        for (call, wall) in walls {
            let parts = call.split(separator: " ").map(String.init)
            let (method, path) = (parts[0], parts[1])
            let inner = RetryFakeSession([.status(200)])
            run(inner, request(method, path))
            XCTAssertEqual(inner.requests.first?.timeoutInterval, wall, "\(method) \(path)")
        }
    }

    /// 15초를 다 쓰고 끊긴 조회 — 다시 보내되 남은 벽(25초)만큼만 기다리고, 그것도 넘기면 그만둔다.
    func testRetriesStopAtTheTotalBudget() {
        let inner = RetryFakeSession([.error(.timedOut), .error(.timedOut), .status(200)])
        inner.seconds = 15
        run(inner, request("GET", "/places"))

        XCTAssertEqual(inner.requests.count, 2)
        XCTAssertEqual(inner.requests[1].timeoutInterval, 9, "25 - (15 + 1)")
        XCTAssertEqual(results.errors, [.timedOut])
    }

    // MARK: 분당 한도

    func testRateLimitWaitsRetryAfterAndRecordsHeaders() {
        let limited = FakeReply.status(429, code: "RATE_LIMITED", headers: [
            "Retry-After": "7", "RateLimit-Limit": "60", "RateLimit-Remaining": "0", "RateLimit-Reset": "7",
        ])
        let fine = FakeReply.status(200, headers: [
            "RateLimit-Limit": "60", "RateLimit-Remaining": "59", "RateLimit-Reset": "60",
        ])
        let inner = RetryFakeSession([limited, fine])
        clock.manual = true
        run(inner, request("GET", "/contents"))

        XCTAssertEqual(clock.delays, [7])
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(ledger.entry(for: .general)?.remaining, 0)
        // 5초 넘게 기다리는 동안은 화면이 알 수 있다.
        XCTAssertEqual(ledger.waitingUntil, Date(timeIntervalSince1970: 7))

        clock.sleeping.removeFirst()()
        XCTAssertEqual(inner.requests.count, 2)
        XCTAssertEqual(results.statuses, [200])
        XCTAssertNil(ledger.waitingUntil)
        XCTAssertEqual(ledger.entry(for: .general)?.remaining, 59)
    }

    func testShortRateLimitWaitIsSilent() {
        let inner = RetryFakeSession([.status(429, code: "RATE_LIMITED", headers: ["Retry-After": "3"]), .status(200)])
        clock.manual = true
        run(inner, request("GET", "/contents"))
        XCTAssertNil(ledger.waitingUntil)
    }

    /// 한도를 기다린 시간은 재시도 벽에 넣지 않는다 — 40초를 기다리라 해 놓고 25초 벽으로 끊으면 안 된다.
    func testRateLimitWaitDoesNotEatTheBudget() {
        let inner = RetryFakeSession([
            .status(429, code: "RATE_LIMITED", headers: ["Retry-After": "40"]), .status(503), .status(200),
        ])
        run(inner, request("GET", "/contents"))
        XCTAssertEqual(clock.delays, [40, 2])
        XCTAssertEqual(results.statuses, [200])
    }

    func testPaidLimitIsHandedToTheScreen() {
        let inner = RetryFakeSession([
            .status(429, code: "NAVIGATION_LIMIT_REACHED", headers: ["Retry-After": "30"]), .status(200),
        ])
        run(inner, request("GET", "/places"))
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.statuses, [429])
    }

    // MARK: 쓰기 — 두 번 나가지 않는다

    /// 닿았는지 모르는 POST 는 **한 번만** 나간다. 코스 만들기가 두 번 가면 코스가 둘이다.
    func testAmbiguousPostIsSentOnce() {
        for reply in [FakeReply.error(.timedOut), .error(.networkConnectionLost), .status(500),
                      .status(503), .status(504)]
        {
            for path in ["/courses", "/cart/items", "/uploads", "/auth/google", "/favorites/contents"] {
                let inner = RetryFakeSession([reply, .status(200)])
                run(inner, request("POST", path, body: #"{"dayCount":2}"#))
                XCTAssertEqual(inner.requests.count, 1, "\(path) \(reply)")
            }
        }
        XCTAssertEqual(clock.delays, [])
    }

    func testPostIsResentWhenTheServerWasSurelyNotReached() {
        let inner = RetryFakeSession([.error(.cannotConnectToHost), .status(201)])
        run(inner, request("POST", "/courses", body: #"{"dayCount":2}"#))
        XCTAssertEqual(inner.requests.count, 2)
        XCTAssertEqual(inner.requests[1].httpBody, inner.requests[0].httpBody, "본문도 그대로")
        XCTAssertEqual(results.statuses, [201])
    }

    /// 챗봇·길찾기·마법사·갱신은 어떤 실패에도 다시 보내지 않는다. 헤더(멱등 키)는 그대로 나간다.
    func testNeverLaneIsSentOnce() {
        let replies: [FakeReply] = [
            .error(.cannotConnectToHost), .error(.timedOut), .status(503), .status(500),
            .status(429, code: "RATE_LIMITED", headers: ["Retry-After": "1"]),
        ]
        for path in ["/guide/chat", "/guide/plan", "/navigation/next-leg", "/auth/refresh"] {
            for reply in replies {
                let inner = RetryFakeSession([reply, .status(200)])
                run(inner, request("POST", path, headers: ["Idempotency-Key": "k-1"], body: "{}"))
                XCTAssertEqual(inner.requests.count, 1, "\(path) \(reply)")
                XCTAssertEqual(inner.requests[0].value(forHTTPHeaderField: "Idempotency-Key"), "k-1")
            }
        }
        XCTAssertEqual(clock.delays, [])
    }

    /// 2026-10-08 실기 재현을 고정한다 — 찜 풀기(서버는 처리, 응답만 잃음) 직후 다시 찜. 「풀기」 가 다시 나가면
    /// 뒤에 온 「찜」 을 지워 화면은 찜인데 서버에는 없다. **응답 잃은 찜 풀기는 다시 나가지 않는다.**
    func testLostUnfavoriteIsNotResentOverALaterFavorite() {
        let inner = RetryFakeSession([.error(.timedOut), .status(201), .status(204)])
        clock.manual = true
        run(inner, request("DELETE", "/favorites/contents/7"))
        run(inner, request("POST", "/favorites/contents", body: #"{"contentId":7}"#))
        clock.sleeping.forEach { $0() }

        XCTAssertEqual(inner.requests.map(\.httpMethod), ["DELETE", "POST"], "늦은 DELETE 가 없다")
        XCTAssertEqual(clock.delays, [])
        XCTAssertEqual(results.errors, [.timedOut, nil])
    }

    /// 같은 꼴의 다른 쓰기들 — 장바구니 빼기, 방문 체크, 여행 시작·종료. 탈퇴는 아예 다시 보내지 않는다.
    func testUnawaitedWritesAndAccountDeletionAreSentOnce() {
        let calls = ["DELETE /cart/items/9", "PUT /courses/3/items/12/visit", "PUT /courses/3/progress", "DELETE /me"]
        for call in calls {
            for reply in [FakeReply.error(.timedOut), .status(503), .status(500)] {
                let parts = call.split(separator: " ").map(String.init)
                let inner = RetryFakeSession([reply, .status(204)])
                run(inner, request(parts[0], parts[1]))
                XCTAssertEqual(inner.requests.count, 1, "\(call) \(reply)")
            }
        }
        XCTAssertEqual(clock.delays, [])
    }

    func testPutIsResentTwice() {
        let inner = RetryFakeSession([.error(.timedOut), .status(503), .status(503), .status(200)])
        run(inner, request("PUT", "/places/49/reviews/me", body: #"{"rating":5}"#))
        XCTAssertEqual(inner.requests.count, 3)
        XCTAssertEqual(clock.delays, [1, 2])
        XCTAssertEqual(results.statuses, [503])
        XCTAssertEqual(Set(inner.requests.map(\.httpBody)).count, 1)
    }

    /// 응답만 잃은 DELETE 를 다시 보냈더니 404 — 이미 지워졌다. 화면에는 성공으로 간다.
    func testRetriedDeleteSeeing404IsDeliveredAsSuccess() {
        let inner = RetryFakeSession([.error(.timedOut), .status(404, code: "REVIEW_NOT_FOUND")])
        run(inner, request("DELETE", "/places/49/reviews/me"))
        XCTAssertEqual(results.statuses, [204])
        XCTAssertEqual(results.errors, [nil])

        let fresh = RetryFakeSession([.status(404, code: "REVIEW_NOT_FOUND")])
        run(fresh, request("DELETE", "/places/49/reviews/me"))
        XCTAssertEqual(results.statuses, [204, 404], "처음부터 404 면 그대로 404")
    }

    // MARK: 401 과의 조합

    /// 401 은 여기서 다시 보내지 않는다 — 바깥(빌더)이 토큰을 갱신하고 다시 보낸다. 그 요청은 새 횟수로 지난다.
    func testUnauthorizedIsLeftToTheBuilderAndTheResendStartsFresh() {
        let inner = RetryFakeSession([
            .status(503), .status(401, code: "ACCESS_TOKEN_EXPIRED"),
            // ↓ 빌더가 갱신한 뒤 다시 보낸 것. 또 끊겨도 세 번까지 다시 보낸다.
            .error(.networkConnectionLost), .status(503), .status(503), .status(200),
        ])
        run(inner, request("GET", "/me", headers: ["Authorization": "Bearer old"]))
        XCTAssertEqual(results.statuses, [401], "재시도 도중 토큰이 만료되면 401 이 그대로 올라간다")
        XCTAssertEqual(inner.requests.count, 2)

        run(inner, request("GET", "/me", headers: ["Authorization": "Bearer new"]))
        XCTAssertEqual(results.statuses, [401, 200])
        XCTAssertEqual(inner.requests.count, 6)
        XCTAssertEqual(inner.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }

    // MARK: 취소

    /// 화면을 떠났다 — **쉬는 중이어도** 다음 요청이 나가지 않는다.
    func testCancelWhileSleepingSendsNothingMore() {
        let inner = RetryFakeSession([.status(503), .status(200)])
        clock.manual = true
        let task = run(inner, request("GET", "/places"))
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.count, 0, "쉬는 중")

        task.cancel()
        XCTAssertEqual(results.errors, [.cancelled])
        XCTAssertEqual(clock.cancelledSleeps, 1)

        clock.sleeping.forEach { $0() } // 타이머가 늦게 울려도
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.count, 1, "completion 은 한 번")
    }

    /// 취소가 온 뒤에 깨어난 재시도는 안쪽 task 를 **띄우지 않는다.**
    func testNothingFliesOnceCancelled() {
        let inner = RetryFakeSession([.status(503), .status(200)])
        clock.manual = true
        let task = run(inner, request("GET", "/places"))
        task.cancel()
        clock.sleeping.forEach { $0() }
        task.resume() // 생성 코드가 이럴 일은 없지만, 그래도 나가지 않는다
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.count, 1)
    }

    func testCancelWhileFlyingIsNotRetried() {
        let inner = RetryFakeSession([.hang, .status(200)])
        let task = run(inner, request("GET", "/places"))
        task.cancel()
        XCTAssertEqual(results.errors, [.cancelled])
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(clock.delays, [])
        task.cancel()
        XCTAssertEqual(results.count, 1)
    }
}
