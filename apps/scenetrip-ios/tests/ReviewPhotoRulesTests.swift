@testable import SceneTrip
import XCTest

/// 리뷰 사진 줄의 규칙 (MZ2AZ-363) — 상한, `photoKeys` 조립, 저장을 막는 때, 바뀌었는지.
final class ReviewPhotoRulesTests: XCTestCase {
    /// 티켓 「사진 최대 10장」 — 계약의 `photoKeys.maxItems` 와 같다.
    func testLimitIsTen() {
        XCTAssertEqual(ReviewPhotoRules.limit, 10)
    }

    func testRemainingNeverGoesBelowZero() {
        XCTAssertEqual(ReviewPhotoRules.remaining(count: 0), 10)
        XCTAssertEqual(ReviewPhotoRules.remaining(count: 9), 1)
        XCTAssertEqual(ReviewPhotoRules.remaining(count: 10), 0)
        XCTAssertEqual(ReviewPhotoRules.remaining(count: 12), 0)
    }

    /// 고른 것은 남은 자리만큼만 받는다.
    func testAcceptedIsCappedByTheRoomLeft() {
        XCTAssertEqual(ReviewPhotoRules.accepted(picked: 3, count: 0), 3)
        XCTAssertEqual(ReviewPhotoRules.accepted(picked: 5, count: 8), 2)
        XCTAssertEqual(ReviewPhotoRules.accepted(picked: 1, count: 10), 0)
        XCTAssertEqual(ReviewPhotoRules.accepted(picked: 0, count: 3), 0)
    }

    /// 보낼 키는 화면 순서 그대로 — 붙어 있던 것과 새로 올라간 것이 섞여도.
    func testKeysFollowTheScreenOrder() {
        let states: [ReviewPhotoState] = [
            .attached(key: "reviews/a.jpg"), .uploaded(key: "uploads/tmp/n1.jpg"),
            .attached(key: "reviews/b.jpg"), .uploaded(key: "uploads/tmp/n2.jpg"),
        ]
        XCTAssertEqual(
            ReviewPhotoRules.keys(states),
            ["reviews/a.jpg", "uploads/tmp/n1.jpg", "reviews/b.jpg", "uploads/tmp/n2.jpg"]
        )
    }

    /// 아직 안 올라갔거나 실패한 칸의 키는 없다 — 보내지 않는다.
    func testKeysSkipPhotosThatAreNotUploaded() {
        let states: [ReviewPhotoState] = [
            .attached(key: "a"), .waiting, .uploading, .failed(.network), .uploaded(key: "b"),
        ]
        XCTAssertEqual(ReviewPhotoRules.keys(states), ["a", "b"])
        XCTAssertEqual(ReviewPhotoRules.keys([]), [])
    }

    /// 어쩌다 칸이 넘쳐도 서버에는 열 개까지만 간다(계약이 400 으로 막는다).
    func testKeysNeverExceedTheLimit() {
        let states = (1 ... 12).map { ReviewPhotoState.attached(key: "k\($0)") }
        XCTAssertEqual(ReviewPhotoRules.keys(states).count, 10)
        XCTAssertEqual(ReviewPhotoRules.keys(states).last, "k10")
        XCTAssertFalse(ReviewPhotoRules.canSave(states))
    }

    /// 올리는 중이거나 못 올린 사진이 있으면 저장하지 못한다 — 사진이 말없이 빠진 리뷰를 만들지 않는다.
    func testCannotSaveWhileUploadingOrFailed() {
        XCTAssertTrue(ReviewPhotoRules.canSave([]))
        XCTAssertTrue(ReviewPhotoRules.canSave([.attached(key: "a"), .uploaded(key: "b")]))
        XCTAssertFalse(ReviewPhotoRules.canSave([.attached(key: "a"), .waiting]))
        XCTAssertFalse(ReviewPhotoRules.canSave([.uploading]))
        XCTAssertFalse(ReviewPhotoRules.canSave([.uploaded(key: "b"), .failed(.tooLarge)]))
        XCTAssertTrue(ReviewPhotoRules.busy([.waiting]))
        XCTAssertFalse(ReviewPhotoRules.busy([.failed(.network)]))
        XCTAssertEqual(ReviewPhotoRules.failedCount([.failed(.network), .uploaded(key: "b"), .failed(.storage)]), 2)
    }

    /// 붙어 있던 사진 그대로면 안 바뀐 것. 빼거나, 순서가 달라지거나, 새로 고르면 바뀐 것.
    func testChangedComparesWithThePhotosTheReviewHad() {
        let had = ["a", "b"]
        XCTAssertFalse(ReviewPhotoRules.changed(existing: had, states: [.attached(key: "a"), .attached(key: "b")]))
        XCTAssertTrue(ReviewPhotoRules.changed(existing: had, states: [.attached(key: "a")]))
        XCTAssertTrue(ReviewPhotoRules.changed(existing: had, states: [.attached(key: "b"), .attached(key: "a")]))
        XCTAssertTrue(ReviewPhotoRules.changed(
            existing: had, states: [.attached(key: "a"), .attached(key: "b"), .uploaded(key: "n")]
        ))
        XCTAssertFalse(ReviewPhotoRules.changed(existing: [], states: []))
    }

    /// 새로 고른 칸은 올리는 중이든 실패했든 「쓰던 것」 이다 — 닫을 때 묻는다.
    func testAPickedPhotoCountsAsChangedEvenBeforeItUploads() {
        XCTAssertTrue(ReviewPhotoRules.changed(existing: [], states: [.waiting]))
        XCTAssertTrue(ReviewPhotoRules.changed(existing: [], states: [.uploading]))
        XCTAssertTrue(ReviewPhotoRules.changed(existing: [], states: [.failed(.network)]))
        XCTAssertTrue(ReviewPhotoRules.changed(existing: ["a"], states: [.attached(key: "a"), .failed(.unreadable)]))
    }

    /// 「사진 올리는 중 2/3」 — 이번 묶음에서 지금 올리는 것이 몇 번째인가. 실패도 끝난 것으로 센다.
    func testProgressIsThePositionInThisBatch() {
        XCTAssertTrue(ReviewPhotoRules.progress([.uploading, .waiting, .waiting]) == (1, 3))
        XCTAssertTrue(ReviewPhotoRules.progress([.uploaded(key: "n1"), .uploading, .waiting]) == (2, 3))
        XCTAssertTrue(ReviewPhotoRules.progress([.uploaded(key: "n1"), .failed(.tooLarge), .uploading]) == (3, 3))
        // 다 끝났으면 마지막 장에 머문다 — 「4/3」 이 되지 않는다.
        XCTAssertTrue(ReviewPhotoRules.progress([.uploaded(key: "n1"), .uploaded(key: "n2")]) == (2, 2))
        XCTAssertTrue(ReviewPhotoRules.progress([]) == (0, 0))
    }

    /// 한도·네트워크로 멈춘 줄은 그 실패 칸이 남아 있는 동안 스스로 돌지 않는다 — 연달아 실패시키지 않는다.
    func testAStoppedQueueStaysStoppedWhileTheBlockingFailureIsThere() {
        XCTAssertFalse(ReviewPhotoRules.shouldResume([.failed(.rateLimited), .waiting, .waiting]))
        XCTAssertFalse(ReviewPhotoRules.shouldResume([.uploaded(key: "a"), .failed(.network), .waiting]))
        XCTAssertFalse(ReviewPhotoRules.shouldResume([.failed(.unavailable), .waiting]))
        // 멈춰 있는 동안 저장은 막힌다.
        XCTAssertFalse(ReviewPhotoRules.canSave([.failed(.rateLimited), .waiting]))
    }

    /// 멈추게 한 칸을 빼거나 다시 시도(→ 기다림)하면 남은 사진을 잇는다. 사진 자체의 실패는 줄을 막지 않는다.
    func testTheQueueResumesOnceTheBlockingFailureIsGone() {
        XCTAssertTrue(ReviewPhotoRules.shouldResume([.waiting, .waiting]))
        XCTAssertTrue(ReviewPhotoRules.shouldResume([.uploaded(key: "a"), .waiting]))
        XCTAssertTrue(ReviewPhotoRules.shouldResume([.failed(.tooLarge), .waiting]))
        XCTAssertTrue(ReviewPhotoRules.shouldResume([.failed(.unreadable), .failed(.expired), .waiting]))
        // 기다리는 사진이 없으면 돌릴 것이 없다.
        XCTAssertFalse(ReviewPhotoRules.shouldResume([]))
        XCTAssertFalse(ReviewPhotoRules.shouldResume([.attached(key: "a"), .uploaded(key: "b")]))
        XCTAssertFalse(ReviewPhotoRules.shouldResume([.failed(.tooLarge)]))
    }
}
