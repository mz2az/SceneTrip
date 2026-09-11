import SceneApiClient
@testable import SceneTrip
import XCTest

/// 가이드 오류 분류 — 계약(`POST /guide/chat`·`/guide/plan`)의 응답별로 화면 말이 갈리는지
/// 못 박는다. 창구가 서기 전에는 전부 「준비 중」이었다(MZ2AZ-297); 이제 그 문장이 남아 있으면 회귀다.
final class RouteGuideFailureTests: XCTestCase {
    private func response(_ status: Int, code: String? = nil) -> Error {
        let data = code.map { #"{"code":"\#($0)","message":"x"}"#.data(using: .utf8)! }
        return ErrorResponse.error(status, data, nil, URLError(.badServerResponse))
    }

    func testStatusCodesMapToContractCases() {
        XCTAssertEqual(RouteGuideFailure(response(401, code: "SIGN_IN_REQUIRED")), .signInRequired)
        XCTAssertEqual(RouteGuideFailure(response(400, code: "INVALID_PARAMETER")), .badRequest)
        XCTAssertEqual(RouteGuideFailure(response(503, code: "GUIDE_UNAVAILABLE")), .unavailable)
        XCTAssertEqual(RouteGuideFailure(response(500)), .other(status: 500))
    }

    /// 생성 클라이언트는 연결 실패를 음수 코드로 준다. HTTP 코드로 읽으면 「(-1)」이 된다.
    func testNegativeCodeIsUnreachable() {
        XCTAssertEqual(RouteGuideFailure(response(-1)), .unreachable)
        XCTAssertEqual(RouteGuideFailure(URLError(.notConnectedToInternet)), .unreachable)
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
