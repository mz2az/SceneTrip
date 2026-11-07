import SceneApiClient
@testable import SceneTrip
import XCTest

/// 검색 탭 지도 핀 규칙(MZ2AZ-519) — 열어 둔 장소를 핀 목록에 맞추고, 홈 쪽지로 연 장소로 확대한다.
/// 기준은 웹 `mapRules.ts` 의 `pinMarks`·`pinsKey`(af6eee6b).
final class SearchPinMarksTests: XCTestCase {
    // MARK: 핀 목록

    func testOpenPlaceAlreadyPinnedIsHighlightedWithoutDuplicateAndKeepsItsNumber() {
        let marks = PinMarks.marks([place(1), place(2), place(3)], selected: place(2), numbered: true)
        XCTAssertEqual(marks.map(\.place.id), [1, 2, 3])
        XCTAssertEqual(marks.map(\.number), [1, 2, 3])
        XCTAssertEqual(marks.map(\.selected), [false, true, false])
    }

    func testOpenPlaceMissingFromPinsIsAppendedAsOneUnnumberedHighlightedPin() {
        let marks = PinMarks.marks([place(1), place(2)], selected: place(424), numbered: true)
        XCTAssertEqual(marks.map(\.place.id), [1, 2, 424])
        // 덧붙인 핀은 번호가 없다 — 목록 행 번호를 밀지 않는다.
        XCTAssertEqual(marks.map(\.number), [1, 2, nil])
        XCTAssertEqual(marks.map(\.selected), [false, false, true])
    }

    func testUnnumberedFirstScreenStillAppendsTheOpenPlace() {
        let marks = PinMarks.marks([place(1), place(2)], selected: place(9), numbered: false)
        XCTAssertEqual(marks.map(\.place.id), [1, 2, 9])
        XCTAssertEqual(marks.map(\.number), [nil, nil, nil])
        XCTAssertEqual(marks.map(\.selected), [false, false, true])
    }

    func testNoOpenPlaceLeavesPinsAsTheyAre() {
        let marks = PinMarks.marks([place(1), place(2)], selected: nil, numbered: true)
        XCTAssertEqual(marks.map(\.place.id), [1, 2])
        XCTAssertEqual(marks.map(\.number), [1, 2])
        XCTAssertEqual(marks.map(\.selected), [false, false])
    }

    func testOpenPlaceIsPinnedEvenWhenThereAreNoOtherPins() {
        let marks = PinMarks.marks([], selected: place(7), numbered: true)
        XCTAssertEqual(marks.map(\.place.id), [7])
        XCTAssertEqual(marks.map(\.number), [nil])
        XCTAssertEqual(marks.map(\.selected), [true])
    }

    func testOpeningAnotherPlaceOrClosingDropsTheExtraPinAndHighlight() {
        let pins = [place(1), place(2)]
        // 다른 장소(목록 안)를 열면 덧붙였던 424 는 사라지고 2 만 강조된다.
        let other = PinMarks.marks(pins, selected: place(2), numbered: true)
        XCTAssertEqual(other.map(\.place.id), [1, 2])
        XCTAssertEqual(other.map(\.selected), [false, true])
        // 닫으면 강조도 덧붙인 핀도 없다.
        let closed = PinMarks.marks(pins, selected: nil, numbered: true)
        XCTAssertEqual(closed.map(\.place.id), [1, 2])
        XCTAssertFalse(closed.contains(where: \.selected))
    }

    // MARK: 다시 그리기 열쇠

    func testKeyChangesWhenTheOpenPlaceChanges() {
        let pins = [place(1), place(2)]
        let none = PinMarks.key(pins, numbered: true, selected: nil)
        let two = PinMarks.key(pins, numbered: true, selected: place(2))
        let one = PinMarks.key(pins, numbered: true, selected: place(1))
        XCTAssertNotEqual(none, two)
        XCTAssertNotEqual(two, one)
        XCTAssertEqual(two, PinMarks.key(pins, numbered: true, selected: place(2)))
        XCTAssertNotEqual(none, PinMarks.key(pins, numbered: false, selected: nil))
    }

    // MARK: 카메라

    func testHomeNoteZoomsToTheOpenedPlaceInsteadOfFittingAllPins() {
        XCTAssertEqual(SearchCameraRules.opening(.homeNote), .focusPlace)
    }

    func testEveryWayOfOpeningAPlaceZoomsToIt() {
        for way in [PlaceOpening.homeNote, .pin, .listRow, .suggestion] {
            XCTAssertEqual(SearchCameraRules.opening(way), .focusPlace, "\(way)")
        }
    }

    func testPinTapOnFirstScreenWorkTabIsNotOverriddenByThePopularPinFit() {
        // 작품 탭 첫 화면에서 핀을 누르면 장소 탭으로 넘어간다 — 그 탭 전환이 인기 핀 맞춤으로 확대를 덮으면 안 된다.
        XCTAssertEqual(
            SearchCameraRules.afterTabChange(to: .place, isInitial: true, hasPins: true, placeOpen: true),
            SearchCameraMove.none
        )
    }

    func testSwitchingToPlaceTabWithNothingOpenStillFitsPopularPins() {
        XCTAssertEqual(
            SearchCameraRules.afterTabChange(to: .place, isInitial: true, hasPins: true, placeOpen: false),
            .fitPins
        )
        XCTAssertEqual(
            SearchCameraRules.afterTabChange(to: .place, isInitial: true, hasPins: false, placeOpen: false),
            SearchCameraMove.none
        )
        XCTAssertEqual(
            SearchCameraRules.afterTabChange(to: .work, isInitial: true, hasPins: true, placeOpen: false),
            .korea
        )
        XCTAssertEqual(
            SearchCameraRules.afterTabChange(to: .place, isInitial: false, hasPins: true, placeOpen: false),
            SearchCameraMove.none
        )
    }

    private func place(_ id: Int64) -> PlaceSummary {
        PlaceSummary(id: id, name: "장소 \(id)", latitude: 37.0 + Double(id) / 1000, longitude: 127.0)
    }
}
