import PostHog
@testable import SceneTrip
import Sentry
import XCTest

/// MZ2AZ-527 — PostHog(사용 분석)·Sentry(오류 추적). 진짜 SDK·네트워크 대신 기록기를 꽂는다.
final class ThirdPartyAnalyticsTests: XCTestCase {
    // MARK: 키가 없으면 SDK 를 한 번도 부르지 않는다

    func testEmptyKeysNeverTouchEitherSDK() {
        let posthogClient = PostHogRecorder()
        let sentryClient = SentryRecorder()
        for key in ["", "   "] {
            let posthog = PostHogAnalytics(apiKey: key, client: posthogClient, statisticsEnabled: { true })
            let errors = ErrorReporting(dsn: key, environment: "dev", client: sentryClient, statisticsEnabled: { true })
            posthog.sync()
            posthog.log(.screenView("home"))
            errors.sync()
        }
        XCTAssertTrue(posthogClient.calls.isEmpty)
        XCTAssertTrue(sentryClient.calls.isEmpty)
    }

    // MARK: 이용 통계 스위치

    func testStatisticsOffAtLaunchNeverInitializes() {
        let posthogClient = PostHogRecorder()
        let sentryClient = SentryRecorder()
        let posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: { false })
        let errors = ErrorReporting(dsn: "https://k@example.invalid/1", environment: "dev",
                                    client: sentryClient, statisticsEnabled: { false })
        posthog.sync()
        posthog.log(.savePlace(placeId: 38))
        errors.sync()
        XCTAssertTrue(posthogClient.calls.isEmpty, "OFF 로 시작하면 PostHog 를 초기화하지 않는다")
        XCTAssertTrue(sentryClient.calls.isEmpty, "OFF 로 시작하면 Sentry 를 초기화하지 않는다")
    }

    func testTurningOnLaterStartsLazilyAndOnce() {
        var enabled = false
        let posthogClient = PostHogRecorder()
        let sentryClient = SentryRecorder()
        let posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: { enabled })
        let errors = ErrorReporting(dsn: "https://k@example.invalid/1", environment: "production",
                                    client: sentryClient, statisticsEnabled: { enabled })
        posthog.sync()
        errors.sync()
        enabled = true
        posthog.sync()
        errors.sync()
        posthog.sync()
        errors.sync()
        posthog.log(.askGuide)
        XCTAssertEqual(posthogClient.calls, [
            .start(apiKey: "phc_test", host: PostHogAnalytics.defaultHost),
            .capture("ask_guide", [:]),
        ])
        XCTAssertEqual(sentryClient.calls, [.start(dsn: "https://k@example.invalid/1", environment: "production")])
    }

    func testTurningOffAfterStartOptsOutAndClosesThenOnAgainResumes() {
        var enabled = true
        let posthogClient = PostHogRecorder()
        let sentryClient = SentryRecorder()
        let posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: { enabled })
        let errors = ErrorReporting(dsn: "https://k@example.invalid/1", environment: "dev",
                                    client: sentryClient, statisticsEnabled: { enabled })
        posthog.sync()
        errors.sync()

        enabled = false
        posthog.sync()
        errors.sync()
        posthog.log(.savePlace(placeId: 38))
        XCTAssertEqual(posthogClient.calls, [.start(apiKey: "phc_test", host: PostHogAnalytics.defaultHost), .optOut])
        XCTAssertEqual(sentryClient.calls, [.start(dsn: "https://k@example.invalid/1", environment: "dev"), .close])

        // OFF 를 거듭 알려도 opt-out·close 를 반복하지 않는다.
        posthog.sync()
        errors.sync()
        XCTAssertEqual(posthogClient.calls.count, 2)
        XCTAssertEqual(sentryClient.calls.count, 2)

        enabled = true
        posthog.sync()
        errors.sync()
        posthog.log(.savePlace(placeId: 39))
        XCTAssertEqual(posthogClient.calls, [
            .start(apiKey: "phc_test", host: PostHogAnalytics.defaultHost), .optOut, .optIn,
            .capture("save_place", ["place_id": Int64(39)]),
        ], "다시 켜면 다시 초기화하지 않고 opt-in 한다. 꺼진 사이 이벤트는 보내지 않는다")
        XCTAssertEqual(sentryClient.calls, [
            .start(dsn: "https://k@example.invalid/1", environment: "dev"), .close,
            .start(dsn: "https://k@example.invalid/1", environment: "dev"),
        ])
    }

    func testLogChecksTheSwitchEachTime() {
        var enabled = true
        let posthogClient = PostHogRecorder()
        let posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: { enabled })
        posthog.log(.screenView("home"))
        enabled = false
        posthog.log(.screenView("profile"))
        XCTAssertEqual(posthogClient.calls.filter(\.isCapture), [.capture("screen_view", ["screen_name": "home"])])
        XCTAssertEqual(posthogClient.calls.last, .optOut)
    }

    // MARK: AppAnalytics.log 하나 → Firebase 와 PostHog 에 같은 이름·매개변수

    func testAppAnalyticsLogSendsSameNameAndParametersToFirebaseAndPostHog() {
        let defaults = UserDefaults.standard
        let saved = defaults.object(forKey: UsageStatistics.key)
        let savedSink = AppAnalytics.sink
        let savedPostHog = AppAnalytics.posthog
        defer {
            defaults.set(saved, forKey: UsageStatistics.key)
            AppAnalytics.sink = savedSink
            AppAnalytics.posthog = savedPostHog
        }
        defaults.set(true, forKey: UsageStatistics.key)
        let firebase = FirebaseRecorder()
        let posthogClient = PostHogRecorder()
        AppAnalytics.sink = firebase
        AppAnalytics.posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: { true })

        let events: [AppEvent] = [
            .createCourse(origin: "ai", dayCount: 2, placeCount: 7),
            .search(termLength: 6, kind: "place"),
            .screenView("home"),
            .logout,
        ]
        for event in events {
            AppAnalytics.log(event)
        }
        XCTAssertEqual(firebase.events, events)
        XCTAssertEqual(
            posthogClient.calls.filter(\.isCapture),
            events.map { .capture($0.name, $0.parameters) },
            "PostHog 는 Firebase 와 같은 이름·매개변수를 받는다"
        )
    }

    func testAppAnalyticsLogSendsNothingToPostHogWhenStatisticsOff() {
        let defaults = UserDefaults.standard
        let saved = defaults.object(forKey: UsageStatistics.key)
        let savedPostHog = AppAnalytics.posthog
        defer {
            defaults.set(saved, forKey: UsageStatistics.key)
            AppAnalytics.posthog = savedPostHog
        }
        defaults.set(false, forKey: UsageStatistics.key)
        let posthogClient = PostHogRecorder()
        AppAnalytics.posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient,
                                                statisticsEnabled: { UsageStatistics.shared.isEnabled })
        AppAnalytics.log(.savePlace(placeId: 38))
        XCTAssertTrue(posthogClient.calls.isEmpty)
    }

    /// 스위치를 바꾸는 길(`UsageStatistics.setEnabled` → `AppAnalytics.setCollectionEnabled`)이 둘 다 맞춘다.
    func testCollectionSwitchSyncsPostHogAndSentry() {
        let defaults = UserDefaults.standard
        let saved = defaults.object(forKey: UsageStatistics.key)
        let savedPostHog = AppAnalytics.posthog
        let savedErrors = AppAnalytics.errorReporting
        defer {
            defaults.set(saved, forKey: UsageStatistics.key)
            AppAnalytics.posthog = savedPostHog
            AppAnalytics.errorReporting = savedErrors
        }
        let posthogClient = PostHogRecorder()
        let sentryClient = SentryRecorder()
        let enabled = { UsageStatistics.shared.isEnabled }
        AppAnalytics.posthog = PostHogAnalytics(apiKey: "phc_test", client: posthogClient, statisticsEnabled: enabled)
        AppAnalytics.errorReporting = ErrorReporting(dsn: "https://k@example.invalid/1", environment: "dev",
                                                     client: sentryClient, statisticsEnabled: enabled)
        defaults.set(false, forKey: UsageStatistics.key)
        AppAnalytics.setCollectionEnabled(false)
        XCTAssertTrue(posthogClient.calls.isEmpty)
        XCTAssertTrue(sentryClient.calls.isEmpty)

        defaults.set(true, forKey: UsageStatistics.key)
        AppAnalytics.setCollectionEnabled(true)
        XCTAssertEqual(posthogClient.calls, [.start(apiKey: "phc_test", host: PostHogAnalytics.defaultHost)])
        XCTAssertEqual(sentryClient.calls, [.start(dsn: "https://k@example.invalid/1", environment: "dev")])

        defaults.set(false, forKey: UsageStatistics.key)
        AppAnalytics.setCollectionEnabled(false)
        XCTAssertEqual(posthogClient.calls.last, .optOut)
        XCTAssertEqual(sentryClient.calls.last, .close)
    }

    // MARK: 환경·SDK 설정

    func testSentryEnvironmentFollowsAPIBaseURL() {
        XCTAssertEqual(ErrorReporting.environment(forAPIBaseURL: "https://api.scenetrip.io/v1"), "production")
        XCTAssertEqual(ErrorReporting.environment(forAPIBaseURL: "https://api-dev.scenetrip.io/v1"), "dev")
        XCTAssertEqual(ErrorReporting.environment(forAPIBaseURL: "http://localhost:8081/v1"), "dev")
    }

    func testSentryOptionsSendErrorsOnly() {
        let options = SentrySDKClient.makeOptions(dsn: "https://k@example.invalid/1", environment: "production")
        XCTAssertEqual(options.dsn, "https://k@example.invalid/1")
        XCTAssertEqual(options.environment, "production")
        XCTAssertFalse(options.sendDefaultPii)
        XCTAssertNil(options.tracesSampleRate, "성능 추적은 켜지 않는다")
        XCTAssertNil(options.configureProfiling, "프로파일링은 켜지 않는다")
        XCTAssertEqual(options.sessionReplay.sessionSampleRate, 0)
        XCTAssertEqual(options.sessionReplay.onErrorSampleRate, 0)
        XCTAssertFalse(options.enableAutoPerformanceTracing)
        XCTAssertFalse(options.enableNetworkTracking)
        XCTAssertFalse(options.enableAutoSessionTracking)
        XCTAssertFalse(options.attachScreenshot)
        XCTAssertFalse(options.attachViewHierarchy)
        XCTAssertFalse(options.enableCaptureFailedRequests, "요청 주소에 검색어·좌표가 실린다")
        XCTAssertTrue(options.enableCrashHandler)
        XCTAssertFalse(options.enableAutoBreadcrumbTracking)
        XCTAssertNotNil(options.beforeBreadcrumb, "브레드크럼은 모두 버린다(웹과 같다)")
    }

    func testPostHogConfigHasNoAutomaticCollection() {
        let config = PostHogSDKClient.makeConfig(apiKey: "phc_test", host: PostHogAnalytics.defaultHost)
        XCTAssertEqual(config.projectToken, "phc_test")
        XCTAssertEqual(config.host.absoluteString, "https://us.i.posthog.com")
        XCTAssertFalse(config.captureApplicationLifecycleEvents)
        XCTAssertFalse(config.captureScreenViews)
        XCTAssertFalse(config.captureElementInteractions)
        XCTAssertFalse(config.sessionReplay)
        XCTAssertFalse(config.surveys)
        XCTAssertFalse(config.preloadFeatureFlags)
        XCTAssertFalse(config.sendFeatureFlagEvent)
        XCTAssertFalse(config.errorTrackingConfig.autoCapture, "충돌은 Sentry 가 맡는다")
        XCTAssertEqual(config.personProfiles, .identifiedOnly)
        // 화난 연타(`$rageclick`)는 기본 ON 이고 좌표·화면명·요소 사슬을 싣는다 — 끈다.
        XCTAssertFalse(config.rageClickConfig.enabled)
        XCTAssertFalse(config.captureSwiftUIElementInteractions)
        XCTAssertFalse(config.captureAutocaptureElementText)
        XCTAssertFalse(config.capturePushNotificationSubscriptions)
        XCTAssertFalse(config.capturePushNotificationOpened)
        XCTAssertFalse(config.enableSwizzling, "화면·누름·푸시 자동 수집은 swizzling 으로 붙는다")
    }

    func testPostHogStartOptsInRightAfterSetup() {
        let sdk = PostHogSDKRecorder()
        let client = PostHogSDKClient(sdk: sdk)
        client.start(apiKey: "phc_test", host: PostHogAnalytics.defaultHost)
        XCTAssertEqual(sdk.calls, ["setup:phc_test", "optIn"], "지난 실행의 opt-out 이 남아도 ON 이면 받는다")
        client.capture("save_place", properties: ["place_id": Int64(39)])
        client.capture("ask_guide", properties: [:])
        XCTAssertEqual(sdk.calls.suffix(2), ["capture:save_place:place_id=39", "capture:ask_guide:nil"])
    }

    func testSentryDropsBreadcrumbsAndUserBeforeSending() throws {
        let options = SentrySDKClient.makeOptions(dsn: "https://k@example.invalid/1", environment: "dev")
        let beforeBreadcrumb = try XCTUnwrap(options.beforeBreadcrumb)
        let crumb = Breadcrumb(level: .info, category: "http")
        crumb.data = ["url": "https://api.scenetrip.io/v1/search?q=seoul"]
        XCTAssertNil(beforeBreadcrumb(crumb))

        let beforeSend = try XCTUnwrap(options.beforeSend)
        let event = Event(level: .error)
        event.user = User(userId: "someone")
        let sent = try XCTUnwrap(beforeSend(event), "오류 보고 자체는 버리지 않는다")
        XCTAssertNil(sent.user)
    }
}

private final class PostHogSDKRecorder: PostHogSDKCalls {
    var calls: [String] = []
    func setup(_ config: PostHogConfig) {
        calls.append("setup:\(config.projectToken)")
    }

    func capture(_ event: String, properties: [String: Any]?) {
        let described = properties.map { $0.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ",") } ?? "nil"
        calls.append("capture:\(event):\(described)")
    }

    func optIn() {
        calls.append("optIn")
    }

    func optOut() {
        calls.append("optOut")
    }
}

// MARK: - 기록기

private final class PostHogRecorder: PostHogClient {
    enum Call: Equatable {
        case start(apiKey: String, host: String)
        case capture(String, [String: AnyHashable])
        case optIn
        case optOut

        var isCapture: Bool {
            if case .capture = self {
                return true
            }
            return false
        }
    }

    var calls: [Call] = []
    func start(apiKey: String, host: String) {
        calls.append(.start(apiKey: apiKey, host: host))
    }

    func capture(_ name: String, properties: [String: AnyHashable]) {
        calls.append(.capture(name, properties))
    }

    func optIn() {
        calls.append(.optIn)
    }

    func optOut() {
        calls.append(.optOut)
    }
}

private final class SentryRecorder: ErrorReportingClient {
    enum Call: Equatable {
        case start(dsn: String, environment: String)
        case close
    }

    var calls: [Call] = []
    func start(dsn: String, environment: String) {
        calls.append(.start(dsn: dsn, environment: environment))
    }

    func close() {
        calls.append(.close)
    }
}

private final class FirebaseRecorder: AnalyticsSink {
    var events: [AppEvent] = []
    func log(_ event: AppEvent) {
        events.append(event)
    }

    func setUserProperty(_: String?, for _: String) {}
}
