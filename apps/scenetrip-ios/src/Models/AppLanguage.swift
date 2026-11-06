import Combine
import Foundation
import SceneApiClient
import SwiftUI

/// 앱의 언어 — 사람이 앱 안에서 고른다 (MZ2AZ-343).
///
/// 기기 언어와 따로 간다. 한국에 여행 온 사람의 폰은 일본어·스페인어일 수 있는데, 그렇다고
/// 앱이 정할 일이 아니다 — 마이페이지에서 언제든 바꾼다. **고른 적이 없으면 기기 언어와 무관하게
/// 영어로 시작한다** — 첫 실행에 언어를 묻지 않는다(MZ2AZ-526, 웹 MZ2AZ-523 과 같다).
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

    /// 언어가 **실제로 바뀐 순간** 새 언어를 알린다 (MZ2AZ-529, 웹 `onLanguageChange` 와 같다).
    ///
    /// 화면은 `RootTabs().id(lang)` 로 통째로 다시 만들어지지만, 앱에 하나뿐인 저장소(`LikeStore.shared`)와
    /// 언어를 바꾸는 동안 떠 있는 덮개(마이페이지)는 그대로 남는다. 그 둘이 받아 둔 서버 내용(작품·장소 이름)은
    /// 받을 때의 언어로 굳어 있으므로 이것을 듣고 새 언어로 다시 받는다.
    static let changes = PassthroughSubject<Lang, Never>()

    private init() {
        let stored = UserDefaults.standard.string(forKey: Self.key)
        lang = Self.startingLanguage(saved: stored)
        Self.current = lang
        // 실행할 때마다 다시 적는다 — 이 기능 전에 고른 사람도 그다음 실행부터 맞는다.
        // 고른 적이 없어도 시작 언어(영어)를 적는다(MZ2AZ-529) — 화면은 영어인데 권한 창·시트 낭독이
        // 기기 언어(예: 한국어)로 남지 않게. 이번 실행의 시스템 글자는 이미 정해졌으므로 다음 실행부터다.
        Self.syncSystemLanguage(Self.systemLanguage(saved: stored))
    }

    /// **시스템이 주는 글자**도 고른 언어를 따르게 한다 (MZ2AZ-367).
    ///
    /// 시트 손잡이·시트 바깥(「팝업 닫기」)의 낭독 문구, 사진 고르기, 권한 창은 우리 번역 표
    /// (`Localizable.strings`)가 아니라 **프로세스의 언어**로 나온다 — 기기가 한국어면 앱에서 English 를
    /// 골라도 한국어로 읽었다(2026-10-08 실기). 그 언어는 실행할 때 `AppleLanguages` 에서 한 번 정해지므로
    /// 여기 적어 두면 **다음 실행부터** 맞는다(설정 앱의 「앱별 언어」 와 같은 자리다).
    ///
    /// **권한 창·ATT 창은 따르지 않는다** — 시스템이 앱 밖에서 그리는 창이라 기기 언어로 나왔다
    /// (MZ2AZ-529 시뮬레이터: 영어를 적은 뒤의 실행에서도 위치·ATT 창이 한국어). 따르는 것은 앱 안에서
    /// 그리는 시스템 글자(「dismiss popup」 낭독 등)다.
    ///
    /// 지도 지명은 여기에 기대지 않고 앱 언어를 지도에 직접 넣는다(`NaverMapLocale`, MZ2AZ-529).
    ///
    /// 내놓는 언어(`choices`)일 때만 적는다 — 번역 표가 없는 언어를 적으면 시스템 글자만 그 언어가 된다.
    private static func syncSystemLanguage(_ lang: Lang) {
        guard choices.contains(lang) else { return }
        UserDefaults.standard.set([lang.rawValue], forKey: "AppleLanguages")
    }

    func choose(_ new: Lang) {
        let changed = new != lang
        UserDefaults.standard.set(new.rawValue, forKey: Self.key)
        Self.syncSystemLanguage(new)
        Self.current = new
        lang = new
        AppLocale.install()
        AppAnalytics.setLanguage(new.rawValue)
        AppAnalytics.log(.selectLanguage(new.rawValue))
        // 요청 머리(`Accept-Language`)를 바꾼 뒤에 알린다 — 듣는 쪽이 곧바로 새 언어로 받는다.
        if changed {
            Self.changes.send(new)
        }
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

    /// 앱을 열 때의 언어 — 저장값이 있으면 그대로, 없거나 읽을 수 없으면 영어 (MZ2AZ-526).
    ///
    /// 기기 언어(`device`)는 **일부러 보지 않는다.** 광고로 들어온 외국인이 첫 화면부터 읽을 수 있어야 하고,
    /// 한국어 기기라도 한국어는 마이페이지에서 고른다. 매개변수는 그 사실을 시험이 여러 기기 언어로
    /// 고정하려고 받는다.
    nonisolated static func startingLanguage(saved: String?, device _: [String] = Locale.preferredLanguages) -> Lang {
        saved.flatMap(Lang.init(rawValue:)) ?? .en
    }

    /// 시스템 언어 키(`AppleLanguages`)에 적을 언어 — **화면에 나오는 언어와 같다** (MZ2AZ-529).
    ///
    /// 고른 적이 없으면(또는 읽을 수 없으면) 시작 언어인 영어다. 전에는 고른 적이 없으면 적지 않아,
    /// 한국어 기기에서 화면은 영어인데 권한 창·시트 바깥 낭독·사진 고르기가 한국어였다(2026-11-07 시뮬레이터).
    /// 적는 곳은 **이 앱의 설정 영역**이다(`UserDefaults.standard`) — 기기 전체 언어는 바뀌지 않는다.
    nonisolated static func systemLanguage(saved: String?) -> Lang {
        startingLanguage(saved: saved)
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

    /// 앱 코드가 속한 번들에서 요청한 언어의 문자열 표를 찾는다.
    /// 호스트 없는 단위 시험에서는 앱 리소스도 테스트 번들에 있으므로 실행 파일의 번들을 쓰지 않는다.
    nonisolated static func bundle(for lang: Lang) -> Bundle {
        let resources = Bundle(for: AppLanguage.self)
        guard let path = resources.path(forResource: lang.rawValue, ofType: "lproj"),
              let found = Bundle(path: path)
        else { return resources }
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
