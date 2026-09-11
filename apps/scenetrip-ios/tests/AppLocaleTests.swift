import SceneApiClient
@testable import SceneTrip
import XCTest

/// `Accept-Language` 와 `guidanceLang` 대조 (MZ2AZ-305).
final class AppLocaleTests: XCTestCase {
    /// 기기 언어를 계약의 셋으로 접는다. 모르는 언어는 영어 — 일본어·중국어 사용자에게
    /// 한국어보다 영어가 낫다.
    func testPreferredLanguageFoldsIntoContractLang() {
        XCTAssertEqual(Lang.from(preferred: ["ko-KR", "en-US"]), .ko)
        XCTAssertEqual(Lang.from(preferred: ["ja-JP"]), .ja)
        XCTAssertEqual(Lang.from(preferred: ["en-GB"]), .en)
        XCTAssertEqual(Lang.from(preferred: ["zh-Hant-TW"]), .en)
        XCTAssertEqual(Lang.from(preferred: []), .ko)
    }

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
