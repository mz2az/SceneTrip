import AppTrackingTransparency
import FacebookCore
import Foundation
import UIKit

/// MZ2AZ-391: 설정과 실제 ATT 허용이 모두 있어야 메타 SDK를 초기화한다.
/// 동의 전 이벤트는 저장하지 않고, 메타에는 사용자 속성과 장소 ID를 전달하지 않는다.
final class MetaAnalytics {
    static let shared = MetaAnalytics(
        configuration: MetaConfiguration.load(),
        authorized: { ATTrackingManager.trackingAuthorizationStatus == .authorized },
        client: FacebookMetaEventsClient(),
        statisticsEnabled: { UsageStatistics.shared.isEnabled }
    )

    private let configuration: MetaConfiguration?
    private let authorized: () -> Bool
    private let client: MetaEventsClient
    private let statisticsEnabled: () -> Bool
    private var started = false

    init(
        configuration: MetaConfiguration?, authorized: @escaping () -> Bool, client: MetaEventsClient,
        statisticsEnabled: @escaping () -> Bool = { true }
    ) {
        self.configuration = configuration
        self.authorized = authorized
        self.client = client
        self.statisticsEnabled = statisticsEnabled
    }

    func activate() {
        guard synchronizeConsent() else { return }
        client.activate()
        flushIfAllowed()
    }

    func log(_ event: AppEvent) {
        let name: String
        switch event {
        case .savePlace: name = "save_place"
        case .createCourse: name = "create_course"
        default: return
        }
        guard synchronizeConsent() else { return }
        client.log(name: name)
        flushIfAllowed()
    }

    private func flushIfAllowed() {
        guard synchronizeConsent() else { return }
        client.flush()
    }

    private func synchronizeConsent() -> Bool {
        guard let configuration, configuration.enabled, authorized(), statisticsEnabled() else {
            if started {
                client.setEnabled(false)
            }
            return false
        }
        if !started {
            client.start(configuration: configuration)
            started = true
        }
        client.setEnabled(true)
        return true
    }
}

struct MetaConfiguration {
    let appID: String
    let clientToken: String
    let enabled: Bool

    init(appID: String, clientToken: String, enabled: Bool) {
        self.appID = appID
        self.clientToken = clientToken
        self.enabled = enabled
    }

    init?(dictionary: [String: Any]) {
        guard let appID = dictionary["AppID"] as? String, !appID.isEmpty,
              appID.allSatisfy({ $0.isASCII && $0.isNumber }),
              let token = dictionary["ClientToken"] as? String, !token.isEmpty,
              token.count <= 256, !token.contains(where: \.isWhitespace),
              dictionary["MeasurementEnabled"] == nil || dictionary["MeasurementEnabled"] is Bool
        else { return nil }
        self.init(appID: appID, clientToken: token, enabled: dictionary["MeasurementEnabled"] as? Bool ?? false)
    }

    static func load(bundle: Bundle = .main) -> MetaConfiguration? {
        guard let url = bundle.url(forResource: "MetaService-Info.local", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let dictionary = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        else { return nil }
        return MetaConfiguration(dictionary: dictionary)
    }
}

protocol MetaEventsClient {
    func start(configuration: MetaConfiguration)
    func setEnabled(_ enabled: Bool)
    func activate()
    func log(name: String)
    func flush()
}

private struct FacebookMetaEventsClient: MetaEventsClient {
    func start(configuration: MetaConfiguration) {
        Settings.shared.appID = configuration.appID
        Settings.shared.clientToken = configuration.clientToken
        Settings.shared.isAutoLogAppEventsEnabled = false
        Settings.shared.isAdvertiserIDCollectionEnabled = true
        // 자동 flush만 막는다. 이미 예약한 flush·설치 전송을 취소하지는 않는다.
        AppEvents.shared.flushBehavior = .explicitOnly
        ApplicationDelegate.shared.application(UIApplication.shared, didFinishLaunchingWithOptions: nil)
    }

    func setEnabled(_ enabled: Bool) {
        AppEvents.shared.flushBehavior = .explicitOnly
        Settings.shared.isAutoLogAppEventsEnabled = false
        // SDK 18.1.1은 iOS 17+에서 이 setter를 무시하고 시스템 ATT 상태를 사용한다.
        // 공개 API에는 이벤트 큐 삭제나 예약된 전송 취소 기능이 없다(MZ2AZ-402).
        Settings.shared.isAdvertiserTrackingEnabled = enabled
        Settings.shared.isAdvertiserIDCollectionEnabled = enabled
    }

    func activate() {
        AppEvents.shared.activateApp()
    }

    func log(name: String) {
        AppEvents.shared.logEvent(AppEvents.Name(rawValue: name))
    }

    func flush() {
        AppEvents.shared.flush()
    }
}
