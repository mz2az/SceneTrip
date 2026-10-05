@testable import SceneTrip
import XCTest

/// 편의시설 카드는 네이버에서 **가져오지 않고 넘긴다** (MZ2AZ-354).
final class NaverMapLinkTests: XCTestCase {
    func testSearchLinkCarriesTheName() {
        XCTAssertEqual(
            NaverMapLink.search("명동교자 본점"),
            "https://map.naver.com/p/search/%EB%AA%85%EB%8F%99%EA%B5%90%EC%9E%90%20%EB%B3%B8%EC%A0%90"
        )
    }

    /// 이름에 주소를 끊는 글자가 있어도 한 칸에 들어간다.
    func testReservedCharactersAreEscaped() throws {
        let link = try XCTUnwrap(NaverMapLink.search("A/B?C#D&E 50%"))
        let url = try XCTUnwrap(URL(string: link))
        XCTAssertEqual(url.host, "map.naver.com")
        XCTAssertNil(url.query)
        XCTAssertNil(url.fragment)
        XCTAssertEqual(url.pathComponents.count, 4) // "/", "p", "search", 이름
    }

    /// 같은 이름의 다른 지점이 뜨지 않게 시군구를 붙인다. 이름에 이미 있으면 두 번 적지 않는다.
    func testNearAddsTheDistrictOnce() throws {
        let link = try XCTUnwrap(NaverMapLink.search("오쏘파스타", near: "종로구"))
        XCTAssertEqual(URL(string: link)?.lastPathComponent, "오쏘파스타 종로구")
        let same = try XCTUnwrap(NaverMapLink.search("스타벅스 종로구청점", near: "종로구"))
        XCTAssertEqual(URL(string: same)?.lastPathComponent, "스타벅스 종로구청점")
        XCTAssertEqual(NaverMapLink.search("가", near: " "), NaverMapLink.search("가"))
        XCTAssertNil(NaverMapLink.search(" ", near: "종로구"))
    }

    func testEmptyNameHasNoLink() {
        XCTAssertNil(NaverMapLink.search("  \n"))
    }

    /// 카드는 네이버 카드 엔드포인트를 모른다 — 리뷰·별점·영업시간 칸이 아예 없다.
    func testCardHasNoScrapedFields() {
        let card = RouteGuide.Card(
            category: "카페", address: "서울 중구", phone: nil, images: [], naverUrl: nil
        )
        let fields = Mirror(reflecting: card).children.compactMap(\.label)
        XCTAssertEqual(Set(fields), ["category", "address", "phone", "images", "naverUrl"])
    }
}
