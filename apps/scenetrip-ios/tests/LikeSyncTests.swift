@testable import SceneTrip
import XCTest

/// 기기에만 있던 찜을 서버로 옮길 때 무엇을 올리는가 (MZ2AZ-335).
///
/// 틀어지면 업데이트 뒤 찜이 사라지거나(안 올림), 서버에서 지운 찜이 되살아난다(다시 올림).
final class LikeSyncTests: XCTestCase {
    func testUploadsOnlyWhatServerLacksBeforeMigration() {
        XCTAssertEqual(LikeSync.pendingUploads(local: [1, 2, 3], server: [2], migrated: false), [1, 3])
    }

    /// 한 번 옮긴 뒤에는 서버가 정본이다 — 기기 사본에만 있는 것은 다른 기기에서 지운 것이다.
    func testUploadsNothingAfterMigration() {
        XCTAssertEqual(LikeSync.pendingUploads(local: [1, 2, 3], server: [2], migrated: true), [])
    }

    func testNothingToUploadWhenLocalIsEmpty() {
        XCTAssertEqual(LikeSync.pendingUploads(local: [], server: [5], migrated: false), [])
    }
}
