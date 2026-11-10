import CoreGraphics
@testable import SceneTrip
import XCTest

/// 검색 탭 서랍의 단 높이 — 떠 있는 탭바 뒤까지 내려간다 (MZ2AZ-547 T3).
final class BottomSheetRulesTests: XCTestCase {
    func testMaxHeightReachesJustBelowTopBar() {
        XCTAssertEqual(BottomSheetRules.maxHeight(total: 800, topInset: 120), 680, accuracy: 0.0001)
    }

    func testMaxHeightKeepsAtLeast80() {
        XCTAssertEqual(BottomSheetRules.maxHeight(total: 100, topInset: 90), 80, accuracy: 0.0001)
        XCTAssertEqual(BottomSheetRules.maxHeight(total: 50, topInset: 200), 80, accuracy: 0.0001)
    }

    func testCollapsedHeightKeepsVisibleShareAboveTabBar() {
        // 100 + (800 − 100) × 0.25 = 275
        let height = BottomSheetRules.height(fraction: 0.25, isExpanded: false, total: 800, topInset: 120, bottomClearance: 100)
        XCTAssertEqual(height, 275, accuracy: 0.0001)
    }

    func testZeroClearanceIsOldCalculation() {
        let height = BottomSheetRules.height(fraction: 0.4, isExpanded: false, total: 800, topInset: 120, bottomClearance: 0)
        XCTAssertEqual(height, 320, accuracy: 0.0001)
    }

    func testExpandedIsMaxHeight() {
        let height = BottomSheetRules.height(fraction: 0.25, isExpanded: true, total: 800, topInset: 120, bottomClearance: 100)
        XCTAssertEqual(height, 680, accuracy: 0.0001)
    }
}
