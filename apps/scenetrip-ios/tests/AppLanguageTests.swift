import SceneApiClient
@testable import SceneTrip
import XCTest

/// 앱 언어 (MZ2AZ-343).
final class AppLanguageTests: XCTestCase {
    /// 고르기 전에는 기기 언어를 따른다 — 한국어면 한국어, **그 밖은 전부 영어**(번역 표가 둘뿐이다).
    func testDeviceLanguageFoldsToKoreanOrEnglish() {
        XCTAssertEqual(AppLanguage.deviceDefault(preferred: ["ko-KR", "en-US"]), .ko)
        XCTAssertEqual(AppLanguage.deviceDefault(preferred: ["en-GB"]), .en)
        XCTAssertEqual(AppLanguage.deviceDefault(preferred: ["ja-JP"]), .en)
        XCTAssertEqual(AppLanguage.deviceDefault(preferred: ["zh-Hant-TW"]), .en)
        XCTAssertEqual(AppLanguage.deviceDefault(preferred: []), .ko)
    }

    /// 내놓는 언어는 번역 표가 있는 것뿐이다. 이름은 그 언어로 적는다.
    func testChoicesAreNamedInTheirOwnLanguage() {
        XCTAssertEqual(AppLanguage.choices, [.ko, .en])
        XCTAssertEqual(AppLanguage.name(of: .ko), "한국어")
        XCTAssertEqual(AppLanguage.name(of: .en), "English")
    }

    /// 한국어일 때는 원문 그대로, 번역이 없는 문구도 원문 그대로 — 빈 글자가 나오면 안 된다.
    func testMissingTranslationFallsBackToTheKoreanSource() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }

        AppLanguage.current = .ko
        XCTAssertEqual(tr("내 코스"), "내 코스")
        AppLanguage.current = .en
        XCTAssertEqual(tr("번역 표에 없는 문구 — 시험용"), "번역 표에 없는 문구 — 시험용")
    }
}
