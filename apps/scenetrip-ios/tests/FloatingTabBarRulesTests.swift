import CoreGraphics
@testable import SceneTrip
import XCTest

/// 떠 있는 알약 탭바의 치수 (MZ2AZ-547 T1–T3, 웹 `ProtoTabBar.tsx`).
final class FloatingTabBarRulesTests: XCTestCase {
    func testDimensionsMatchWeb() {
        XCTAssertEqual(FloatingTabBarRules.sideMargin, 16)
        XCTAssertEqual(FloatingTabBarRules.bottomGap, 8)
        XCTAssertEqual(FloatingTabBarRules.height, 58)
        XCTAssertEqual(FloatingTabBarRules.innerPadding, 5)
    }

    func testListClearanceIs74() {
        XCTAssertEqual(FloatingTabBarRules.clearance, 74)
    }

    func testSelectionSlidesIn038Seconds() {
        XCTAssertEqual(FloatingTabBarRules.slideDuration, 0.38, accuracy: 0.0001)
    }

    func testSelectionWidthSplitsInnerWidthEqually() {
        // 바깥 폭 361 − 안쪽 여백 10 = 351 → 탭 셋이면 117.
        XCTAssertEqual(FloatingTabBarRules.selectionWidth(barWidth: 361, tabCount: 3), 117, accuracy: 0.0001)
        XCTAssertEqual(FloatingTabBarRules.selectionWidth(barWidth: 110, tabCount: 2), 50, accuracy: 0.0001)
    }

    func testSelectionOffsetStartsAtInnerPaddingAndStepsByWidth() {
        XCTAssertEqual(FloatingTabBarRules.selectionOffset(index: 0, barWidth: 361, tabCount: 3), 5, accuracy: 0.0001)
        XCTAssertEqual(FloatingTabBarRules.selectionOffset(index: 1, barWidth: 361, tabCount: 3), 122, accuracy: 0.0001)
        XCTAssertEqual(FloatingTabBarRules.selectionOffset(index: 2, barWidth: 361, tabCount: 3), 239, accuracy: 0.0001)
    }
}
