@testable import SceneTrip
import XCTest

/// 챗봇 턴을 다시 보낼지의 규칙 (MZ2AZ-366 PR B, 계획 `app-retry.md` §4).
///
/// 챗봇은 멱등이 아니다 — 토큰이 나가고 장바구니에 담는다. **멱등 키를 실었고 서버가 그 키를 알 때만** 다시
/// 보낸다. 틀어지면 모델을 두 번 부르고 한도를 두 번 깎는다.
final class GuideChatRetryRulesTests: XCTestCase {
    private typealias Rules = RetryRules

    private let keyed = RetryRules.policy(method: "POST", path: "/v1/guide/chat", keyed: true)
    private let unkeyed = RetryRules.policy(method: "POST", path: "/v1/guide/chat")
    private let inProgress = RetryRules.Outcome.http(
        status: 409, code: "IDEMPOTENCY_IN_PROGRESS", retryAfter: nil
    )

    private func http(_ status: Int, code: String? = nil, retryAfter: Int? = nil) -> RetryRules.Outcome {
        .http(status: status, code: code, retryAfter: retryAfter)
    }

    private func next(
        _ policy: RetryRules.Policy, _ outcome: RetryRules.Outcome,
        history: RetryRules.History = .init(), elapsed: TimeInterval = 0, keyAge: TimeInterval? = nil
    ) -> RetryRules.Step {
        Rules.next(
            policy: policy, method: "POST", history: history, outcome: outcome, elapsed: elapsed, keyAge: keyAge
        )
    }

    /// 같은 결과가 되풀이될 때의 걸음들. 그만둘 때까지.
    private func steps(_ policy: RetryRules.Policy, _ outcome: RetryRules.Outcome) -> [RetryRules.Step] {
        var history = Rules.History()
        var found: [RetryRules.Step] = []
        while found.count < 50 {
            let step = next(policy, outcome, history: history)
            if step == .stop {
                break
            }
            found.append(step)
            history = Rules.record(step, outcome: outcome, in: history)
        }
        return found
    }

    // MARK: 갈래

    func testKeyOnlyChangesTheChatLane() {
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/guide/chat", keyed: true), .keyedChat)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/guide/chat", keyed: false), .never)
        // 다른 창구에는 계약에 키가 없다 — 실려 있어도 갈래가 바뀌지 않는다.
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/guide/plan", keyed: true), .never)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/navigation/next-leg", keyed: true), .never)
        XCTAssertEqual(Rules.lane(method: "POST", path: "/v1/courses", keyed: true), .unsafeWrite)
    }

    func testChatNumbersLiveInTuning() {
        XCTAssertEqual(keyed.maxRetries, 1)
        XCTAssertEqual(keyed.attemptTimeout, 50, "서버 벽 40초·게이트웨이 벽 45초보다 길다")
        XCTAssertEqual(keyed.budget, 110)
        XCTAssertEqual(keyed.maxPolls, 20)
        XCTAssertEqual(unkeyed.maxRetries, 0)
        XCTAssertEqual(unkeyed.maxPolls, 0)
    }

    // MARK: 자동 한 번

    func testLostAndUnavailableAreResentOnce() {
        let once = [RetryRules.Step.retry(after: 1, forLimit: false)]
        for code in [URLError.Code.timedOut, .networkConnectionLost, .cannotConnectToHost] {
            XCTAssertEqual(steps(keyed, .noResponse(code)), once, "\(code)")
        }
        for status in [500, 502, 503, 504] {
            XCTAssertEqual(steps(keyed, http(status)), once, "\(status)")
        }
    }

    /// 실패의 종류가 바뀌어도 자동 재시도는 합쳐 한 번이다.
    func testOneAutomaticRetryInTotal() {
        var history = Rules.History()
        let first = next(keyed, .noResponse(.timedOut), history: history)
        history = Rules.record(first, outcome: .noResponse(.timedOut), in: history)
        XCTAssertEqual(history.retries, 1)
        for outcome in [.noResponse(.networkConnectionLost), http(503), http(500),
                        http(429, code: "RATE_LIMITED", retryAfter: 1)] as [RetryRules.Outcome]
        {
            XCTAssertEqual(next(keyed, outcome, history: history), .stop, "\(outcome)")
        }
    }

    func testClientErrorsAndOfflineAreFinal() {
        let final: [RetryRules.Outcome] = [
            http(400, code: "INVALID_PARAMETER"), http(401, code: "SIGN_IN_REQUIRED"), http(403), http(404),
            http(409, code: "COURSE_NOT_ACTIVE"), http(409),
            http(422, code: "IDEMPOTENCY_KEY_REUSED"),
            // 챗봇 한도는 기다려도 안 풀린다 — 화면이 안내한다.
            http(429, code: "GUIDE_LIMIT_REACHED", retryAfter: 1800),
            .noResponse(.cancelled), .noResponse(.notConnectedToInternet),
        ]
        for outcome in final {
            XCTAssertEqual(next(keyed, outcome), .stop, "\(outcome)")
        }
    }

    /// 분당 한도는 처리 전에 거절된 것이 확실하다 — `Retry-After` 만큼 기다렸다 한 번.
    func testRateLimitIsWaitedForOnce() {
        XCTAssertEqual(
            steps(keyed, http(429, code: "RATE_LIMITED", retryAfter: 7)), [.retry(after: 7, forLimit: true)]
        )
        XCTAssertEqual(steps(keyed, http(429)), [.retry(after: 1, forLimit: true)])
    }

    // MARK: 409 처리 중

    func testInProgressIsPolledEveryThreeSecondsUpToTheCap() {
        let polls = steps(keyed, inProgress)
        XCTAssertEqual(polls.count, 20, "끝없이 묻지 않는다")
        XCTAssertEqual(polls, Array(repeating: .poll(after: 3), count: 20))
    }

    /// 되묻기는 「자동 한 번」 과 따로 센다 — 응답을 잃고 다시 보낸 것이 바로 409 를 받는다.
    func testPollingIsCountedApartFromTheRetry() {
        var history = Rules.History()
        history = Rules.record(.retry(after: 1, forLimit: false), outcome: .noResponse(.timedOut), in: history)
        XCTAssertEqual(next(keyed, inProgress, history: history), .poll(after: 3))

        history = Rules.record(.poll(after: 3), outcome: inProgress, in: history)
        XCTAssertEqual(history.polls, 1)
        XCTAssertEqual(history.retries, 1, "되묻기가 재시도 횟수를 쓰지 않는다")
        // 되묻던 중의 실패는 다시 보내지 않는다 — 자동 한 번은 이미 썼다.
        XCTAssertEqual(next(keyed, http(503), history: history), .stop)
    }

    /// 되묻기만 하다 받은 실패에는 자동 한 번이 남아 있다.
    func testAFailureAfterPollingStillHasItsOneRetry() {
        var history = Rules.History()
        history = Rules.record(.poll(after: 3), outcome: inProgress, in: history)
        XCTAssertEqual(next(keyed, http(503), history: history), .retry(after: 1, forLimit: false))
    }

    func testPollingStopsAtTheTurnWall() {
        // 키는 방금 새로 받아들여졌고(한도를 오래 기다린 뒤) 턴의 벽만 남은 경우.
        XCTAssertEqual(next(keyed, inProgress, elapsed: 106, keyAge: 0), .poll(after: 3))
        XCTAssertEqual(next(keyed, inProgress, elapsed: 107, keyAge: 0), .stop, "쉬고 나면 110초 벽이다")
        XCTAssertEqual(next(keyed, inProgress, elapsed: 200, keyAge: 0), .stop)
    }

    /// **서버의 「처리 중 1 분」 경계를 넘겨 묻지 않는다.** 서버는 처리 중인 채 1 분이 지난 키에 같은 키로 요청이
    /// 오면 새로 처리한다(`IdempotencyStore.begin`) — 첫 처리가 살아 있으면 모델 두 번·한도 두 번이다. 그래서
    /// 키를 처음 보낸 때부터 55초 전에 나가는 되묻기까지만.
    func testPollingStopsBeforeTheServerForgetsTheKey() {
        XCTAssertEqual(RetryRules.Tuning.inProgressDeadline, 55)
        XCTAssertLessThan(RetryRules.Tuning.inProgressDeadline, 60, "서버의 1 분보다 짧아야 한다")
        XCTAssertEqual(next(keyed, inProgress, keyAge: 51), .poll(after: 3), "54초에 나간다")
        XCTAssertEqual(next(keyed, inProgress, keyAge: 52), .stop, "55초에 나가게 된다 — 묻지 않는다")
        XCTAssertEqual(next(keyed, inProgress, keyAge: 70), .stop)
        // 키의 나이는 이 요청이 흐른 시간과 따로다 — 「다시 시도」 로 막 나간 요청도 키가 늙었으면 묻지 않는다.
        XCTAssertEqual(next(keyed, inProgress, elapsed: 0, keyAge: 58), .stop)
        // 키의 나이를 모르면 이 요청이 흐른 시간으로 본다.
        XCTAssertEqual(next(keyed, inProgress, elapsed: 51), .poll(after: 3))
        XCTAssertEqual(next(keyed, inProgress, elapsed: 52), .stop)
    }

    /// 키가 없거나 서버가 키를 모르면 409 도 그냥 실패다. 다른 창구의 409 도 그렇다.
    func testOnlyAKeyedChatPolls() {
        XCTAssertEqual(next(unkeyed, inProgress), .stop)
        let read = Rules.policy(method: "GET", path: "/v1/places")
        XCTAssertEqual(
            Rules.next(policy: read, method: "GET", history: .init(), outcome: inProgress, elapsed: 0), .stop
        )
        XCTAssertEqual(Rules.kind(of: inProgress), .inProgress)
        XCTAssertEqual(Rules.kind(of: http(409, code: "COURSE_NOT_ACTIVE")), .final)
    }

    /// 스위치가 꺼져 있으면(서버가 키를 모른다) 어떤 실패에도 한 번만 나간다 — 전과 같다.
    func testUnkeyedChatIsNeverResent() {
        for outcome in [.noResponse(.cannotConnectToHost), .noResponse(.timedOut), http(503), http(500),
                        http(429, code: "RATE_LIMITED", retryAfter: 1), inProgress] as [RetryRules.Outcome]
        {
            XCTAssertEqual(next(unkeyed, outcome), .stop, "\(outcome)")
        }
    }
}
