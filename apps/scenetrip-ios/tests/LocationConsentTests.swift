import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class LocationConsentTests: XCTestCase {
    func testCancelRevokeAndVersionRequireConsentAgain() {
        let storage = MemoryAIConsentStorage()
        let store = LocationConsentStore(storage: storage)
        let gate = AIConsentGate(store: store)
        var runs = 0
        gate.request { runs += 1 }
        XCTAssertTrue(gate.isPresented)
        gate.cancel()
        gate.didDismiss()
        XCTAssertEqual(runs, 0)
        gate.request { runs += 1 }
        gate.allow()
        gate.didDismiss()
        gate.didDismiss()
        XCTAssertEqual(runs, 1)
        XCTAssertTrue(LocationConsentStore(storage: storage).isAllowed)
        gate.setEnabled(false)
        gate.request { runs += 1 }
        XCTAssertTrue(gate.isPresented)
        XCTAssertEqual(runs, 1)
        store.allow()
        XCTAssertFalse(LocationConsentStore(storage: storage, version: 2).isAllowed)
    }

    func testRequiredCoordinateRequestsNeverReachTransportWithoutConsent() {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        for path in ["/guide/chat", "/navigation/next-leg", "/navigation/route"] {
            let result = send(path, store: store)
            XCTAssertTrue(result.inner.requests.isEmpty)
            XCTAssertTrue(result.state.required)
            store.allow()
            XCTAssertEqual(send(path, store: store).inner.resumedTasks, 1)
            store.revoke()
            XCTAssertTrue(send(path, store: store).inner.requests.isEmpty)
        }
    }

    func testOptionalPlanCoordinatesAreRemovedButMapViewportIsPreserved() throws {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        let result = send("/guide/plan", store: store)
        let body = try XCTUnwrap(result.inner.requests.first?.httpBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertNil(json["latitude"])
        XCTAssertNil(json["longitude"])
        XCTAssertEqual(json["days"] as? Int, 2)
        store.allow()
        XCTAssertEqual(send("/guide/plan", store: store).inner.requests.first?.httpBody, Data(payload.utf8))
        store.revoke()
        let address = "https://example.invalid/v1/pois?bbox=126,37,127,38&lat=37&lng=127"
        var map = try URLRequest(url: XCTUnwrap(URL(string: address)))
        map.httpMethod = "GET"
        map.setValue(
            LocationCoordinateSource.userSelected.rawValue, forHTTPHeaderField: LocationCoordinateSource.header
        )
        XCTAssertEqual(try store.requestForSending(map), map, "지도 중심은 기기 좌표가 아니다")
    }

    func testRevocationImmediatelyBeforeResumeStillBlocksCoordinates() {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        store.allow()
        let result = send("/navigation/next-leg", store: store) { _ in
            store.revoke()
            return nil
        }
        XCTAssertEqual(result.inner.resumedTasks, 0)
        XCTAssertTrue(result.state.required)
    }

    func testKakaoNoticeOncePerUserRequestAndIgnoresOldResponse() {
        let notice = KakaoDirectionsNotice()
        let first = notice.beginRequest()
        notice.succeeded(first)
        let shown = notice.visibleRequest
        XCTAssertEqual(shown, first)
        notice.hide(first)
        notice.succeeded(first) // 위치 관찰·중복 완료는 같은 사용자 요청이다.
        XCTAssertNil(notice.visibleRequest)
        let second = notice.beginRequest()
        notice.succeeded(first)
        XCTAssertNil(notice.visibleRequest)
        notice.succeeded(second)
        notice.hide(first)
        XCTAssertEqual(notice.visibleRequest, second)
    }

    func testEachOverlappingSuccessfulRequestStillNotifies() {
        let notice = KakaoDirectionsNotice()
        let first = notice.beginRequest()
        let second = notice.beginRequest()
        notice.succeeded(first)
        XCTAssertEqual(notice.visibleRequest, first)
        notice.succeeded(second)
        XCTAssertEqual(notice.visibleRequest, second)
        notice.hide(first)
        XCTAssertEqual(notice.visibleRequest, second)
        notice.hide(second)
        notice.succeeded(first)
        XCTAssertNil(notice.visibleRequest)
    }

    func testRevocationStopsAutomaticGuideResend() throws {
        let consent = LocationConsentStore(storage: MemoryAIConsentStorage())
        consent.allow()
        let clock = RetryFakeClock()
        clock.manual = true
        let ledger = RateLimitLedger()
        let url = try XCTUnwrap(URL(string: "https://example.invalid/v1/guide/chat"))
        try ledger.record(path: url.path, response: XCTUnwrap(HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["RateLimit-Limit": "15", "RateLimit-Remaining": "14", "RateLimit-Reset": "30"]
        )), now: clock.now)
        var environment = clock.environment()
        environment.locationConsent = consent
        let inner = RetryFakeSession([.status(503), .status(200)])
        let session = RetryingSession(inner: inner, environment: environment, ledger: ledger)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data(payload.utf8)
        request.setValue(UUID().uuidString, forHTTPHeaderField: "Idempotency-Key")
        let state = LocationRequestState()
        session.dataTaskFromProtocol(with: request) { _, _, error in
            state.required = error is LocationConsentRequired
        }.resume()
        XCTAssertEqual(inner.resumedTasks, 1)
        XCTAssertEqual(clock.sleeping.count, 1)
        consent.revoke()
        clock.sleeping.first?()
        XCTAssertEqual(inner.resumedTasks, 1)
        XCTAssertTrue(state.required)
    }

    func testUserDefaultsPersistsLocationIndependentlyOfAI() throws {
        let suite = "LocationConsentTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let aiConsent = AIConsentStore(storage: defaults)
        let location = LocationConsentStore(storage: defaults)
        aiConsent.allow()
        XCTAssertFalse(location.isAllowed)
        location.allow()
        let restored = try LocationConsentStore(storage: XCTUnwrap(UserDefaults(suiteName: suite)))
        XCTAssertTrue(restored.isAllowed)
        restored.revoke()
        XCTAssertFalse(location.isAllowed)
        XCTAssertTrue(aiConsent.isAllowed)
    }

    func testTripConsentCancelRevokeAndSuccessNoticeWithoutNetwork() async {
        let consent = LocationConsentStore(storage: MemoryAIConsentStorage())
        var sent = 0
        let trip = TripSession(limits: NavLimitStore(), locationConsent: consent) { _ in
            sent += 1
            return NextLeg(totalMinutes: 12, transfers: 0, guidanceLang: .ko, legs: [])
        }
        defer { trip.end() }
        var stop = RouteStop(place: PlaceSummary(id: 1, name: "목적지", latitude: 35, longitude: 129))
        stop.serverItemId = 1
        trip.start(to: stop, number: 1, courseId: 1)
        trip.locator.inject(latitude: 37.5, longitude: 127)
        await settle { trip.directionsConsent.gate.isPresented }
        XCTAssertEqual(sent, 0)
        XCTAssertNil(trip.directionsConsent.notice.visibleRequest)
        trip.directionsConsent.gate.cancel()
        trip.directionsConsent.gate.didDismiss()
        trip.cancelLocationRequest()
        trip.locator.inject(latitude: 37.6, longitude: 127)
        XCTAssertEqual(sent, 0)
        trip.retry()
        await settle { trip.directionsConsent.gate.isPresented }
        trip.directionsConsent.gate.allow()
        trip.directionsConsent.gate.didDismiss()
        await settle { trip.result != nil }
        XCTAssertEqual(sent, 1)
        let firstNotice = trip.directionsConsent.notice.visibleRequest
        XCTAssertNotNil(firstNotice)
        trip.locator.inject(latitude: 37.7, longitude: 127)
        for _ in 0 ..< 50 {
            await Task.yield()
        }
        XCTAssertEqual(sent, 1, "자동 GPS 갱신은 재탐색 요청을 보내지 않는다")
        XCTAssertEqual(trip.directionsConsent.notice.visibleRequest, firstNotice)
        consent.revoke()
        trip.retry()
        await settle { trip.directionsConsent.gate.isPresented }
        XCTAssertEqual(sent, 1)
        trip.directionsConsent.gate.allow()
        trip.directionsConsent.gate.didDismiss()
        await settle { trip.result != nil }
        XCTAssertEqual(sent, 2)
        XCTAssertNotEqual(trip.directionsConsent.notice.visibleRequest, firstNotice)
    }

    private func settle(until done: () -> Bool) async {
        for _ in 0 ..< 5000 where !done() {
            await Task.yield()
        }
    }

    private let payload = #"{"latitude":37.5,"longitude":127,"days":2}"#

    private func send(
        _ path: String, store: LocationConsentStore,
        fault: @escaping (URLRequest) -> NetFault.Injection? = { _ in nil }
    ) -> (inner: RetryFakeSession, state: LocationRequestState) {
        let inner = RetryFakeSession([.status(200)])
        var environment = RetryFakeClock().environment(fault: fault)
        let aiConsent = AIConsentStore(storage: MemoryAIConsentStorage())
        aiConsent.allow()
        environment.aiConsent = aiConsent
        environment.locationConsent = store
        let session = RetryingSession(inner: inner, environment: environment, ledger: RateLimitLedger())
        var request = URLRequest(url: URL(string: "https://example.invalid/v1\(path)")!)
        request.httpMethod = "POST"
        request.httpBody = Data(payload.utf8)
        let state = LocationRequestState()
        session.dataTaskFromProtocol(with: request) { _, _, error in
            state.required = error is LocationConsentRequired
        }.resume()
        return (inner, state)
    }
}

private final class LocationRequestState: @unchecked Sendable {
    var required = false
}
