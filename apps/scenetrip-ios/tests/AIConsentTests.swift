import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class AIConsentTests: XCTestCase {
    private let storage = MemoryAIConsentStorage()

    func testFirstUseRequestsSheetAndBlocksBothEndpoints() {
        let store = AIConsentStore(storage: storage)
        let gate = AIConsentGate(store: store)
        var proceeded = false
        XCTAssertFalse(gate.isPresented, "앱 시작에는 묻지 않는다")
        gate.request { proceeded = true }
        XCTAssertTrue(gate.isPresented)
        XCTAssertFalse(proceeded)
        for path in ["/guide/chat", "/guide/plan"] {
            let (inner, result) = send(path, store: store)
            XCTAssertTrue(inner.requests.isEmpty)
            XCTAssertEqual(result.failures, [.consentRequired])
        }
    }

    func testAllowContinuesOnceAndCoversBothFeatures() {
        let store = AIConsentStore(storage: storage)
        let chat = AIConsentGate(store: store)
        var proceeds = 0
        chat.request { proceeds += 1 }
        chat.allow()
        XCTAssertEqual(proceeds, 0, "시트가 닫힌 뒤 진행한다")
        chat.didDismiss()
        chat.didDismiss()
        XCTAssertEqual(proceeds, 1)
        let planner = AIConsentGate(store: AIConsentStore(storage: storage))
        planner.request { proceeds += 1 }
        XCTAssertFalse(planner.isPresented)
        XCTAssertEqual(proceeds, 2)
        for path in ["/guide/chat", "/guide/plan"] {
            let (inner, result) = send(path, store: store)
            XCTAssertEqual(inner.requests.count, 1)
            XCTAssertEqual(result.statuses, [200])
        }
    }

    func testCancelAndSwipeDismissPreserveTypedQuestion() {
        let store = AIConsentStore(storage: storage)
        let gate = AIConsentGate(store: store)
        var draft = "  주변 촬영지 알려줘  "
        for explicitCancel in [true, false] {
            gate.request { draft = "" }
            if explicitCancel {
                gate.cancel()
            } else {
                gate.isPresented = false
            }
            gate.didDismiss()
            XCTAssertEqual(draft, "  주변 촬영지 알려줘  ")
            XCTAssertFalse(store.isAllowed)
            XCTAssertTrue(send("/guide/chat", store: store).0.requests.isEmpty)
        }
        gate.request { draft = "" }
        gate.allow()
        gate.didDismiss()
        XCTAssertEqual(draft, "")
    }

    func testRevokeViaSettingsAsksAgainAndCancelDoesNotEnable() {
        let store = AIConsentStore(storage: storage)
        let gate = AIConsentGate(store: store)
        gate.setEnabled(true)
        XCTAssertFalse(store.isAllowed, "켜기만으로 동의하지 않는다")
        gate.cancel()
        gate.didDismiss()
        XCTAssertFalse(store.isAllowed)
        gate.setEnabled(true)
        gate.allow()
        gate.didDismiss()
        XCTAssertTrue(store.isAllowed)
        gate.setEnabled(false)
        XCTAssertFalse(store.isAllowed)
        gate.request { XCTFail("철회 뒤 동의 없이 진행") }
        XCTAssertTrue(gate.isPresented)
        XCTAssertTrue(send("/guide/plan", store: store).0.requests.isEmpty)
    }

    func testVersionChangeRequiresNewConsent() {
        let original = AIConsentStore(storage: storage, version: 1)
        original.allow()
        XCTAssertTrue(AIConsentStore(storage: storage, version: 1).isAllowed)
        let updated = AIConsentStore(storage: storage, version: 2)
        let gate = AIConsentGate(store: updated)
        gate.request {}
        XCTAssertTrue(gate.isPresented)
        XCTAssertTrue(send("/guide/chat", store: updated).0.requests.isEmpty)
        gate.allow()
        gate.didDismiss()
        XCTAssertTrue(updated.isAllowed)
        XCTAssertFalse(original.isAllowed, "다른 버전을 묵시적으로 허용하지 않는다")
    }

    func testOtherRequestsAreUnaffected() {
        let store = AIConsentStore(storage: storage)
        for path in ["/courses", "/navigation/next-leg", "/events"] {
            XCTAssertEqual(send(path, store: store).0.requests.count, 1)
        }
    }

    func testUserDefaultsRestoresConsentAndRevocation() throws {
        let suite = "AIConsentTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AIConsentStore(storage: defaults)
        XCTAssertFalse(store.isAllowed)
        store.allow()
        let restored = try AIConsentStore(storage: XCTUnwrap(UserDefaults(suiteName: suite)))
        XCTAssertTrue(restored.isAllowed)
        restored.revoke()
        XCTAssertFalse(store.isAllowed)
    }

    func testRevocationBeforeSheetFinishesDoesNotRunPendingAction() {
        let store = AIConsentStore(storage: storage)
        let gate = AIConsentGate(store: store)
        gate.request { XCTFail("허용 후 철회되면 진행하지 않는다") }
        gate.allow()
        store.revoke()
        gate.didDismiss()
        gate.request {}
        XCTAssertTrue(gate.isPresented)
    }

    func testRevocationWhileWaitingStopsAutomaticChatRetry() throws {
        let store = AIConsentStore(storage: storage)
        store.allow()
        let clock = RetryFakeClock()
        clock.manual = true
        let inner = RetryFakeSession([.status(503), .status(200)])
        let ledger = RateLimitLedger()
        let url = try XCTUnwrap(URL(string: "https://example.invalid/v1/guide/chat"))
        try ledger.record(path: "/v1/guide/chat", response: XCTUnwrap(HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["RateLimit-Limit": "15", "RateLimit-Remaining": "14", "RateLimit-Reset": "30"]
        )), now: clock.now)
        var environment = clock.environment()
        environment.aiConsent = store
        let session = RetryingSession(
            inner: inner, environment: environment, ledger: ledger, keyClock: IdempotencyClock()
        )
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(UUID().uuidString, forHTTPHeaderField: "Idempotency-Key")
        let result = AIConsentResults()
        session.dataTaskFromProtocol(with: request) { _, _, error in
            if let error {
                result.failures.append(RouteGuideFailure(error))
            }
        }.resume()
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(clock.sleeping.count, 1)
        store.revoke()
        clock.sleeping.first?()
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(result.failures, [.consentRequired])
    }

    func testRevocationAfterConsentCheckPreventsTaskResume() {
        let store = AIConsentStore(storage: storage)
        for path in ["/guide/chat", "/guide/plan"] {
            store.allow()
            var injected = false
            let (inner, result) = send(path, store: store) { _ in
                injected = true
                store.revoke()
                return nil
            }
            XCTAssertTrue(injected, "첫 동의 검사를 통과한 뒤 철회한다")
            XCTAssertEqual(inner.resumedTasks, 0, "철회 완료 뒤 요청을 시작하지 않는다")
            XCTAssertEqual(result.failures, [.consentRequired])
            XCTAssertTrue(result.statuses.isEmpty)
        }
    }

    private func send(
        _ path: String, store: AIConsentStore,
        fault: @escaping (URLRequest) -> NetFault.Injection? = { _ in nil }
    ) -> (RetryFakeSession, AIConsentResults) {
        let inner = RetryFakeSession([.status(200)])
        var environment = RetryFakeClock().environment(fault: fault)
        environment.aiConsent = store
        let session = RetryingSession(inner: inner, environment: environment, ledger: RateLimitLedger())
        var request = URLRequest(url: URL(string: "https://example.invalid/v1\(path)")!)
        request.httpMethod = "POST"
        let result = AIConsentResults()
        session.dataTaskFromProtocol(with: request) { _, response, error in
            if let error {
                result.failures.append(RouteGuideFailure(error))
            }
            if let response = response as? HTTPURLResponse {
                result.statuses.append(response.statusCode)
            }
        }.resume()
        return (inner, result)
    }
}

final class MemoryAIConsentStorage: AIConsentStorage {
    private var values: [String: Int] = [:]
    func integer(forKey key: String) -> Int {
        values[key] ?? 0
    }

    func set(_ value: Int, forKey key: String) {
        values[key] = value
    }
}

private final class AIConsentResults: @unchecked Sendable {
    var failures: [RouteGuideFailure] = []
    var statuses: [Int] = []
}
