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

    // MARK: 찜을 켠 직후의 다시 읽기 (MZ2AZ-372)

    /// 화면의 하트를 흉내 낸다 — 누르면 바로 바뀌고, 켠 직후의 다시 읽기는 요약만 가져온다.
    private struct Hearts {
        var ids: Set<Int64>
        var works: [Int64] = []

        mutating func tap(_ id: Int64) {
            if ids.contains(id) {
                ids.remove(id)
            } else {
                ids.insert(id)
            }
        }

        /// 찜 목록 응답이 도착했다 — 떠날 때 서버에 있던 것(`server`)을 들고.
        mutating func summariesArrived(_ server: [Int64]) {
            ids = LikeSync.idsAfterSummaryReload(current: ids, fetched: server)
            works = server
        }

        var shown: [Int64] {
            LikeSync.shown(works, liked: ids, id: { $0 })
        }
    }

    /// A 를 켜고 곧바로 껐다. 켠 직후의 다시 읽기는 끄기가 서버에 닿기 전에 떠나 「A 있음」 을 들고 온다 —
    /// 그것이 하트를 다시 켜면 화면(켜짐)과 서버(꺼짐)가 어긋난 채 남는다.
    func testQuickOnOffStaysOffWhenTheReloadArrivesLate() {
        var hearts = Hearts(ids: [7, 8])
        hearts.tap(20)
        hearts.tap(20)
        hearts.summariesArrived([20, 8, 7])
        XCTAssertEqual(hearts.ids, [7, 8])
        // 꺼진 작품의 요약이 응답에 남아 있어도 목록에는 그리지 않는다.
        XCTAssertEqual(hearts.shown, [8, 7])
    }

    /// A·B 를 연달아 켰다. A 의 다시 읽기는 B 가 서버에 닿기 전에 떠나 「B 없음」 을 들고 온다 —
    /// B 의 하트가 꺼졌다 켜지면(깜빡임) 안 된다.
    func testSecondHeartDoesNotBlinkWhenTheFirstReloadArrives() {
        var hearts = Hearts(ids: [7])
        hearts.tap(20)
        hearts.tap(21)
        hearts.summariesArrived([20, 7])
        XCTAssertEqual(hearts.ids, [7, 20, 21])
        // B 의 요약은 아직 없다 — B 의 다시 읽기가 오면 채워진다.
        XCTAssertEqual(hearts.shown, [20, 7])
        hearts.summariesArrived([21, 20, 7])
        XCTAssertEqual(hearts.shown, [21, 20, 7])
    }

    /// 목록은 받은 순서(최근 찜부터)를 지킨다.
    func testShownKeepsServerOrder() {
        XCTAssertEqual(LikeSync.shown([11, 8, 7, 3], liked: [3, 7, 11], id: { $0 }), [11, 7, 3])
        XCTAssertEqual(LikeSync.shown([Int64](), liked: [1], id: { $0 }), [])
    }
}
