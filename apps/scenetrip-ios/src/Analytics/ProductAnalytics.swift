import Foundation
import PostHog

/// PostHog SDK 를 감싸는 자리. 진짜(`PostHogSDKClient`)와 시험용 기록기가 같은 모양이다.
protocol PostHogClient: AnyObject {
    func start(apiKey: String, host: String)
    func capture(_ name: String, properties: [String: AnyHashable])
    func optIn()
    func optOut()
}

/// 사용 분석 PostHog (MZ2AZ-527). `AppAnalytics.log` 가 Firebase 와 **같은 이름·매개변수**로 여기에도 보낸다.
/// 웹(`apps/scenetrip-web/src/analytics/posthog.ts`)과 같은 프로젝트·같은 방침이다.
///
/// - 프로젝트 키(`.env` 의 `POSTHOG_KEY` → `--define=posthog_key` → `Secrets.posthogKey`)가 비면 SDK 를 한 번도 부르지 않는다.
/// - 이용 통계(`UsageStatistics`)가 OFF 로 시작하면 초기화하지 않는다. 나중에 ON 이 되면 그때 초기화한다.
///   시작한 뒤 OFF 가 되면 opt-out, 다시 ON 이면 opt-in 한다 — 꺼진 사이의 이벤트는 모아 두지 않는다.
/// - ATT 와 무관하다 — 다른 앱·사이트를 넘나드는 추적이 아니고 IDFA 를 쓰지 않는다.
/// - 자동 수집(앱 생명주기·화면·누름·화난 연타·요소 글자·푸시)·swizzling·화면 녹화·설문·기능 플래그 미리 받기를 끈다.
final class PostHogAnalytics {
    static let defaultHost = "https://us.i.posthog.com"
    static let shared = PostHogAnalytics(
        apiKey: Secrets.posthogKey,
        client: PostHogSDKClient(),
        statisticsEnabled: { UsageStatistics.shared.isEnabled }
    )

    private let apiKey: String
    private let host: String
    private let client: PostHogClient
    private let statisticsEnabled: () -> Bool
    private var started = false
    private var optedOut = false

    init(
        apiKey: String, host: String = defaultHost, client: PostHogClient,
        statisticsEnabled: @escaping () -> Bool
    ) {
        self.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.host = host
        self.client = client
        self.statisticsEnabled = statisticsEnabled
    }

    /// 이용 통계 설정에 맞춰 켜고 끈다. 앱이 뜰 때와 설정이 바뀔 때 부른다.
    @discardableResult
    func sync() -> Bool {
        guard !apiKey.isEmpty else { return false }
        guard statisticsEnabled() else {
            if started, !optedOut {
                client.optOut()
                optedOut = true
            }
            return false
        }
        if !started {
            client.start(apiKey: apiKey, host: host)
            started = true
        } else if optedOut {
            client.optIn()
        }
        optedOut = false
        return true
    }

    /// 보낼 때마다 설정을 다시 본다.
    func log(_ event: AppEvent) {
        guard sync() else { return }
        client.capture(event.name, properties: event.parameters)
    }
}

/// `PostHogSDK` 에서 쓰는 호출만 모은 자리. 시험이 setup → optIn 순서를 진짜 SDK 없이 본다.
protocol PostHogSDKCalls: AnyObject {
    func setup(_ config: PostHogConfig)
    func capture(_ event: String, properties: [String: Any]?)
    func optIn()
    func optOut()
}

extension PostHogSDK: PostHogSDKCalls {}

final class PostHogSDKClient: PostHogClient {
    private let sdk: PostHogSDKCalls

    init(sdk: PostHogSDKCalls = PostHogSDK.shared) {
        self.sdk = sdk
    }

    /// 자동으로 모으는 것은 모두 끈다. 우리가 `capture` 로 보낸 이벤트만 간다.
    static func makeConfig(apiKey: String, host: String) -> PostHogConfig {
        let config = PostHogConfig(projectToken: apiKey, host: host)
        config.captureApplicationLifecycleEvents = false
        config.captureScreenViews = false
        config.captureElementInteractions = false
        config.captureSwiftUIElementInteractions = false
        config.captureAutocaptureElementText = false
        // 화난 연타(`$rageclick`)는 기본 ON 이고 좌표·화면명·요소 사슬을 싣는다(검증에서 시뮬레이터 큐에 쌓였다).
        config.rageClickConfig.enabled = false
        // 푸시 토큰 등록·알림 열기 자동 기록도 끈다. swizzling 을 끄면 화면·누름·푸시 자동 수집이 아예 붙지 않는다.
        config.capturePushNotificationSubscriptions = false
        config.capturePushNotificationOpened = false
        config.enableSwizzling = false
        config.sessionReplay = false
        config.surveys = false
        config.preloadFeatureFlags = false
        config.sendFeatureFlagEvent = false
        // 충돌·오류는 Sentry 몫이다.
        config.errorTrackingConfig.autoCapture = false
        config.personProfiles = .identifiedOnly
        return config
    }

    func start(apiKey: String, host: String) {
        sdk.setup(Self.makeConfig(apiKey: apiKey, host: host))
        // SDK 는 opt-out 을 디스크에 남겨 다음 실행에도 되살린다. 이 함수는 통계 ON 일 때만 불리므로
        // 지난 실행의 opt-out 이 남아 있어도 지금의 설정(ON)을 따른다.
        sdk.optIn()
    }

    func capture(_ name: String, properties: [String: AnyHashable]) {
        // 값은 문자열·정수뿐이다(`AppEvent.parameters`). 상자를 벗겨 JSON 으로 바로 적히게 한다.
        let values: [String: Any] = properties.mapValues { $0.base }
        sdk.capture(name, properties: values.isEmpty ? nil : values)
    }

    func optIn() {
        sdk.optIn()
    }

    func optOut() {
        sdk.optOut()
    }
}
