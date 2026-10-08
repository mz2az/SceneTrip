import SceneApiClient
@testable import SceneTrip
import XCTest

/// 가이드 오류 분류 — 계약(`POST /guide/chat`·`/guide/plan`)의 응답별로 화면 말이 갈리는지
/// 못 박는다. 창구가 서기 전에는 전부 「준비 중」이었다(MZ2AZ-297); 이제 그 문장이 남아 있으면 회귀다.
final class RouteGuideFailureTests: XCTestCase {
    private func response(
        _ status: Int, code: String? = nil, underlying: URLError.Code = .badServerResponse
    ) -> Error {
        let data = code.map { #"{"code":"\#($0)","message":"x"}"#.data(using: .utf8)! }
        return ErrorResponse.error(status, data, nil, URLError(underlying))
    }

    func testStatusCodesMapToContractCases() {
        XCTAssertEqual(RouteGuideFailure(response(401, code: "SIGN_IN_REQUIRED")), .signInRequired)
        XCTAssertEqual(RouteGuideFailure(response(401, code: "ACCESS_TOKEN_INVALID")), .sessionExpired)
        XCTAssertEqual(RouteGuideFailure(response(400, code: "INVALID_PARAMETER")), .badRequest)
        XCTAssertEqual(RouteGuideFailure(response(503, code: "GUIDE_UNAVAILABLE")), .unavailable)
        XCTAssertEqual(RouteGuideFailure(response(500)), .other(status: 500))
    }

    /// 생성 클라이언트는 연결 실패를 음수 코드로 준다. HTTP 코드로 읽으면 「(-1)」이 된다.
    func testNegativeCodeIsUnreachable() {
        XCTAssertEqual(RouteGuideFailure(response(-1)), .unreachable)
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .cannotConnectToHost)), .unreachable)
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .networkConnectionLost)), .unreachable)
        XCTAssertEqual(RouteGuideFailure(URLError(.cannotFindHost)), .unreachable)
    }

    /// 응답이 없을 때 **왜 없는지**를 가른다 (MZ2AZ-366) — 셋 다 「백엔드가 켜져 있나요?」 이던 것.
    func testNoResponseIsToldApart() {
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .notConnectedToInternet)), .offline)
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .dataNotAllowed)), .offline)
        XCTAssertEqual(RouteGuideFailure(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .timedOut)), .timedOut)
        XCTAssertEqual(RouteGuideFailure(response(-1, underlying: .cancelled)), .interrupted)
        XCTAssertEqual(RouteGuideFailure(CancellationError()), .interrupted)
    }

    /// 계약 1.5.0 의 멱등 키·한도 응답.
    func testIdempotencyAndLimitResponses() {
        XCTAssertEqual(RouteGuideFailure(response(409, code: "IDEMPOTENCY_IN_PROGRESS")), .timedOut)
        XCTAssertEqual(RouteGuideFailure(response(409, code: "SOMETHING_ELSE")), .other(status: 409))
        XCTAssertEqual(RouteGuideFailure(response(422, code: "IDEMPOTENCY_KEY_REUSED")), .keyReused)
        XCTAssertEqual(RouteGuideFailure(response(429, code: "RATE_LIMITED")), .rateLimited)
        XCTAssertEqual(RouteGuideFailure(response(429)), .rateLimited, "게이트웨이의 429 에는 code 가 없다")
        // 챗봇 한도의 안내는 뒤의 일이다 — 그때까지는 전과 같은 말이고, 다시 시도 단추는 없다.
        XCTAssertEqual(RouteGuideFailure(response(429, code: "GUIDE_LIMIT_REACHED")), .other(status: 429))
        XCTAssertEqual(RouteGuideFailure(response(502)), .unavailable)
        XCTAssertEqual(RouteGuideFailure(response(504)), .unavailable)
    }

    /// 「다시 시도」 가 무엇을 보내나 — 같은 키, 새 키, 또는 단추 없음.
    func testWhichFailuresCanBeRetried() {
        let sameKey: [RouteGuideFailure] = [
            .unavailable, .timedOut, .offline, .unreachable, .rateLimited, .interrupted, .other(status: 500),
        ]
        for failure in sameKey {
            XCTAssertEqual(failure.retry, .sameKey, "\(failure)")
        }
        XCTAssertEqual(RouteGuideFailure.keyReused.retry, .newKey)
        let none: [RouteGuideFailure] = [
            .signInRequired, .sessionExpired, .badRequest, .other(status: 429), .other(status: 404),
        ]
        for failure in none {
            XCTAssertEqual(failure.retry, RouteGuideFailure.Retry.none, "\(failure)")
        }
    }

    /// 사용자에게 개발자 말을 하지 않는다 — 포트 번호도 「백엔드」 도.
    func testNoCaseTalksToDevelopers() {
        let all: [RouteGuideFailure] = [
            .signInRequired, .sessionExpired, .badRequest, .unavailable, .timedOut, .offline, .unreachable,
            .rateLimited, .interrupted, .keyReused, .other(status: 500),
        ]
        for failure in all {
            XCTAssertFalse(failure.message.contains("8081"), failure.message)
            XCTAssertFalse(failure.message.contains("백엔드"), failure.message)
            XCTAssertFalse(failure.message.isEmpty)
        }
        XCTAssertNotEqual(RouteGuideFailure.offline.message, RouteGuideFailure.unreachable.message)
        XCTAssertNotEqual(RouteGuideFailure.unreachable.message, RouteGuideFailure.timedOut.message)
    }

    /// 50초 벽을 넘긴 것은 서버 탓도 네트워크 탓도 아니라 따로 말한다.
    func testTimeoutIsItsOwnCase() {
        XCTAssertEqual(RouteGuideFailure(RouteGuideTimedOut()), .timedOut)
    }

    /// 벽은 실제로 요청을 끊는다 — 오래 걸리는 일을 짧은 벽으로 감싸면 벽이 이긴다.
    func testTimeoutWallCutsSlowWork() async {
        do {
            _ = try await RouteGuideTimeout.run(seconds: 0.05) {
                try await Task.sleep(nanoseconds: 2_000_000_000)
                return "늦은 답"
            }
            XCTFail("벽을 넘겼는데 답이 왔다")
        } catch {
            XCTAssertEqual(RouteGuideFailure(error), .timedOut)
        }
    }

    func testFastWorkPassesTheWall() async throws {
        let answer = try await RouteGuideTimeout.run(seconds: 5) { "빠른 답" }
        XCTAssertEqual(answer, "빠른 답")
    }

    /// 「준비 중」은 서버가 없던 시절의 말이다. 어느 갈래에도 남아 있으면 안 된다.
    func testNoCaseStillSaysComingSoon() {
        let all: [RouteGuideFailure] = [
            .signInRequired, .badRequest, .unavailable, .timedOut, .unreachable, .other(status: 500),
        ]
        for failure in all {
            XCTAssertFalse(failure.message.contains("준비 중"), failure.message)
        }
    }
}
