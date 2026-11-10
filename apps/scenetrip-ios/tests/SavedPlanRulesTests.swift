import SceneApiClient
@testable import SceneTrip
import XCTest

/// 담은 곳으로(A4) — 시·도 묶기, 가까운 순 잇기, 날마다 고르게, 보관함 → 장소 (MZ2AZ-547 P3, 웹 `ProtoPlanFlows.tsx`).
final class SavedPlanRulesTests: XCTestCase {
    private typealias B = BusanSpots

    // MARK: 시·도 이름

    func testRegionStripsMetropolitanSuffix() {
        XCTAssertEqual(SavedPlanRules.regionOf("서울특별시 중구 세종대로 110"), "서울")
        XCTAssertEqual(SavedPlanRules.regionOf("부산광역시 해운대구 우동"), "부산")
        XCTAssertEqual(SavedPlanRules.regionOf("인천광역시 중구"), "인천")
    }

    func testRegionShortensProvinces() {
        XCTAssertEqual(SavedPlanRules.regionOf("경상북도 경주시 황남동"), "경북")
        XCTAssertEqual(SavedPlanRules.regionOf("경상남도 통영시"), "경남")
        XCTAssertEqual(SavedPlanRules.regionOf("전라북도 전주시"), "전북")
        XCTAssertEqual(SavedPlanRules.regionOf("전북특별자치도 전주시"), "전북")
        XCTAssertEqual(SavedPlanRules.regionOf("전라남도 여수시"), "전남")
        XCTAssertEqual(SavedPlanRules.regionOf("충청북도 청주시"), "충북")
        XCTAssertEqual(SavedPlanRules.regionOf("충청남도 공주시"), "충남")
        XCTAssertEqual(SavedPlanRules.regionOf("강원도 강릉시"), "강원")
        XCTAssertEqual(SavedPlanRules.regionOf("강원특별자치도 강릉시"), "강원")
        XCTAssertEqual(SavedPlanRules.regionOf("제주특별자치도 제주시"), "제주")
        XCTAssertEqual(SavedPlanRules.regionOf("경기도 수원시"), "경기")
        XCTAssertEqual(SavedPlanRules.regionOf("세종특별자치시 한누리대로"), "세종")
    }

    /// 표에 없는 머리말은 그대로 — 이미 짧은 「경기」·「서울」 같은 것.
    func testRegionKeepsUnknownHead() {
        XCTAssertEqual(SavedPlanRules.regionOf("경기 수원시"), "경기")
        XCTAssertEqual(SavedPlanRules.regionOf("서울 중구"), "서울")
    }

    func testRegionTrimsSpaces() {
        XCTAssertEqual(SavedPlanRules.regionOf("  경기도   수원시"), "경기")
    }

    func testRegionWithoutAddressIsOther() {
        XCTAssertEqual(SavedPlanRules.regionOf(nil), "기타")
        XCTAssertEqual(SavedPlanRules.regionOf(""), "기타")
        XCTAssertEqual(SavedPlanRules.regionOf("   "), "기타")
    }

    // MARK: 가까운 곳 잇기

    /// 큰 묶음(부산 5곳)이 먼저, 묶음 안에서는 첫 곳부터 가까운 곳 차례로, 좌표 없는 곳은 그 묶음 맨 뒤. 그다음 서울 2곳.
    func testArrangeGroupsByRegionBiggestFirst() {
        let cityHall = B.spot(101, "서울시청", 37.5665, 126.9780, "서울특별시 중구 세종대로 110")
        let namsan = B.spot(102, "남산타워", 37.5512, 126.9882, "서울특별시 용산구 남산공원길")
        let nowhere = B.spot(103, "좌표 없는 곳", 0, 0, "부산광역시 어딘가")
        let input = [cityHall, B.haeundae, B.gamcheon, nowhere, B.gwangalli, namsan, B.busanStation]
        XCTAssertEqual(B.ids(SavedPlanRules.arrange(input)), B.ids([
            B.haeundae, B.gwangalli, B.busanStation, B.gamcheon, nowhere,
            cityHall, namsan,
        ]))
    }

    /// 같은 크기면 먼저 나온 묶음이 먼저.
    func testArrangeTiesKeepFirstSeenOrder() {
        let oreung = B.spot(201, "경주 오릉", 35.8255, 129.2115, "경상북도 경주시 탑동")
        let cityHall = B.spot(101, "서울시청", 37.5665, 126.9780, "서울특별시 중구 세종대로 110")
        XCTAssertEqual(B.ids(SavedPlanRules.arrange([oreung, cityHall])), [oreung.id, cityHall.id])
        XCTAssertEqual(SavedPlanRules.arrange([]), [])
    }

    // MARK: 날마다 고르게

    func testSplitEvenGivesExtrasToEarlierDays() {
        XCTAssertEqual(SavedPlanRules.splitEven([1, 2, 3, 4, 5], days: 2), [[1, 2, 3], [4, 5]])
        XCTAssertEqual(SavedPlanRules.splitEven([1, 2, 3, 4, 5, 6, 7], days: 3), [[1, 2, 3], [4, 5], [6, 7]])
        XCTAssertEqual(SavedPlanRules.splitEven([1, 2, 3, 4], days: 3), [[1, 2], [3], [4]])
    }

    func testSplitEvenLeavesLaterDaysEmptyWhenShort() {
        XCTAssertEqual(SavedPlanRules.splitEven([1, 2], days: 3), [[1], [2], []])
        XCTAssertEqual(SavedPlanRules.splitEven([Int](), days: 2), [[], []])
    }

    // MARK: 보관함 → 장소

    func testPlacesFromCartDropsDelistedAndZeroFillsCoords() {
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        let items = [
            CartItem(placeId: 1, name: "해운대", address: "부산광역시 해운대구", latitude: 35.1587, longitude: 129.1604, imageUrl: "https://img.example/a.jpg", addedAt: date),
            CartItem(placeId: 2, name: "문 닫은 곳", latitude: 35.1, longitude: 129.0, addedAt: date, delisted: true),
            CartItem(placeId: 3, name: "좌표 없음", addedAt: date, delisted: false),
        ]
        let places = SavedPlanRules.places(from: items)
        XCTAssertEqual(places.map(\.id), [1, 3])
        XCTAssertEqual(places.first?.name, "해운대")
        XCTAssertEqual(places.first?.address, "부산광역시 해운대구")
        XCTAssertEqual(places.first?.latitude, 35.1587)
        XCTAssertEqual(places.first?.longitude, 129.1604)
        XCTAssertEqual(places.first?.imageUrl, "https://img.example/a.jpg")
        XCTAssertEqual(places.last?.name, "좌표 없음")
        XCTAssertEqual(places.last?.latitude, 0)
        XCTAssertEqual(places.last?.longitude, 0)
        XCTAssertNil(places.last?.address)
    }
}
