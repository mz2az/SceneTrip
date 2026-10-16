import CoreLocation
@testable import SceneTrip
import XCTest

/// 위치 고르기 규칙 — 서면역이 전포로 뜨던 것(2026-10-16 사용자 실기 지적)의 바탕.
final class LocationFixTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    /// 서면역 근처.
    private let seomyeon = CLLocationCoordinate2D(latitude: 35.1578, longitude: 129.0600)

    private func fix(
        _ coordinate: CLLocationCoordinate2D? = nil, accuracy: CLLocationAccuracy, age: TimeInterval = 0
    ) -> CLLocation {
        CLLocation(
            coordinate: coordinate ?? seomyeon, altitude: 0,
            horizontalAccuracy: accuracy, verticalAccuracy: -1,
            timestamp: now.addingTimeInterval(-age)
        )
    }

    /// 북쪽으로 미터만큼 옮긴 자리.
    private func north(_ meters: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: seomyeon.latitude + meters / 111_000, longitude: seomyeon.longitude)
    }

    func testInvalidAndStaleFixesAreNotUsable() {
        XCTAssertTrue(LocationFix.isUsable(fix(accuracy: 30), now: now))
        XCTAssertFalse(LocationFix.isUsable(fix(accuracy: -1), now: now))
        XCTAssertFalse(LocationFix.isUsable(fix(accuracy: 10, age: 30), now: now))
    }

    func testFirstFixIsAlwaysTaken() {
        XCTAssertTrue(LocationFix.shouldReplace(nil, with: fix(accuracy: 500)))
    }

    /// 대략값(수백 m) 다음에 GPS 값(수 m)이 오면 갈아탄다 — 첫 값에 멈추지 않는다.
    func testMoreAccurateFixReplacesCoarseOne() {
        let coarse = fix(north(700), accuracy: 400, age: 2)
        XCTAssertTrue(LocationFix.shouldReplace(coarse, with: fix(accuracy: 8, age: 1)))
    }

    /// 조금 흐려져도 더 새것이면 따라간다. 확 흐려진 값(실내 기지국)은 버틴다.
    func testNewerSlightlyWorseFixReplacesButMuchWorseDoesNot() {
        let shown = fix(accuracy: 10, age: 3)
        XCTAssertTrue(LocationFix.shouldReplace(shown, with: fix(north(15), accuracy: 14, age: 0)))
        XCTAssertFalse(LocationFix.shouldReplace(shown, with: fix(north(300), accuracy: 300, age: 0)))
        XCTAssertFalse(LocationFix.shouldReplace(fix(accuracy: 10, age: 0), with: fix(accuracy: 30, age: 2)))
    }

    func testRecentersOnlyWhenBetterAndVisiblyMoved() {
        let coarse = fix(north(700), accuracy: 400)
        XCTAssertTrue(LocationFix.worthRecentering(from: coarse, to: fix(accuracy: 8)))
        // 더 정확해졌어도 거의 같은 자리면 카메라를 흔들지 않는다.
        XCTAssertFalse(LocationFix.worthRecentering(from: fix(north(5), accuracy: 30), to: fix(accuracy: 8)))
        // 덜 정확한 값으로는 다시 맞추지 않는다.
        XCTAssertFalse(LocationFix.worthRecentering(from: fix(accuracy: 8), to: fix(north(100), accuracy: 50)))
    }

    func testTripDropsVeryCoarseFixOnceWeHaveOne() {
        XCTAssertTrue(LocationFix.acceptsTripFix(fix(accuracy: 500), hasFix: false))
        XCTAssertFalse(LocationFix.acceptsTripFix(fix(accuracy: 500), hasFix: true))
        XCTAssertTrue(LocationFix.acceptsTripFix(fix(accuracy: 40), hasFix: true))
    }

    /// 「정확한 위치」를 잠깐 켜 달라는 문구가 두 언어로 들어 있다 — 없으면 iOS 가 묻지 않는다.
    func testTemporaryFullAccuracyPurposeIsBundled() throws {
        let bundle = Bundle(for: AppLanguage.self)
        for language in ["ko", "en"] {
            let url = try XCTUnwrap(bundle.url(
                forResource: "InfoPlist", withExtension: "strings", subdirectory: nil, localization: language
            ))
            let values = try XCTUnwrap(
                PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
            )
            XCTAssertFalse((values[LocationFix.purposeKey] ?? "").isEmpty, language)
        }
    }
}
