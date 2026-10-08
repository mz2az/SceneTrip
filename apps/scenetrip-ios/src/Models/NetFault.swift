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
        text.split(separator: ",").compactMap { piece in
            var parts = piece.split(separator: ":").map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count >= 2, !parts[0].isEmpty else { return nil }
            let target = parts.removeFirst().split(separator: "@", maxSplits: 1).map(String.init)
            guard let match = target.last, !match.isEmpty else { return nil }
            let method = target.count == 2 ? target[0].uppercased() : nil
            let injection: Injection
            switch parts.removeFirst() {
            case "status":
                guard let status = parts.first.flatMap(Int.init) else { return nil }
                parts.removeFirst()
                injection = .status(status)
            case "down": injection = .down
            case "offline": injection = .offline
            case "lost": injection = .lost
            default: return nil
            }
            let remaining: Int? = switch parts.first {
            case nil: 1
            case "all": nil
            case let count?: Int(count) ?? 1
            }
            return Rule(method: method, match: match, injection: injection, remaining: remaining)
        }
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
        let body = #"{"code":"\#(limited ? "RATE_LIMITED" : "NET_FAULT")","message":"netFault"}"#
        var headers = ["Content-Type": "application/json"]
        if limited {
            headers["Retry-After"] = "3"
        }
        let response = url.flatMap {
            HTTPURLResponse(url: $0, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
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
            private var rules = NetFault.parse(UserDefaults.standard.string(forKey: "netFault") ?? "")

            func take(for request: URLRequest) -> Injection? {
                lock.lock()
                defer { lock.unlock() }
                return NetFault.take(for: request, from: &rules)
            }
        }
    #endif
}
