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
///
/// ## 새로 만드는 때가 하나 더 있다 (MZ2AZ-521)
///
/// 로그인하면 서버가 이 값을 가입 계정에 묶는다. 정식 로그아웃은 서버가 묶음을 풀어 새 비회원에
/// 짝지어 준다. 그런데 로그인이 **저절로** 풀리면(리프레시 거절 등) 토큰만 지워지고 이 값은 가입
/// 계정을 가리킨 채 남아, 토큰 없는 요청이 모두 `401 SESSION_REQUIRED` 를 받는다. 그때
/// `renew(from:)` 로 새 값을 만들어 새 비회원이 된다 — 웹(MZ2AZ-520 `renewInstallId`)과 같은 규칙.
/// 그래서 `current` 는 고정값이 아니라 **매번 읽는 값**이다. 화면 객체가 만들 때 한 번 받아 쥐고
/// 있으면 바뀐 뒤에도 옛 값을 보낸다.
enum InstallIdentity {
    /// 이 앱의 설치 식별자를 든 자리 하나. 처음 읽을 때 키체인에서 정한다(`resolve`).
    static let shared = InstallIdHolder(
        secure: KeychainItem(account: "installId"),
        legacy: LegacyInstallIdStore(),
        freshInstall: { InstallMarker.freshInstall }
    )

    /// 지금 설치 식별자. 최초 실행에 만들어 보관한 값이고, 이후로는 `renew(from:)` 가 바꾸기 전까지
    /// 계속 같은 것을 돌려준다.
    ///
    /// **매번 새로 만들면 앱을 껐다 켤 때마다 장바구니와 코스가 빈다.**
    static var current: UUID {
        shared.current
    }

    /// 낡은 값을 버리고 새 비회원이 된다 — `InstallIdHolder.renew(from:)`.
    @discardableResult
    static func renew(from previous: UUID) -> InstallIdHolder.Renewal {
        shared.renew(from: previous)
    }

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

/// `InstallIdHolder.renew(from:)` 의 결과 — 그 뒤의 지금 값과, 무슨 일이 있었는가.
struct InstallIdRenewal: Equatable {
    enum Outcome: Equatable {
        /// 이번 호출이 새 값을 만들었다.
        case renewed
        /// 이미 다른 값으로 바뀌어 있다(`previous` 가 낡았다) — 지금 값을 쓰면 된다.
        case superseded
        /// 지금 값이 `previous` 인데 이번 실행에서 이미 한 번 바꿨다 — 더 바꾸지 않는다.
        case exhausted
    }

    let current: UUID
    let outcome: Outcome

    var renewed: Bool {
        outcome == .renewed
    }
}

/// 지금 설치 식별자를 메모리에 들고, 바꿀 때 같은 자리(키체인, 못 쓰면 옛 자리)에 적는다 (MZ2AZ-521).
///
/// 요청은 여러 스레드에서 나가므로 잠근다. 저장소를 바꿔 끼울 수 있어 규칙을 가짜 저장소로 시험한다.
final class InstallIdHolder: @unchecked Sendable {
    typealias Renewal = InstallIdRenewal

    private let secure: InstallIdStore
    private let legacy: InstallIdStore
    private let freshInstall: () -> Bool
    private let lock = NSLock()
    private var value: UUID?
    /// 이 실행(프로세스)에서 이미 한 번 바꿨는가.
    private var renewedThisRun = false

    init(secure: InstallIdStore, legacy: InstallIdStore, freshInstall: @escaping () -> Bool) {
        self.secure = secure
        self.legacy = legacy
        self.freshInstall = freshInstall
    }

    var current: UUID {
        locked { loaded }
    }

    /// 지금 값이 `previous` 일 때만 새 값을 만들어 저장한다. 같은 낡은 값으로 거절받은 요청 여럿이
    /// 불러도 **한 번만** 바뀐다 — 이미 바뀌었으면(또는 `previous` 가 더 옛 값이면) 지금 값을 그대로
    /// 돌려준다(`superseded`). **한 실행에서 두 번째로는 바꾸지 않는다**(`exhausted`).
    ///
    /// 키체인에 적었으면 옛 자리에 남았을지 모르는 값은 지운다(키체인이 먼저 읽히지만 남길 이유가 없다).
    /// 키체인에 못 쓰면 키체인의 낡은 값을 지우고 옛 자리에 적는다 — `InstallIdentity.resolve` 가 다음 실행에
    /// 키체인(비었다) → 옛 자리 순서로 읽는다.
    func renew(from previous: UUID) -> Renewal {
        locked {
            let now = loaded
            guard now == previous else { return Renewal(current: now, outcome: .superseded) }
            // 한 실행에 한 번 — 서버가 새 값에도 거절하면 바꾸기 → 다시 읽기 → 거절 → 바꾸기 고리가 된다.
            guard !renewedThisRun else { return Renewal(current: now, outcome: .exhausted) }
            let fresh = UUID()
            if secure.write(fresh.uuidString) {
                legacy.remove()
            } else {
                // 키체인에 남은 낡은 값을 지운다 — 남기면 다음 실행의 `resolve` 가 그것을 먼저 읽어 거절받던 값으로
                // 돌아간다. 지우기도 실패하면 어쩔 수 없다(이번 실행은 새 값을 쓴다).
                secure.remove()
                _ = legacy.write(fresh.uuidString)
            }
            value = fresh
            renewedThisRun = true
            return Renewal(current: fresh, outcome: .renewed)
        }
    }

    /// 잠근 채로만 부른다.
    private var loaded: UUID {
        if let value {
            return value
        }
        let resolved = InstallIdentity.resolve(secure: secure, legacy: legacy, freshInstall: freshInstall())
        value = resolved
        return resolved
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
