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
    /// 액세스 토큰이 죽는 때. 비밀이 아니라 `UserDefaults` 에 둔다 — 앱을 다시 켜도 안다.
    private static var expiry: Date?
    private static let expiryKey = "scenetrip.auth.accessExpiresAt"

    static var accessToken: String? {
        locked { access }
    }

    static var refreshToken: String? {
        locked { refresh }
    }

    static var hasSession: Bool {
        refreshToken != nil
    }

    /// 액세스 토큰이 죽는 때 — 받을 때 서버가 일러 준 수명(`accessTokenExpiresIn`)으로 셈한 것이다.
    /// 토큰 안을 읽지 않는다. 이 값을 적기 전에 로그인한 설치본은 모른다(nil).
    static var accessExpiresAt: Date? {
        locked { expiry }
    }

    /// 로그인·갱신이 돌려준 묶음. **두 토큰을 모두 바꾼다** — 리프레시 토큰은 일회용이다.
    static func store(_ session: AuthSession) {
        locked {
            access = session.accessToken
            refresh = session.refreshToken
            expiry = Date().addingTimeInterval(TimeInterval(session.accessTokenExpiresIn))
            UserDefaults.standard.set(expiry, forKey: expiryKey)
            accessItem.write(session.accessToken)
            refreshItem.write(session.refreshToken)
        }
    }

    static func clear() {
        locked {
            access = nil
            refresh = nil
            expiry = nil
            UserDefaults.standard.removeObject(forKey: expiryKey)
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
            expiry = UserDefaults.standard.object(forKey: expiryKey) as? Date
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

    /// 액세스 토큰이 죽었거나 곧 죽으면 **부르기 전에** 갱신한다. 로그인하지 않았으면 아무것도 하지 않는다.
    ///
    /// 보통은 401(`ACCESS_TOKEN_EXPIRED`)을 받고 갱신하면 된다 — 그런데 **누구나 읽는 창구**(리뷰 목록)는
    /// 만료된 토큰에 401 을 주지 않고 비회원으로 답한다(서버 `CurrentAccount.signedInOrNull`). 그러면 갱신할
    /// 계기가 없어 「내 리뷰」 표시가 빠진 목록이 그대로 그려진다(2026-10-08 실기 — 앱을 켠 직후, 그리고
    /// 30분 넘게 열어 둔 뒤의 첫 리뷰 시트). 그런 창구는 부르기 전에 이것을 거친다.
    ///
    /// 갱신에 실패해도 던지지 않는다 — 목록은 비회원으로라도 보여야 한다.
    static func refreshIfStale() async {
        guard AuthTokens.hasSession,
              AuthRules.stale(expiresAt: AuthTokens.accessExpiresAt, now: Date())
        else { return }
        _ = await refresh()
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
