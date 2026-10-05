import Foundation
import SceneApiClient
import SwiftUI

/// 앱의 언어 — 사람이 앱 안에서 고른다 (MZ2AZ-343).
///
/// 기기 언어와 따로 간다. 한국에 여행 온 사람의 폰은 일본어·스페인어일 수 있는데, 그렇다고
/// 앱이 정할 일이 아니다 — 첫 실행에 묻고, 마이페이지에서 언제든 바꾼다. 고르기 전에는
/// 기기 언어를 따른다(한국어가 아니면 영어).
///
/// 고른 언어는 두 군데에 쓰인다.
/// 1. **화면 문구** — `Localizable.strings` 를 이 언어로 찾는다. SwiftUI 의 글자(`Text("…")` 등)는
///    루트에 준 `\.locale` 로, 코드가 만드는 문자열은 [tr] 로.
/// 2. **서버 내용** — `Accept-Language`(`AppLocale`). 작품·장소 이름과 장면 설명이 같은 언어로 온다.
///
/// 문구의 열쇠는 **한국어 원문**이다. 번역이 없으면 원문이 그대로 나온다 — 빈 화면보다 낫다.
@MainActor
final class AppLanguage: ObservableObject {
    static let shared = AppLanguage()

    /// 화면에 고를 수 있게 내놓는 언어. 번역 표가 있는 것만 넣는다.
    nonisolated static let choices: [Lang] = [.ko, .en]

    private static let key = "scenetrip.language"

    @Published private(set) var lang: Lang

    /// 사람이 직접 고른 적이 있는가. 없으면 첫 실행에 묻는다.
    @Published private(set) var hasChosen: Bool

    private init() {
        let saved = UserDefaults.standard.string(forKey: Self.key).flatMap(Lang.init(rawValue:))
        hasChosen = saved != nil
        lang = saved ?? Self.deviceDefault()
        Self.current = lang
    }

    func choose(_ new: Lang) {
        UserDefaults.standard.set(new.rawValue, forKey: Self.key)
        hasChosen = true
        Self.current = new
        lang = new
        AppLocale.install()
    }

    var locale: Locale {
        Locale(identifier: lang.rawValue)
    }

    /// 언어 이름은 **그 언어로** 적는다 — 한국어를 못 읽는 사람이 「영어」를 찾을 수는 없다.
    nonisolated static func name(of lang: Lang) -> String {
        switch lang {
        case .ko: "한국어"
        case .en: "English"
        case .ja: "日本語"
        case .zhHant: "繁體中文"
        }
    }

    /// 기기 언어를 내놓는 언어로 접는다 — 한국어면 한국어, 그 밖은 영어.
    nonisolated static func deviceDefault(preferred: [String] = Locale.preferredLanguages) -> Lang {
        Lang.from(preferred: preferred) == .ko ? .ko : .en
    }

    // MARK: 코드가 만드는 문자열

    /// 지금 언어. 화면 밖(오류 문구를 만드는 `enum` 등)에서도 읽으므로 격리하지 않는다 —
    /// 쓰는 곳은 메인 스레드의 [choose] 하나다.
    nonisolated(unsafe) static var current: Lang = .ko

    /// 날짜·시각을 **앱 언어로** 적을 때 쓴다 — `Date.formatted` 는 기기 로케일을 따라, 영어 화면에
    /// 「어제」「오전 4:06」이 섞였다(MZ2AZ-351 검증). `.locale(AppLanguage.currentLocale)` 로 넘긴다.
    nonisolated static var currentLocale: Locale {
        Locale(identifier: current.rawValue)
    }

    /// 그 언어의 문자열 표. 한 번 찾아 두고 다시 쓴다.
    nonisolated static func bundle(for lang: Lang) -> Bundle {
        guard let path = Bundle.main.path(forResource: lang.rawValue, ofType: "lproj"),
              let found = Bundle(path: path)
        else { return .main }
        return found
    }
}

/// 한국어 원문을 지금 언어로. **`Text("…")` 처럼 글자를 바로 적는 곳에는 필요 없다** — SwiftUI 가
/// 알아서 찾는다. 문자열을 변수로 넘기거나 코드에서 조립할 때 쓴다.
///
/// 숫자가 들어가면 원문에 `%d`·`%@` 를 두고 `String(format: tr("%d개"), count)` 처럼 쓴다.
func tr(_ korean: String) -> String {
    let lang = AppLanguage.current
    guard lang != .ko else { return korean }
    return AppLanguage.bundle(for: lang).localizedString(forKey: korean, value: korean, table: nil)
}

/// 같은 한국어가 자리에 따라 다른 영어가 되어야 할 때 — 「코스」가 화면 제목이면 Courses, 딱지면 Course.
/// 번역 표의 열쇠는 `원문|자리`. 그 열쇠가 없으면 자리 없는 번역으로 물러선다.
func tr(_ korean: String, at place: String) -> String {
    let lang = AppLanguage.current
    guard lang != .ko else { return korean }
    let key = "\(korean)|\(place)"
    let found = AppLanguage.bundle(for: lang).localizedString(forKey: key, value: key, table: nil)
    return found == key ? tr(korean) : found
}
