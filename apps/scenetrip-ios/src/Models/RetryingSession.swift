import Foundation
import os
import SceneApiClient

/// 실패한 요청을 규칙(`RetryRules`)대로 **다시 보내는 세션** (MZ2AZ-366, 계획 `app-retry.md` §3).
///
/// 생성 클라이언트는 빌더에게 세션을 묻는다(`createURLSession()`). 우리 빌더가 진짜 세션 대신 이것을 내준다 —
/// 생성 코드에게는 dataTask 하나로 보이고, 안에서 몇 번을 보내든 completion 은 **한 번**만 불린다.
///
/// ## 왜 빌더의 `execute` 가 아니라 세션인가
///
/// `execute` 를 되풀이하면(401 재시도가 그렇게 한다) **쉬는 중의 취소를 알 길이 없다.** 생성 코드의 취소는
/// 떠 있는 dataTask 를 끊을 뿐이라, 백오프로 쉬는 동안에는 끊을 것이 없어 화면을 떠난 뒤에 요청이 나간다.
/// 여기서는 돌려준 `RetryTask` 가 곧 그 dataTask 라 `cancel()` 이 언제든 닿는다.
///
/// ## 401 갱신과의 순서
///
/// 재시도가 안쪽, 401 이 바깥이다. 401 은 규칙에 없어 그대로 올라가고, 빌더가 토큰을 갱신해 다시 보내면 그
/// 요청은 새 횟수로 여기를 다시 지난다. 갱신 요청 자신(`POST /auth/refresh`)은 다시 보내지 않는다 — 리프레시
/// 토큰이 일회용이다.
final class RetryingSession: URLSessionProtocol, @unchecked Sendable {
    /// 시계·난수·쉬기. 시험이 가짜로 바꿔 끼운다.
    struct Environment {
        var now: () -> Date = Date.init
        /// -1...1.
        var jitter: () -> Double = { Double.random(in: -1 ... 1) }
        /// `seconds` 뒤에 일을 돌린다. 돌려주는 것을 부르면 그 일을 취소한다.
        var schedule: (_ seconds: TimeInterval, _ work: @escaping () -> Void) -> () -> Void = { seconds, work in
            let item = DispatchWorkItem(block: work)
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + seconds, execute: item)
            return { item.cancel() }
        }

        /// 실패 넣기 뒷문(`NetFault`). 개발 빌드가 아니면 언제나 nil.
        var fault: (URLRequest) -> NetFault.Injection? = NetFault.take(for:)
    }

    static let log = Logger(subsystem: "com.mz2az.scenetrip", category: "retry")
    private static let ids = NSLock()
    private static var lastId = 1_000_000

    let inner: URLSessionProtocol
    let environment: Environment
    let ledger: RateLimitLedger

    init(
        inner: URLSessionProtocol,
        environment: Environment = Environment(),
        ledger: RateLimitLedger = .shared
    ) {
        self.inner = inner
        self.environment = environment
        self.ledger = ledger
    }

    func dataTaskFromProtocol(
        with request: URLRequest,
        completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) -> URLSessionDataTaskProtocol {
        RetryTask(session: self, request: request, completion: completionHandler)
    }

    /// 생성 코드가 dataTask 마다 다른 번호를 기대한다(인증 챌린지 표의 열쇠 — 우리는 쓰지 않는다).
    /// 진짜 세션의 번호(1부터)와 겹치지 않게 멀리서 센다.
    static func nextIdentifier() -> Int {
        ids.lock()
        defer { ids.unlock() }
        lastId += 1
        return lastId
    }
}

/// 생성 코드에게 dataTask 하나로 보이는 것. 안에서 진짜 dataTask 를 한 번 또는 여러 번 띄운다.
final class RetryTask: URLSessionDataTaskProtocol, @unchecked Sendable {
    let taskIdentifier = RetryingSession.nextIdentifier()
    let progress = Progress()

    private let session: RetryingSession
    private let request: URLRequest
    /// 끝나면 놓는다 — 이 클로저가 빌더를 붙들고, 빌더는 `requestTask` 로 이 task 를 붙든다. 진짜 세션이
    /// 끝난 dataTask 의 completion 을 놓는 것과 같은 일이다.
    private var completion: (@Sendable (Data?, URLResponse?, Error?) -> Void)?
    private let policy: RetryRules.Policy
    private let method: String
    private let path: String

    private let lock = NSLock()
    private var history = RetryRules.History()
    private var startedAt = Date()
    /// 한도가 풀리기를 기다린 시간 — 재시도 벽에서 뺀다. 60초를 기다리라 해 놓고 25초 벽으로 끊으면 안 된다.
    private var waitedForLimit: TimeInterval = 0
    private var flying: URLSessionDataTaskProtocol?
    private var cancelSleep: (() -> Void)?
    private var cancelled = false
    private var finished = false

    init(
        session: RetryingSession, request: URLRequest,
        completion: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) {
        self.session = session
        self.request = request
        self.completion = completion
        method = request.httpMethod ?? "GET"
        path = request.url?.path ?? ""
        policy = RetryRules.policy(method: method, path: path)
    }

    func resume() {
        locked { startedAt = session.environment.now() }
        send()
    }

    /// 화면을 떠났다. 떠 있는 요청을 끊고, **쉬는 중이면 다음 요청을 내보내지 않는다.**
    func cancel() {
        let (task, wake): (URLSessionDataTaskProtocol?, (() -> Void)?) = locked {
            cancelled = true
            defer { cancelSleep = nil }
            return (flying, cancelSleep)
        }
        wake?()
        if let task {
            task.cancel() // 그 completion 이 취소 오류를 들고 `handle` 로 온다 — 거기서 끝난다.
        } else {
            finish(nil, nil, URLError(.cancelled))
        }
    }

    // MARK: 보내기

    private func send() {
        guard !locked({ cancelled }) else {
            finish(nil, nil, URLError(.cancelled))
            return
        }
        var attempt = request
        // 남은 벽보다 오래 기다리지 않는다 — 15초짜리 요청이 벽 25초의 20초째에 나가면 5초만 기다린다.
        attempt.timeoutInterval = max(1, min(policy.attemptTimeout, policy.budget - elapsed()))

        switch session.environment.fault(request) {
        case nil:
            fly(attempt) { data, response, error in self.handle(data, response, error) }
        case .lost:
            // 보내기는 한다 — 서버는 처리하고, 앱은 응답을 못 받은 것으로 친다.
            fly(attempt) { _, _, error in
                let cancelled = (error as? URLError)?.code == .cancelled
                self.handle(nil, nil, URLError(cancelled ? .cancelled : .timedOut))
            }
        case .down:
            handle(nil, nil, URLError(.cannotConnectToHost))
        case .offline:
            handle(nil, nil, URLError(.notConnectedToInternet))
        case let .status(status):
            let (data, response) = NetFault.response(status: status, url: request.url)
            handle(data, response, nil)
        }
    }

    private func fly(
        _ attempt: URLRequest, _ handler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) {
        let task = session.inner.dataTaskFromProtocol(with: attempt, completionHandler: handler)
        // `send()` 가 취소를 확인한 뒤 여기까지 오는 사이에 취소가 올 수 있다. 그랬다면 **내보내지 않는다** —
        // `cancel()` 이 떠 있는 것이 없다고 보고 이미 끝을 알렸다.
        let late = locked { () -> Bool in
            if !cancelled {
                flying = task
            }
            return cancelled
        }
        guard !late else {
            task.cancel() // 만들기만 하고 띄우지 않은 것 — 그냥 두면 안쪽 세션에 매달려 남는다.
            return
        }
        task.resume()
    }

    // MARK: 받기

    private func handle(_ data: Data?, _ response: URLResponse?, _ error: Error?) {
        locked { flying = nil }
        let now = session.environment.now()
        let outcome: RetryRules.Outcome
        if let error {
            outcome = .noResponse((error as? URLError)?.code ?? .unknown)
        } else if let http = response as? HTTPURLResponse {
            session.ledger.record(path: path, response: http, now: now)
            guard !(200 ..< 300).contains(http.statusCode) else {
                finish(data, response, nil)
                return
            }
            outcome = .http(
                status: http.statusCode,
                code: AuthRules.apiCode(from: data),
                retryAfter: RateLimitLedger.retryAfter(in: http)
            )
        } else {
            finish(data, response, error) // HTTP 가 아닌 응답 — 생성 코드가 `-2` 로 올린다.
            return
        }

        let step = locked { () -> RetryRules.Step in
            guard !cancelled else { return .stop }
            let step = RetryRules.next(
                policy: policy, method: method, history: history, outcome: outcome,
                elapsed: now.timeIntervalSince(startedAt) - waitedForLimit,
                jitter: session.environment.jitter()
            )
            history = RetryRules.record(step, outcome: outcome, in: history)
            if case let .retry(after, forLimit) = step, forLimit {
                waitedForLimit += after
            }
            return step
        }
        switch step {
        case .stop:
            finish(data, response, error)
        case .alreadyDone:
            log("이미 지워졌다 — 성공으로 올린다")
            let done = request.url.flatMap {
                HTTPURLResponse(url: $0, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)
            }
            finish(Data(), done, nil)
        case let .retry(after, forLimit):
            sleep(after, forLimit: forLimit, outcome: outcome, now: now)
        }
    }

    private func sleep(_ seconds: TimeInterval, forLimit: Bool, outcome: RetryRules.Outcome, now: Date) {
        let count = locked { history.retries }
        log("\(describe(outcome)) → \(String(format: "%.1f", seconds))초 뒤 다시 (\(count)/\(policy.maxRetries))")
        let announces = forLimit && seconds > RetryRules.Tuning.rateLimitQuietSeconds
        if announces {
            session.ledger.setWaiting(until: now.addingTimeInterval(seconds))
        }
        // 쉬는 동안 이 task 를 붙드는 것은 이 타이머뿐이다 — 약하게 잡으면 깨어났을 때 보낼 것이 없다.
        let wake = session.environment.schedule(seconds) {
            if announces {
                self.session.ledger.setWaiting(until: nil)
            }
            self.locked { self.cancelSleep = nil }
            self.send()
        }
        let late = locked { () -> Bool in
            if !cancelled {
                cancelSleep = wake
            }
            return cancelled
        }
        if late {
            wake() // 쉬러 가는 사이에 취소가 왔다 — `cancel()` 이 이미 끝을 알렸다.
            if announces {
                session.ledger.setWaiting(until: nil)
            }
        }
    }

    private func finish(_ data: Data?, _ response: URLResponse?, _ error: Error?) {
        let handler = locked { () -> (@Sendable (Data?, URLResponse?, Error?) -> Void)? in
            defer {
                finished = true
                completion = nil
            }
            return finished ? nil : completion
        }
        guard let handler else { return }
        if locked({ history.retries }) > 0 {
            let status = (response as? HTTPURLResponse)?.statusCode
            log("끝 — \(status.map(String.init) ?? "응답 없음"), 다시 보낸 횟수 \(locked { history.retries })")
        }
        handler(data, response, error)
    }

    // MARK: 속

    private func elapsed() -> TimeInterval {
        let now = session.environment.now()
        return locked { now.timeIntervalSince(startedAt) - waitedForLimit }
    }

    private func describe(_ outcome: RetryRules.Outcome) -> String {
        switch outcome {
        case let .noResponse(code): "응답 없음(\(code.rawValue))"
        case let .http(status, code, retryAfter):
            "\(status)" + (code.map { " \($0)" } ?? "") + (retryAfter.map { " Retry-After \($0)" } ?? "")
        }
    }

    /// 경로만 적는다 — 쿼리에는 검색어가 실린다.
    private func log(_ message: String) {
        let line = "\(method) \(path) \(message)"
        RetryingSession.log.notice("\(line, privacy: .public)")
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
