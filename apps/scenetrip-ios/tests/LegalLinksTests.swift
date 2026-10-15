@testable import SceneTrip
import XCTest

/// 처리방침·약관 주소는 우리 도메인의 https 페이지다 (MZ2AZ-455).
final class LegalLinksTests: XCTestCase {
    func testLinksAreHttpsOnOurDomain() {
        for url in [LegalLinks.privacy, LegalLinks.terms, LegalLinks.locationServices] {
            XCTAssertEqual(url.scheme, "https", url.absoluteString)
            XCTAssertEqual(url.host, "scenetrip.io", url.absoluteString)
            XCTAssertNil(url.user)
            XCTAssertNil(url.port)
        }
    }

    func testEachLinkHasItsOwnPage() {
        XCTAssertEqual(LegalLinks.privacy.path, "/privacy")
        XCTAssertEqual(LegalLinks.terms.path, "/terms")
        XCTAssertEqual(LegalLinks.locationServices.path, "/terms")
        XCTAssertEqual(LegalLinks.locationServices.fragment, "location-services")
    }

    func testLocationDisclosureHasTheRequestedEnglishCopy() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: "en"
        ))
        let table = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
        )
        let key = """
        현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. \
        위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다.
        """
        XCTAssertEqual(table[key], """
        We use your current location to find nearby filming locations and places, \
        for the AI guide, and for directions. \
        It is used only to handle the request and deleted right away; \
        for directions it is sent to Kakao to calculate the route.
        """)
    }

    /// 안내 문구의 번역이 빠지면 영어 화면에 한국어가 섞인다.
    func testNoticeWordingIsTranslated() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: "en"
        ))
        let table = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
        )
        let keys = [
            "개인정보 처리방침", "이용약관", "약관 및 정책", "계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다.",
            "위치기반서비스 이용 동의", "위치기반서비스 이용약관", "동의하고 계속", "위치기반서비스 이용",
            "끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.", "길 안내를 위해 카카오에 현재 위치를 보냈어요.",
            "위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.",
        ]
        for key in keys {
            let english = try XCTUnwrap(table[key], key)
            XCTAssertNotEqual(english, key)
            XCTAssertNil(english.range(of: "\\p{Hangul}", options: .regularExpression), english)
        }
    }
}
