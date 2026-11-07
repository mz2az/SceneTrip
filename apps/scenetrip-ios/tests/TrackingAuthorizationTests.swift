import AppTrackingTransparency
@testable import SceneTrip
import XCTest

/// ATT 요청 시점 (MZ2AZ-402 → MZ2AZ-526). 첫 실행 사용법이 없어졌으므로 사용법을 봤는지와 무관하게
/// **앱이 활성으로 처음 보인 뒤 0.6초에** 한 번 묻는다. 중복·미결정·백그라운드 규칙은 그대로다.
final class TrackingAuthorizationTests: XCTestCase {
    @MainActor
    func testWaitsForActiveAppThenRequestsOnlyOnce() {
        let fixture = TrackingFixture()
        fixture.tracking.requestIfNeeded(isActive: false)
        XCTAssertEqual(fixture.requests, 0)
        fixture.request()
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
        fixture.answer(.authorized)
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
        XCTAssertEqual(fixture.client.activations, 1)
    }

    /// 사용법을 한 번도 안 본 첫 실행이어도 활성 화면이면 묻는다 — 예전에는 영영 묻지 않았다.
    @MainActor
    func testFirstLaunchWithoutTutorialRequestsOnFirstActiveOpportunity() {
        let fixture = TrackingFixture()
        fixture.request()
        XCTAssertEqual(fixture.requests, 1)
    }

    /// 첫 화면이 자리 잡을 0.6초를 기다린 뒤, 그때도 활성이면 묻는다.
    @MainActor
    func testSettledRequestWaitsSixTenthsOfASecondThenAsks() async {
        let fixture = TrackingFixture()
        var waited: [Duration] = []
        await fixture.tracking.requestWhenSettled(isActive: { true }, sleep: { waited.append($0) })
        XCTAssertEqual(waited, [.milliseconds(600)])
        XCTAssertEqual(fixture.requests, 1)
    }

    /// 기다리는 사이 백그라운드로 가면(작업 취소) 묻지 않는다. 다음에 활성이 되면 그때 다시 기다린다.
    @MainActor
    func testBackgroundDuringWaitCancelsThenNextActiveAsks() async {
        let fixture = TrackingFixture()
        await fixture.tracking.requestWhenSettled(isActive: { true }, sleep: { _ in throw CancellationError() })
        XCTAssertEqual(fixture.requests, 0)
        await fixture.tracking.requestWhenSettled(isActive: { false }, sleep: { _ in })
        XCTAssertEqual(fixture.requests, 0)
        await fixture.tracking.requestWhenSettled(isActive: { true }, sleep: { _ in })
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
        tracking.requestIfNeeded(isActive: true)
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
