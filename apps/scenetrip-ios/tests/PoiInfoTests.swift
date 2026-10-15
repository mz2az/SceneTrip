import SceneApiClient
@testable import SceneTrip
import XCTest

final class PoiInfoTests: XCTestCase {
    private let fields = ["overview", "useTime", "restDate", "parking", "firstMenu", "useFee"]

    func testAbsentNullAndEmptyInfoHideTheArea() throws {
        let nullFields = Dictionary(uniqueKeysWithValues: (fields + ["homepage"]).map { ($0, NSNull()) })
        let cases: [[String: Any]] = [[:], ["info": NSNull()], ["info": [:]], ["info": nullFields]]
        for extra in cases {
            let detail = try decode(extra)
            XCTAssertTrue(PoiInfoRules.rows(detail.info).isEmpty)
            XCTAssertFalse(PoiInfoRules.isVisible(detail.info))
        }
    }

    func testEachOmittedOrNullFieldHidesOnlyThatRow() throws {
        let populated = Dictionary(uniqueKeysWithValues: fields.map { ($0, ["text": $0, "lang": "ko"] as Any) })
        for field in fields {
            for null in [false, true] {
                var info = populated
                if null {
                    info[field] = NSNull()
                } else {
                    info.removeValue(forKey: field)
                }
                let rows = try PoiInfoRules.rows(decode(["info": info]).info)
                XCTAssertEqual(rows.count, fields.count - 1, field)
                XCTAssertFalse(rows.contains { $0.text == field }, field)
            }
        }
    }

    func testOnlyPresentRowsAppearAndFreeFormLineBreaksSurvive() throws {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .ko
        let text = "10:30~21:00\n라스트오더 20:30\n예약 문의"
        let detail = try decode(["info": ["useTime": ["text": text, "lang": "ko"]]])
        let rows = PoiInfoRules.rows(detail.info)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.label, "이용 시간")
        XCTAssertEqual(rows.first?.text, text)
    }

    func testLanguageTagsArePerFieldAndUseTheAppLanguage() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        let info = PoiInfo(overview: PoiInfoText(text: "Overview", lang: .en),
                           parking: PoiInfoText(text: "주차 가능", lang: .ko))
        AppLanguage.current = .en
        XCTAssertNil(PoiInfoRules.rows(info)[0].languageTag)
        XCTAssertEqual(PoiInfoRules.rows(info)[1].languageTag, "Korean")
        AppLanguage.current = .ko
        XCTAssertEqual(PoiInfoRules.rows(info)[0].languageTag, "영어")
        XCTAssertNil(PoiInfoRules.rows(info)[1].languageTag)
    }

    func testLabelsUseExistingKoreanAndEnglishStrings() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        let value = PoiInfoText(text: "안내", lang: .ko)
        let info = PoiInfo(overview: value, useTime: value, restDate: value,
                           parking: value, firstMenu: value, useFee: value)
        AppLanguage.current = .ko
        XCTAssertEqual(PoiInfoRules.rows(info).map(\.label), ["개요", "이용 시간", "쉬는 날", "주차", "대표 메뉴", "입장료"])
        AppLanguage.current = .en
        XCTAssertEqual(PoiInfoRules.rows(info).map(\.label),
                       ["Overview", "Hours", "Closed", "Parking", "Signature menu", "Admission"])
        XCTAssertEqual(tr("홈페이지"), "Website")
    }

    func testHomepageOnlyAllowsWebAddressesAndCanStandAlone() {
        for raw in ["https://example.com/info", "http://example.com"] {
            let info = PoiInfo(homepage: raw)
            XCTAssertEqual(PoiInfoRules.homepage(info)?.absoluteString, raw)
            XCTAssertTrue(PoiInfoRules.isVisible(info))
        }
        let invalid = [nil, "", "javascript:alert(1)", "file:///tmp/page", "//example.com", "https://",
                       "https://u:p@example.com"]
        for raw in invalid {
            let info = PoiInfo(homepage: raw)
            XCTAssertNil(PoiInfoRules.homepage(info))
            XCTAssertFalse(PoiInfoRules.isVisible(info))
        }
    }

    func testGeneratedDetailInfoReachesTheExistingPoiCard() throws {
        let detail = try decode(["info": ["overview": ["text": "관광공사 안내", "lang": "ko"]]])
        let place = RouteGuide.Place(poi: PoiSummary(id: 7, name: "명소", category: "관광지", categoryGroup: .sight,
                                                     latitude: 37.5, longitude: 127))
        let card = RouteGuide.card(poi: detail, listed: place)
        XCTAssertEqual(card.info, detail.info)
        XCTAssertNil(RouteGuide.card(poi: nil, listed: place).info)
    }

    private func decode(_ extra: [String: Any]) throws -> PoiDetail {
        var base: [String: Any] = ["id": 7, "name": "명소", "category": "관광지", "categoryGroup": "sight",
                                   "latitude": 37.5, "longitude": 127.0, "images": []]
        base.merge(extra) { _, new in new }
        return try JSONDecoder().decode(PoiDetail.self, from: JSONSerialization.data(withJSONObject: base))
    }
}
