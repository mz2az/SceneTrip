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

    /// 누구나 읽는 창구는 만료된 토큰에 401 을 주지 않는다 — 부르기 전에 살핀다(MZ2AZ-363).
    func testAccessTokenIsStaleWhenExpiredOrAboutTo() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertFalse(AuthRules.stale(expiresAt: now.addingTimeInterval(1800), now: now))
        XCTAssertFalse(AuthRules.stale(expiresAt: now.addingTimeInterval(61), now: now))
        XCTAssertTrue(AuthRules.stale(expiresAt: now.addingTimeInterval(60), now: now), "곧 죽는다 — 가는 길에 죽는다")
        XCTAssertTrue(AuthRules.stale(expiresAt: now.addingTimeInterval(-1), now: now))
        // 죽는 때를 적기 전에 로그인한 설치본 — 모르면 갱신한다.
        XCTAssertTrue(AuthRules.stale(expiresAt: nil, now: now))
    }

    /// 서명된 사진 주소가 실려 오는 창구의 응답은 디스크에 남기지 않는다(MZ2AZ-363). 나머지는 기본 세션 그대로.
    func testSignedUrlEndpointsAreRecognised() {
        let base = "http://localhost:8081/v1"
        for path in [
            "/places/82", "/pois/1234", "/places/82/photos?offset=20&limit=20", "/pois/7/photos",
            "/places/82/reviews?sort=recent&limit=20&offset=0", "/pois/7/reviews", "/places/82/reviews/me",
            "/pois/7/reviews/me", "/me/reviews?limit=20",
        ] {
            XCTAssertTrue(AuthRules.carriesSignedUrls(url: base + path), path)
        }
        for path in [
            "/places?q=cafe", "/places/map?bbox=1,2,3,4", "/pois?bbox=1,2,3,4", "/contents/3", "/me", "/me/nickname",
            "/cart", "/cart/items", "/courses/12", "/uploads", "/auth/refresh", "/places/82/scenes",
        ] {
            XCTAssertFalse(AuthRules.carriesSignedUrls(url: base + path), path)
        }
    }
}
