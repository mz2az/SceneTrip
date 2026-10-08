import Foundation

/// 실패한 요청을 **다시 보낼 것인가** (MZ2AZ-366, 계획 `docs/project/plans/app-retry.md` §2).
///
/// `URLSession` 도 생성 클라이언트도 끊김·5xx 를 스스로 다시 보내지 않는다. 화면마다 따로 풀면 창구가
/// 60 군데라 빠뜨린다 — 규칙은 여기 한 곳이고, 보내는 일은 `RetryingSession` 이 한다.
///
/// **순수 함수다.** 시계도 난수도 네트워크도 없다 — 「무엇을 받았고 지금까지 몇 번 다시 보냈나」 를 주면
/// 「몇 초 뒤 다시」 나 「그만」 을 돌려준다. 그래서 표의 칸마다 단위 시험 한 줄이 선다(`RetryRulesTests`).
///
/// ## 다시 보내도 되는 것만
///
/// 다시 보낸다는 것은 **같은 요청이 서버에 두 번 닿을 수 있다**는 뜻이다. 조회와 PUT·DELETE 는 두 번
/// 닿아도 결과가 같다. POST 는 아니다 — 코스가 둘 생기고, 일회용 리프레시 토큰은 재사용으로 판정돼 세션이
/// 폐기된다. 그래서 POST 는 **서버에 닿지 않은 것이 확실할 때만** 다시 보낸다.
enum RetryRules {
    /// 숫자는 전부 여기 있다. 바꿀 때 이 한 곳만 본다(2026-10-08 정한 값 — 계획 §7).
    enum Tuning {
        /// 지수 백오프(초). 재시도 n 번째가 n 번째 값을 쓴다.
        static let backoff: [TimeInterval] = [1, 2, 4]
        /// 지터 — 간격의 ±20%. 같은 화면의 조회 다섯이 한 순간에 몰려 나가지 않게 흩는다.
        static let jitter = 0.2
        /// 조회: 끊김·502·503·504 에 세 번.
        static let readRetries = 3
        /// PUT·DELETE: 두 번.
        static let idempotentWriteRetries = 2
        /// 멱등이 아닌 POST: 닿지 않은 것이 확실할 때 두 번.
        static let unsafeWriteRetries = 2
        /// 한 번의 요청이 기다리는 벽(초). 기본값(60초)에 재시도 세 번이면 빈 화면이 4 분이다.
        static let readTimeout: TimeInterval = 15
        static let writeTimeout: TimeInterval = 30
        /// 챗봇·마법사는 서버 벽(40초)·게이트웨이 벽(45초)보다 길어야 한다. 챗봇의 50초 벽은
        /// `RouteGuideTimeout` 이 따로 건다 — 여기 값은 그보다 길어 그 벽을 가리지 않는다.
        static let guideTimeout: TimeInterval = 60
        /// 재시도까지 합친 벽(초). 넘으면 남은 횟수가 있어도 그만둔다.
        static let readBudget: TimeInterval = 25
        static let writeBudget: TimeInterval = 45
        /// `RATE_LIMITED` 를 조용히 기다리는 상한(초). 분당 창이라 `Retry-After` 는 60 을 넘지 않는다 —
        /// 넘으면 하루 한도 같은 다른 것이므로 기다리지 않는다.
        static let rateLimitMaxWait: TimeInterval = 60
        /// `Retry-After` 가 없을 때(게이트웨이가 낸 429) 기다릴 시간.
        static let rateLimitDefaultWait: TimeInterval = 1
        /// 한도 대기에 **더하는** 지터(초)의 상한. 같은 한도에 걸린 요청들이 풀리는 순간 한꺼번에 나가지 않게
        /// 흩는다 — 줄이는 쪽으로는 쓰지 않는다(일찍 보내면 또 429 다).
        static let rateLimitJitter: TimeInterval = 1
        /// 한도를 기다린 뒤 남은 벽이 이보다 작으면 보내지 않는다 — 보내 봐야 답을 받을 시간이 없다.
        static let minAttemptSeconds: TimeInterval = 3
        /// 이보다 오래 기다리게 되면 화면이 「잠시 기다리는 중」 을 말할 수 있다(`RateLimitLedger.waitingUntil`).
        static let rateLimitQuietSeconds: TimeInterval = 5

        // 챗봇 — **멱등 키를 실었고 서버가 키를 아는 때만**(`Lane.keyedChat`, 계획 §4).

        /// 끊김·502·503·504·500 에 자동으로 한 번. 그 뒤는 사람이 누르는 「다시 시도」(같은 키).
        static let chatRetries = 1
        /// 한 번의 요청 벽(초). 서버 벽(40초)·게이트웨이 벽(45초)보다 길다 — 전에는 `RouteGuideTimeout` 이 걸던 값.
        static let chatAttemptTimeout: TimeInterval = 50
        /// 재시도·되묻기까지 합친 턴 전체의 벽(초).
        static let chatBudget: TimeInterval = 110
        /// 화면이 따로 거는 벽은 이만큼 더 길다 — 이 계층이 먼저 끝나 제 이유(끊김·503)를 말하게.
        static let chatWallMargin: TimeInterval = 5
        /// `409 IDEMPOTENCY_IN_PROGRESS` — 서버가 같은 키를 아직 처리 중이다. 이 간격으로 같은 키로 다시 묻는다.
        static let inProgressPollSeconds: TimeInterval = 3
        /// **그 키를 처음 보낸 때부터** 이 초가 지나면 더 되묻지 않는다. 서버는 처리 중인 채 **1 분**이 지난 키를
        /// 죽은 것으로 보고, 그 뒤에 같은 키로 온 요청을 **새로 처리한다**(`IdempotencyStore.begin`) — 첫 처리가
        /// 아직 살아 있으면 모델을 두 번 부르고 한도를 두 번 깎는다. 그래서 그 1 분보다 짧게 끊는다: 마지막
        /// 되묻기가 55초 전에 나가고, 그 요청이 서버에 닿는 것도 60초 전이다. 넘기면 「오래 걸려 멈췄어요」 와
        /// 「다시 시도」 로 끝낸다(사람이 누르는 것은 한 번만 나간다 — 계획 §10).
        static let inProgressDeadline: TimeInterval = 55
        /// 되묻기 횟수의 상한. 위의 시각 벽이 먼저 닿는다(3초 간격이면 열여덟 번) — 이것은 뒤를 받치는 값이다.
        static let inProgressMaxPolls = 20
    }

    /// 창구의 갈래. 메서드와 경로가 정한다.
    enum Lane: Equatable {
        /// 조회(GET).
        case read
        /// 두 번 닿아도 같은 쓰기(PUT·DELETE).
        case idempotentWrite
        /// **닿지 않은 것이 확실할 때만** 다시 보내는 쓰기 — 두 번 닿으면 안 되는 것(POST, 닉네임)과,
        /// 화면이 응답을 기다리지 않는 것(찜·방문 체크·장바구니 빼기·여행 시작/종료).
        case unsafeWrite
        /// **멱등 키를 실은 챗봇 턴**(`POST /guide/chat` + `Idempotency-Key`, 서버가 키를 아는 때).
        /// 서버가 같은 키를 한 번만 처리하므로 닿았는지 몰라도 다시 보낼 수 있다 — 자동으로 한 번.
        /// `409 처리 중` 은 그 횟수와 따로 세어 기다린다.
        case keyedChat
        /// 자동으로는 다시 보내지 않는다 — 키 없는 챗봇(또는 서버가 키를 모를 때), 길찾기·마법사(유료일 수
        /// 있다), 토큰 갱신·로그아웃.
        case never
    }

    /// 챗봇 멱등 키가 실리는 헤더(계약 `IdempotencyKey`).
    static let idempotencyHeader = "Idempotency-Key"

    struct Policy: Equatable {
        let lane: Lane
        /// 자동 재시도 횟수의 상한.
        let maxRetries: Int
        /// 한 번의 요청 벽(초).
        let attemptTimeout: TimeInterval
        /// 재시도까지 합친 벽(초). `RATE_LIMITED` 를 기다린 시간은 세지 않는다.
        let budget: TimeInterval
        /// `409 처리 중` 을 되묻는 횟수의 상한. 챗봇 말고는 0.
        var maxPolls = 0
    }

    /// 한 번 보낸 결과.
    enum Outcome: Equatable {
        /// 응답이 없다 — 끊김·시간 초과·연결 실패. 생성 클라이언트의 `-1`.
        case noResponse(URLError.Code)
        /// 2xx 가 아닌 응답. `code` 는 본문(`ApiError.code`), `retryAfter` 는 헤더(초).
        case http(status: Int, code: String?, retryAfter: Int?)
    }

    /// 지금까지 다시 보낸 내력. `RetryingSession` 이 요청마다 하나 든다.
    struct History: Equatable {
        /// 자동으로 다시 보낸 횟수.
        var retries = 0
        /// 500 으로 한 번 다시 보냈다 — 500 은 한 번뿐이다.
        var retried500 = false
        /// 한도(429)로 한 번 기다렸다 — 한 번뿐이다.
        var waitedForLimit = false
        /// 앞선 시도가 **서버에 닿았을 수 있다**(응답만 잃었거나 5xx). 뒤이은 DELETE 의 404 를 읽는 데 쓴다.
        var mayHaveReached = false
        /// `409 처리 중` 을 받고 같은 키로 다시 물은 횟수. `retries` 와 따로 센다 — 새 처리를 일으키지 않는다.
        var polls = 0
    }

    enum Step: Equatable {
        /// `after` 초 뒤 같은 요청을 다시 보낸다. `forLimit` 이면 한도가 풀리기를 기다리는 것이다.
        case retry(after: TimeInterval, forLimit: Bool)
        /// 서버가 같은 키를 처리 중이다(409) — `after` 초 뒤 **같은 요청으로 다시 묻는다.** 재시도 횟수에 넣지 않는다.
        case poll(after: TimeInterval)
        /// 그만둔다 — 받은 결과를 그대로 올린다.
        case stop
        /// 다시 보낸 DELETE 가 404 를 받았다 — 앞선 시도가 이미 지운 것이다. 성공으로 올린다.
        case alreadyDone
    }

    // MARK: 창구 → 갈래

    /// - Parameter keyed: 이 요청이 멱등 키를 실었고 **서버가 그 키를 안다**(`RateLimitLedger.serverKnowsLimits`).
    ///   챗봇만 본다 — 다른 창구에는 계약에 키가 없다.
    static func policy(method: String, path: String, keyed: Bool = false) -> Policy {
        let lane = lane(method: method, path: path, keyed: keyed)
        switch lane {
        case .keyedChat:
            return Policy(
                lane: lane, maxRetries: Tuning.chatRetries,
                attemptTimeout: Tuning.chatAttemptTimeout, budget: Tuning.chatBudget,
                maxPolls: Tuning.inProgressMaxPolls
            )
        case .read:
            return Policy(
                lane: lane, maxRetries: Tuning.readRetries,
                attemptTimeout: Tuning.readTimeout, budget: Tuning.readBudget
            )
        case .idempotentWrite:
            return Policy(
                lane: lane, maxRetries: Tuning.idempotentWriteRetries,
                attemptTimeout: Tuning.writeTimeout, budget: Tuning.writeBudget
            )
        case .unsafeWrite:
            return Policy(
                lane: lane, maxRetries: Tuning.unsafeWriteRetries,
                attemptTimeout: Tuning.writeTimeout, budget: Tuning.writeBudget
            )
        case .never:
            let guide = path.contains("/guide/")
            let timeout = guide ? Tuning.guideTimeout : Tuning.writeTimeout
            return Policy(lane: lane, maxRetries: 0, attemptTimeout: timeout, budget: timeout)
        }
    }

    static func lane(method: String, path: String, keyed: Bool = false) -> Lane {
        let method = method.uppercased()
        if keyed, method == "POST", path.hasSuffix("/guide/chat") {
            return .keyedChat
        }
        if neverRetried(method: method, path: path) {
            return .never
        }
        switch method {
        case "GET", "HEAD":
            return .read
        case "PUT", "DELETE":
            return unawaited(path: path) ? .unsafeWrite : .idempotentWrite
        default:
            return .unsafeWrite
        }
    }

    /// 어떤 실패에도 자동으로는 다시 보내지 않는 창구. 실패를 그대로 올리고 사람이 다시 누르게 둔다.
    private static func neverRetried(method: String, path: String) -> Bool {
        // 챗봇은 멱등이 아니다(토큰·이력·장바구니) — 키 없이 다시 보내면 두 번 처리된다(키를 실었고 서버가
        // 키를 알면 위에서 `keyedChat` 으로 빠졌다). 길찾기·마법사는
        // 부를 때마다 돈이 나갈 수 있다. 갱신은 리프레시 토큰이 일회용이라 응답만 잃은 요청을 다시 보내면
        // 재사용으로 판정된다.
        let posts = ["/guide/chat", "/guide/plan", "/navigation/next-leg", "/auth/refresh", "/auth/sign-out"]
        if posts.contains(where: path.hasSuffix) {
            return true
        }
        // 탈퇴 — 응답만 잃고 다시 보내면 지워진 계정의 토큰으로 나가 401 이 온다. 탈퇴는 됐는데 「세션이
        // 끊겼다」 로 보이고 탈퇴 기록(분석)이 빠진다.
        // (`…/reviews/me` 는 내 리뷰 지우기다 — 그것은 화면이 기다리는 멱등 쓰기.)
        return method == "DELETE" && path.hasSuffix("/me") && !path.hasSuffix("/reviews/me")
    }

    /// **화면이 응답을 기다리지 않고 다음 누름을 받는 쓰기.** PUT·DELETE 라 두 번 닿아도 같지만, 늦게 도착한
    /// 재시도가 **그 뒤에 보낸 쓰기를 덮는다** — 찜을 풀었다(응답 잃음) 곧바로 다시 찜하면, 0.8초 뒤 다시 나간
    /// 「풀기」 가 「찜」 을 지운다(화면은 찜, 서버는 없음. 2026-10-08 실기 재현). 그래서 닿지 않은 것이 확실할
    /// 때만 다시 보낸다.
    ///
    /// 리뷰·코스의 저장과 지우기는 여기 없다 — 화면이 끝날 때까지 잠그고 기다린다.
    private static func unawaited(path: String) -> Bool {
        // 찜 풀기 — 하트는 누르는 즉시 바뀌고 요청은 뒤에서 나간다(`LikeStore.toggle`).
        if path.contains("/favorites/") {
            return true
        }
        // 장바구니 빼기 — 뺀 직후 다시 담을 수 있다(`CartStore.remove`).
        if path.contains("/cart/items/") {
            return true
        }
        // 방문 체크 — 스탬프가 찍히면 기다리지 않고 보낸다(`RouteEditorView.markVisited`).
        if path.hasSuffix("/visit") {
            return true
        }
        // 여행 시작·종료 — 단추가 먼저 바뀌어 곧바로 반대로 누를 수 있다(`RouteStore.setRunning`).
        if path.hasSuffix("/progress") {
            return true
        }
        // 마켓의 좋아요 풀기 — 찜과 같은 꼴(지금 앱은 부르지 않는다).
        if path.hasSuffix("/likes") {
            return true
        }
        // 닉네임 — 닿지 않은 것이 확실할 때만(계획 §7-7).
        return path.hasSuffix("/me/nickname")
    }

    // MARK: 결과 → 다음

    /// - Parameters:
    ///   - elapsed: 첫 요청을 보낸 뒤 흐른 시간(초). 한도를 기다린 시간은 뺀 값.
    ///   - jitter: -1...1. 간격에 `Tuning.jitter` 만큼 곱해 더한다. 시험은 0 을 준다.
    ///   - keyAge: 이 멱등 키를 **처음 보낸 때부터** 흐른 시간(초). 「다시 시도」 로 새 요청이 나가도 이어서 센다
    ///     (`IdempotencyClock`). 모르면 `elapsed` 로 본다.
    static func next(
        policy: Policy, method: String, history: History, outcome: Outcome,
        elapsed: TimeInterval, jitter: Double = 0, keyAge: TimeInterval? = nil
    ) -> Step {
        if case let .http(status, _, _) = outcome, alreadyDone(method: method, status: status, history: history) {
            return .alreadyDone
        }
        let nature = kind(of: outcome)
        // 처리 중 — 「자동 한 번」 을 이미 썼어도 묻는다. 응답을 잃고 다시 보낸 것이 바로 이 답을 받는다.
        if nature == .inProgress {
            let wait = Tuning.inProgressPollSeconds
            guard policy.lane == .keyedChat, history.polls < policy.maxPolls, elapsed + wait < policy.budget,
                  // 서버의 「처리 중 1 분」 경계를 넘겨 묻지 않는다 — 넘기면 서버가 새로 처리한다.
                  (keyAge ?? elapsed) + wait < Tuning.inProgressDeadline
            else { return .stop }
            return .poll(after: wait)
        }
        guard policy.lane != .never, history.retries < policy.maxRetries else { return .stop }

        switch nature {
        case .rateLimited:
            return limitStep(policy: policy, outcome: outcome, history: history, elapsed: elapsed, jitter: jitter)
        case .unreached:
            return backoff(policy: policy, history: history, elapsed: elapsed, jitter: jitter)
        case .lost, .unavailable:
            guard policy.lane != .unsafeWrite else { return .stop }
            return backoff(policy: policy, history: history, elapsed: elapsed, jitter: jitter)
        case .serverError:
            guard policy.lane != .unsafeWrite, !history.retried500 else { return .stop }
            return backoff(policy: policy, history: history, elapsed: elapsed, jitter: jitter)
        case .final, .inProgress:
            return .stop
        }
    }

    /// 이번 결과를 내력에 적는다. `step` 은 방금 `next` 가 돌려준 것.
    static func record(_ step: Step, outcome: Outcome, in history: History) -> History {
        var history = history
        if case .poll = step {
            history.polls += 1
            history.mayHaveReached = true
            return history
        }
        guard case let .retry(_, forLimit) = step else { return history }
        history.retries += 1
        if forLimit {
            history.waitedForLimit = true
        }
        switch kind(of: outcome) {
        case .serverError:
            history.retried500 = true
            history.mayHaveReached = true
        case .lost, .unavailable:
            history.mayHaveReached = true
        case .unreached, .rateLimited, .final, .inProgress:
            break
        }
        return history
    }

    /// 기기가 오프라인이라 실패했나. 화면이 「인터넷 연결을 확인해 주세요」 를 말하는 데 쓴다.
    static func isOffline(_ code: URLError.Code) -> Bool {
        [.notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff].contains(code)
    }

    // MARK: 속

    /// 결과의 성격.
    enum Kind: Equatable {
        /// 서버에 **닿지 않은 것이 확실하다** — 연결 거부·호스트 못 찾음. 어떤 쓰기든 다시 보내도 된다.
        case unreached
        /// 닿았는지 모른다 — 보낸 뒤 끊겼거나 시간을 넘겼다.
        case lost
        /// 502·503·504 — 잠시 뒤면 될 수 있다.
        case unavailable
        /// 500.
        case serverError
        /// 분당 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). 처리되지 않은 것이 확실하다.
        case rateLimited
        /// `409 IDEMPOTENCY_IN_PROGRESS` — 같은 멱등 키의 턴을 서버가 아직 처리 중이다. 다른 409 는 `final`.
        case inProgress
        /// 다시 보내도 같다 — 4xx, 유료 한도, 취소, 오프라인.
        case final
    }

    /// 계약의 오류 `code` — 멱등 키.
    static let inProgressCode = "IDEMPOTENCY_IN_PROGRESS"
    static let keyReusedCode = "IDEMPOTENCY_KEY_REUSED"

    static func kind(of outcome: Outcome) -> Kind {
        switch outcome {
        case let .noResponse(code):
            switch code {
            case .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed:
                .unreached
            case .timedOut, .networkConnectionLost, .secureConnectionFailed, .badServerResponse,
                 .cannotParseResponse:
                .lost
            default:
                // 취소는 화면을 떠난 것이고, 오프라인은 1·2·4초 안에 돌아오지 않는다 — 횟수만 쓴다.
                .final
            }
        case let .http(status, code, _):
            switch status {
            case 502, 503, 504: .unavailable
            case 500: .serverError
            // 유료 한도(`GUIDE_LIMIT_REACHED`·`NAVIGATION_LIMIT_REACHED`)는 기다려도 안 풀린다 — 화면이 안내한다.
            case 429: code == nil || code == "RATE_LIMITED" ? .rateLimited : .final
            case 409: code == inProgressCode ? .inProgress : .final
            default: .final
            }
        }
    }

    private static func limitStep(
        policy: Policy, outcome: Outcome, history: History, elapsed: TimeInterval, jitter: Double
    ) -> Step {
        guard !history.waitedForLimit, case let .http(_, _, retryAfter) = outcome else { return .stop }
        let wait = retryAfter.map(TimeInterval.init) ?? Tuning.rateLimitDefaultWait
        // 기다린 시간은 벽에서 빼지만, 그 전에 이미 벽을 거의 다 썼으면 기다려 봐야 보낼 시간이 없다.
        guard wait <= Tuning.rateLimitMaxWait, policy.budget - elapsed >= Tuning.minAttemptSeconds else {
            return .stop
        }
        return .retry(after: max(0, wait) + Tuning.rateLimitJitter * min(1, abs(jitter)), forLimit: true)
    }

    private static func backoff(
        policy: Policy, history: History, elapsed: TimeInterval, jitter: Double
    ) -> Step {
        let base = Tuning.backoff[min(history.retries, Tuning.backoff.count - 1)]
        let delay = base * (1 + Tuning.jitter * max(-1, min(1, jitter)))
        // 쉬고 나면 벽을 넘는다 — 보내 봐야 기다릴 시간이 없다.
        guard elapsed + delay < policy.budget else { return .stop }
        return .retry(after: delay, forLimit: false)
    }

    /// 응답만 잃은 DELETE 를 다시 보내면 서버는 「그런 것 없다」(404)고 답한다 — 지워진 것이다.
    private static func alreadyDone(method: String, status: Int, history: History) -> Bool {
        method.uppercased() == "DELETE" && status == 404 && history.mayHaveReached
    }
}
