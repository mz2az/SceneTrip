import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class RouteNearbySearchTests: XCTestCase {
    private let center = TripSpot(latitude: 35.2, longitude: 129.1)
    private let device = TripSpot(latitude: 37.5, longitude: 127)

    func testSearchBoundsAreCenteredOnChosenMapPosition() {
        let query = RouteNearbySearch.Query(anchor: .init(spot: center, isMapCenter: true, source: .userSelected))
        let bounds = query.bbox.split(separator: ",").compactMap { Double($0) }
        XCTAssertEqual(bounds.count, 4)
        guard bounds.count == 4 else { return }
        XCTAssertEqual((bounds[0] + bounds[2]) / 2, center.longitude, accuracy: 0.000001)
        XCTAssertEqual((bounds[1] + bounds[3]) / 2, center.latitude, accuracy: 0.000001)
        XCTAssertLessThan(bounds[0], center.longitude)
        XCTAssertLessThan(bounds[1], center.latitude)
        XCTAssertGreaterThan(bounds[2], center.longitude)
        XCTAssertGreaterThan(bounds[3], center.latitude)
    }

    func testMapCenterSearchNeedsNeitherLocationNorAIConsent() async {
        var queries: [RouteNearbySearch.Query] = []
        let search = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { query in
            queries.append(query)
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        await search.load(.init(spot: center, isMapCenter: true, source: .userSelected))
        XCTAssertEqual(queries.map(\.center.latitude), [center.latitude])
        XCTAssertEqual(queries.map(\.center.longitude), [center.longitude])
        XCTAssertEqual(queries.map(\.source), [.userSelected])
        XCTAssertFalse(search.gate.isPresented)
        XCTAssertEqual(search.phase, .loaded)
    }

    func testDeviceLocationRequiresConsentAndCancelDoesNotSearch() async {
        var calls = 0
        let search = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { _ in
            calls += 1
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        let anchor = RouteGuideAnchor(spot: device, isMapCenter: false)
        await search.load(anchor)
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(search.phase, .needsConsent)
        search.gate.requestLocation(for: anchor.source) { XCTFail("취소 후 조회하면 안 됨") }
        XCTAssertTrue(search.gate.isPresented)
        search.gate.cancel()
        search.gate.didDismiss()
        XCTAssertEqual(calls, 0)
    }

    func testConsentAllowsDeviceThenRevocationBlocksItButMapStillWorks() async {
        let consent = LocationConsentStore(storage: MemoryAIConsentStorage())
        var queries: [RouteNearbySearch.Query] = []
        let search = RouteNearbySearch(consent: consent) { query in
            queries.append(query)
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        search.gate.request {}
        search.gate.allow()
        search.gate.didDismiss()
        await search.load(.init(spot: device, isMapCenter: false))
        consent.revoke()
        await search.load(.init(spot: device, isMapCenter: false))
        await search.load(.init(spot: center, isMapCenter: true, source: .userSelected))
        XCTAssertEqual(queries.map(\.center.latitude), [device.latitude, center.latitude])
        XCTAssertEqual(queries.map(\.source), [.device, .userSelected])
    }

    func testGPSDerivedMapKeepsConsentRequirement() async {
        var calls = 0
        let search = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { _ in
            calls += 1
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        await search.load(.init(spot: center, isMapCenter: true, source: .device))
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(search.phase, .needsConsent)
    }

    func testAIUnavailableKeepsNearbyAndCartWorkingAndErrorSeparate() async {
        let guide = RouteGuideSession(environment: .init(
            send: { _, _, _, _ in
                throw ErrorResponse.error(503, nil, nil, URLError(.badServerResponse))
            }, serverKeepsKeys: { false }, asked: {}
        ))
        await guide.ask("주변 알려줘", here: .init(latitude: center.latitude, longitude: center.longitude),
                        context: nil, source: .userSelected)
        XCTAssertEqual(guide.failure, .unavailable)
        let nearby = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { _ in
            PlaceList(items: [PlaceSummary(id: 42, name: "촬영지", latitude: 35.2, longitude: 129.1)],
                      total: 1, limit: 40, offset: 0)
        }
        await nearby.load(.init(spot: center, isMapCenter: true, source: .userSelected))
        let cart = CartStore(installId: UUID(), client: NearbyCartClient())
        await cart.refresh()
        XCTAssertEqual(nearby.places.map(\.id), [42])
        XCTAssertEqual(cart.items.map(\.placeId), [42])
        XCTAssertEqual(guide.failure, .unavailable, "비AI 조회가 AI 실패를 지우면 안 됨")
        XCTAssertFalse(guide.asking)
    }

    func testNearbyFailureCanBeRetriedWithoutAI() async {
        var calls = 0
        let nearby = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { _ in
            calls += 1
            if calls == 1 {
                throw URLError(.notConnectedToInternet)
            }
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        let anchor = RouteGuideAnchor(spot: center, isMapCenter: true, source: .userSelected)
        await nearby.load(anchor)
        guard case .failed = nearby.phase else { return XCTFail("조회 실패를 표시해야 함") }
        await nearby.load(anchor)
        XCTAssertEqual(nearby.phase, .loaded)
        XCTAssertEqual(calls, 2)
    }

    func testLateOldMapResponseDoesNotReplaceNewLocation() async {
        let started = expectation(description: "이전 지도 조회 시작")
        var reply: CheckedContinuation<PlaceList, Error>?
        let nearby = RouteNearbySearch(consent: LocationConsentStore(storage: MemoryAIConsentStorage())) { query in
            if query.center.latitude == 35.2 {
                return try await withCheckedThrowingContinuation {
                    reply = $0
                    started.fulfill()
                }
            }
            return PlaceList(items: [], total: 0, limit: 40, offset: 0)
        }
        let old = Task { await nearby.load(.init(spot: center, isMapCenter: true, source: .userSelected)) }
        await fulfillment(of: [started], timeout: 1)
        await nearby.load(.init(spot: device, isMapCenter: true, source: .userSelected))
        reply?.resume(returning: PlaceList(
            items: [PlaceSummary(id: 42, name: "이전 결과", latitude: 35.2, longitude: 129.1)],
            total: 1, limit: 40, offset: 0
        ))
        await old.value
        XCTAssertEqual(nearby.phase, .loaded)
        XCTAssertTrue(nearby.places.isEmpty)
    }

    func testQueryUsesExistingPlacesBuilderAndPreservesSource() throws {
        let query = RouteNearbySearch.Query(anchor: .init(spot: center, isMapCenter: true, source: .userSelected))
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let session = RetryFakeSession([.hang])
        NearbyCapturedSession.current = session
        SceneApiClientAPI.requestBuilderFactory = NearbyCapturingFactory()
        let task = RouteNearbySearch.builder(query).execute { _ in }
        task.cancel()
        let request = try XCTUnwrap(session.requests.first)
        XCTAssertEqual(request.url?.path, "/v1/places")
        let components = try XCTUnwrap(URLComponents(url: XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.queryItems?.first { $0.name == "bbox" }?.value, query.bbox)
        XCTAssertEqual(request.value(forHTTPHeaderField: LocationCoordinateSource.header), "userSelected")
        XCTAssertNoThrow(try LocationConsentStore(storage: MemoryAIConsentStorage()).requestForSending(request))
    }
}

private enum NearbyCapturedSession {
    static var current: URLSessionProtocol = RetryFakeSession([])
}

private final class NearbyCapturingFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        URLSessionRequestBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        NearbyCapturingBuilder<T>.self
    }
}

private final class NearbyCapturingBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        NearbyCapturedSession.current
    }
}

@MainActor
private struct NearbyCartClient: CartClient {
    func fetch(installId _: UUID) async throws -> Cart {
        Cart(items: [CartItem(placeId: 42, name: "저장한 곳", addedAt: Date(timeIntervalSince1970: 1))], totalCount: 1)
    }

    func add(installId _: UUID, placeId _: Int64, sourceContentId _: Int64?) async throws {}

    func remove(installId _: UUID, placeId _: Int64) async throws {}
}
