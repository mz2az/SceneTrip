import Foundation

/// 확인용 뒷문 — **요청에 실패를 넣는다** (MZ2AZ-366). `simctl launch … -netFault "contents:status:503:2"`.
///
/// 재시도는 실패가 나야 보인다. 「보냈는데 응답만 잃음」 이나 임의의 상태 코드는 서버를 끄는 것으로는 못
/// 만든다 — 그래서 요청이 나가는 자리(`RetryingSession`)에서 지어낸다. 지어낸 응답은 서버에 닿지 않으므로
/// 유료 호출이 없다.
///
/// **개발 빌드의 시뮬레이터에서만 동작한다**(`take`). 기기에 올라가는 빌드에서는 실행 인자를 읽는 코드가
/// 컴파일되지 않는다 — 스토어·테스트플라이트 빌드는 시뮬레이터 빌드가 아니다.
///
/// ## 적는 법
///
/// `경로에 든 글자:종류[:값][:횟수]`, 여럿이면 쉼표로.
///
/// | 종류 | 뜻 |
/// | --- | --- |
/// | `status:503` | 보내지 않고 그 상태 코드를 받은 척한다. 429 는 `RATE_LIMITED` 와 `Retry-After: 3` 을 싣는다 |
/// | `down` | 보내지 않고 「연결 거부」 — 서버에 닿지 않은 것이 확실한 실패 |
/// | `offline` | 보내지 않고 「인터넷 연결 없음」 |
/// | `lost` | **보내고** 응답을 버린다 — 닿았는지 모르는 실패(시간 초과) |
/// | `cut:2` | **보내고 2초 뒤에 끊는다** — 서버는 아직 처리 중인데 앱은 연결을 잃은 것. 같은 멱등 키로 다시 보내면 `409 처리 중` 을 받는다(챗봇). 그 전에 답이 와도 버린다 |
///
/// `status:409` 는 `IDEMPOTENCY_IN_PROGRESS`, `status:422` 는 `IDEMPOTENCY_KEY_REUSED` 를 본문에 싣는다 —
/// 챗봇의 되묻기와 「키 재사용」 갈래를 서버(와 모델) 없이 본다.
///
/// ## 응답을 통째로 지어내기 — 한도 화면용 (MZ2AZ-366 C)
///
/// 상태 코드 뒤에 `;이름=값` 을 붙이면 본문의 `code` 와 헤더를 정한다. **2xx 도 지어낼 수 있다** — 챗봇 창구
/// (`guide/chat`)에는 가짜 답이 실린다. 어느 것도 서버에 닿지 않는다(모델 호출 없음).
///
/// | 이름 | 뜻 |
/// | --- | --- |
/// | `code=GUIDE_LIMIT_REACHED` | 본문의 `code` |
/// | `retry=20` | `Retry-After`(초). `code` 를 적었는데 이것이 없으면 헤더도 없다 |
/// | `limit=15` · `left=3` · `reset=1200` | `RateLimit-Limit` · `-Remaining` · `-Reset`. 셋이 다 있어야 앱이 읽는다 |
/// | `say=…` | 가짜 답의 글(2xx, 챗봇). `:` `,` `;` 는 못 쓴다 |
///
/// 예: `guide/chat:status:429;code=GUIDE_LIMIT_REACHED;retry=20;limit=15;left=0;reset=20:1` ·
/// `guide/chat:status:200;limit=15;left=3;reset=1200:all`.
///
/// **적은 것 중 하나라도 못 읽으면 아무 요청도 내보내지 않는다**(전부 「연결 거부」) — 글자가 깨진 뒷문이
/// 조용히 꺼져 유료 요청이 진짜로 나간 일이 있었다(계획 §10 끝).
///
/// 앞에 `메서드@` 를 붙이면 그 메서드의 요청에만 넣는다 — `DELETE@reviews/me:lost` 는 지우기의 응답만 잃게
/// 한다(같은 경로의 조회가 횟수를 먼저 쓰지 않게).
///
/// 횟수는 맞는 요청 몇 번에 넣을지(기본 1). `all` 이면 끝없이. 예: `places:status:503:2` 는 `/places` 가
/// 든 요청 처음 두 번을 503 으로 만들고 세 번째부터는 진짜로 보낸다.
enum NetFault {
    enum Injection: Equatable {
        case status(Int)
        case down
        case offline
        case lost
        /// 보낸 뒤 이 초에 끊는다.
        case cut(TimeInterval)
        /// 보내지 않고 이 모양의 응답을 받은 척한다 — 상태 코드·`code`·헤더·(2xx 면) 가짜 답.
        case shaped(Shape)
    }

    /// 지어낼 응답의 모양(`status:429;code=…;retry=…`).
    struct Shape: Equatable {
        var status: Int
        var code: String?
        var retryAfter: String?
        var limit: String?
        var left: String?
        var reset: String?
        var say: String?

        /// `429;code=GUIDE_LIMIT_REACHED;retry=20`. 모르는 이름이 있으면 nil — 오타를 조용히 넘기지 않는다.
        init?(_ text: String) {
            var pieces = text.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
            guard let status = Int(pieces.removeFirst()) else { return nil }
            self.status = status
            for piece in pieces {
                let pair = piece.split(separator: "=", maxSplits: 1).map(String.init)
                guard pair.count == 2 else { return nil }
                switch pair[0] {
                case "code": code = pair[1]
                case "retry": retryAfter = pair[1]
                case "limit": limit = pair[1]
                case "left": left = pair[1]
                case "reset": reset = pair[1]
                case "say": say = pair[1]
                default: return nil
                }
            }
        }

        /// 덧붙인 것이 없다 — 전부터 있던 `status:503` 꼴이다.
        var isPlain: Bool {
            self == Shape(String(status))
        }
    }

    struct Rule: Equatable {
        /// 메서드(대문자). nil 이면 가리지 않는다.
        var method: String?
        let match: String
        let injection: Injection
        /// 남은 횟수. nil 이면 끝없이.
        var remaining: Int?
    }

    /// 실행 인자의 글자를 규칙으로. 모르는 꼴은 버린다.
    static func parse(_ text: String) -> [Rule] {
        text.split(separator: ",").compactMap(rule)
    }

    /// 적은 것을 **전부** 읽었을 때만 규칙을 준다. 하나라도 못 읽으면 nil — 부르는 쪽이 요청을 전부 막는다.
    static func parseStrictly(_ text: String) -> [Rule]? {
        let pieces = text.split(separator: ",")
        let rules = pieces.compactMap(rule)
        return rules.count == pieces.count ? rules : nil
    }

    private static func rule(_ piece: Substring) -> Rule? {
        var parts = piece.split(separator: ":").map { $0.trimmingCharacters(in: .whitespaces) }
        guard parts.count >= 2, !parts[0].isEmpty else { return nil }
        let target = parts.removeFirst().split(separator: "@", maxSplits: 1).map(String.init)
        guard let match = target.last, !match.isEmpty else { return nil }
        let method = target.count == 2 ? target[0].uppercased() : nil
        let injection: Injection
        switch parts.removeFirst() {
        case "status":
            guard let shape = parts.first.flatMap(Shape.init) else { return nil }
            parts.removeFirst()
            injection = shape.isPlain ? .status(shape.status) : .shaped(shape)
        case "down": injection = .down
        case "offline": injection = .offline
        case "lost": injection = .lost
        case "cut":
            guard let seconds = parts.first.flatMap(TimeInterval.init), seconds >= 0 else { return nil }
            parts.removeFirst()
            injection = .cut(seconds)
        default: return nil
        }
        let remaining: Int? = switch parts.first {
        case nil: 1
        case "all": nil
        case let count?: Int(count) ?? 1
        }
        return Rule(method: method, match: match, injection: injection, remaining: remaining)
    }

    /// 이 요청에 넣을 실패. 넣으면 그 규칙의 횟수가 하나 준다.
    static func take(for request: URLRequest, from rules: inout [Rule]) -> Injection? {
        let path = request.url?.path ?? ""
        let method = (request.httpMethod ?? "GET").uppercased()
        guard let index = rules.firstIndex(where: {
            path.contains($0.match) && $0.remaining != 0 && ($0.method == nil || $0.method == method)
        }) else { return nil }
        if let remaining = rules[index].remaining {
            rules[index].remaining = remaining - 1
        }
        return rules[index].injection
    }

    /// 지어낸 응답. 본문은 우리 오류 모양(`ApiError`)이라 화면이 진짜와 같게 읽는다.
    static func response(status: Int, url: URL?) -> (Data, URLResponse?) {
        let limited = status == 429
        let code = switch status {
        case 429: "RATE_LIMITED"
        case 409: RetryRules.inProgressCode
        case 422: RetryRules.keyReusedCode
        default: "NET_FAULT"
        }
        let body = #"{"code":"\#(code)","message":"netFault"}"#
        var headers = ["Content-Type": "application/json"]
        if limited {
            headers["Retry-After"] = "3"
        }
        let response = url.flatMap {
            HTTPURLResponse(url: $0, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
        }
        return (Data(body.utf8), response)
    }

    /// 모양대로 지어낸 응답. 2xx 면 챗봇 답 모양(`GuideChatReply`)의 본문을 싣는다 — 다른 창구의 2xx 는 본문이
    /// 맞지 않아 풀이에서 실패한다(챗봇 한도 화면을 보려고 만든 것이다).
    static func response(_ shape: Shape, url: URL?) -> (Data, URLResponse?) {
        let success = (200 ..< 300).contains(shape.status)
        let body: String
        if success {
            let reply = (shape.say ?? "netFault reply").replacingOccurrences(of: "\"", with: "'")
            body = #"{"reply":"\#(reply)","toolsUsed":[],"places":[],"tookSeconds":0.1,"effects":[],"ui":[]}"#
        } else {
            body = #"{"code":"\#(shape.code ?? "NET_FAULT")","message":"netFault"}"#
        }
        var headers = ["Content-Type": "application/json"]
        headers["Retry-After"] = shape.retryAfter
        headers["RateLimit-Limit"] = shape.limit
        headers["RateLimit-Remaining"] = shape.left
        headers["RateLimit-Reset"] = shape.reset
        let response = url.flatMap {
            HTTPURLResponse(url: $0, statusCode: shape.status, httpVersion: "HTTP/1.1", headerFields: headers)
        }
        return (Data(body.utf8), response)
    }

    /// 실행 인자 `-netFault` 를 읽어 이 요청에 넣을 실패를 준다. **개발 빌드의 시뮬레이터가 아니면 언제나 nil.**
    static func take(for request: URLRequest) -> Injection? {
        #if DEBUG && targetEnvironment(simulator)
            Launch.shared.take(for: request)
        #else
            nil
        #endif
    }

    #if DEBUG && targetEnvironment(simulator)
        private final class Launch: @unchecked Sendable {
            static let shared = Launch()
            private let lock = NSLock()
            private var rules: [Rule]
            /// 적은 것을 못 읽었다 — 전부 막는다.
            private let broken: Bool

            init() {
                let text = UserDefaults.standard.string(forKey: "netFault") ?? ""
                let parsed = NetFault.parseStrictly(text)
                rules = parsed ?? []
                broken = parsed == nil
                if broken {
                    RetryingSession.log.fault("뒷문 -netFault 를 읽지 못했다 — 요청을 전부 막는다: \(text, privacy: .public)")
                } else if !text.isEmpty {
                    RetryingSession.log.notice("뒷문 -netFault: \(text, privacy: .public)")
                }
            }

            func take(for request: URLRequest) -> Injection? {
                lock.lock()
                defer { lock.unlock() }
                return broken ? .down : NetFault.take(for: request, from: &rules)
            }
        }
    #endif
}
