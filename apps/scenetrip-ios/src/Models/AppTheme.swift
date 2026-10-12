import SwiftUI

/// 기기의 화면 모드 선택. 시스템을 기본으로 하며 계정 데이터와 분리한다 (MZ2AZ-419).
enum AppTheme: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    static let key = "scenetrip.appearance"
    var id: String {
        rawValue
    }

    static func resolve(_ raw: String?) -> AppTheme {
        raw.flatMap(AppTheme.init(rawValue:)) ?? .system
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    var title: String {
        switch self {
        case .system: tr("시스템 설정")
        case .light: tr("라이트 모드")
        case .dark: tr("다크 모드")
        }
    }

    func store(in defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.key)
    }
}
