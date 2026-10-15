import CoreLocation
import NMapsMap
import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class LocationSourceTests: XCTestCase {
    func testGPSRegionAsksBeforeSearchAndCancelDoesNotSend() {
        let region = MapRegionSource()
        region.source = .device
        let gate = AIConsentGate(store: LocationConsentStore(storage: MemoryAIConsentStorage()))
        var sent = 0
        gate.requestLocation(for: region.source) { sent += 1 }
        XCTAssertTrue(gate.isPresented)
        gate.cancel()
        gate.didDismiss()
        XCTAssertEqual(sent, 0)
        region.cameraWillChange(reason: Int(NMFMapChangedByGesture))
        gate.requestLocation(for: region.source) { sent += 1 }
        XCTAssertEqual(sent, 1)
        XCTAssertFalse(gate.isPresented)
        region.cameraWillChange(reason: Int(NMFMapChangedByLocation))
        XCTAssertEqual(region.source, .device)
        region.cameraWillChange(reason: Int(NMFMapChangedByControl))
        XCTAssertEqual(region.source, .userSelected)
    }

    func testGPSRegionAsksAgainAfterRevokeAndResultSelectionResetsSource() {
        let region = MapRegionSource()
        region.source = .device
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        let gate = AIConsentGate(store: store)
        store.allow()
        var sent = 0
        gate.requestLocation(for: region.source) { sent += 1 }
        store.revoke()
        region.cameraWillChange(reason: Int(NMFMapChangedByContentPadding))
        gate.requestLocation(for: region.source) { sent += 1 }
        XCTAssertEqual(sent, 1)
        XCTAssertTrue(gate.isPresented)
        gate.cancel()
        gate.didDismiss()
        region.source = .userSelected
        gate.requestLocation(for: region.source) { sent += 1 }
        XCTAssertEqual(sent, 2)
    }

    func testUnmarkedCoordinateRequestsDefaultToDeviceLocation() throws {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        for path in ["/places?bbox=126,37,127,38", "/pois?lat=37.5&lng=127"] {
            let request = try URLRequest(url: XCTUnwrap(URL(string: "https://example.invalid/v1\(path)")))
            XCTAssertThrowsError(try store.requestForSending(request))
        }
    }

    func testGPSViewportTransportBlocksWithoutConsentAndAfterRevoke() {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        for path in ["/places?bbox=126,37,127,38", "/pois?bbox=126,37,127,38&lat=37.5&lng=127"] {
            XCTAssertEqual(send(path, source: .device, store: store).resumedTasks, 0)
            store.allow()
            let allowed = send(path, source: .device, store: store)
            XCTAssertEqual(allowed.resumedTasks, 1)
            XCTAssertNil(allowed.requests.first?.value(forHTTPHeaderField: LocationCoordinateSource.header))
            store.revoke()
            XCTAssertEqual(send(path, source: .device, store: store).resumedTasks, 0)
            XCTAssertEqual(send(path, source: .userSelected, store: store).resumedTasks, 1)
        }
    }

    func testRevocationJustBeforeViewportResumeBlocksTransport() {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        store.allow()
        let inner = send("/pois?bbox=126,37,127,38&lat=37.5&lng=127", source: .device, store: store) {
            store.revoke()
        }
        XCTAssertEqual(inner.resumedTasks, 0)
    }

    func testMapAndDestinationGuideRequestsNeedOnlyAIConsent() {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        let gate = AIConsentGate(store: store)
        var sent = 0
        gate.requestLocation(for: .userSelected) { sent += 1 }
        XCTAssertEqual(sent, 1)
        XCTAssertFalse(gate.isPresented)
        XCTAssertEqual(send("/guide/chat", source: .userSelected, store: store).resumedTasks, 1)
        XCTAssertEqual(send("/guide/chat", source: .device, store: store).resumedTasks, 0)
        XCTAssertEqual(send("/guide/chat", source: .userSelected, store: store, aiAllowed: false).resumedTasks, 0)
        store.allow()
        XCTAssertEqual(send("/guide/chat", source: .device, store: store).resumedTasks, 1)
        store.revoke()
        XCTAssertEqual(send("/guide/chat", source: .device, store: store).resumedTasks, 0)
        XCTAssertEqual(send("/guide/chat", source: .userSelected, store: store).resumedTasks, 1)
    }

    func testGuideAnchorRetainsGPSDerivedMapSource() throws {
        let center = TripSpot(latitude: 37.5, longitude: 127)
        let denied = try XCTUnwrap(RouteGuideAnchor.resolve(current: nil, mapCenter: center, preferMap: false))
        XCTAssertEqual(denied.source, .userSelected)
        let gpsMap = try XCTUnwrap(RouteGuideAnchor.resolve(
            current: center, mapCenter: center, preferMap: true, mapSource: .device
        ))
        XCTAssertTrue(gpsMap.isMapCenter)
        XCTAssertEqual(gpsMap.source, .device)
    }

    func testGuideRetryKeepsOriginalSourceAndCoordinates() async {
        var sources: [LocationCoordinateSource] = []
        var coordinates: [Double] = []
        let session = RouteGuideSession(environment: .init(
            send: { request, _, _, source in
                sources.append(source)
                coordinates.append(request.latitude)
                throw URLError(.notConnectedToInternet)
            },
            serverKeepsKeys: { true }, asked: {}
        ))
        await session.ask("주변 알려줘", here: .init(latitude: 37.5, longitude: 127), context: nil, source: .userSelected)
        XCTAssertNotNil(session.pending)
        await session.retry()
        XCTAssertEqual(sources, [.userSelected, .userSelected])
        XCTAssertEqual(coordinates, [37.5, 37.5])
    }

    private func send(
        _ path: String, source: LocationCoordinateSource, store: LocationConsentStore,
        aiAllowed: Bool = true, beforeResume: @escaping () -> Void = {}
    ) -> RetryFakeSession {
        let inner = RetryFakeSession([.status(200)])
        var environment = RetryFakeClock().environment(fault: { _ in beforeResume(); return nil })
        environment.locationConsent = store
        let aiConsent = AIConsentStore(storage: MemoryAIConsentStorage())
        if aiAllowed {
            aiConsent.allow()
        }
        environment.aiConsent = aiConsent
        let session = RetryingSession(inner: inner, environment: environment, ledger: RateLimitLedger())
        var request = URLRequest(url: URL(string: "https://example.invalid/v1\(path)")!)
        request.httpMethod = path == "/guide/chat" ? "POST" : "GET"
        request.setValue(source.rawValue, forHTTPHeaderField: LocationCoordinateSource.header)
        if path == "/guide/chat" {
            request.httpBody = Data(#"{"latitude":37.5,"longitude":127}"#.utf8)
        }
        session.dataTaskFromProtocol(with: request) { _, _, _ in }.resume()
        return inner
    }
}
