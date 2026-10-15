@testable import SceneTrip
import XCTest

/// 처리방침·약관 주소는 우리 도메인의 https 페이지다 (MZ2AZ-455).
final class LegalLinksTests: XCTestCase {
    func testLinksAreHttpsOnOurDomain() {
        for url in [LegalLinks.privacy, LegalLinks.terms] {
            XCTAssertEqual(url.scheme, "https", url.absoluteString)
            XCTAssertEqual(url.host, "scenetrip.io", url.absoluteString)
            XCTAssertNil(url.user)
            XCTAssertNil(url.port)
        }
    }

    func testEachLinkHasItsOwnPage() {
        XCTAssertEqual(LegalLinks.privacy.path, "/privacy")
        XCTAssertEqual(LegalLinks.terms.path, "/terms")
    }

    /// 안내 문구의 번역이 빠지면 영어 화면에 한국어가 섞인다.
    func testNoticeWordingIsTranslated() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: "en"
        ))
        let table = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
        )
        let keys = ["개인정보 처리방침", "이용약관", "약관 및 정책", "계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다."]
        for key in keys {
            let english = try XCTUnwrap(table[key], key)
            XCTAssertNotEqual(english, key)
            XCTAssertNil(english.range(of: "\\p{Hangul}", options: .regularExpression), english)
        }
    }
}
