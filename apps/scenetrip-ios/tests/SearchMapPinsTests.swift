import CoreGraphics
import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 검색 탭 지도 핀(MZ2AZ-547 G1·G2) — 첫 화면에는 보관함에 담은 곳만 번호 핀으로, 핀 모양은 웹 `PROTO_NUMBERED`.
/// 기준은 웹 `useSearchTab.ts` 의 savedPins·mapPlaces·numbered 와 `protoPoi.tsx` 의 `protoNumberedPinSvg`.
final class SearchMapPinsTests: XCTestCase {
    // MARK: 보관함 → 지도 핀

    /// 보관함 순서 그대로, 좌표가 없는 곳과 목록에서 빠진 곳(delisted)은 뺀다.
    func testSavedKeepsCartOrderAndDropsItemsWithoutCoordinatesOrDelisted() {
        let items = [
            cartItem(3, lat: 37.3, lng: 127.3),
            cartItem(1, lat: nil, lng: 127.1), // 위도 없음
            cartItem(5, lat: 37.5, lng: 127.5, delisted: true), // 목록에서 빠짐
            cartItem(2, lat: 37.2, lng: 127.2, delisted: false),
            cartItem(4, lat: 37.4, lng: nil), // 경도 없음
            cartItem(6, lat: 37.6, lng: 127.6),
        ]
        let pins = SearchMapPins.saved(items)
        XCTAssertEqual(pins.map(\.id), [3, 2, 6])
        XCTAssertEqual(pins.map(\.name), ["담은 곳 3", "담은 곳 2", "담은 곳 6"])
        XCTAssertEqual(pins.map(\.latitude), [37.3, 37.2, 37.6])
        XCTAssertEqual(pins.map(\.longitude), [127.3, 127.2, 127.6])
    }

    func testSavedIsEmptyForEmptyCart() {
        XCTAssertTrue(SearchMapPins.saved([]).isEmpty)
    }

    // MARK: 지도에 꽂을 장소

    /// 첫 화면(고른 작품 없음)에는 보관함에 담은 곳만 — 검색 결과(visible)가 아니다.
    func testInitialScreenPinsOnlySavedPlaces() {
        let pins = SearchMapPins.places(
            contentSelected: false, contentPlaces: [],
            initial: true, saved: [place(10), place(11)], visible: [place(1), place(2)]
        )
        XCTAssertEqual(pins.map(\.id), [10, 11])
    }

    /// 첫 화면에 보관함이 비었으면 핀이 없다 — 검색 결과로 메우지 않는다.
    func testInitialScreenWithEmptyCartHasNoPins() {
        let pins = SearchMapPins.places(
            contentSelected: false, contentPlaces: [],
            initial: true, saved: [], visible: [place(1)]
        )
        XCTAssertEqual(pins.map(\.id), [])
    }

    /// 작품을 골랐으면 그 작품의 촬영지 — 첫 화면 조건이 함께 켜져 있어도 작품이 이긴다.
    func testSelectedContentWinsEvenOverInitial() {
        let pins = SearchMapPins.places(
            contentSelected: true, contentPlaces: [place(20), place(21)],
            initial: true, saved: [place(10)], visible: [place(1)]
        )
        XCTAssertEqual(pins.map(\.id), [20, 21])
    }

    func testSelectedContentWinsOverSearchResults() {
        let pins = SearchMapPins.places(
            contentSelected: true, contentPlaces: [place(20)],
            initial: false, saved: [place(10)], visible: [place(1), place(2)]
        )
        XCTAssertEqual(pins.map(\.id), [20])
    }

    /// 검색했으면(첫 화면 아님, 작품 없음) 목록과 같은 검색 결과.
    func testOtherwiseVisibleSearchResults() {
        let pins = SearchMapPins.places(
            contentSelected: false, contentPlaces: [place(20)],
            initial: false, saved: [place(10)], visible: [place(1), place(2)]
        )
        XCTAssertEqual(pins.map(\.id), [1, 2])
    }

    // MARK: 번호

    /// 첫 화면은 보관함 순서 번호라 어느 탭이든 번호를 찍는다 — 작품 탭(.work)에서도.
    func testInitialScreenIsNumberedOnWorkTab() {
        XCTAssertTrue(SearchMapPins.numbered(initial: true, tab: .work, contentSelected: false))
    }

    func testInitialScreenIsNumberedOnPlaceTab() {
        XCTAssertTrue(SearchMapPins.numbered(initial: true, tab: .place, contentSelected: false))
    }

    /// 첫 화면이 아니면 늘 목록 행 번호와 짝이 있어 번호를 찍는다.
    func testNonInitialIsAlwaysNumbered() {
        XCTAssertTrue(SearchMapPins.numbered(initial: false, tab: .work, contentSelected: false))
        XCTAssertTrue(SearchMapPins.numbered(initial: false, tab: .place, contentSelected: false))
        XCTAssertTrue(SearchMapPins.numbered(initial: false, tab: .work, contentSelected: true))
        XCTAssertTrue(SearchMapPins.numbered(initial: false, tab: .place, contentSelected: true))
    }

    // MARK: 코스 번호 핀 모양

    func testPinSizeIs30By38() {
        let size = CoursePinShape.size(selected: false)
        XCTAssertEqual(size.width, 30, accuracy: 0.001)
        XCTAssertEqual(size.height, 38, accuracy: 0.001)
    }

    /// 고른 핀은 한 번만 1.2배 — 웹이 1.3배와 겹쳐 1.6배가 되던 것을 고쳤다(09400c6b).
    func testSelectedScaleIsOnce1Point2() {
        XCTAssertEqual(CoursePinShape.selectedScale, 1.2, accuracy: 0.0001)
    }

    func testSelectedPinSizeIs36By45Point6() {
        let size = CoursePinShape.size(selected: true)
        XCTAssertEqual(size.width, 36, accuracy: 0.001)
        XCTAssertEqual(size.height, 45.6, accuracy: 0.001)
    }

    /// 꼬리 끝 (15, 37) 이 좌표에 닿는다 — 비율로 (0.5, 37/38).
    func testAnchorIsTheTailTip() {
        XCTAssertEqual(CoursePinShape.tip.x, 15, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.tip.y, 37, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.anchor.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(CoursePinShape.anchor.y, 37.0 / 38.0, accuracy: 0.0001)
    }

    /// 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9.
    func testFontSizeByDigitCount() {
        XCTAssertEqual(CoursePinShape.fontSize(1), 13, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.fontSize(9), 13, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.fontSize(10), 11.5, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.fontSize(99), 11.5, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.fontSize(100), 9, accuracy: 0.001)
        XCTAssertEqual(CoursePinShape.fontSize(250), 9, accuracy: 0.001)
    }

    // MARK: G2 — 검색칸 오른쪽 「보관함」 영어 번역

    func testSavedBoxWordingHasEnglishTranslation() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .en
        XCTAssertEqual(tr("보관함"), "Saved")
        let missing = "__없음__"
        let counted = AppLanguage.bundle(for: .en).localizedString(forKey: "보관함 %d곳", value: missing, table: nil)
        XCTAssertNotEqual(counted, missing, "en Localizable 에 「보관함 %d곳」 키가 없다")
    }

    // MARK: 도우미

    private func place(_ id: Int64) -> PlaceSummary {
        PlaceSummary(id: id, name: "장소 \(id)", latitude: 37.0 + Double(id) / 1000, longitude: 127.0)
    }

    private func cartItem(_ id: Int64, lat: Double?, lng: Double?, delisted: Bool? = nil) -> CartItem {
        CartItem(
            placeId: id, name: "담은 곳 \(id)", latitude: lat, longitude: lng,
            addedAt: Date(timeIntervalSince1970: Double(id)), delisted: delisted
        )
    }
}
