import Foundation

/// 서버가 응답 헤더로 알려 준 **남은 양**을 적어 두는 장부 (MZ2AZ-366, 계약 「요청 한도」).
///
/// 생성 클라이언트의 `async` 함수는 본문만 돌려준다 — 성공 응답의 `RateLimit-*` 는 화면까지 오지 않는다.
/// 그래서 응답이 지나가는 자리(`RetryingSession`)에서 받아 여기 둔다. **지금은 적어 두기만 한다** — 「N번 더
/// 물어볼 수 있어요」 를 보이는 것은 뒤의 일이다(계획 §5-4).
///
/// 숫자를 앱에 박지 않는다. 한도는 요금제 설정이라 바뀐다 — 서버가 준 것만 읽는다.
final class RateLimitLedger: @unchecked Sendable {
    static let shared = RateLimitLedger()

    /// 한도가 따로 도는 묶음. 챗봇·길찾기는 자기 한도의 헤더를 싣고, 나머지는 분당 한도의 것을 싣는다.
    enum Bucket: String, Equatable {
        case guide
        case navigation
        case general
    }

    struct Entry: Equatable {
        /// 그 요청에 걸린 한도 중 가장 빠듯한 것의 크기.
        let limit: Int
        /// 그 한도의 남은 횟수.
        let remaining: Int
        /// 다시 차는 때.
        let resetsAt: Date
    }

    private let lock = NSLock()
    private var entries: [Bucket: Entry] = [:]
    private var waiting: Date?

    /// 이번 실행에서 `RateLimit-*` 를 한 번이라도 봤나 — **이 서버가 요청 한도를 안다**는 표시다.
    /// 챗봇 멱등 키는 같은 서버 변경으로 들어왔으므로, 그 재시도를 켜도 되는지의 근거가 된다(계획 §4-5).
    var serverKnowsLimits: Bool {
        locked { !entries.isEmpty }
    }

    /// 분당 한도가 풀리기를 **오래**(`RetryRules.Tuning.rateLimitQuietSeconds` 넘게) 기다리는 중이면 그 끝.
    /// 화면이 「요청이 많아 잠시 기다리는 중」 을 말할 재료다.
    var waitingUntil: Date? {
        locked { waiting }
    }

    func entry(for bucket: Bucket) -> Entry? {
        locked { entries[bucket] }
    }

    /// 응답 하나를 읽는다. 헤더가 없으면(옛 서버, 멱등 키로 되돌려 준 답) **적어 둔 것을 지우지 않는다.**
    func record(path: String, response: HTTPURLResponse, now: Date = Date()) {
        guard let entry = Self.entry(from: response, now: now) else { return }
        locked { entries[Self.bucket(for: path)] = entry }
    }

    func setWaiting(until date: Date?) {
        locked { waiting = date }
    }

    static func bucket(for path: String) -> Bucket {
        if path.hasSuffix("/guide/chat") {
            return .guide
        }
        if path.hasSuffix("/navigation/next-leg") {
            return .navigation
        }
        return .general
    }

    /// 헤더 셋이 다 있고 숫자일 때만. 이름의 대소문자는 가리지 않는다(HTTP/2 는 소문자로 온다).
    static func entry(from response: HTTPURLResponse, now: Date) -> Entry? {
        guard let limit = int("RateLimit-Limit", in: response),
              let remaining = int("RateLimit-Remaining", in: response),
              let reset = int("RateLimit-Reset", in: response)
        else { return nil }
        return Entry(limit: limit, remaining: remaining, resetsAt: now.addingTimeInterval(TimeInterval(reset)))
    }

    /// `Retry-After` — 초. 숫자가 아니면(HTTP 날짜 꼴 등) 모르는 것으로 본다.
    static func retryAfter(in response: HTTPURLResponse) -> Int? {
        int("Retry-After", in: response)
    }

    private static func int(_ name: String, in response: HTTPURLResponse) -> Int? {
        guard let text = response.value(forHTTPHeaderField: name)?.trimmingCharacters(in: .whitespaces),
              let value = Int(text), value >= 0
        else { return nil }
        return value
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
