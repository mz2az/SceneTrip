import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class GuestDataDeletionTests: XCTestCase {
    func testSuccessPassesSameInstallId() async {
        let id = UUID()
        var sent: [UUID] = []
        let outcome = await GuestDataDeletion.delete(installId: id) {
            sent.append($0)
        }
        XCTAssertEqual(outcome, .deleted)
        XCTAssertEqual(sent, [id])
    }

    func testSessionRequiredIsDistinctFromOtherFailures() async {
        let outcome = await GuestDataDeletion.delete(installId: UUID()) { _ in
            throw self.serverError(401, code: "SESSION_REQUIRED")
        }
        XCTAssertEqual(outcome, .sessionRequired)
    }

    func testNetworkServerAndOtherAuthFailuresStayFailures() async {
        let errors: [Error] = [URLError(.notConnectedToInternet), URLError(.timedOut),
                               serverError(500), serverError(503), serverError(401),
                               serverError(500, code: "SESSION_REQUIRED")]
        for error in errors {
            let outcome = await GuestDataDeletion.delete(installId: UUID()) { _ in throw error }
            XCTAssertEqual(outcome, .failed)
        }
    }

    func testRowIsOnlyVisibleToGuests() {
        XCTAssertTrue(GuestDataDeletion.isVisible(signedIn: false))
        XCTAssertFalse(GuestDataDeletion.isVisible(signedIn: true))
    }

    func testGeneratedRequestSendsOnlyInstallIdentityAndAccepts204() async {
        let headers = SceneApiClientAPI.customHeaders
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer {
            SceneApiClientAPI.customHeaders = headers
            SceneApiClientAPI.requestBuilderFactory = factory
        }
        SceneApiClientAPI.requestBuilderFactory = AuthRequestBuilderFactory()
        SceneApiClientAPI.customHeaders["Authorization"] = "Bearer unit-global"
        SceneApiClientAPI.customHeaders["authorization"] = "Bearer unit-lowercase"
        let id = UUID()
        let session = RetryFakeSession([.status(204)])
        let outcome = await GuestDataDeletion.delete(installId: id) {
            try await GuestDeleteRequest.send(installId: $0, session: session)
        }
        XCTAssertEqual(outcome, .deleted)
        XCTAssertEqual(session.requests.count, 1)
        XCTAssertEqual(session.requests.first?.httpMethod, "DELETE")
        XCTAssertEqual(session.requests.first?.url?.path, "/v1/me")
        XCTAssertEqual(session.requests.first?.value(forHTTPHeaderField: "X-Install-Id"), id.uuidString)
        XCTAssertNil(session.requests.first?.value(forHTTPHeaderField: "Authorization"))
    }

    func testGeneratedFailuresNeverRetryOrClearSession() async {
        let tokens = AuthTokens.snapshot
        let replies: [(FakeReply, GuestDataDeletion.Outcome)] = [
            (.status(401, code: "SESSION_REQUIRED"), .sessionRequired),
            (.status(503), .failed), (.error(.timedOut), .failed), (.status(200), .failed),
        ]
        for (reply, expected) in replies {
            let session = RetryFakeSession([reply])
            let outcome = await GuestDataDeletion.delete(installId: UUID()) {
                try await GuestDeleteRequest.send(installId: $0, session: session)
            }
            XCTAssertEqual(outcome, expected)
            XCTAssertEqual(session.requests.count, 1)
            XCTAssertTrue(AuthTokens.isSameSession(as: tokens))
        }
    }

    func testSignedInUserCannotDeleteGuestData() async {
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let auth = AuthStore(hasStoredSession: { true }, fetchProfile: { throw URLError(.cancelled) }, refreshLikes: {})
        auth.start()
        var calls = 0
        var clears = 0
        let result = await auth.deleteDeviceData(delete: { _ in
            calls += 1
            return .deleted
        }, clearOwner: { clears += 1 })
        XCTAssertEqual(result, .failed)
        XCTAssertTrue(auth.signedIn)
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(clears, 0)
    }

    func testOnlySuccessClearsLocalDataWithoutChangingIdentityOrSession() async {
        let auth = AuthStore(hasStoredSession: { false }, refreshLikes: {})
        let id = InstallIdentity.current
        let tokens = AuthTokens.snapshot
        var clears = 0
        for outcome in [GuestDataDeletion.Outcome.failed, .sessionRequired, .deleted] {
            let result = await auth.deleteDeviceData(delete: { _ in outcome }, clearOwner: { clears += 1 })
            XCTAssertEqual(result, outcome)
            XCTAssertEqual(clears, outcome == .deleted ? 1 : 0)
            XCTAssertEqual(auth.epoch, outcome == .deleted ? 1 : 0)
            XCTAssertFalse(auth.signedIn)
            XCTAssertFalse(auth.busy)
            XCTAssertEqual(InstallIdentity.current, id)
            XCTAssertTrue(AuthTokens.isSameSession(as: tokens))
            XCTAssertEqual(AuthTokens.accessToken, tokens.accessToken)
        }
    }

    func testDuplicateDeleteDoesNotSendOrClearTwice() async {
        let auth = AuthStore(hasStoredSession: { false }, refreshLikes: {})
        var calls = 0
        var clears = 0
        let result = await auth.deleteDeviceData(delete: { _ in
            calls += 1
            XCTAssertTrue(auth.busy)
            let duplicate = await auth.deleteDeviceData(delete: { _ in
                calls += 1
                return .deleted
            }, clearOwner: { clears += 1 })
            XCTAssertEqual(duplicate, .failed)
            return .deleted
        }, clearOwner: { clears += 1 })
        XCTAssertEqual(result, .deleted)
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(clears, 1)
    }

    func testPersistedFavoritesArePreservedOnFailureAndClearedOnSuccess() async throws {
        let suite = "GuestDataDeletionTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set([12, 34], forKey: "scenetrip.likedContents")
        let likes = LikeStore(defaults: defaults, refreshOnInit: false)
        let auth = AuthStore(hasStoredSession: { false }, refreshLikes: {})

        for outcome in [GuestDataDeletion.Outcome.failed, .sessionRequired] {
            _ = await auth.deleteDeviceData(delete: { _ in outcome }, clearOwner: {})
            XCTAssertEqual(likes.contentIds, [12, 34])
            XCTAssertEqual(defaults.array(forKey: "scenetrip.likedContents")?.count, 2)
        }
        _ = await auth.deleteDeviceData(delete: { _ in .deleted }, clearOwner: {})
        XCTAssertTrue(likes.contentIds.isEmpty)
        XCTAssertTrue(likes.works.isEmpty)
        XCTAssertEqual(defaults.array(forKey: "scenetrip.likedContents")?.count, 0)
        XCTAssertTrue(defaults.bool(forKey: "scenetrip.likesOnServer"), "삭제한 옛 찜을 다시 올리지 않는다")
    }

    private func serverError(_ status: Int, code: String = "OTHER") -> ErrorResponse {
        .error(status, Data("{\"code\":\"\(code)\"}".utf8), nil, URLError(.badServerResponse))
    }
}
