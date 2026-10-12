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
}
