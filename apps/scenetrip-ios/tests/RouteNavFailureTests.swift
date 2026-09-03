import SceneApiClient
@testable import SceneTrip
import XCTest

/// 길찾기 오류 분류 — 계약(`POST /navigation/next-leg`)의 응답별로 화면 말이
/// 갈리는지 못 박는다. 서버가 서기 전에는 전부 「준비 중」이었다(MZ2AZ-297);
/// 이제 그 문장이 남아 있으면 회귀다.
final class RouteNavFailureTests: XCTestCase {
    private func response(_ status: Int, code: String? = nil) -> Error {
        let data = code.map { #"{"code":"\#($0)","message":"x"}"#.data(using: .utf8)! }
        return ErrorResponse.error(status, data, nil, URLError(.badServerResponse))
    }

    func testStatusCodesMapToContractCases() {
        XCTAssertEqual(RouteNavFailure(response(401, code: "SIGN_IN_REQUIRED")), .signInRequired)
        XCTAssertEqual(RouteNavFailure(response(404, code: "COURSE_NOT_FOUND")), .notFound)
        XCTAssertEqual(RouteNavFailure(response(409, code: "COURSE_NOT_ACTIVE")), .courseNotActive)
        XCTAssertEqual(RouteNavFailure(response(503)), .providerDown)
        XCTAssertEqual(RouteNavFailure(response(500)), .other(status: 500))
    }

    /// 422 는 본문의 `code` 로 뜻이 갈린다 — 경로 자체가 없는 것과 정류장이 없는 것.
    func testNoRouteKeepsApiCode() {
        XCTAssertEqual(
            RouteNavFailure(response(422, code: "NO_TRANSIT_NEARBY")),
            .noRoute(code: "NO_TRANSIT_NEARBY")
        )
        // 본문이 없으면 경로 없음으로 떨어뜨린다 — 422 의 기본 뜻이다.
        XCTAssertEqual(RouteNavFailure(response(422)), .noRoute(code: "ROUTE_NOT_FOUND"))
    }

    /// 생성 클라이언트는 연결 실패를 음수 코드로 준다. HTTP 코드로 읽으면
    /// 「처리하지 못했어요 (-1)」 이 된다 — 서버 탓이 아닌데.
    func testNegativeCodeIsUnreachable() {
        XCTAssertEqual(RouteNavFailure(response(-1)), .unreachable)
        XCTAssertEqual(RouteNavFailure(URLError(.notConnectedToInternet)), .unreachable)
    }

    /// 다시 불러도 같은 답이 오는 것에는 「다시 시도」를 두지 않는다.
    func testRetryOnlyWhereItCanChangeTheAnswer() {
        XCTAssertTrue(RouteNavFailure.providerDown.canRetry)
        XCTAssertTrue(RouteNavFailure.unreachable.canRetry)
        XCTAssertFalse(RouteNavFailure.noRoute(code: "ROUTE_NOT_FOUND").canRetry)
        XCTAssertFalse(RouteNavFailure.signInRequired.canRetry)
        XCTAssertFalse(RouteNavFailure.detourUnsupported.canRetry)
    }

    /// 「준비 중」은 서버가 없던 시절의 말이다. 어느 갈래에도 남아 있으면 안 된다.
    func testNoCaseStillSaysComingSoon() {
        let all: [RouteNavFailure] = [
            .signInRequired, .notFound, .courseNotActive, .noRoute(code: "ROUTE_NOT_FOUND"),
            .providerDown, .unreachable, .other(status: 500), .detourUnsupported, .unsavedCourse,
        ]
        for failure in all {
            XCTAssertFalse(failure.message.contains("준비 중"), failure.message)
        }
    }
}
