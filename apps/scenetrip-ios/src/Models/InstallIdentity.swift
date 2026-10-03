import Foundation

/// 설치 식별자를 두는 자리 하나. 키체인과 옛 자리(UserDefaults)가 같은 모양이라
/// 옮기는 규칙을 가짜 저장소로 시험할 수 있다.
protocol InstallIdStore {
    func read() -> String?
    /// 쓰지 못하면 `false`.
    @discardableResult func write(_ value: String) -> Bool
    func remove()
}

/// 옛 자리 — `UserDefaults` 의 `scenetrip.deviceId`. 읽어서 옮기고 지우는 데만 쓴다.
struct LegacyInstallIdStore: InstallIdStore {
    static let key = "scenetrip.deviceId"
    var defaults: UserDefaults = .standard

    func read() -> String? {
        defaults.string(forKey: Self.key)
    }

    func write(_ value: String) -> Bool {
        defaults.set(value, forKey: Self.key)
        return true
    }

    func remove() {
        defaults.removeObject(forKey: Self.key)
    }
}

/// 이 설치본을 가리키는 값 (MZ2AZ-261).
///
/// 서버가 「누구의 장바구니·코스인가」를 이 값으로 가른다. 계약의 `X-Install-Id`
/// 헤더에 실려 나간다.
///
/// ## 이름이 기기가 아니라 설치본인 이유
///
/// 이 값은 기기를 가리키지 않는다 — **앱을 지웠다 깔면 새 값이 되고, 같은 폰에 두 번
/// 깔면 서로 다른 사람으로 보인다.** `deviceId` 라고 부르면 「폰을 바꿨는데 왜 데이터가
/// 없죠」를 버그로 오해하게 된다. 헤더도 같은 이유로 `X-Device-Id` 에서 `X-Install-Id` 로
/// 바꿨다(MZ2AZ-328).
///
/// ## 왜 장바구니에서 꺼냈나
///
/// 이 값이 `CartStore` 안에 있었다. 코스·마켓·찜 API 가 전부 같은 값을 필요로 하는데
/// 그 자리에 두면 **코스 기능이 장바구니 코드에 의존하게 된다.** 둘 사이에는 아무
/// 관계가 없는데 코드에는 관계가 생긴다.
///
/// ## 키체인에 둔다 (MZ2AZ-335)
///
/// `UserDefaults` 의 `scenetrip.deviceId` 에 있던 값을 키체인으로 옮겼다 — 이유는
/// `KeychainItem` 머리말. **옛 자리에서 한 번 읽어 옮기는 폴백이 있다.** 그것 없이 자리만
/// 바꾸면 이미 깔린 앱의 값이 고아가 되어 그 사람의 장바구니가 끊긴다.
enum InstallIdentity {
    /// 최초 실행에 만들어 보관한 값. 이후로는 계속 같은 것을 돌려준다.
    ///
    /// **매번 새로 만들면 앱을 껐다 켤 때마다 장바구니와 코스가 빈다.**
    static let current: UUID = resolve(
        secure: KeychainItem(account: "installId"),
        legacy: LegacyInstallIdStore(),
        freshInstall: InstallMarker.consumeFreshInstall(legacyKey: LegacyInstallIdStore.key)
    )

    /// 읽는 순서: 키체인 → 옛 자리(옮기고 지운다) → 새로 만든다.
    ///
    /// - `freshInstall`: 설치 뒤 첫 실행이면 키체인에 남은 값을 버린다 — 지웠다 깐 앱은
    ///   새 설치본이다(`InstallMarker`).
    /// - 키체인에 못 쓰면 옛 자리를 그대로 둔다(또는 거기에 쓴다). 실행마다 값이 바뀌는
    ///   것이 가장 나쁘다.
    static func resolve(secure: InstallIdStore, legacy: InstallIdStore, freshInstall: Bool) -> UUID {
        if freshInstall {
            secure.remove()
        } else if let kept = secure.read().flatMap(UUID.init(uuidString:)) {
            return kept
        }
        if let old = legacy.read().flatMap(UUID.init(uuidString:)) {
            if secure.write(old.uuidString) {
                legacy.remove()
            }
            return old
        }
        let fresh = UUID()
        if !secure.write(fresh.uuidString) {
            _ = legacy.write(fresh.uuidString)
        }
        return fresh
    }
}
