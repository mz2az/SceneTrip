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

    /// 오류 본문의 `code`. 본문이 없거나 우리 모양이 아니면 `nil`.
    static func apiCode(from data: Data?) -> String? {
        struct Body: Decodable { let code: String }
        guard let data else { return nil }
        return (try? JSONDecoder().decode(Body.self, from: data))?.code
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
