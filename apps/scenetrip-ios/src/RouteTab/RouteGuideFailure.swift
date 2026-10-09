import Foundation
import SceneApiClient

/// 가이드가 답하지 못한 이유 — **계약이 정한 응답별로 갈린다** (`POST /guide/chat` ·
/// `POST /guide/plan`, docs/api/errors.md).
///
/// 「준비 중」은 서버가 없던 때의 말이다(MZ2AZ-297). 창구가 섰으니 그 문장이 남아 있으면 거짓말이다 —
/// 401 도 503 도 「준비 중」이면 사용자는 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다.
///
/// `RouteNavFailure` 와 같은 꼴이지만 갈래가 다르다. 길찾기에는 404·409·422 가 있고 여기에는 없다.
/// 대신 **시간 초과**가 있다 — 서버 벽(40초)보다 앱이 길게 기다리기로 한 값이라(MZ2AZ-321 §6) 그
/// 시간을 넘긴 것은 따로 말해야 한다.
///
/// 응답이 없을 때는 **왜 없는지**를 가른다(MZ2AZ-366) — 기기가 오프라인인지, 서버에 닿지 못했는지, 기다리다
/// 그만둔 것인지. 전에는 셋 다 「백엔드(:8081)가 켜져 있나요?」 였다(개발자에게 하는 말).
enum RouteGuideFailure: Equatable, Error {
    /// 가입해야 부를 수 있다 (401). 로컬 kind 는 벽을 치워 두어(MZ2AZ-302) 안 난다.
    case signInRequired
    /// 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). 다시 로그인한다 (MZ2AZ-336).
    case sessionExpired
    /// 요청이 계약을 어겼다 (400). `message` 는 서버 사정이라 화면에 그대로 띄우지 않는다.
    case badRequest
    /// 에이전트가 응답하지 않는다 (503 `GUIDE_UNAVAILABLE`, 게이트웨이의 502·504) — 프로세스가 죽었거나 모델이
    /// 꺼져 있다. **규칙 기반으로 조용히 떨어지지 않는다** — 왜 답이 없는지 사용자가 알아야 한다.
    case unavailable
    /// 기다리다 그만뒀다 — 턴 전체의 벽을 넘겼거나, 요청이 시간을 넘겼거나, 서버가 끝내 「처리 중」(409)이다.
    /// 서버가 뒤늦게 답을 만들었을 수 있다. 같은 멱등 키로 다시 물으면 그 답을 받는다.
    case timedOut
    /// 기기가 오프라인이다.
    case offline
    /// 서버에 닿지 못했다(연결 거부·끊김) — 기기 탓이 아니다.
    case unreachable
    /// 분당 요청 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). 공통 계층이 한 번 기다려 본 뒤다.
    case rateLimited
    /// 답을 기다리던 중에 화면이 그만 기다리게 했다(가이드 창을 닫음) — 재시도·되묻기를 끊은 것이다.
    case interrupted
    /// `422 IDEMPOTENCY_KEY_REUSED` — 같은 멱등 키에 다른 내용을 보냈다. **앱 버그다.**
    case keyReused
    /// 챗봇 한도(429 `GUIDE_LIMIT_REACHED`) — 1 시간 창이든 하루 창이든 이 하나다. `retryAfter` 는 풀릴 때까지의
    /// 초(서버의 `Retry-After`), 없으면 모른다. **자동으로 다시 보내지 않는다** — 화면이 안내한다(`GuideLimit`).
    case limitReached(retryAfter: Int?)
    /// 그 밖의 응답. 코드를 그대로 보여 준다.
    case other(status: Int)

    /// 「다시 시도」 가 무엇을 보내야 하나 (MZ2AZ-366).
    enum Retry: Equatable {
        /// **같은 멱등 키**로 — 서버가 이미 처리했으면 저장한 답을 주고, 아니면 한 번만 처리한다.
        case sameKey
        /// 새 키로 — 그 키로는 영영 422 다.
        case newKey
        /// 다시 보내도 같다. 단추를 내지 않는다.
        case none
    }

    /// 오류를 계약 응답으로 분류한다.
    init(_ error: Error) {
        if error is RouteGuideTimedOut {
            self = .timedOut
            return
        }
        guard case let ErrorResponse.error(status, data, response, underlying) = error else {
            self = Self.unanswered(error)
            return
        }
        // 음수는 HTTP 코드가 아니라 「연결 실패」 신호다 (URLSessionImplementations).
        guard status > 0 else {
            self = Self.unanswered(underlying)
            return
        }
        let code = AuthRules.apiCode(from: data)
        switch status {
        case 400: self = .badRequest
        // 401 이 전부 「가입하세요」는 아니다 — 토큰 만료·폐기도 401 로 온다. `code` 로 가른다.
        case 401:
            switch AuthRules.action(status: status, code: code) {
            case .refreshAndRetry, .signOut: self = .sessionExpired
            case .promptSignIn, .none: self = .signInRequired
            }
        case 409 where code == RetryRules.inProgressCode: self = .timedOut
        case 422 where code == RetryRules.keyReusedCode: self = .keyReused
        case 429 where code == GuideLimit.code:
            self = .limitReached(
                retryAfter: (response as? HTTPURLResponse).flatMap(RateLimitLedger.retryAfter(in:))
            )
        // 분당 한도. 공통 계층이 `Retry-After` 뒤에 한 번 다시 보내 본 뒤다.
        case 429 where code == nil || code == "RATE_LIMITED": self = .rateLimited
        case 502, 503, 504: self = .unavailable
        default: self = .other(status: status)
        }
    }

    /// 응답이 없었다 — 원래 오류로 왜인지 가른다.
    private static func unanswered(_ error: Error) -> RouteGuideFailure {
        if error is CancellationError {
            return .interrupted
        }
        guard let code = (error as? URLError)?.code else { return .unreachable }
        if RetryRules.isOffline(code) {
            return .offline
        }
        switch code {
        case .cancelled: return .interrupted
        case .timedOut: return .timedOut
        default: return .unreachable
        }
    }

    /// 다시 해 볼 만한가. 서버 사정·끊김·시간 초과는 그렇다 — 요청이 틀렸거나(400) 로그인이 필요하면(401) 아니다.
    var retry: Retry {
        switch self {
        case .unavailable, .timedOut, .offline, .unreachable, .rateLimited, .interrupted: .sameKey
        // 한도에 걸린 턴은 서버가 처리하지 않았다(키도 지웠다) — 풀린 뒤 같은 키로 다시 보낸다. **단추는 풀린
        // 뒤에만 뜬다**(`RouteGuideSession.canRetry`).
        case .limitReached: .sameKey
        case .keyReused: .newKey
        // 500 은 공통 계층이 한 번 다시 보내 본 뒤다 — 그래도 사람이 한 번 더 해 볼 수 있다.
        case let .other(status): status == 500 ? .sameKey : .none
        case .signInRequired, .sessionExpired, .badRequest: .none
        }
    }

    /// 챗봇 한도인가 — 대화창은 이것을 실패 줄이 아니라 한도 안내로 그린다.
    var isLimit: Bool {
        if case .limitReached = self {
            return true
        }
        return false
    }

    /// 화면에 보이는 한 줄. **안드로이드와 같은 문구여야 한다.**
    var message: String {
        switch self {
        case .signInRequired:
            tr("여행 가이드는 가입한 분만 쓸 수 있어요")
        case .sessionExpired:
            tr("로그인이 풀렸어요. 다시 로그인해 주세요")
        case .badRequest:
            tr("요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요")
        case .unavailable:
            tr("가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요")
        case .timedOut:
            tr("답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요")
        case .offline:
            tr("인터넷 연결을 확인해 주세요.")
        case .unreachable:
            tr("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요")
        case .rateLimited:
            tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
        case .interrupted:
            tr("답을 기다리다 멈췄어요. 다시 시도해 주세요")
        case let .limitReached(retryAfter):
            // 대화창은 풀리는 때까지 남은 시간을 다시 세어 보인다(`RouteGuideSheet`) — 이것은 받은 순간의 말이다.
            GuideLimit.Block(retryAfter: retryAfter, now: 0).message(at: 0)
        case .keyReused:
            String(format: tr("가이드 요청을 처리하지 못했어요 (%d)"), 422)
        case let .other(status):
            String(format: tr("가이드 요청을 처리하지 못했어요 (%d)"), status)
        }
    }
}

/// 벽을 넘겼다는 신호. `RouteGuideTimeout.run` 이 던진다.
struct RouteGuideTimedOut: Error, Equatable {}

/// 시간 벽. 벽을 넘기면 요청 태스크를 취소하고 끝낸다. **여기서는 다시 보내지 않는다** — 다시 보내는 것은
/// 멱등 키를 실은 턴에 한해 공통 계층(`RetryingSession`)이 한다. 키 없이 다시 보내면 이력이 두 번 쌓이고
/// `cart.add` 가 두 번 저장된다.
///
/// 생성 클라이언트의 `execute()` 가 `withTaskCancellationHandler` 로 취소를 URLSession 까지
/// 전달하므로, 여기서 태스크를 취소하면 연결도 끊긴다.
enum RouteGuideTimeout {
    static func run<T: Sendable>(
        seconds: Double,
        _ work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw RouteGuideTimedOut()
            }
            // 먼저 끝난 쪽이 답이다. 요청이 이기면 잠자는 태스크를, 잠이 이기면 요청을 취소한다.
            guard let first = try await group.next() else {
                throw RouteGuideTimedOut()
            }
            group.cancelAll()
            return first
        }
    }
}
