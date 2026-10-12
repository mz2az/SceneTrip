import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

final class AppleAuthStoreTests: XCTestCase {
    @MainActor
    func testUnconfiguredServerHidesAppleButtonAndDoesNotSignIn() async {
        let auth = AuthStore()
        var calls = 0
        await auth.signInWithApple { _ in
            calls += 1
            throw ErrorResponse.error(501, nil, nil, URLError(.badServerResponse))
        }
        XCTAssertFalse(auth.appleSignInEnabled)
        XCTAssertFalse(auth.signedIn)
        XCTAssertNil(auth.me)
        XCTAssertFalse(auth.busy)
        XCTAssertEqual(auth.message, tr("Apple 로그인이 아직 준비되지 않았어요"))
        await auth.signInWithApple { _ in
            calls += 1
            throw AppleOAuth.Failure.cancelled
        }
        XCTAssertEqual(calls, 1, "501 이후 Apple 인증 창을 다시 보내지 않는다")
    }

    @MainActor
    func testBusyGuardBlocksSecondAppleAndGoogleThenCancellationReleasesButtons() async {
        let auth = AuthStore()
        let entered = expectation(description: "Apple 인증 창 시작")
        var calls = 0
        var finish: CheckedContinuation<AuthSession, Error>?
        let first = Task { @MainActor in
            await auth.signInWithApple { _ in
                calls += 1
                return try await withCheckedThrowingContinuation { continuation in
                    finish = continuation
                    entered.fulfill()
                }
            }
        }
        await fulfillment(of: [entered], timeout: 1)
        XCTAssertTrue(auth.busy)
        await auth.signInWithApple { _ in
            calls += 1
            throw AppleOAuth.Failure.cancelled
        }
        await auth.signInWithGoogle()
        XCTAssertEqual(calls, 1)
        finish?.resume(throwing: AppleOAuth.Failure.cancelled)
        await first.value
        XCTAssertFalse(auth.busy)
        XCTAssertTrue(auth.appleSignInEnabled)
        XCTAssertFalse(auth.signedIn)
        XCTAssertNil(auth.message)
    }

    @MainActor
    func testProviderOutageLeavesAppleButtonForFreshAttempt() async {
        let auth = AuthStore()
        await auth.signInWithApple { _ in
            throw ErrorResponse.error(503, Data(#"{"code":"AUTH_PROVIDER_UNAVAILABLE"}"#.utf8), nil,
                                      URLError(.badServerResponse))
        }
        XCTAssertTrue(auth.appleSignInEnabled)
        XCTAssertFalse(auth.busy)
        XCTAssertFalse(auth.signedIn)
        XCTAssertEqual(auth.message, tr("Apple에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요"))
    }
}
