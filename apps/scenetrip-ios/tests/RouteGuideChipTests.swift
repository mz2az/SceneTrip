@testable import SceneTrip
import XCTest

/// 해태 동그라미가 화면 바닥의 카드를 비키는 규칙 (MZ2AZ-367).
final class RouteGuideChipTests: XCTestCase {
    private func offsetY(stored: CGFloat, cardTop: CGFloat?) -> CGFloat {
        RouteGuideFloatingChip.offsetY(stored: stored, cardTop: cardTop, bottom: 76, gap: 8)
    }

    /// 카드가 없으면 사용자가 둔 자리 그대로다.
    func testWithoutACardTheChipStaysWhereItWasPut() {
        XCTAssertEqual(offsetY(stored: 0, cardTop: nil), 0)
        XCTAssertEqual(offsetY(stored: -120, cardTop: nil), -120)
    }

    /// 카드가 뜨면 그 윗변보다 8pt 위로 올라선다 — 기본 자리(바닥에서 76)에서 잰 만큼.
    func testWithACardTheChipRisesAboveItsTopEdge() {
        XCTAssertEqual(offsetY(stored: 0, cardTop: 400), -332)
    }

    /// 이미 더 위로 옮겨 둔 사람의 자리는 건드리지 않는다.
    func testAChipAlreadyAboveTheCardIsLeftAlone() {
        XCTAssertEqual(offsetY(stored: -500, cardTop: 400), -500)
    }

    /// 카드가 뜬 채 끌면 **비켜 선 자리에서부터** 손가락을 따라온다 — 위로도 아래로도.
    func testDraggingWithACardFollowsTheFingerFromWhereTheChipStands() {
        let drag: (CGFloat) -> CGFloat = {
            RouteGuideFloatingChip.offsetY(stored: 0, dragging: $0, cardTop: 400, bottom: 76, gap: 8)
        }
        XCTAssertEqual(drag(-50), -382)
        XCTAssertEqual(drag(30), -302)
    }

    /// 놓으면 보이던 자리가 저장된다 — 카드를 닫아도 놓은 그 자리에 있다.
    func testAfterDroppingTheChipStaysThereWhenTheCardCloses() {
        let dropped = RouteGuideFloatingChip.offsetY(stored: 0, dragging: -50, cardTop: 400, bottom: 76, gap: 8)
        XCTAssertEqual(offsetY(stored: dropped, cardTop: 400), -382) // 카드가 아직 떠 있다
        XCTAssertEqual(offsetY(stored: dropped, cardTop: nil), -382) // 카드를 닫았다
    }

    /// 꾹 눌렀다 그대로 떼면 옮긴 것이 아니다 — 비켜 선 자리가 저장되지 않는다.
    func testAPressWithoutMovingIsNotAMove() {
        XCTAssertFalse(RouteGuideFloatingChip.moved(.zero))
        XCTAssertFalse(RouteGuideFloatingChip.moved(CGSize(width: 2, height: -3)))
        XCTAssertTrue(RouteGuideFloatingChip.moved(CGSize(width: 0, height: -6)))
        XCTAssertTrue(RouteGuideFloatingChip.moved(CGSize(width: -40, height: 10)))
    }

    /// 카드가 없을 때 끄는 것은 전과 같다.
    func testDraggingWithoutACardAddsToTheStoredSpot() {
        XCTAssertEqual(RouteGuideFloatingChip.offsetY(stored: -120, dragging: 20, cardTop: nil, bottom: 76), -100)
    }
}
