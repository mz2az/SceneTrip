import SceneApiClient
@testable import SceneTrip
import XCTest

/// `Accept-Language` 와 `guidanceLang` 대조 (MZ2AZ-305).
final class AppLocaleTests: XCTestCase {
    /// 앱이 뜨면 기본 헤더에 실린다 — 창구마다 인자를 넘길 필요가 없다.
    func testInstallSetsDefaultHeader() {
        AppLocale.install()
        XCTAssertEqual(SceneApiClientAPI.customHeaders["Accept-Language"], AppLocale.lang.rawValue)
    }

    /// 응답 언어가 앱 언어와 같으면 말할 것이 없다. 다르면 그대로 보여 주고 언어만 표시한다.
    func testGuidanceNoteOnlyWhenLanguagesDiffer() throws {
        XCTAssertNil(AppLocale.guidanceNote(appLang: .ko, guidanceLang: "ko"))
        XCTAssertNil(AppLocale.guidanceNote(appLang: .en, guidanceLang: "en"))
        XCTAssertNotNil(AppLocale.guidanceNote(appLang: .ja, guidanceLang: "en"))
        XCTAssertNotNil(AppLocale.guidanceNote(appLang: .en, guidanceLang: "ko"))
        XCTAssertTrue(try XCTUnwrap(AppLocale.guidanceNote(appLang: .ja, guidanceLang: "en")?.contains("로마자")))
    }
}
