import Foundation
import SceneApiClient

/// 로그인 토큰 두 개를 키체인에 둔다 (MZ2AZ-336).
///
/// **UserDefaults 에 두지 않는다** — 백업으로 다른 기기에 따라가면 그대로 남의 손에 들어간다
/// (`KeychainItem` 머리말). 앱은 토큰 안을 읽지 않는다. 만료는 401 로 안다.
///
/// 요청마다 키체인을 읽지 않도록 메모리에 사본을 든다. 요청은 여러 스레드에서 나가므로 잠근다.
enum AuthTokens {
    private static let accessItem = KeychainItem(account: "accessToken")
    private static let refreshItem = KeychainItem(account: "refreshToken")
    private static let lock = NSLock()
    private static var loaded = false
    private static var access: String?
    private static var refresh: String?

    static var accessToken: String? {
        locked { access }
    }

    static var refreshToken: String? {
        locked { refresh }
    }

    static var hasSession: Bool {
        refreshToken != nil
    }

    /// 로그인·갱신이 돌려준 묶음. **두 토큰을 모두 바꾼다** — 리프레시 토큰은 일회용이다.
    static func store(_ session: AuthSession) {
        locked {
            access = session.accessToken
            refresh = session.refreshToken
            accessItem.write(session.accessToken)
            refreshItem.write(session.refreshToken)
        }
    }

    static func clear() {
        locked {
            access = nil
            refresh = nil
            accessItem.remove()
            refreshItem.remove()
        }
    }

    private static func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        if !loaded {
            loaded = true
            access = accessItem.read()
            refresh = refreshItem.read()
        }
        return body()
    }
}

/// 토큰 갱신. **동시에 하나만** 나간다(`SingleFlight`).
enum AuthRefresher {
    private static let flight = SingleFlight<Bool>()

    /// 새 토큰을 받았으면 `true`. 리프레시 토큰이 거절되면 세션을 지우고 로그인 화면을 띄운다.
    /// 서버에 못 닿은 것이면 토큰을 그대로 둔다 — 다음 요청에서 다시 해 본다.
    static func refresh() async -> Bool {
        let done = try? await flight.run {
            guard let token = AuthTokens.refreshToken else { return false }
            do {
                let session = try await AuthAPI.refreshSession(
                    refreshTokenBody: RefreshTokenBody(refreshToken: token)
                )
                AuthTokens.store(session)
                return true
            } catch let ErrorResponse.error(status, _, _, _) where status == 401 {
                await AuthStore.shared.sessionLost()
                return false
            } catch {
                return false
            }
        }
        return done ?? false
    }
}

/// 나가 있는 쓰기 요청의 수.
///
/// 로그인은 이것이 0 이 되기를 기다렸다 부른다 — 겹치면 그 쓰기가 합쳐지기 전 비회원 계정에
/// 떨어질 수 있다(계약 `/auth/google` 설명, `social-login.md` §5 「알려진 틈」).
enum PendingWrites {
    private static let lock = NSLock()
    private static var count = 0

    static func begin() {
        lock.lock()
        count += 1
        lock.unlock()
    }

    static func end() {
        lock.lock()
        count = max(0, count - 1)
        lock.unlock()
    }

    private static var isIdle: Bool {
        lock.lock()
        defer { lock.unlock() }
        return count == 0
    }

    /// 쓰기가 다 끝날 때까지, 길어야 3초 기다린다.
    static func settle() async {
        for _ in 0 ..< 30 where !isIdle {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }
}
