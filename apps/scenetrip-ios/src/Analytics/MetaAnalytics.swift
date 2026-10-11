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
        client: FacebookMetaEventsClient()
    )

    private let configuration: MetaConfiguration?
    private let authorized: () -> Bool
    private let client: MetaEventsClient
    private var started = false

    init(configuration: MetaConfiguration?, authorized: @escaping () -> Bool, client: MetaEventsClient) {
        self.configuration = configuration
        self.authorized = authorized
        self.client = client
    }

    func activate() {
        guard synchronizeConsent() else { return }
        client.activate()
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
    }

    private func synchronizeConsent() -> Bool {
        guard let configuration, configuration.enabled, authorized() else {
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
}

private struct FacebookMetaEventsClient: MetaEventsClient {
    func start(configuration: MetaConfiguration) {
        Settings.shared.appID = configuration.appID
        Settings.shared.clientToken = configuration.clientToken
        Settings.shared.isAutoLogAppEventsEnabled = false
        Settings.shared.isAdvertiserIDCollectionEnabled = true
        // 정기 자동 전송을 끄고, ATT 허용을 확인한 호출에서만 전송한다.
        AppEvents.shared.flushBehavior = .explicitOnly
        ApplicationDelegate.shared.application(UIApplication.shared, didFinishLaunchingWithOptions: nil)
    }

    func setEnabled(_ enabled: Bool) {
        Settings.shared.isAdvertiserIDCollectionEnabled = enabled
    }

    func activate() {
        AppEvents.shared.activateApp()
        AppEvents.shared.flush()
    }

    func log(name: String) {
        AppEvents.shared.logEvent(AppEvents.Name(rawValue: name))
        AppEvents.shared.flush()
    }
}
