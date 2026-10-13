import SceneApiClient
@testable import SceneTrip
import XCTest

final class AuthRefreshTests: XCTestCase {
    @MainActor func testDelayedRefreshCannotReplaceNewLogin() async {
        let entered = expectation(description: "첫 계정 갱신 시작")
        let gate = RefreshGate(entered: entered)
        AuthTokens.store(session("first"))
        defer { AuthTokens.clear() }
        let refreshing = Task {
            await AuthRefresher.refresh(request: { try await gate.request($0) }, sessionLost: {})
        }
        await fulfillment(of: [entered], timeout: 1)
        AuthTokens.clear()
        AuthTokens.store(session("second"))
        await gate.finish(.success(session("first-rotated")))
        let changed = await refreshing.value
        XCTAssertFalse(changed)
        XCTAssertEqual(AuthTokens.accessToken, "unit-second-access")
        XCTAssertEqual(AuthTokens.refreshToken, "unit-second-refresh")
    }

    @MainActor func testDelayedRejectionCannotSignOutNewLogin() async {
        let entered = expectation(description: "거절될 갱신 시작")
        let gate = RefreshGate(entered: entered)
        AuthTokens.store(session("first"))
        defer { AuthTokens.clear() }
        var losses = 0
        let refreshing = Task {
            await AuthRefresher.refresh(request: { try await gate.request($0) }, sessionLost: {
                losses += 1
                AuthTokens.clear()
            })
        }
        await fulfillment(of: [entered], timeout: 1)
        AuthTokens.clear()
        AuthTokens.store(session("second"))
        await gate.finish(.failure(ErrorResponse.error(401, nil, nil, NSError(domain: "unit", code: 401))))
        let changed = await refreshing.value
        XCTAssertFalse(changed)
        XCTAssertEqual(losses, 0)
        XCTAssertEqual(AuthTokens.accessToken, "unit-second-access")
    }

    @MainActor func testCurrentRefreshStillRotatesBothTokens() async {
        AuthTokens.store(session("first"))
        defer { AuthTokens.clear() }
        let changed = await AuthRefresher.refresh(request: { token in
            XCTAssertEqual(token, "unit-first-refresh")
            return self.session("first-rotated")
        }, sessionLost: { XCTFail("정상 갱신에서 세션을 지우면 안 됩니다") })
        XCTAssertTrue(changed)
        XCTAssertEqual(AuthTokens.accessToken, "unit-first-rotated-access")
        XCTAssertEqual(AuthTokens.refreshToken, "unit-first-rotated-refresh")
    }

    @MainActor func testNewAccountDoesNotWaitForOldRefresh() async {
        let firstEntered = expectation(description: "옛 계정 갱신 대기")
        let secondEntered = expectation(description: "새 계정 갱신은 독립 실행")
        let gate = RefreshGate(entered: firstEntered)
        AuthTokens.store(session("first"))
        defer { AuthTokens.clear() }
        let first = Task {
            await AuthRefresher.refresh(request: { try await gate.request($0) }, sessionLost: {})
        }
        await fulfillment(of: [firstEntered], timeout: 1)
        AuthTokens.clear()
        AuthTokens.store(session("second"))
        let second = Task {
            await AuthRefresher.refresh(request: { token in
                XCTAssertEqual(token, "unit-second-refresh")
                secondEntered.fulfill()
                return self.session("second-rotated")
            }, sessionLost: {})
        }
        await fulfillment(of: [secondEntered], timeout: 1)
        await gate.finish(.success(session("first-rotated")))
        let firstChanged = await first.value
        let secondChanged = await second.value
        XCTAssertFalse(firstChanged)
        XCTAssertTrue(secondChanged)
        XCTAssertEqual(AuthTokens.accessToken, "unit-second-rotated-access")
    }

    @MainActor func testOldRequestDoesNotRefreshAnotherAccount() async {
        AuthTokens.store(session("first"))
        let old = AuthTokens.snapshot
        AuthTokens.clear()
        AuthTokens.store(session("second"))
        defer { AuthTokens.clear() }
        let changed = await AuthRefresher.refresh(for: old, request: { _ in
            XCTFail("이전 계정의 요청을 새 계정으로 재전송하면 안 됩니다")
            return self.session("unexpected")
        }, sessionLost: {})
        XCTAssertFalse(changed)
        XCTAssertEqual(AuthTokens.accessToken, "unit-second-access")
    }

    private func session(_ name: String) -> AuthSession {
        AuthSession(accessToken: "unit-\(name)-access", accessTokenExpiresIn: 1800,
                    refreshToken: "unit-\(name)-refresh", refreshTokenExpiresIn: 2_592_000,
                    user: Me(nickname: "시험회원", nicknameConfirmed: true,
                             id: UUID(uuidString: "00000000-0000-0000-0000-000000000148")!, identities: [],
                             registeredAt: Date(timeIntervalSince1970: 1)),
                    isNewUser: false, merged: false)
    }
}

private actor RefreshGate {
    private let entered: XCTestExpectation
    private var continuation: CheckedContinuation<AuthSession, Error>?

    init(entered: XCTestExpectation) {
        self.entered = entered
    }

    func request(_: String) async throws -> AuthSession {
        try await withCheckedThrowingContinuation {
            continuation = $0
            entered.fulfill()
        }
    }

    func finish(_ result: Result<AuthSession, Error>) {
        continuation?.resume(with: result)
        continuation = nil
    }
}
