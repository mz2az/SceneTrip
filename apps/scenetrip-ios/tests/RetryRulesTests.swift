@testable import SceneTrip
import XCTest

/// 실패한 요청을 다시 보낼지의 규칙 (MZ2AZ-366, 계획 `app-retry.md` §2 의 표).
///
/// 틀어지면 두 가지로 아프다 — 다시 보내야 할 조회를 안 보내 빈 화면이 뜨거나, **다시 보내면 안 되는 쓰기를
/// 보내** 코스가 둘 생기고 리프레시 토큰이 재사용으로 판정된다. 뒤쪽이 더 아프다.
final class RetryRulesTests: XCTestCase {
    private typealias Rules = RetryRules

    private let read = RetryRules.policy(method: "GET", path: "/v1/places")
    private let put = RetryRules.policy(method: "PUT", path: "/v1/places/49/reviews/me")
    private let post = RetryRules.policy(method: "POST", path: "/v1/courses")
    private let chat = RetryRules.policy(method: "POST", path: "/v1/guide/chat")

    private func http(_ status: Int, code: String? = nil, retryAfter: Int? = nil) -> RetryRules.Outcome {
        .http(status: status, code: code, retryAfter: retryAfter)
    }

    /// 같은 결과가 되풀이될 때 쉬는 간격들. 그만둘 때까지.
    private func delays(
        _ policy: RetryRules.Policy, method: String = "GET", _ outcome: RetryRules.Outcome
    ) -> [TimeInterval] {
        var history = Rules.History()
        var found: [TimeInterval] = []
        while case let .retry(after, forLimit) = Rules.next(
            policy: policy, method: method, history: history, outcome: outcome, elapsed: 0
        ) {
            found.append(after)
            history = Rules.record(.retry(after: after, forLimit: forLimit), outcome: outcome, in: history)
            if found.count > 10 {
                XCTFail("끝없이 다시 보낸다")
                break
            }
        }
        return found
    }

    // MARK: 창구 → 갈래

    func testLaneFollowsMethodAndPath() {
        XCTAssertEqual(Rules.lane(method: "GET", path: "/v1/places"), .read)
        XCTAssertEqual(Rules.lane(method: "PUT", path: "/v1/courses/3"), .idempotentWrite)
        XCTAssertEqual(Rules.lane(method: "DELETE", path: "/v1/courses/3"), .idempotentWrite)
        XCTAssertEqual(Rules.lane(method: "PUT", path: "/v1/pois/7/reviews/me"), .idempotentWrite)
        XCTAssertEqual(Rules.lane(method: "DELETE", path: "/v1/places/49/reviews/me"), .idempotentWrite)
        XCTAssertEqual(Rules.lane(method: "GET", path: "/v1/me"), .read)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/courses"), .unsafeWrite)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/cart/items"), .unsafeWrite)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/uploads"), .unsafeWrite)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/auth/google"), .unsafeWrite)
        // 닉네임은 PUT 이지만 닿지 않은 것이 확실할 때만.
        XCTAssertEqual(Rules.lane(method: "PUT", path: "/v1/me/nickname"), .unsafeWrite)
    }

    /// **화면이 응답을 기다리지 않는 쓰기**는 PUT·DELETE 여도 닿지 않은 것이 확실할 때만 다시 보낸다 — 늦게
    /// 도착한 재시도가 그 뒤의 쓰기를 덮는다(찜 풀기 → 찜 → 늦은 「풀기」 가 찜을 지운다).
    func testWritesTheScreenDoesNotWaitForAreNotResentWhenTheyMayHaveLanded() {
        let unawaited = [
            ("DELETE", "/v1/favorites/contents/7"), ("DELETE", "/v1/cart/items/9"),
            ("PUT", "/v1/courses/3/items/12/visit"), ("PUT", "/v1/courses/3/progress"),
            ("DELETE", "/v1/market/courses/5/likes"), ("POST", "/v1/favorites/contents"),
        ]
        for (method, path) in unawaited {
            XCTAssertEqual(Rules.lane(method: method, path: path), .unsafeWrite, "\(method) \(path)")
            let policy = Rules.policy(method: method, path: path)
            XCTAssertEqual(delays(policy, method: method, .noResponse(.timedOut)), [], path)
            XCTAssertEqual(delays(policy, method: method, .noResponse(.networkConnectionLost)), [], path)
            XCTAssertEqual(delays(policy, method: method, http(503)), [], path)
            XCTAssertEqual(delays(policy, method: method, http(500)), [], path)
            XCTAssertEqual(delays(policy, method: method, .noResponse(.cannotConnectToHost)), [1, 2], path)
        }
    }

    /// 탈퇴는 다시 보내지 않는다 — 응답만 잃고 다시 보내면 지워진 계정의 토큰으로 나가 401 이 온다.
    func testDeletingTheAccountIsNeverRetried() {
        XCTAssertEqual(Rules.lane(method: "DELETE", path: "/v1/me"), .never)
        let policy = Rules.policy(method: "DELETE", path: "/v1/me")
        XCTAssertEqual(delays(policy, method: "DELETE", .noResponse(.timedOut)), [])
        XCTAssertEqual(delays(policy, method: "DELETE", .noResponse(.cannotConnectToHost)), [])
        XCTAssertEqual(delays(policy, method: "DELETE", http(503)), [])
    }

    /// 챗봇은 멱등 키가 없으면(또는 서버가 키를 모르면), 길찾기·마법사는 돈이 들 수 있고, 갱신은 토큰이 일회용이다.
    func testPaidAndOneShotEndpointsAreNeverRetried() {
        for path in ["/v1/guide/chat", "/v1/guide/plan", "/v1/navigation/next-leg", "/v1/auth/refresh",
                     "/v1/auth/sign-out"]
        {
            XCTAssertEqual(Rules.lane(method: "POST", path: path), .never, path)
            let policy = Rules.policy(method: "POST", path: path)
            for outcome in [.noResponse(.cannotConnectToHost), .noResponse(.timedOut), http(503), http(500),
                            http(429, code: "RATE_LIMITED", retryAfter: 1)] as [RetryRules.Outcome]
            {
                XCTAssertEqual(
                    Rules.next(policy: policy, method: "POST", history: .init(), outcome: outcome, elapsed: 0),
                    .stop, "\(path) \(outcome)"
                )
            }
        }
    }

    func testTimeWalls() {
        XCTAssertEqual(read.attemptTimeout, 15)
        XCTAssertEqual(read.budget, 25)
        XCTAssertEqual(put.attemptTimeout, 30)
        XCTAssertEqual(post.attemptTimeout, 30)
        // 키 없는 챗봇 — 50초 벽(`RouteGuideTimeout`)보다 길어야 그 벽이 먼저 말한다.
        XCTAssertGreaterThan(chat.attemptTimeout, RouteGuide.timeoutSeconds)
        // 키를 실은 챗봇은 요청마다 50초, 턴 전체 110초 — 화면의 벽은 그보다 조금 길다.
        let keyed = Rules.policy(method: "POST", path: "/v1/guide/chat", keyed: true)
        XCTAssertEqual(keyed.attemptTimeout, RouteGuide.timeoutSeconds)
        XCTAssertEqual(keyed.budget, 110)
        XCTAssertGreaterThan(Rules.Tuning.chatWallMargin, 0)
        XCTAssertEqual(Rules.policy(method: "POST", path: "/v1/guide/plan").attemptTimeout, 60)
    }

    // MARK: 조회

    func testReadRetriesThreeTimesWithBackoff() {
        XCTAssertEqual(delays(read, .noResponse(.timedOut)), [1, 2, 4])
        XCTAssertEqual(delays(read, .noResponse(.networkConnectionLost)), [1, 2, 4])
        XCTAssertEqual(delays(read, .noResponse(.cannotConnectToHost)), [1, 2, 4])
        for status in [502, 503, 504] {
            XCTAssertEqual(delays(read, http(status)), [1, 2, 4], "\(status)")
        }
    }

    func testServerErrorIsRetriedOnce() {
        XCTAssertEqual(delays(read, http(500)), [1])
        XCTAssertEqual(delays(put, method: "PUT", http(500)), [1])
    }

    func testClientErrorsAreFinal() {
        for status in [400, 401, 403, 404, 409, 422] {
            XCTAssertEqual(delays(read, http(status)), [], "\(status)")
            XCTAssertEqual(delays(put, method: "PUT", http(status)), [], "\(status)")
        }
    }

    /// 취소는 화면을 떠난 것이고, 오프라인은 몇 초 안에 돌아오지 않는다.
    func testCancelledAndOfflineAreFinal() {
        for code in [URLError.Code.cancelled, .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff] {
            XCTAssertEqual(delays(read, .noResponse(code)), [], "\(code.rawValue)")
        }
        XCTAssertTrue(Rules.isOffline(.notConnectedToInternet))
        XCTAssertFalse(Rules.isOffline(.timedOut))
    }

    func testJitterStaysWithinTwentyPercent() {
        func delay(_ jitter: Double) -> TimeInterval? {
            guard case let .retry(after, _) = Rules.next(
                policy: read, method: "GET", history: .init(), outcome: http(503), elapsed: 0, jitter: jitter
            ) else { return nil }
            return after
        }
        XCTAssertEqual(delay(-1) ?? 0, 0.8, accuracy: 0.0001)
        XCTAssertEqual(delay(1) ?? 0, 1.2, accuracy: 0.0001)
        XCTAssertEqual(delay(99) ?? 0, 1.2, accuracy: 0.0001) // 범위를 넘는 난수는 잘라 쓴다
    }

    /// 재시도까지 합친 벽 — 쉬고 나면 벽을 넘는 재시도는 하지 않는다.
    func testBudgetStopsRetries() {
        func step(_ elapsed: TimeInterval) -> RetryRules.Step {
            Rules.next(policy: read, method: "GET", history: .init(), outcome: http(503), elapsed: elapsed)
        }
        XCTAssertEqual(step(23.9), .retry(after: 1, forLimit: false))
        XCTAssertEqual(step(24), .stop)
        XCTAssertEqual(step(60), .stop)
    }

    // MARK: 분당 한도

    func testRateLimitWaitsRetryAfterOnce() {
        XCTAssertEqual(delays(read, http(429, code: "RATE_LIMITED", retryAfter: 37)), [37])
        XCTAssertEqual(delays(post, method: "POST", http(429, code: "RATE_LIMITED", retryAfter: 3)), [3])
    }

    /// 게이트웨이(nginx)가 낸 429 에는 우리 본문도 `Retry-After` 도 없다.
    func testCodelessRateLimitUsesDefaultWait() {
        XCTAssertEqual(delays(read, http(429)), [Rules.Tuning.rateLimitDefaultWait])
    }

    func testRateLimitWaitHasACeiling() {
        XCTAssertEqual(delays(read, http(429, code: "RATE_LIMITED", retryAfter: 60)), [60])
        XCTAssertEqual(delays(read, http(429, code: "RATE_LIMITED", retryAfter: 61)), [])
    }

    /// 한도 대기의 지터는 **더하는 쪽으로만** — 일찍 보내면 또 429 다.
    func testRateLimitJitterOnlyAddsToTheWait() {
        func wait(_ jitter: Double) -> TimeInterval? {
            guard case let .retry(after, true) = Rules.next(
                policy: read, method: "GET", history: .init(),
                outcome: http(429, code: "RATE_LIMITED", retryAfter: 10), elapsed: 0, jitter: jitter
            ) else { return nil }
            return after
        }
        XCTAssertEqual(wait(0), 10)
        XCTAssertEqual(wait(0.5) ?? 0, 10.5, accuracy: 0.0001)
        XCTAssertEqual(wait(-0.5) ?? 0, 10.5, accuracy: 0.0001)
        XCTAssertEqual(wait(-1) ?? 0, 11, accuracy: 0.0001)
    }

    /// 벽을 거의 다 쓴 뒤의 429 — 기다려 봐야 보낼 시간이 없다.
    func testRateLimitIsNotWaitedForWhenTheBudgetIsNearlySpent() {
        func step(_ elapsed: TimeInterval) -> RetryRules.Step {
            Rules.next(
                policy: read, method: "GET", history: .init(),
                outcome: http(429, code: "RATE_LIMITED", retryAfter: 2), elapsed: elapsed
            )
        }
        XCTAssertEqual(step(22), .retry(after: 2, forLimit: true))
        XCTAssertEqual(step(22.5), .stop)
    }

    /// 유료 한도는 기다려도 안 풀린다 — 화면이 안내한다.
    func testPaidLimitsAreNotWaitedFor() {
        XCTAssertEqual(delays(read, http(429, code: "GUIDE_LIMIT_REACHED", retryAfter: 5)), [])
        XCTAssertEqual(delays(read, http(429, code: "NAVIGATION_LIMIT_REACHED", retryAfter: 5)), [])
    }

    // MARK: 쓰기

    func testIdempotentWriteRetriesTwice() {
        XCTAssertEqual(delays(put, method: "PUT", .noResponse(.timedOut)), [1, 2])
        XCTAssertEqual(delays(put, method: "PUT", http(503)), [1, 2])
    }

    /// **닿았는지 모르는 POST 는 다시 보내지 않는다** — 코스가 둘 생긴다.
    func testUnsafeWriteRetriesOnlyWhenTheServerWasNotReached() {
        XCTAssertEqual(delays(post, method: "POST", .noResponse(.timedOut)), [])
        XCTAssertEqual(delays(post, method: "POST", .noResponse(.networkConnectionLost)), [])
        XCTAssertEqual(delays(post, method: "POST", http(500)), [])
        XCTAssertEqual(delays(post, method: "POST", http(503)), [])
        XCTAssertEqual(delays(post, method: "POST", http(504)), [])
        XCTAssertEqual(delays(post, method: "POST", .noResponse(.cannotConnectToHost)), [1, 2])
        XCTAssertEqual(delays(post, method: "POST", .noResponse(.dnsLookupFailed)), [1, 2])
    }

    // MARK: 다시 보낸 DELETE 의 404

    func testRetriedDeleteSeeing404IsAlreadyDone() {
        let policy = Rules.policy(method: "DELETE", path: "/v1/places/49/reviews/me")
        func after(_ first: RetryRules.Outcome) -> RetryRules.Step {
            let step = Rules.next(policy: policy, method: "DELETE", history: .init(), outcome: first, elapsed: 0)
            let history = Rules.record(step, outcome: first, in: .init())
            return Rules.next(policy: policy, method: "DELETE", history: history, outcome: http(404), elapsed: 1)
        }
        XCTAssertEqual(after(.noResponse(.timedOut)), .alreadyDone)
        XCTAssertEqual(after(http(503)), .alreadyDone)
        // 닿지 않은 것이 확실했거나 한도로 거절된 뒤의 404 는 진짜 「없음」 이다.
        XCTAssertEqual(after(.noResponse(.cannotConnectToHost)), .stop)
        XCTAssertEqual(after(http(429, code: "RATE_LIMITED", retryAfter: 1)), .stop)
        // 처음부터 404 — 없는 것을 지우려 했다.
        XCTAssertEqual(
            Rules.next(policy: policy, method: "DELETE", history: .init(), outcome: http(404), elapsed: 0), .stop
        )
        // 조회의 404 는 언제나 404 다.
        var reached = Rules.History()
        reached.mayHaveReached = true
        XCTAssertEqual(
            Rules.next(policy: read, method: "GET", history: reached, outcome: http(404), elapsed: 0), .stop
        )
    }
}
