import Foundation
import Sentry

/// Sentry SDK 를 감싸는 자리. 진짜(`SentrySDKClient`)와 시험용 기록기가 같은 모양이다.
/// (Sentry 에 `SentryClient` 라는 형이 있어 이름을 달리한다.)
protocol ErrorReportingClient: AnyObject {
    func start(dsn: String, environment: String)
    func close()
}

/// 오류 추적 Sentry (MZ2AZ-527). **충돌·오류만** 보낸다 — 성능 추적·프로파일링·화면 녹화·세션 집계는 켜지 않는다.
/// 웹(`apps/scenetrip-web/src/analytics/sentry.ts`)과 같은 방침이다.
///
/// - DSN(`.env` 의 `SENTRY_DSN_IOS` → `--define=sentry_dsn_ios` → `Secrets.sentryDsnIos`)이 비면 SDK 를 한 번도 부르지 않는다.
/// - 이용 통계(`UsageStatistics`)가 OFF 로 시작하면 시작하지 않는다. 나중에 ON 이 되면 그때 시작하고,
///   시작한 뒤 OFF 가 되면 닫는다(`SentrySDK.close`). ATT 와 무관하다.
/// - 환경은 API 주소로 가른다 — 운영 주소면 `production`, 그 밖(DEV·로컬)은 `dev`.
final class ErrorReporting {
    static let productionAPIBaseURL = "https://api.scenetrip.io/v1"
    static let shared = ErrorReporting(
        dsn: Secrets.sentryDsnIos,
        environment: environment(forAPIBaseURL: ApiConfiguration.baseURL),
        client: SentrySDKClient(),
        statisticsEnabled: { UsageStatistics.shared.isEnabled }
    )

    static func environment(forAPIBaseURL baseURL: String) -> String {
        baseURL == productionAPIBaseURL ? "production" : "dev"
    }

    private let dsn: String
    private let environment: String
    private let client: ErrorReportingClient
    private let statisticsEnabled: () -> Bool
    private var running = false

    init(dsn: String, environment: String, client: ErrorReportingClient, statisticsEnabled: @escaping () -> Bool) {
        self.dsn = dsn.trimmingCharacters(in: .whitespacesAndNewlines)
        self.environment = environment
        self.client = client
        self.statisticsEnabled = statisticsEnabled
    }

    /// 이용 통계 설정에 맞춰 시작하고 닫는다. 앱이 뜰 때와 설정이 바뀔 때 부른다.
    func sync() {
        guard !dsn.isEmpty else { return }
        let wanted = statisticsEnabled()
        if wanted, !running {
            client.start(dsn: dsn, environment: environment)
            running = true
        } else if !wanted, running {
            client.close()
            running = false
        }
    }
}

final class SentrySDKClient: ErrorReportingClient {
    static func makeOptions(dsn: String, environment: String) -> Options {
        let options = Options()
        options.dsn = dsn
        options.environment = environment
        // 사용자·IP 를 붙이지 않는다.
        options.sendDefaultPii = false
        // 충돌·오류만 — 성능 추적·프로파일링·화면 녹화·세션 집계는 끈다.
        options.tracesSampleRate = nil
        options.configureProfiling = nil
        options.sessionReplay.sessionSampleRate = 0
        options.sessionReplay.onErrorSampleRate = 0
        options.enableAutoPerformanceTracing = false
        options.enableNetworkTracking = false
        options.enableUserInteractionTracing = false
        options.enableAutoSessionTracking = false
        options.enableLogs = false
        options.attachScreenshot = false
        options.attachViewHierarchy = false
        // 요청 주소에 검색어·좌표가 실린다 — 실패한 요청 보고와 브레드크럼을 모두 끈다(웹과 같다).
        options.enableCaptureFailedRequests = false
        options.enableNetworkBreadcrumbs = false
        options.enableAutoBreadcrumbTracking = false
        options.beforeBreadcrumb = { _ in nil }
        options.enableCrashHandler = true
        options.beforeSend = { event in
            event.user = nil
            return event
        }
        return options
    }

    func start(dsn: String, environment: String) {
        SentrySDK.start(options: Self.makeOptions(dsn: dsn, environment: environment))
    }

    func close() {
        SentrySDK.close()
    }
}
