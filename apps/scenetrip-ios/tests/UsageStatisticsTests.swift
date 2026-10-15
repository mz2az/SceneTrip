@testable import SceneTrip
import XCTest

final class UsageStatisticsTests: XCTestCase {
    func testDefaultsOnAndRestoresOffAcrossInstances() throws {
        let suite = "UsageStatisticsTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var firebase: [Bool] = []
        var meta: [Bool] = []
        let settings = UsageStatistics(
            defaults: defaults,
            setFirebaseEnabled: { firebase.append($0) },
            activateMeta: { meta.append(defaults.bool(forKey: UsageStatistics.key)) }
        )
        XCTAssertTrue(settings.isEnabled)
        settings.setEnabled(false)
        let restored = UsageStatistics(defaults: defaults, setFirebaseEnabled: { _ in }, activateMeta: {})
        XCTAssertFalse(restored.isEnabled)
        XCTAssertEqual(firebase, [false])
        XCTAssertEqual(meta, [false], "저장한 OFF를 Meta가 즉시 읽어야 한다")
        settings.setEnabled(true)
        XCTAssertTrue(restored.isEnabled)
        XCTAssertEqual(firebase, [false, true])
        XCTAssertEqual(meta, [false, true])
    }

    func testDisabledStatisticsDropsFirebaseEventsAndPropertiesAtEntry() {
        let defaults = UserDefaults.standard
        let saved = defaults.object(forKey: UsageStatistics.key)
        let sink = AppAnalytics.sink
        defer {
            defaults.set(saved, forKey: UsageStatistics.key)
            AppAnalytics.sink = sink
        }
        let recorder = StatisticsRecorder()
        AppAnalytics.sink = recorder
        defaults.set(false, forKey: UsageStatistics.key)
        AppAnalytics.log(.screenView("home"))
        AppAnalytics.setLanguage("en")
        AppAnalytics.setMember(true)
        XCTAssertTrue(recorder.events.isEmpty)
        XCTAssertEqual(recorder.propertyWrites, 0)
        defaults.set(true, forKey: UsageStatistics.key)
        AppAnalytics.log(.screenView("profile"))
        AppAnalytics.setLanguage("ko")
        XCTAssertEqual(recorder.events, [.screenView("profile")])
        XCTAssertEqual(recorder.propertyWrites, 1)
    }
}

private final class StatisticsRecorder: AnalyticsSink {
    var events: [AppEvent] = []
    var propertyWrites = 0
    func log(_ event: AppEvent) {
        events.append(event)
    }

    func setUserProperty(_: String?, for _: String) {
        propertyWrites += 1
    }
}
