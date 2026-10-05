import FirebaseAnalytics
import FirebaseCore
import Foundation

/// 이벤트를 받아 주는 곳. 진짜(Firebase)와 가짜(시험)가 같은 모양이다.
protocol AnalyticsSink {
    func log(_ event: AppEvent)
    func setUserProperty(_ value: String?, for name: String)
}

/// 앱 분석의 입구 (MZ2AZ-353). 화면 코드는 `AppAnalytics.log(.createCourse(...))` 한 줄만 쓴다.
///
/// ## 설정 파일이 없으면 아무것도 보내지 않는다
///
/// Firebase 는 `GoogleService-Info.plist` 가 번들에 있어야 켜진다. 이 파일은 Firebase 콘솔에서 받는
/// 것이라 CI 와 다른 팀원의 빌드에는 없을 수 있다 — 없으면 켜지 않고(켜면 죽는다) 이벤트를 버린다.
/// 그래서 **파일이 없어도 앱은 그대로 빌드되고 돈다.**
///
/// 광고 식별자(IDFA)는 쓰지 않는다(`FirebaseAnalyticsCore`). 광고를 집행하게 되면 다시 정한다 —
/// 그때는 추적 동의 창이 필요하다.
enum AppAnalytics {
    /// 시험에서 가짜로 갈아 끼운다.
    nonisolated(unsafe) static var sink: AnalyticsSink = NoSink()

    /// 앱이 뜰 때 한 번.
    static func start(language: String, member: Bool) {
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            sink = FirebaseSink()
        }
        setLanguage(language)
        setMember(member)
    }

    static func log(_ event: AppEvent) {
        sink.log(event)
    }

    /// 사용자 속성 — 이벤트를 언어별·회원 여부별로 나눠 보려고 둔다. 사람을 가리키는 값은 아니다.
    static func setLanguage(_ language: String) {
        sink.setUserProperty(language, for: "app_language")
    }

    static func setMember(_ member: Bool) {
        sink.setUserProperty(member ? "member" : "guest", for: "account")
    }
}

private struct NoSink: AnalyticsSink {
    func log(_: AppEvent) {}
    func setUserProperty(_: String?, for _: String) {}
}

private struct FirebaseSink: AnalyticsSink {
    func log(_ event: AppEvent) {
        Analytics.logEvent(event.name, parameters: event.parameters.isEmpty ? nil : event.parameters)
    }

    func setUserProperty(_ value: String?, for name: String) {
        Analytics.setUserProperty(value, forName: name)
    }
}
