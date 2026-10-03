import Foundation
import Security

/// 키체인에 문자열 하나를 두는 얇은 래퍼 (MZ2AZ-335).
///
/// 설치 식별자가 먼저 쓰고, 로그인 토큰(MZ2AZ-336)이 같은 자리를 쓴다.
///
/// ## 왜 UserDefaults 가 아닌가
///
/// 앱 설정 저장소는 백업·복원으로 **다른 기기에 따라간다.** 설치 식별자가 따라가면 두
/// 기기가 서버에서 한 사람으로 보이고, 토큰이 따라가면 그대로 남의 손에 들어간다.
/// 그래서 접근 등급도 `ThisDeviceOnly` 다 — 이 기기 밖으로 나가지 않는다.
///
/// `AfterFirstUnlock` 인 이유: 여행 중 화면이 잠긴 채 위치 갱신으로 앱이 깨어나 서버를
/// 부를 수 있다. `WhenUnlocked` 면 그때 값을 못 읽는다.
struct KeychainItem: InstallIdStore {
    let account: String

    private static let service = "com.mz2az.scenetrip"

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
        ]
    }

    func read() -> String? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess,
              let data = found as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 쓰지 못하면 `false` — 부르는 쪽이 다른 자리에 두거나 옛 값을 지우지 않게 한다.
    @discardableResult
    func write(_ value: String) -> Bool {
        let data = Data(value.utf8)
        let update = SecItemUpdate(
            query as CFDictionary, [kSecValueData as String: data] as CFDictionary
        )
        if update == errSecSuccess {
            return true
        }
        guard update == errSecItemNotFound else { return false }
        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    func remove() {
        SecItemDelete(query as CFDictionary)
    }
}

/// 이번 실행이 **설치 뒤 첫 실행인가.**
///
/// 키체인은 앱을 지워도 남는다. 그대로 두면 지웠다 깐 앱이 옛 설치 식별자(와 나중에는
/// 옛 로그인 토큰)를 이어 쓴다 — 「지우면 처음부터」라는 사람의 기대와 어긋나고, 계약의
/// 설명(앱을 지웠다 깔면 새 값)과도 어긋난다. UserDefaults 는 앱과 함께 지워지므로
/// 거기에 표식을 두어 첫 실행을 알아낸다.
enum InstallMarker {
    private static let key = "scenetrip.installMarker"

    /// 한 번만 `true` 다. 옛 버전에서 올라온 앱(옛 자리에 값이 있다)은 첫 실행이 아니다.
    static func consumeFreshInstall(
        defaults: UserDefaults = .standard, legacyKey: String
    ) -> Bool {
        if defaults.bool(forKey: key) {
            return false
        }
        defaults.set(true, forKey: key)
        return defaults.string(forKey: legacyKey) == nil
    }
}
