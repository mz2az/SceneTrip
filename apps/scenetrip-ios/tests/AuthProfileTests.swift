import Combine
import SceneApiClient
@testable import SceneTrip
import XCTest

final class AuthProfileTests: XCTestCase {
    private func user(confirmed: Bool) -> Me {
        Me(nickname: "여행자12345", nicknameConfirmed: confirmed, id: UUID(), identities: [],
           registeredAt: Date(timeIntervalSince1970: 1))
    }

    @MainActor func testRestoredSessionAsksForUnconfirmedNickname() async {
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let profile = user(confirmed: false)
        let auth = AuthStore(hasStoredSession: { true }, fetchProfile: { profile },
                             refreshLikes: {}, nicknamePromptDelay: .zero)
        let shown = expectation(description: "복원한 회원의 닉네임 질문")
        let subscription = auth.$askingNickname.filter { $0 }.prefix(1).sink { _ in shown.fulfill() }
        defer { subscription.cancel() }
        auth.start()
        await fulfillment(of: [shown], timeout: 1)
        XCTAssertEqual(auth.me?.id, profile.id)
        XCTAssertTrue(auth.signedIn)
    }

    @MainActor func testPreviouslySkippedNicknameIsNotAskedAfterRestoration() async {
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let profile = user(confirmed: false)
        NicknameAsked.mark(profile.id.uuidString)
        defer { UserDefaults.standard.removeObject(forKey: "scenetrip.nickname.asked.\(profile.id.uuidString)") }
        let auth = AuthStore(hasStoredSession: { true }, fetchProfile: { profile }, refreshLikes: {})
        let loaded = expectation(description: "회원 복원")
        let subscription = auth.$me.compactMap { $0 }.prefix(1).sink { _ in loaded.fulfill() }
        defer { subscription.cancel() }
        auth.start()
        await fulfillment(of: [loaded], timeout: 1)
        XCTAssertFalse(auth.askingNickname)
    }

    @MainActor func testProfileReturningAfterSessionLossCannotRestoreAccount() async {
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let profile = user(confirmed: false)
        let entered = expectation(description: "프로필 요청 시작")
        let returned = expectation(description: "늦은 프로필 응답")
        var finish: CheckedContinuation<Me, Never>?
        let auth = AuthStore(hasStoredSession: { true }, fetchProfile: {
            let fresh = await withCheckedContinuation { continuation in
                finish = continuation
                entered.fulfill()
            }
            returned.fulfill()
            return fresh
        }, refreshLikes: {}, nicknamePromptDelay: .zero)
        auth.start()
        await fulfillment(of: [entered], timeout: 1)
        auth.sessionLost()
        finish?.resume(returning: profile)
        await fulfillment(of: [returned], timeout: 1)
        await Task.yield()
        XCTAssertFalse(auth.signedIn)
        XCTAssertNil(auth.me)
        XCTAssertFalse(auth.askingNickname)
    }

    @MainActor func testNicknameReturningAfterAccountSwitchCannotReplaceNewAccount() async {
        let first = user(confirmed: true)
        let second = user(confirmed: true)
        let auth = AuthStore(refreshLikes: {})
        defer {
            AuthTokens.clear()
            UserDefaults.standard.removeObject(forKey: "scenetrip.nickname.asked.\(first.id.uuidString)")
        }
        await auth.signInWithApple { _ in self.session(first) }
        let entered = expectation(description: "첫 계정의 닉네임 저장 시작")
        var finish: CheckedContinuation<Me, Never>?
        let saving = Task { @MainActor in
            await auth.setNickname("서울여행자", save: { _ in
                await withCheckedContinuation { continuation in
                    finish = continuation
                    entered.fulfill()
                }
            })
        }
        await fulfillment(of: [entered], timeout: 1)
        auth.sessionLost()
        await auth.signInWithApple { _ in self.session(second) }
        finish?.resume(returning: first)
        let outcome = await saving.value
        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(auth.me?.id, second.id)
        XCTAssertFalse(NicknameAsked.has(first.id.uuidString))
    }

    @MainActor func testNicknameResponseForDifferentAccountIsRejected() async {
        let current = user(confirmed: true)
        let unrelated = user(confirmed: true)
        let auth = AuthStore(refreshLikes: {})
        defer {
            AuthTokens.clear()
            UserDefaults.standard.removeObject(forKey: "scenetrip.nickname.asked.\(unrelated.id.uuidString)")
        }
        await auth.signInWithApple { _ in self.session(current) }
        let outcome = await auth.setNickname("제주여행자", save: { _ in unrelated })
        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(auth.me?.id, current.id)
        XCTAssertFalse(NicknameAsked.has(unrelated.id.uuidString))
    }

    @MainActor func testNicknameSuccessNormalizesInputAndKeepsCurrentAccount() async {
        let current = user(confirmed: true)
        var updated = current
        updated.nickname = "서울팬"
        let auth = AuthStore(refreshLikes: {})
        defer {
            AuthTokens.clear()
            UserDefaults.standard.removeObject(forKey: "scenetrip.nickname.asked.\(current.id.uuidString)")
        }
        await auth.signInWithApple { _ in self.session(current) }
        let outcome = await auth.setNickname("  서울팬\n", save: { input in
            XCTAssertEqual(input.nickname, "서울팬")
            return updated
        })
        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(auth.me?.id, current.id)
        XCTAssertEqual(auth.me?.nickname, "서울팬")
        XCTAssertTrue(NicknameAsked.has(current.id.uuidString))
    }

    @MainActor func testOldProfileCannotOverwriteSavedNickname() async {
        var old = user(confirmed: false)
        old.nickname = "이전닉네임"
        var updated = old
        updated.nickname = "새닉네임"
        updated.nicknameConfirmed = true
        let entered = expectation(description: "옛 프로필 조회 시작")
        var finish: CheckedContinuation<Me, Never>?
        let auth = AuthStore(fetchProfile: {
            await withCheckedContinuation {
                finish = $0
                entered.fulfill()
            }
        }, refreshLikes: {})
        defer {
            AuthTokens.clear()
            UserDefaults.standard.removeObject(forKey: "scenetrip.nickname.asked.\(old.id.uuidString)")
        }
        await auth.signInWithApple { _ in self.session(old) }
        let loading = Task { @MainActor in await auth.loadMe() }
        await fulfillment(of: [entered], timeout: 1)
        let outcome = await auth.setNickname("새닉네임", save: { _ in updated })
        XCTAssertEqual(outcome, .saved)
        finish?.resume(returning: old)
        await loading.value
        XCTAssertEqual(auth.me?.nickname, "새닉네임")
        XCTAssertEqual(auth.me?.nicknameConfirmed, true)
    }

    private func session(_ profile: Me) -> AuthSession {
        AuthSession(accessToken: "unit-access", accessTokenExpiresIn: 1800, refreshToken: "unit-refresh",
                    refreshTokenExpiresIn: 2_592_000, user: profile, isNewUser: false, merged: false)
    }
}
