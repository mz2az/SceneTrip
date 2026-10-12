@testable import SceneTrip
import XCTest

final class VisitSyncTests: XCTestCase {
    @MainActor func testFailureIsPendingUntilExplicitRetrySucceeds() async {
        var attempts = 0
        let sync = VisitSync { course, item in
            XCTAssertEqual(course, 3)
            XCTAssertEqual(item, 7)
            attempts += 1
            if attempts == 1 {
                throw URLError(.notConnectedToInternet)
            }
        }
        let failed = await sync.save(courseId: 3, itemId: 7)
        XCTAssertFalse(failed)
        XCTAssertTrue(sync.isPending(7))
        XCTAssertTrue(sync.saving.isEmpty)
        let saved = await sync.save(courseId: 3, itemId: 7)
        XCTAssertTrue(saved)
        XCTAssertFalse(sync.isPending(7))
        XCTAssertEqual(attempts, 2)
    }

    @MainActor func testSameVisitCannotBeSentAgainWhileSaving() async {
        let entered = expectation(description: "방문 저장 시작")
        var finish: CheckedContinuation<Void, Never>?
        var calls = 0
        let sync = VisitSync { _, _ in
            calls += 1
            await withCheckedContinuation { continuation in
                finish = continuation
                entered.fulfill()
            }
        }
        let pending = Task { await sync.save(courseId: 3, itemId: 7) }
        await fulfillment(of: [entered], timeout: 1)
        XCTAssertTrue(sync.isPending(7))
        let duplicate = await sync.save(courseId: 3, itemId: 7)
        XCTAssertFalse(duplicate)
        XCTAssertEqual(calls, 1)
        finish?.resume()
        let saved = await pending.value
        XCTAssertTrue(saved)
        XCTAssertFalse(sync.isPending(7))
        XCTAssertFalse(sync.isPending(nil))
    }
}
