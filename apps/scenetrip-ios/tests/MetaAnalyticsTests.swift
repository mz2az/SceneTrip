@testable import SceneTrip
import XCTest

final class MetaAnalyticsTests: XCTestCase {
    private let configuration = MetaConfiguration(appID: "12345", clientToken: "test-token", enabled: true)

    func testMissingOrDisabledConfigurationNeverInitializesSDK() {
        for configuration in [nil, MetaConfiguration(appID: "12345", clientToken: "test-token", enabled: false)] {
            let client = MetaRecorder()
            let analytics = MetaAnalytics(configuration: configuration, authorized: { true }, client: client)
            analytics.activate()
            analytics.log(.savePlace(placeId: 38))
            XCTAssertEqual(client.starts, 0)
            XCTAssertEqual(client.activations, 0)
            XCTAssertTrue(client.events.isEmpty)
        }
    }

    func testNoConsentDropsEventsInsteadOfReplayingAfterConsent() {
        var authorized = false
        let client = MetaRecorder()
        let analytics = MetaAnalytics(configuration: configuration, authorized: { authorized }, client: client)
        analytics.activate()
        analytics.log(.savePlace(placeId: 38))
        XCTAssertEqual(client.starts, 0)
        authorized = true
        analytics.activate()
        analytics.log(.createCourse(origin: "ai", dayCount: 2, placeCount: 7))
        XCTAssertEqual(client.starts, 1)
        XCTAssertEqual(client.activations, 1)
        XCTAssertEqual(client.events, ["create_course"])
    }

    func testOnlyTwoCustomEventsReachMetaWithoutUserOrPlaceParameters() {
        let client = MetaRecorder()
        let analytics = MetaAnalytics(configuration: configuration, authorized: { true }, client: client)
        let events: [AppEvent] = [.screenView("home"), .login(method: "google"), .search(termLength: 6, kind: "place"),
                                  .viewPlace(placeId: 38), .likeTitle(contentId: 1, liked: true), .askGuide,
                                  .savePlace(placeId: 38), .createCourse(origin: "self", dayCount: 1, placeCount: 3)]
        for event in events {
            analytics.log(event)
        }
        XCTAssertEqual(client.starts, 1)
        XCTAssertEqual(client.events, ["save_place", "create_course"])
    }

    func testConsentRevocationStopsNewEventsAndActivation() {
        var authorized = true
        let client = MetaRecorder()
        let analytics = MetaAnalytics(configuration: configuration, authorized: { authorized }, client: client)
        analytics.activate()
        authorized = false
        analytics.log(.savePlace(placeId: 38))
        analytics.activate()
        XCTAssertTrue(client.events.isEmpty)
        XCTAssertEqual(client.activations, 1)
        XCTAssertEqual(client.enabledStates.last, false)
        authorized = true
        analytics.log(.savePlace(placeId: 38))
        XCTAssertEqual(client.starts, 1, "동의를 다시 허용해도 SDK를 중복 초기화하지 않는다")
        XCTAssertEqual(client.events, ["save_place"])
    }

    func testInvalidConfigurationFailsClosed() {
        XCTAssertNil(MetaConfiguration(dictionary: [:]))
        XCTAssertNil(MetaConfiguration(dictionary: ["AppID": "12345", "ClientToken": ""]))
        XCTAssertNil(MetaConfiguration(dictionary: ["AppID": "wrong", "ClientToken": "test-token"]))
        let invalid: [String: Any] = ["AppID": "12345", "ClientToken": "test-token", "MeasurementEnabled": "true"]
        XCTAssertNil(MetaConfiguration(dictionary: invalid))
        let configured = MetaConfiguration(dictionary: ["AppID": "12345", "ClientToken": "test-token"])
        XCTAssertEqual(configured?.enabled, false, "명시적으로 활성화하기 전에는 기본 비활성화")
    }
}

private final class MetaRecorder: MetaEventsClient {
    var starts = 0
    var activations = 0
    var enabledStates: [Bool] = []
    var events: [String] = []
    func start(configuration _: MetaConfiguration) {
        starts += 1
    }

    func setEnabled(_ enabled: Bool) {
        enabledStates.append(enabled)
    }

    func activate() {
        activations += 1
    }

    func log(name: String) {
        events.append(name)
    }
}
