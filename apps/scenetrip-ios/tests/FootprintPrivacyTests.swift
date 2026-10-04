@testable import SceneTrip
import XCTest

/// 발자취는 개인정보다 (MZ2AZ-348). 동의 없이 기록하지 않고, 계정이 떠나면 지운다.
///
/// 이 규칙은 개인정보 처리방침에 적는 내용과 같다 — 깨지면 문서와 앱이 어긋난다.
@MainActor
final class FootprintPrivacyTests: XCTestCase {
    private let store = FootprintStore.shared
    private var savedPoints: [FootprintPoint] = []
    private var savedRecording = false

    override func setUp() async throws {
        savedPoints = store.points
        savedRecording = store.recording
        store.clear()
    }

    override func tearDown() async throws {
        store.forgetOwner()
        store.recording = true
        for point in savedPoints {
            store.record(latitude: point.latitude, longitude: point.longitude, at: point.at)
        }
        store.recording = savedRecording
    }

    /// 서울시청과 거기서 수백 미터 떨어진 점 — 25 m 규칙에 걸리지 않게.
    private func walk() {
        store.record(latitude: 37.5663, longitude: 126.9779)
        store.record(latitude: 37.5700, longitude: 126.9830)
    }

    func testNothingIsRecordedWithoutConsent() {
        store.recording = false
        walk()
        XCTAssertTrue(store.points.isEmpty)
    }

    func testRecordsOnceConsentIsGiven() {
        store.recording = true
        walk()
        XCTAssertEqual(store.points.count, 2)
    }

    /// 로그아웃·탈퇴·세션 소실 — 기록도 동의도 남지 않는다.
    func testLeavingTheAccountErasesFootprintsAndConsent() {
        store.recording = true
        walk()
        store.forgetOwner()
        XCTAssertTrue(store.points.isEmpty)
        XCTAssertFalse(store.recording)
        walk()
        XCTAssertTrue(store.points.isEmpty, "동의가 되돌려졌으니 다시 쌓이면 안 된다")
    }
}
