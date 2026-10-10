import Foundation

/// 401 을 받았을 때 할 일 (MZ2AZ-336). 계약 `docs/api/errors.md` 인증 표를 옮긴 것이다.
enum AuthAction: Equatable {
    /// 액세스 토큰이 만료됐다 — 갱신하고 원래 요청을 **한 번** 다시 보낸다.
    case refreshAndRetry
    /// 세션이 깨졌다(위조·폐기·리프레시 만료) — 토큰을 지우고 로그인 화면.
    case signOut
    /// 비회원이 가입해야 쓰는 기능을 불렀다 — 로그인 화면으로 이끈다.
    case promptSignIn
    case none
}

enum AuthRules {
    static func action(status: Int, code: String?) -> AuthAction {
        guard status == 401 else { return .none }
        switch code {
        case "ACCESS_TOKEN_EXPIRED": return .refreshAndRetry
        case "ACCESS_TOKEN_INVALID", "SESSION_REQUIRED", "REFRESH_TOKEN_INVALID": return .signOut
        case "SIGN_IN_REQUIRED": return .promptSignIn
        default: return .none
        }
    }

    /// 401(만료)을 받은 요청이 **낡은 토큰으로 나갔던 것**인가 — 실었던 토큰과 지금 저장된 토큰이 다르면 그 사이에
    /// 누가 갱신했다. 그러면 또 갱신하지 않고 새 토큰으로 다시 보낸다(MZ2AZ-366).
    static func alreadyRefreshed(sent: String?, current: String?) -> Bool {
        guard let sent, let current else { return false }
        return sent != current
    }

    /// 오류 본문의 `code`. 본문이 없거나 우리 모양이 아니면 `nil`.
    static func apiCode(from data: Data?) -> String? {
        struct Body: Decodable { let code: String }
        guard let data else { return nil }
        return (try? JSONDecoder().decode(Body.self, from: data))?.code
    }

    /// 액세스 토큰을 미리 갱신할 때인가 — 죽었거나 `margin` 안에 죽는다. **죽는 때를 모르면 갱신한다**
    /// (이 값을 적기 전에 로그인한 설치본) — 한 번 갱신하면 그 뒤로는 안다.
    static func stale(expiresAt: Date?, now: Date, margin: TimeInterval = 60) -> Bool {
        guard let expiresAt else { return true }
        return expiresAt.timeIntervalSince(now) <= margin
    }

    /// 응답에 **서명된 사진 주소**가 실려 오는 창구인가 (MZ2AZ-363) — 촬영지·편의시설 상세(`photos`), 사진첩(`…/photos`),
    /// 리뷰 목록·내 리뷰(`…/reviews`, `…/reviews/me`, `/me/reviews`). 그런 응답은 디스크 캐시에 남기지 않는다:
    /// 한 시간짜리라도 남의 사진을 여는 주소가 앱 컨테이너에 파일로 쌓인다.
    static func carriesSignedUrls(url: String) -> Bool {
        let path = URLComponents(string: url)?.path ?? url
        return path.range(
            of: #"/(places|pois)/\d+(/photos|/reviews(/me)?)?/?$|/me/(reviews|posts)/?$|/posts(/\d+)?/?$"#, options: .regularExpression
        ) != nil
    }

    /// 로그인·갱신·로그아웃 창구 자신의 401 은 가로채지 않는다 — 갱신이 갱신을 부르면 끝나지 않는다.
    static func intercepts(url: String) -> Bool {
        !url.contains("/auth/")
    }
}

/// 같은 일을 **동시에 하나만** 돌린다. 도는 중에 온 호출은 그 결과를 같이 받는다.
///
/// 토큰 갱신에 쓴다. 여러 요청이 동시에 만료를 만나 저마다 갱신하면, 리프레시 토큰은
/// 일회용이라 두 번째부터 재사용으로 판정돼 서버가 그 계정의 토큰을 전부 폐기한다.
actor SingleFlight<Value: Sendable> {
    private var running: Task<Value, Error>?

    func run(_ work: @escaping @Sendable () async throws -> Value) async throws -> Value {
        if let running {
            return try await running.value
        }
        let task = Task { try await work() }
        running = task
        defer { running = nil }
        return try await task.value
    }
}
