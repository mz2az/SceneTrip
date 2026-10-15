import AppTrackingTransparency
@testable import SceneTrip
import XCTest

final class TrackingAuthorizationTests: XCTestCase {
    @MainActor
    func testWaitsForOnboardingAndActiveAppThenRequestsOnlyOnce() {
        let fixture = TrackingFixture()
        fixture.tracking.requestIfNeeded(onboardingComplete: false, isActive: true)
        fixture.tracking.requestIfNeeded(onboardingComplete: true, isActive: false)
        XCTAssertEqual(fixture.requests, 0)
        fixture.request()
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
        fixture.answer(.authorized)
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
        XCTAssertEqual(fixture.client.activations, 1)
    }

    @MainActor
    func testExistingOnboardingCompletionRequestsOnFirstActiveOpportunity() {
        let fixture = TrackingFixture()
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
    }

    @MainActor
    func testEveryAnsweredStatusSkipsRequestEvenInNewCoordinator() {
        for status in [ATTrackingManager.AuthorizationStatus.authorized, .denied, .restricted] {
            let fixture = TrackingFixture()
            fixture.status = status
            fixture.request()
            XCTAssertEqual(fixture.requests, 0)
        }
    }

    @MainActor
    func testDeniedRestrictedAndUndeterminedAnswersNeverStartMetaOrRepeat() {
        // 기기의 추적 요청 허용 스위치 OFF도 시스템이 .denied를 반환하는 같은 경로다.
        for status in [ATTrackingManager.AuthorizationStatus.denied, .restricted, .notDetermined] {
            let fixture = TrackingFixture()
            fixture.request()
            fixture.answer(status)
            fixture.request()
            XCTAssertEqual(fixture.requests, 1)
            XCTAssertEqual(fixture.client.starts, 0)
            XCTAssertEqual(fixture.client.activations, 0)
        }
    }

    @MainActor
    func testStatisticsOffWhilePromptIsOpenOverridesAuthorizedAnswer() {
        let fixture = TrackingFixture()
        fixture.request()
        fixture.statisticsEnabled = false
        fixture.answer(.authorized)
        XCTAssertEqual(fixture.client.starts, 0)
        XCTAssertEqual(fixture.client.activations, 0)
    }
}

@MainActor
private final class TrackingFixture {
    var status: ATTrackingManager.AuthorizationStatus = .notDetermined
    var statisticsEnabled = true
    var requests = 0
    var completion: (@MainActor (ATTrackingManager.AuthorizationStatus) -> Void)?
    let client = TrackingMetaRecorder()
    lazy var meta = MetaAnalytics(
        configuration: MetaConfiguration(appID: "12345", clientToken: "test-token", enabled: true),
        authorized: { self.status == .authorized },
        client: client,
        statisticsEnabled: { self.statisticsEnabled }
    )
    lazy var tracking = TrackingAuthorization(
        status: { self.status },
        request: { completion in
            self.requests += 1
            self.completion = completion
        },
        activateMeta: { self.meta.activate() }
    )

    func request() {
        tracking.requestIfNeeded(onboardingComplete: true, isActive: true)
    }

    func answer(_ status: ATTrackingManager.AuthorizationStatus) {
        self.status = status
        completion?(status)
        completion = nil
    }
}

private final class TrackingMetaRecorder: MetaEventsClient {
    var starts = 0
    var activations = 0
    func start(configuration _: MetaConfiguration) {
        starts += 1
    }

    func setEnabled(_: Bool) {}
    func activate() {
        activations += 1
    }

    func log(name _: String) {}
    func flush() {}
}
