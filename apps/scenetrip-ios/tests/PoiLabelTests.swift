@testable import SceneTrip
import XCTest

/// 편의시설을 앱 언어로 적는 규칙 (MZ2AZ-360). 계약: 제목은 `displayName ?? name`, 영어 이름이 없으면
/// 한국어 이름 아래 `nameRoman`.
final class PoiLabelTests: XCTestCase {
    private func label(
        name: String = "명동해물탕", displayName: String? = nil, nameRoman: String? = "Myeongdonghaemultang",
        category: String? = "한식", categoryLabel: String? = "Korean",
        address: String? = "서울 중구 명동2가", displayAddress: String? = "21-7 Myeongdong 8-gil, Jung-gu, Seoul",
        korean: Bool = false
    ) -> PoiLabel {
        PoiLabel.make(
            name: name, displayName: displayName, nameRoman: nameRoman,
            category: category, categoryLabel: categoryLabel,
            address: address, displayAddress: displayAddress, korean: korean
        )
    }

    /// 영어 이름이 있으면 그것이 제목이고, 읽는 법은 덧붙이지 않는다.
    func testEnglishNameIsTheTitle() {
        let shown = label(name: "스타벅스 명동점", displayName: "Starbucks Myeongdong Branch", nameRoman: "Seutabeokseu")
        XCTAssertEqual(shown.title, "Starbucks Myeongdong Branch")
        XCTAssertNil(shown.reading)
        XCTAssertEqual(shown.caption, "Starbucks Myeongdong Branch")
    }

    /// 영어 이름이 없으면 **한글 이름을 지우지 않는다** — 간판이 한글이다. 아래에 읽는 법.
    func testWithoutEnglishNameKoreanStaysAndReadingFollows() {
        let shown = label()
        XCTAssertEqual(shown.title, "명동해물탕")
        XCTAssertEqual(shown.reading, "Myeongdonghaemultang")
        XCTAssertEqual(shown.category, "Korean")
        XCTAssertEqual(shown.address, "21-7 Myeongdong 8-gil, Jung-gu, Seoul")
        // 지도 이름표는 한 줄뿐이라 읽는 법을 적는다.
        XCTAssertEqual(shown.caption, "Myeongdonghaemultang")
    }

    /// 한국어 화면은 전과 같다 — 서버가 영어 칸을 비워 보내고, 읽는 법은 와도 쓰지 않는다.
    func testKoreanScreenIsUnchanged() {
        let shown = label(displayName: nil, categoryLabel: "한식", displayAddress: nil, korean: true)
        XCTAssertEqual(shown, PoiLabel(title: "명동해물탕", reading: nil, category: "한식", address: "서울 중구 명동2가"))
        XCTAssertEqual(shown.caption, "명동해물탕")
    }

    /// 영어로 받아 둔 목록이 남은 채 한국어로 바꿔도 영어가 섞여 나오지 않는다.
    func testKoreanScreenIgnoresStaleEnglishFields() {
        let shown = label(name: "스타벅스 명동점", displayName: "Starbucks Myeongdong Branch", korean: true)
        XCTAssertEqual(shown, PoiLabel(title: "스타벅스 명동점", reading: nil, category: "한식", address: "서울 중구 명동2가"))
    }

    /// 새 칸이 없으면(옛 서버·가이드가 찾아 준 곳) 한국어 원본이 그대로 보인다.
    func testMissingFieldsFallBackToTheOriginal() {
        let shown = label(nameRoman: nil, categoryLabel: nil, displayAddress: "  ")
        XCTAssertEqual(shown, PoiLabel(title: "명동해물탕", reading: nil, category: "한식", address: "서울 중구 명동2가"))
    }

    /// 영어 이름 칸이 빈 글자로 와도 없는 것으로 친다 — 한글 이름과 읽는 법이 나온다.
    func testBlankEnglishNameCountsAsMissing() {
        let shown = label(displayName: "  ")
        XCTAssertEqual(shown.title, "명동해물탕")
        XCTAssertEqual(shown.reading, "Myeongdonghaemultang")
    }

    func testKoreanScreenWithoutCategory() {
        XCTAssertNil(label(category: nil, korean: true).category)
    }

    /// 이름이 원래 로마자면 읽는 법이 같은 글자다 — 두 번 적지 않는다.
    func testReadingIdenticalToTheNameIsDropped() {
        XCTAssertNil(label(name: "GS25", nameRoman: "gs25").reading)
        XCTAssertEqual(label(name: "GS25 명동점", nameRoman: "GS25 Myeongdongjeom").reading, "GS25 Myeongdongjeom")
    }
}
