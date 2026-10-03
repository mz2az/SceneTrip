import CryptoKit
@testable import SceneTrip
import XCTest

/// 401 을 무엇으로 읽는가 (MZ2AZ-336). 계약 `docs/api/errors.md` 인증 표와 같아야 한다.
///
/// 틀어지면 만료된 토큰으로 로그아웃되거나(갱신해야 하는데 지움), 탈취 판정된 토큰을
/// 계속 갱신하려 든다(지워야 하는데 갱신).
final class AuthRulesTests: XCTestCase {
    func testExpiredAccessTokenRefreshesAndRetries() {
        XCTAssertEqual(AuthRules.action(status: 401, code: "ACCESS_TOKEN_EXPIRED"), .refreshAndRetry)
    }

    func testBrokenSessionSignsOut() {
        for code in ["ACCESS_TOKEN_INVALID", "SESSION_REQUIRED", "REFRESH_TOKEN_INVALID"] {
            XCTAssertEqual(AuthRules.action(status: 401, code: code), .signOut, code)
        }
    }

    func testGuestHittingMemberOnlyFeatureIsPromptedToSignIn() {
        XCTAssertEqual(AuthRules.action(status: 401, code: "SIGN_IN_REQUIRED"), .promptSignIn)
    }

    func testOtherResponsesAreLeftAlone() {
        XCTAssertEqual(AuthRules.action(status: 401, code: nil), .none)
        XCTAssertEqual(AuthRules.action(status: 403, code: "ACCESS_TOKEN_EXPIRED"), .none)
        XCTAssertEqual(AuthRules.action(status: 500, code: "INTERNAL_ERROR"), .none)
    }

    func testReadsCodeFromErrorBody() {
        let body = Data(#"{"code":"ACCESS_TOKEN_EXPIRED","message":"만료"}"#.utf8)
        XCTAssertEqual(AuthRules.apiCode(from: body), "ACCESS_TOKEN_EXPIRED")
        XCTAssertNil(AuthRules.apiCode(from: Data("not json".utf8)))
        XCTAssertNil(AuthRules.apiCode(from: nil))
    }

    /// 로그인·갱신 창구 자신의 401 은 가로채지 않는다 — 갱신이 갱신을 부르면 끝나지 않는다.
    func testAuthEndpointsAreNotIntercepted() {
        XCTAssertFalse(AuthRules.intercepts(url: "http://localhost:8081/v1/auth/refresh"))
        XCTAssertFalse(AuthRules.intercepts(url: "http://localhost:8081/v1/auth/google"))
        XCTAssertTrue(AuthRules.intercepts(url: "http://localhost:8081/v1/cart"))
        XCTAssertTrue(AuthRules.intercepts(url: "http://localhost:8081/v1/me"))
    }

    // MARK: 갱신은 동시에 하나

    /// 여러 요청이 동시에 만료를 만나도 갱신은 **한 번만** 나간다. 같은 리프레시 토큰으로 두 번
    /// 갱신하면 서버가 탈취로 보고 그 계정의 토큰을 전부 폐기한다 — 사용자가 로그아웃된다.
    func testConcurrentCallersShareOneRefresh() async throws {
        let flight = SingleFlight<Int>()
        let calls = Counter()
        let results = try await withThrowingTaskGroup(of: Int.self) { group in
            for _ in 0 ..< 8 {
                group.addTask {
                    try await flight.run {
                        await calls.increment()
                        try await Task.sleep(nanoseconds: 80_000_000)
                        return 7
                    }
                }
            }
            return try await group.reduce(into: [Int]()) { $0.append($1) }
        }
        XCTAssertEqual(results, Array(repeating: 7, count: 8))
        let count = await calls.value
        XCTAssertEqual(count, 1)
    }

    /// 끝난 뒤의 다음 만료는 새로 갱신한다.
    func testNextRoundRunsAgain() async throws {
        let flight = SingleFlight<Int>()
        let calls = Counter()
        for _ in 0 ..< 2 {
            _ = try await flight.run {
                await calls.increment()
                return 1
            }
        }
        let count = await calls.value
        XCTAssertEqual(count, 2)
    }

    private actor Counter {
        var value = 0
        func increment() {
            value += 1
        }
    }

    // MARK: 구글 로그인 재료

    func testNonceIsLongEnoughAndFreshEachTime() {
        let first = GoogleOAuth.randomToken()
        let second = GoogleOAuth.randomToken()
        XCTAssertGreaterThanOrEqual(first.count, 43) // 32 바이트 base64url
        XCTAssertNotEqual(first, second)
    }

    /// RFC 7636 부록 B 의 예시.
    func testPkceChallengeMatchesRfcExample() {
        XCTAssertEqual(
            GoogleOAuth.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        )
    }

    func testAuthorizationUrlCarriesNonceAndChallenge() throws {
        let url = GoogleOAuth.authorizationURL(nonce: "N0NCE", state: "ST", challenge: "CH")
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(query["client_id"], GoogleOAuth.clientId)
        XCTAssertEqual(query["nonce"], "N0NCE")
        XCTAssertEqual(query["state"], "ST")
        XCTAssertEqual(query["code_challenge"], "CH")
        XCTAssertEqual(query["code_challenge_method"], "S256")
        XCTAssertEqual(query["response_type"], "code")
        XCTAssertEqual(query["redirect_uri"], GoogleOAuth.redirectURI)
    }

    func testCallbackYieldsCodeOnlyWhenStateMatches() throws {
        let good = try XCTUnwrap(URL(string: "\(GoogleOAuth.redirectURI)?state=ST&code=abc"))
        XCTAssertEqual(GoogleOAuth.code(from: good, expectedState: "ST"), "abc")
        XCTAssertNil(GoogleOAuth.code(from: good, expectedState: "OTHER"))
        let denied = try XCTUnwrap(URL(string: "\(GoogleOAuth.redirectURI)?state=ST&error=access_denied"))
        XCTAssertNil(GoogleOAuth.code(from: denied, expectedState: "ST"))
    }
}
