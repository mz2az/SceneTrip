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

    // MARK: 「머무는 시간 ›」 단추를 덮지 않는다 (MZ2AZ-368)

    /// 실기에서 겹친 그 배치 — 화면 402×874, 단추 (322,471) 63×35, 카드 윗변이 바닥에서 347.
    private let stay = RouteGuideFloatingChip.clearance(
        of: CGRect(x: 322, y: 471, width: 63, height: 35), in: CGRect(x: 0, y: 0, width: 402, height: 874)
    )

    private func offsetX(stored: CGSize, dragging: CGFloat = 0, cardTop: CGFloat?) -> CGFloat {
        RouteGuideFloatingChip.offsetX(
            stored: stored, dragging: dragging, cardTop: cardTop, clear: stay, bottom: 76, edge: 12
        )
    }

    /// 단추의 자리는 동그라미와 같은 잣대로 옮겨진다 — 오른쪽 가장자리·바닥에서 잰 거리.
    func testTheButtonIsMeasuredFromTheSameCorner() {
        XCTAssertEqual(stay, .init(left: 80, low: 368, high: 403))
        XCTAssertNil(RouteGuideFloatingChip.clearance(of: nil, in: CGRect(x: 0, y: 0, width: 402, height: 874)))
    }

    /// 카드를 비켜 올라선 자리가 단추와 겹치면 **단추 왼쪽으로** 8pt 더 비킨다.
    func testARisenChipStepsLeftOfTheStayButton() {
        // 올라선 동그라미는 바닥에서 355~401, 단추는 368~403 — 겹친다.
        XCTAssertEqual(offsetX(stored: .zero, cardTop: 347), -76)
        // 그 자리의 오른쪽 변은 오른쪽 가장자리에서 88 — 단추 왼쪽 변(80)보다 8 왼쪽이다.
        XCTAssertEqual(12 - offsetX(stored: .zero, cardTop: 347), 80 + 8)
    }

    /// 카드가 없으면, 또는 올라선 자리가 단추 줄과 높이가 다르면 옆으로 가지 않는다.
    func testNoSidestepWithoutACardOrWhenTheRowIsElsewhere() {
        XCTAssertEqual(offsetX(stored: .zero, cardTop: nil), 0)
        XCTAssertEqual(offsetX(stored: .zero, cardTop: 200), 0) // 바닥에서 208~254 — 단추 아래
        XCTAssertEqual(offsetX(stored: .zero, cardTop: 400), 0) // 408~454 — 단추 위
    }

    /// **사용자가 둔 자리는 건드리지 않는다** — 이미 단추 왼쪽에 있거나, 스스로 카드보다 위에 뒀으면.
    func testASpotTheUserChoseIsLeftAlone() {
        XCTAssertEqual(offsetX(stored: CGSize(width: -150, height: 0), cardTop: 347), -150)
        XCTAssertEqual(offsetX(stored: CGSize(width: 0, height: -300), cardTop: 347), 0)
    }

    /// 끄는 만큼은 비킨 자리에 더한다 — 세로와 같은 규칙. 놓으면 그 자리가 남는다.
    func testDraggingStartsFromTheSidesteppedSpot() {
        let dropped = offsetX(stored: .zero, dragging: -30, cardTop: 347)
        XCTAssertEqual(dropped, -106)
        XCTAssertEqual(offsetX(stored: CGSize(width: dropped, height: 0), cardTop: 347), -106)
        XCTAssertEqual(offsetX(stored: CGSize(width: dropped, height: 0), cardTop: nil), -106)
    }
}
