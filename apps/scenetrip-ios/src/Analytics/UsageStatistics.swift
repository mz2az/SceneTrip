import Foundation

/// Firebase·Meta 수집을 함께 제어하는 설치별 설정. 회원 여부나 ATT와 별도로 저장한다.
struct UsageStatistics {
    static let key = "scenetrip.analytics.collectionEnabled"
    static let shared = UsageStatistics()

    private let defaults: UserDefaults
    private let setFirebaseEnabled: (Bool) -> Void
    private let activateMeta: () -> Void

    init(
        defaults: UserDefaults = .standard,
        setFirebaseEnabled: @escaping (Bool) -> Void = { AppAnalytics.setCollectionEnabled($0) },
        activateMeta: @escaping () -> Void = { MetaAnalytics.shared.activate() }
    ) {
        self.defaults = defaults
        self.setFirebaseEnabled = setFirebaseEnabled
        self.activateMeta = activateMeta
    }

    var isEnabled: Bool {
        defaults.object(forKey: Self.key) as? Bool ?? true
    }

    func setEnabled(_ enabled: Bool) {
        // SDK를 건드리기 전에 저장한다. Meta도 같은 최신 값을 검사한다.
        defaults.set(enabled, forKey: Self.key)
        setFirebaseEnabled(enabled)
        activateMeta()
    }
}
