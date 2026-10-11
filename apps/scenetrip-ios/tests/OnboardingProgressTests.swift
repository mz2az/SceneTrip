@testable import SceneTrip
import XCTest

final class OnboardingProgressTests: XCTestCase {
    func testPagingStaysWithinLessonsAndMovesBack() {
        var progress = OnboardingProgress(count: 4)
        XCTAssertEqual(progress.page, 0)
        progress.previous()
        XCTAssertEqual(progress.page, 0)
        for _ in 0 ..< 8 {
            progress.next()
        }
        XCTAssertEqual(progress.page, 3)
        XCTAssertTrue(progress.isLast)
        progress.previous()
        XCTAssertEqual(progress.page, 2)
        XCTAssertFalse(progress.isLast)
        progress.select(-5)
        XCTAssertEqual(progress.page, 0)
        progress.select(9)
        XCTAssertEqual(progress.page, 3)
    }

    func testFinishOrSkipCompletesOnlyOnceAndLocksProgress() {
        var progress = OnboardingProgress(count: 4)
        progress.next()
        XCTAssertTrue(progress.finish())
        XCTAssertFalse(progress.finish())
        progress.previous()
        progress.next()
        progress.select(3)
        XCTAssertEqual(progress.page, 1)
    }

    func testSinglePageStartsOnLastAndCanFinish() {
        var progress = OnboardingProgress(count: 1)
        XCTAssertTrue(progress.isLast)
        progress.next()
        XCTAssertEqual(progress.page, 0)
        XCTAssertTrue(progress.finish())
    }
}
