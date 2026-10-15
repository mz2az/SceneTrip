import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class TripSessionRequestTests: XCTestCase {
    func testLatestDestinationRunsAfterDelayedOldResponseWithoutGPSUpdate() async {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        store.allow()
        var sent: [Int64] = []
        var finish: CheckedContinuation<NextLeg, Error>?
        let trip = TripSession(limits: NavLimitStore(), locationConsent: store) { request in
            sent.append(request.itemId)
            if sent.count == 1 {
                return try await withCheckedThrowingContinuation { finish = $0 }
            }
            return NextLeg(totalMinutes: 22, transfers: 0, guidanceLang: .ko, legs: [])
        }
        defer { trip.end() }
        trip.start(to: stop(1), number: 1, courseId: 1)
        trip.locator.inject(latitude: 37.5, longitude: 127)
        await settle { finish != nil }
        XCTAssertEqual(sent, [1])
        trip.start(to: stop(2), number: 2, courseId: 1)
        trip.start(to: stop(3), number: 3, courseId: 1)
        finish?.resume(returning: NextLeg(totalMinutes: 11, transfers: 0, guidanceLang: .ko, legs: []))
        await settle { trip.result != nil && !trip.asking }
        XCTAssertEqual(sent, [1, 3])
        XCTAssertEqual(trip.result?.destination, "목적지 3")
        XCTAssertNil(trip.failure)
    }

    func testEndingTripDoesNotRunQueuedDestinationAfterDelayedFailure() async {
        let store = LocationConsentStore(storage: MemoryAIConsentStorage())
        store.allow()
        var sent = 0
        var finish: CheckedContinuation<NextLeg, Error>?
        let trip = TripSession(limits: NavLimitStore(), locationConsent: store) { _ in
            sent += 1
            return try await withCheckedThrowingContinuation { finish = $0 }
        }
        trip.start(to: stop(1), number: 1, courseId: 1)
        trip.locator.inject(latitude: 37.5, longitude: 127)
        await settle { finish != nil }
        trip.start(to: stop(2), number: 2, courseId: 1)
        trip.end()
        finish?.resume(throwing: URLError(.notConnectedToInternet))
        await settle { !trip.asking }
        XCTAssertEqual(sent, 1)
        XCTAssertNil(trip.result)
        XCTAssertNil(trip.failure)
    }

    private func stop(_ id: Int64) -> RouteStop {
        var stop = RouteStop(place: PlaceSummary(id: id, name: "목적지 \(id)", latitude: 35, longitude: 129))
        stop.serverItemId = id
        return stop
    }

    private func settle(until done: () -> Bool) async {
        for _ in 0 ..< 5000 where !done() {
            await Task.yield()
        }
        XCTAssertTrue(done())
    }
}
