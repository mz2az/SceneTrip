import Foundation
import SceneApiClient

/// 가이드가 답하지 못한 이유 — **계약이 정한 응답별로 갈린다** (`POST /guide/chat` ·
/// `POST /guide/plan`, docs/api/errors.md).
///
/// 「준비 중」은 서버가 없던 때의 말이다(MZ2AZ-297). 창구가 섰으니 그 문장이 남아 있으면 거짓말이다 —
/// 401 도 503 도 「준비 중」이면 사용자는 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다.
///
/// `RouteNavFailure` 와 같은 꼴이지만 갈래가 다르다. 길찾기에는 404·409·422 가 있고 여기에는 없다.
/// 대신 **50초 초과**가 있다 — 서버 벽(40초)보다 앱이 길게 기다리기로 한 값이라(MZ2AZ-321 §6) 그
/// 시간을 넘긴 것은 따로 말해야 한다.
enum RouteGuideFailure: Equatable, Error {
    /// 가입해야 부를 수 있다 (401). 로컬 kind 는 벽을 치워 두어(MZ2AZ-302) 안 난다.
    case signInRequired
    /// 요청이 계약을 어겼다 (400). `message` 는 서버 사정이라 화면에 그대로 띄우지 않는다.
    case badRequest
    /// 에이전트가 응답하지 않는다 (503 `GUIDE_UNAVAILABLE`) — 프로세스가 죽었거나 모델이 꺼져 있거나
    /// 한도 초과. **규칙 기반으로 조용히 떨어지지 않는다** — 왜 답이 없는지 사용자가 알아야 한다.
    case unavailable
    /// 50초를 넘겼다. 앱이 먼저 포기했으므로 서버가 뒤늦게 답을 만들었을 수 있다 — 자동으로 다시
    /// 묻지 않는다(멱등이 아니다).
    case timedOut
    /// 서버에 닿지 못했다. 백엔드가 꺼져 있거나 네트워크가 없다.
    case unreachable
    /// 그 밖의 응답. 코드를 그대로 보여 준다.
    case other(status: Int)

    /// 오류를 계약 응답으로 분류한다.
    init(_ error: Error) {
        if error is RouteGuideTimedOut {
            self = .timedOut
            return
        }
        guard case let ErrorResponse.error(status, _, _, _) = error else {
            self = .unreachable
            return
        }
        // 음수는 HTTP 코드가 아니라 「연결 실패」 신호다 (URLSessionImplementations).
        guard status > 0 else {
            self = .unreachable
            return
        }
        switch status {
        case 400: self = .badRequest
        case 401: self = .signInRequired
        case 503: self = .unavailable
        default: self = .other(status: status)
        }
    }

    /// 화면에 보이는 한 줄. **안드로이드와 같은 문구여야 한다.**
    var message: String {
        switch self {
        case .signInRequired:
            "여행 가이드는 가입한 분만 쓸 수 있어요"
        case .badRequest:
            "요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요"
        case .unavailable:
            "가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요"
        case .timedOut:
            "답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요"
        case .unreachable:
            "서버에 연결하지 못했어요 — 백엔드(:8081)가 켜져 있나요?"
        case let .other(status):
            "가이드 요청을 처리하지 못했어요 (\(status))"
        }
    }
}

/// 50초를 넘겼다는 신호. `RouteGuideTimeout.run` 이 던진다.
struct RouteGuideTimedOut: Error, Equatable {}

/// 시간 벽. **자동 재시도는 없다** — `/guide/chat` 은 멱등이 아니라 다시 보내면 이력이 두 번
/// 쌓이고 `cart.add` 가 두 번 저장된다. 벽을 넘기면 요청 태스크를 취소하고 끝낸다.
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
