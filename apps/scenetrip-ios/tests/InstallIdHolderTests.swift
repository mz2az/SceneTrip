@testable import SceneTrip
import XCTest

/// 설치 식별자를 바꾸는 자리(`InstallIdHolder.renew(from:)`)의 규칙 (MZ2AZ-521). 요청 가로채기 규칙은
/// `InstallRenewalTests`, 생성 클라이언트 경로는 `InstallRenewalWiringTests`.
final class InstallIdHolderTests: XCTestCase {
    private let old = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    private func holder(
        _ secure: RenewalFakeStore, _ legacy: RenewalFakeStore = RenewalFakeStore()
    ) -> InstallIdHolder {
        InstallIdHolder(secure: secure, legacy: legacy, freshInstall: { false })
    }

    /// 지금 값으로 거절받았으면 새 UUID 로 바꾸고 **키체인에 적는다** — 다음 실행(새 자리)도 새 값을 읽는다.
    func testRenewReplacesCurrentValueAndKeepsItInKeychain() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        XCTAssertEqual(ids.current, old)

        let renewal = ids.renew(from: old)

        XCTAssertTrue(renewal.renewed)
        XCTAssertNotEqual(renewal.current, old)
        XCTAssertEqual(ids.current, renewal.current)
        XCTAssertEqual(secure.value, renewal.current.uuidString)
        XCTAssertEqual(holder(secure).current, renewal.current, "다음 실행에도 새 값이어야 합니다")
    }

    /// 이미 바뀐 뒤 옛 값(또는 더 옛 값)으로 온 거절은 무시한다 — 다시 바꾸지 않는다.
    func testRenewFromStaleValueKeepsCurrent() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let first = ids.renew(from: old)
        XCTAssertTrue(first.renewed)

        let again = ids.renew(from: old)
        let older = ids.renew(from: UUID())

        XCTAssertFalse(again.renewed)
        XCTAssertFalse(older.renewed)
        XCTAssertEqual(again.current, first.current)
        XCTAssertEqual(older.current, first.current)
        XCTAssertEqual(ids.current, first.current)
        XCTAssertEqual(secure.writes, 1)
    }

    /// 같은 낡은 값으로 동시에 거절받은 요청 여럿이 불러도 한 번만 바뀐다.
    func testConcurrentRenewalsFromSameValueRenewOnce() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let lock = NSLock()
        var outcomes: [InstallIdHolder.Renewal] = []
        DispatchQueue.concurrentPerform(iterations: 16) { _ in
            let outcome = ids.renew(from: old)
            lock.lock()
            outcomes.append(outcome)
            lock.unlock()
        }
        XCTAssertEqual(outcomes.filter(\.renewed).count, 1)
        XCTAssertEqual(Set(outcomes.map(\.current)), [ids.current])
        XCTAssertNotEqual(ids.current, old)
        XCTAssertEqual(secure.writes, 1)
    }

    /// 키체인에 못 쓰면 옛 자리에 적는다 — 다음 실행에 `resolve` 가 그 값을 읽는다.
    func testRenewFallsBackToLegacyWhenKeychainIsUnavailable() {
        let secure = RenewalFakeStore()
        secure.writable = false
        let legacy = RenewalFakeStore(old.uuidString)
        let ids = holder(secure, legacy)
        XCTAssertEqual(ids.current, old)

        let renewal = ids.renew(from: old)

        XCTAssertTrue(renewal.renewed)
        XCTAssertEqual(legacy.value, renewal.current.uuidString)
        XCTAssertEqual(holder(secure, legacy).current, renewal.current)
    }

    /// 한 실행에서 번호는 한 번만 바꾼다 — 서버가 새 번호에도 거절하면 바꾸기 → 다시 읽기 → 거절 → 바꾸기 고리가 된다.
    func testSecondRenewalInOneRunIsRefused() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let first = ids.renew(from: old)
        XCTAssertTrue(first.renewed)

        let second = ids.renew(from: first.current)

        XCTAssertFalse(second.renewed)
        XCTAssertEqual(second.outcome, .exhausted)
        XCTAssertEqual(second.current, first.current)
        XCTAssertEqual(ids.current, first.current)
        XCTAssertEqual(secure.writes, 1)
    }

    /// 키체인은 읽히는데 쓰기만 실패하면 옛 자리에 적고 **키체인의 낡은 값을 지운다** — 남겨 두면 다음 실행의
    /// `resolve` 가 키체인을 먼저 읽어 거절받던 번호로 돌아간다.
    func testRenewDropsStaleKeychainValueWhenOnlyLegacyIsWritable() {
        let secure = RenewalFakeStore(old.uuidString)
        secure.writable = false
        let legacy = RenewalFakeStore()
        let ids = holder(secure, legacy)
        XCTAssertEqual(ids.current, old)

        let renewal = ids.renew(from: old)

        XCTAssertTrue(renewal.renewed)
        XCTAssertNil(secure.value)
        XCTAssertEqual(legacy.value, renewal.current.uuidString)
        XCTAssertEqual(holder(secure, legacy).current, renewal.current, "다음 실행에도 새 값이어야 합니다")
    }
}
