import SceneApiClient
@testable import SceneTrip
import SwiftUI
import XCTest

/// 앱 언어 (MZ2AZ-343).
final class AppLanguageTests: XCTestCase {
    @MainActor
    func testNavigationSummaryGrowsForNarrowScreensAndLargeText() {
        for summary in ["환승 3회 · 도보 1234 m · 12,500원", "3 transfers · Walk 1234 m · ₩12,500"] {
            func height(width: CGFloat, size: DynamicTypeSize) -> CGFloat {
                let controller = UIHostingController(rootView:
                    TripNavigationSummary(duration: "128 min", summary: summary)
                        .environment(\.dynamicTypeSize, size))
                return controller.sizeThatFits(in: CGSize(width: width, height: 1000)).height
            }
            let wide = height(width: 600, size: .large)
            let narrow = height(width: 240, size: .large)
            let accessible = height(width: 240, size: .accessibility3)
            XCTAssertGreaterThan(narrow, wide, summary)
            XCTAssertGreaterThan(accessible, narrow, summary)
            XCTAssertLessThan(accessible, 1000, summary)
        }
    }

    /// 저장된 언어가 없으면 **기기 언어와 무관하게** 영어로 시작한다 (MZ2AZ-526). 첫 실행에 언어를
    /// 묻지 않으므로 고르는 곳은 마이페이지다.
    func testNoSavedLanguageStartsInEnglishWhateverTheDevice() {
        for device in [["ko-KR", "en-US"], ["ko"], ["en-GB"], ["ja-JP"], ["zh-Hant-TW"], []] {
            XCTAssertEqual(AppLanguage.startingLanguage(saved: nil, device: device), .en, "\(device)")
        }
    }

    /// 이미 고른 사람은 기기 언어가 무엇이든 고른 그대로다.
    func testSavedLanguageIsKept() {
        XCTAssertEqual(AppLanguage.startingLanguage(saved: "ko", device: ["en-US"]), .ko)
        XCTAssertEqual(AppLanguage.startingLanguage(saved: "ko", device: ["ko-KR"]), .ko)
        XCTAssertEqual(AppLanguage.startingLanguage(saved: "en", device: ["ko-KR"]), .en)
    }

    /// 읽을 수 없는 저장값은 저장값이 없는 것과 같다 — 영어.
    func testUnreadableSavedLanguageStartsInEnglish() {
        XCTAssertEqual(AppLanguage.startingLanguage(saved: "fr", device: ["ko-KR"]), .en)
        XCTAssertEqual(AppLanguage.startingLanguage(saved: "", device: ["ko-KR"]), .en)
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
