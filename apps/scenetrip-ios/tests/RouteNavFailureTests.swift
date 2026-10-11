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
        // 401 이 전부 「가입하세요」는 아니다 — 세션이 깨진 것은 다시 로그인이다 (MZ2AZ-336).
        XCTAssertEqual(RouteNavFailure(response(401, code: "ACCESS_TOKEN_INVALID")), .sessionExpired)
        XCTAssertEqual(RouteNavFailure(response(401, code: "ACCESS_TOKEN_EXPIRED")), .sessionExpired)
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

    /// 오프라인·서버 연결 거부 모두 개발 서버를 켜라는 말 대신 사용자가 할 수 있는 복구를 안내한다.
    func testConnectionFailuresGiveLocalizedRecoveryAdvice() throws {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .ko
        // 호스트 없는 XCTest는 앱의 번역 표를 테스트 번들에 싣는다.
        let englishPath = try XCTUnwrap(Bundle(for: Self.self).path(forResource: "en", ofType: "lproj"))
        let english = try XCTUnwrap(Bundle(path: englishPath))
        let errors: [Error] = [
            response(-1), URLError(.notConnectedToInternet), URLError(.cannotConnectToHost),
        ]
        for language in [Lang.ko, .en] {
            for error in errors {
                let failure = RouteNavFailure(error)
                XCTAssertEqual(failure, .unreachable)
                XCTAssertTrue(failure.canRetry)
                let message = language == .ko ? failure.message : english.localizedString(
                    forKey: failure.message, value: nil, table: nil
                )
                XCTAssertFalse(message.contains("8081"), message)
                XCTAssertFalse(message.contains("백엔드"), message)
                XCTAssertFalse(message.lowercased().contains("backend"), message)
                if language == .ko {
                    XCTAssertTrue(message.contains("인터넷 연결"), message)
                    XCTAssertTrue(message.contains("다시 시도"), message)
                } else {
                    XCTAssertTrue(message.contains("Check your internet connection"), message)
                    XCTAssertTrue(message.contains("try again"), message)
                }
            }
        }
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
