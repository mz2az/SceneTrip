@testable import SceneTrip
import XCTest

/// 설치 식별자를 어디서 읽고 어디로 옮기는가를 고정한다 (MZ2AZ-335).
///
/// 틀어지면 **업데이트한 사람의 장바구니·코스가 통째로 사라진 것처럼 보인다** — 서버가
/// 처음 보는 설치본으로 여겨 새 비회원 계정을 만들기 때문이다. 화면에는 오류가 없다.
final class InstallIdentityTests: XCTestCase {
    private final class FakeStore: InstallIdStore {
        var value: String?
        var writable = true

        init(_ value: String? = nil) {
            self.value = value
        }

        func read() -> String? {
            value
        }

        func write(_ new: String) -> Bool {
            guard writable else { return false }
            value = new
            return true
        }

        func remove() {
            value = nil
        }
    }

    private let saved = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    func testKeychainValueWins() {
        let secure = FakeStore(saved.uuidString)
        let legacy = FakeStore(UUID().uuidString)
        XCTAssertEqual(InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false), saved)
    }

    /// 옛 자리(UserDefaults)에만 있으면 **같은 값을** 키체인으로 옮기고 옛 자리는 지운다.
    func testLegacyValueMovesToKeychain() {
        let secure = FakeStore()
        let legacy = FakeStore(saved.uuidString)
        XCTAssertEqual(InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false), saved)
        XCTAssertEqual(secure.value, saved.uuidString)
        XCTAssertNil(legacy.value)
    }

    /// 키체인에 못 쓰면 옛 자리를 지우지 않는다 — 지우면 다음 실행에 값이 바뀐다.
    func testLegacyStaysWhenKeychainWriteFails() {
        let secure = FakeStore()
        secure.writable = false
        let legacy = FakeStore(saved.uuidString)
        XCTAssertEqual(InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false), saved)
        XCTAssertEqual(legacy.value, saved.uuidString)
    }

    func testFirstLaunchCreatesAndKeeps() {
        let secure = FakeStore()
        let legacy = FakeStore()
        let first = InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: true)
        XCTAssertEqual(secure.value, first.uuidString)
        XCTAssertNil(legacy.value)
        XCTAssertEqual(InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false), first)
    }

    /// 키체인이 막혀 있으면 옛 자리에라도 둔다 — 매 실행마다 새 값이 되면 안 된다.
    func testFallsBackToLegacyWhenKeychainIsUnavailable() {
        let secure = FakeStore()
        secure.writable = false
        let legacy = FakeStore()
        let first = InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: true)
        XCTAssertEqual(legacy.value, first.uuidString)
        XCTAssertEqual(InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false), first)
    }

    /// 키체인은 앱을 지워도 남는다. **지웠다 깔면 새 설치본**이어야 하므로 남은 값을 버린다.
    func testReinstallDropsLeftoverKeychainValue() {
        let secure = FakeStore(saved.uuidString)
        let legacy = FakeStore()
        let fresh = InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: true)
        XCTAssertNotEqual(fresh, saved)
        XCTAssertEqual(secure.value, fresh.uuidString)
    }

    func testGarbageIsReplaced() {
        let secure = FakeStore("not-a-uuid")
        let legacy = FakeStore("also-bad")
        let fresh = InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: false)
        XCTAssertEqual(secure.value, fresh.uuidString)
    }
}
