import SceneApiClient
@testable import SceneTrip
import XCTest

/// 편의시설 카드는 네이버에서 **가져오지 않고 넘긴다** (MZ2AZ-354) — 갈 곳은 서버가 찾아 준
/// 그 가게의 장소 화면이고, 없으면 버튼이 없다 (MZ2AZ-374).
final class NaverMapLinkTests: XCTestCase {
    /// 계약의 꼴(`https://map.naver.com/p/entry/place/{번호}`)은 글자 그대로 연다.
    func testPlaceLinkOpensAsGiven() {
        let link = "https://map.naver.com/p/entry/place/11679241"
        XCTAssertEqual(NaverMapLink.place(link)?.absoluteString, link)
        XCTAssertEqual(NaverMapLink.place("  \(link)\n")?.absoluteString, link)
    }

    /// 없거나 빈 값이면 갈 곳이 없다 — 버튼이 서지 않는다. 이름 검색으로 넘기지 않는다.
    func testMissingLinkHasNoDestination() {
        XCTAssertNil(NaverMapLink.place(nil))
        XCTAssertNil(NaverMapLink.place(""))
        XCTAssertNil(NaverMapLink.place("  \n"))
        XCTAssertNil(NaverMapLink.place("장소 번호 없음"))
    }

    /// 네이버의 다른 문(모바일 장소·짧은 주소)도 네이버다.
    func testOtherNaverHostsAreAllowed() {
        XCTAssertNotNil(NaverMapLink.place("https://m.place.naver.com/restaurant/11679241/home"))
        XCTAssertNotNil(NaverMapLink.place("https://naver.me/abcd"))
        XCTAssertNotNil(NaverMapLink.place("HTTPS://MAP.NAVER.COM/p/entry/place/1"))
    }

    /// 「네이버 지도에서 보기」라고 적고 다른 데로 보내지 않는다 — 스킴과 호스트를 본다.
    func testForeignLinksAreRefused() {
        XCTAssertNil(NaverMapLink.place("http://map.naver.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("nmap://place?id=1"))
        XCTAssertNil(NaverMapLink.place("tel:0212345678"))
        XCTAssertNil(NaverMapLink.place("javascript:alert(1)"))
        XCTAssertNil(NaverMapLink.place("https://example.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("https://map.naver.com.example.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("https://notnaver.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("https://map.naver.com@example.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("//map.naver.com/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("/p/entry/place/1"))
    }

    /// 호스트는 퍼센트 인코딩을 **풀기 전** 글자로 본다 — 풀면 네이버로 끝나 보이는 남의 호스트가 있다.
    func testDisguisedHostsAreRefused() {
        XCTAssertNil(NaverMapLink.place("https://evil.io%2F.naver.com/x"))
        XCTAssertNil(NaverMapLink.place("https://evil.io%40map.naver.com/x"))
        XCTAssertNil(NaverMapLink.place("https://evil.io%23.naver.com/x"))
        XCTAssertNil(NaverMapLink.place("https://evil.io%3F.naver.com/x"))
        XCTAssertNil(NaverMapLink.place("https://evil.io%00.naver.com/x"))
        XCTAssertNil(NaverMapLink.place("https://map.naver.com%2Eevil.io/x"))
        // 끝 점이 붙은 이름과 포트가 붙은 주소 — 서버는 그런 주소를 만들지 않는다.
        XCTAssertNil(NaverMapLink.place("https://map.naver.com./p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("https://map.naver.com:8443/p/entry/place/1"))
        XCTAssertNil(NaverMapLink.place("https://map.naver.com:443/p/entry/place/1"))
    }

    /// 편의시설 상세의 칸은 선택이다 — 서버가 번호를 못 찾았거나 옛 서버면 안 온다. 그때 해석이 깨지지 않는다.
    func testPoiDetailCarriesTheOptionalLink() throws {
        let base = """
        "id": 7, "name": "명동교자 본점", "category": "한식", "categoryGroup": "food",
        "latitude": 37.5, "longitude": 127.0, "images": []
        """
        let with = try decode("{\(base), \"naverPlaceUrl\": \"https://map.naver.com/p/entry/place/11679241\"}")
        XCTAssertEqual(
            NaverMapLink.place(with.naverPlaceUrl)?.absoluteString,
            "https://map.naver.com/p/entry/place/11679241"
        )
        let without = try decode("{\(base)}")
        XCTAssertNil(without.naverPlaceUrl)
        XCTAssertNil(NaverMapLink.place(without.naverPlaceUrl))
    }

    /// 카드는 네이버 카드 엔드포인트를 모른다 — 네이버의 리뷰 수·별점·영업시간 칸이 없다.
    /// (`rating` 은 우리 리뷰의 별점, `photos` 는 우리 서버의 사진첩이다 — MZ2AZ-363. 옛 `images` 칸을 그리로 옮겼다.)
    func testCardHasNoScrapedFields() {
        let card = RouteGuide.Card(
            category: "카페", address: "서울 중구", phone: nil, photos: PhotoGalleryRules.Book(), naverUrl: nil
        )
        let fields = Mirror(reflecting: card).children.compactMap(\.label)
        XCTAssertEqual(
            Set(fields),
            ["title", "reading", "category", "address", "phone", "photos", "naverUrl", "rating", "detailed"]
        )
    }

    // MARK: 카드 배선

    private let listed = RouteGuide.Place(
        id: "poi-7", name: "명동교자 본점", category: "한식", latitude: 37.5, longitude: 127.0
    )

    private let detailJson = """
    "id": 7, "name": "명동교자 본점", "category": "한식", "categoryGroup": "food", "city": "중구",
    "latitude": 37.5, "longitude": 127.0, "images": [], "tel": "02-000-0000"
    """

    /// 상세에 주소가 있으면 카드의 버튼이 그 주소로 간다.
    func testPoiCardUsesTheServerLink() throws {
        let link = "https://map.naver.com/p/entry/place/11679241"
        let card = try RouteGuide.card(poi: decode("{\(detailJson), \"naverPlaceUrl\": \"\(link)\"}"), listed: listed)
        XCTAssertEqual(card.naverUrl, link)
        XCTAssertTrue(card.detailed)
    }

    /// 상세에 주소가 없으면 버튼이 없다 — **이름으로 검색한 주소를 만들어 넣지 않는다.**
    func testPoiCardWithoutLinkHasNoButton() throws {
        let card = try RouteGuide.card(poi: decode("{\(detailJson)}"), listed: listed)
        XCTAssertNil(card.naverUrl)
        XCTAssertEqual(card.phone, "02-000-0000")
        let foreign = try RouteGuide.card(
            poi: decode("{\(detailJson), \"naverPlaceUrl\": \"https://example.com/p/entry/place/1\"}"), listed: listed
        )
        XCTAssertNil(foreign.naverUrl)
    }

    /// 상세를 못 받아도 카드는 목록이 준 것으로 서지만 버튼은 없다.
    func testPoiCardWithoutDetailHasNoButton() {
        let card = RouteGuide.card(poi: nil, listed: listed)
        XCTAssertNil(card.naverUrl)
        XCTAssertEqual(card.category, "한식")
        XCTAssertFalse(card.detailed)
    }

    /// 떠 있는 카드를 다시 읽다 실패하면 가진 것을 둔다 — 버튼이 사라지지 않는다.
    func testFailedRereadKeepsTheShownCard() throws {
        let link = "https://map.naver.com/p/entry/place/11679241"
        let shown = try RouteGuide.card(poi: decode("{\(detailJson), \"naverPlaceUrl\": \"\(link)\"}"), listed: listed)
        let failed = RouteGuide.card(poi: nil, listed: listed)
        // 다시 읽기 실패: 편의시설은 목록만으로 지은 카드, 촬영지는 nil.
        XCTAssertTrue(RouteGuide.Card.keeps(shown, over: failed))
        XCTAssertTrue(RouteGuide.Card.keeps(shown, over: nil))
        // 다시 읽기 성공이면 새것으로 — 서버가 번호를 잃었으면 버튼도 따라 없어진다.
        XCTAssertFalse(try RouteGuide.Card.keeps(shown, over: RouteGuide.card(poi: decode("{\(detailJson)}"), listed: listed)))
        // 처음 읽을 때(가진 것이 없다)는 받은 대로.
        XCTAssertFalse(RouteGuide.Card.keeps(nil, over: failed))
        XCTAssertFalse(RouteGuide.Card.keeps(nil, over: nil))
    }

    private func decode(_ json: String) throws -> PoiDetail {
        try JSONDecoder().decode(PoiDetail.self, from: Data(json.utf8))
    }
}
