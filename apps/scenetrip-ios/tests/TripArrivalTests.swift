import Foundation
@testable import SceneTrip
import XCTest

/// 머무름 판정 — 반경 안에 **연속으로** 있어야 도착이다 (계획 trip-mode.md §2).
/// 지나가기만 한 것과 들른 것을 가르는 규칙이라, 값 타입 하나로 고정해 둔다.
final class TripArrivalTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_000_000)

    func testEnteringRadiusDoesNotArriveImmediately() {
        var arrival = TripArrival()
        XCTAssertFalse(arrival.observe(distanceMeters: 50, now: base))
        XCTAssertNotNil(arrival.dwelt(now: base))
    }

    func testStayingForDwellArrives() {
        var arrival = TripArrival()
        _ = arrival.observe(distanceMeters: 50, now: base)
        XCTAssertFalse(arrival.observe(distanceMeters: 30, now: base.addingTimeInterval(TripMode.dwell - 1)))
        XCTAssertTrue(arrival.observe(distanceMeters: 30, now: base.addingTimeInterval(TripMode.dwell)))
    }

    /// GPS 가 한 번 튀어 밖으로 나갔다 들어오면 **처음부터 다시** 센다 — 그것이 「머무름」이다.
    func testLeavingRadiusResetsTheClock() {
        var arrival = TripArrival()
        _ = arrival.observe(distanceMeters: 50, now: base)
        _ = arrival.observe(distanceMeters: TripMode.arriveRadiusMeters + 1, now: base.addingTimeInterval(60))
        XCTAssertNil(arrival.dwelt(now: base.addingTimeInterval(60)))
        XCTAssertFalse(arrival.observe(distanceMeters: 50, now: base.addingTimeInterval(TripMode.dwell + 10)))
    }

    func testKoreaBoundsRejectsAbroad() {
        XCTAssertTrue(KoreaBounds.contains(latitude: 37.5826, longitude: 126.9831))
        XCTAssertFalse(KoreaBounds.contains(latitude: 35.6762, longitude: 139.6503)) // 도쿄
    }
}
