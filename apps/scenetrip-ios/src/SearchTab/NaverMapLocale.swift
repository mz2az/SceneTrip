import NMapsMap
import SceneApiClient

/// 지도 지명의 언어 — **앱 언어를 지도에 직접 넣는다** (MZ2AZ-529, 웹 c5724c98 의 `language=` 와 같은 일).
///
/// `NMFMapView.locale` 을 비워 두면 SDK 는 시스템 로캘(프로세스의 `AppleLanguages`)을 따른다. 그 값은
/// 실행할 때 정해지므로, 한국어 기기에서 고른 적 없이 영어로 시작한 화면에는 지명만 한국어였다
/// (2026-11-07 시뮬레이터 재현). 앱 언어를 넣으면 기기 언어·실행 시점과 무관하게 같은 언어가 된다.
///
/// 지도를 만들 때(`makeUIView`)와 다시 그릴 때(`updateUIView`) 모두 넣는다 — 언어를 바꾸면 `RootTabs` 가
/// 통째로 다시 만들어지지만, 살아남는 지도가 있어도 다음 그리기에서 맞춰진다.
enum NaverMapLocale {
    /// 지도에 넣을 로캘. 번역 표가 있는 언어(`AppLanguage.choices`)만 넣고, 나머지는 시스템에 맡긴다(nil).
    nonisolated static func code(for lang: Lang) -> String? {
        AppLanguage.choices.contains(lang) ? lang.rawValue : nil
    }

    /// 지금 앱 언어를 지도에 넣는다. 같은 값이면 건드리지 않는다 — 다시 넣으면 지도가 라벨을 새로 받는다.
    @MainActor
    static func apply(to mapView: NMFMapView, lang: Lang = AppLanguage.current) {
        let wanted = code(for: lang)
        if mapView.locale != wanted {
            mapView.locale = wanted
        }
    }
}
