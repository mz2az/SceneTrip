import Foundation
import SceneApiClient

/// 앱의 언어를 서버에 알린다 — `Accept-Language` (MZ2AZ-305).
///
/// 계약의 모든 창구가 이 헤더를 받는다(장소 이름·장면 설명·길찾기 안내·가이드 답). 호출마다
/// `acceptLanguage:` 인자를 넘기는 대신 **생성 클라이언트의 기본 헤더에 한 번** 둔다 — 창구가
/// 늘어도 빠뜨릴 자리가 없다. 서버는 생략하거나 번역이 없으면 `ko` 로 폴백한다.
///
/// 길찾기 안내문은 서버가 **번역하지 않는다**(ADR 0010). 제공자(카카오)는 한국어와 영어만
/// 주므로 `ko` 면 `ko`, 그 외(`en`·`ja`)는 전부 `en` 으로 온다 — 응답의 `guidanceLang` 이
/// 그것을 말한다. 앱 언어와 다르면 **그대로 보여 주고 언어 표시만 둔다**(번역은 별도 티켓).
/// 영어판은 고유명사가 로마자라 표지판과 맞는다.
enum AppLocale {
    /// 지금 앱의 언어. 시뮬레이터·기기 설정의 선호 언어 첫 줄을 계약의 셋(`ko`·`en`·`ja`)으로 접는다.
    static var lang: Lang {
        Lang.from(preferred: Locale.preferredLanguages)
    }

    /// 앱이 뜰 때 한 번. 이후 모든 요청에 `Accept-Language` 가 실린다.
    static func install() {
        SceneApiClientAPI.customHeaders["Accept-Language"] = lang.rawValue
    }

    /// 안내문 옆에 붙일 언어 표시. 응답 언어가 앱 언어와 같으면 없다 — 굳이 말할 것이 없다.
    static func guidanceNote(appLang: Lang, guidanceLang: String) -> String? {
        guard guidanceLang != appLang.rawValue else { return nil }
        return guidanceLang == "en"
            ? "안내는 English 로 와요 — 지명이 로마자라 표지판과 같아요"
            : "안내는 한국어로 와요"
    }
}

extension Lang {
    /// 선호 언어 목록(`ko-KR`·`ja-JP`·`en-US`…)의 첫 줄을 계약의 셋으로. 모르는 언어는 `en` —
    /// 일본어·중국어 사용자에게 한국어보다 영어가 낫다(계약 `guidanceLang` 설명).
    static func from(preferred: [String]) -> Lang {
        guard let first = preferred.first?.lowercased() else { return .ko }
        if first.hasPrefix("ko") {
            return .ko
        }
        if first.hasPrefix("ja") {
            return .ja
        }
        return .en
    }
}
