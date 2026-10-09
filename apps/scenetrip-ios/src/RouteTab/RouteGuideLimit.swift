import Foundation

/// 가이드 챗봇의 **한도 안내와 남은 양** (MZ2AZ-366 C, 계획 `app-retry.md` §5-2·§5-4·§11).
///
/// 서버는 챗봇을 1 시간 창과 하루 창으로 센다. 넘으면 `429 GUIDE_LIMIT_REACHED` — **두 창의 `code` 가 같다.**
/// 가르는 것은 `Retry-After`(그 창이 끝날 때까지의 초)의 크기뿐이다. 그래서 「어느 창인가」 를 묻지 않고
/// 「언제 풀리나」 로 말한다 — 1 시간 안에 풀리면 몇 분 뒤인지, 그보다 멀면 내일.
///
/// 숫자(한도 크기)는 앱에 없다. 서버가 헤더로 준 것만 읽는다 — 요금제 설정이라 바뀐다.
///
/// 전부 값만 보는 순수 함수다. 시계는 밖에서 넣는다.
///
/// **시각은 벽시계가 아니라 연속 시계의 초다**(`uptime`). 기기 시계를 돌려도(설정·시간대·자동 맞춤) 잠금이
/// 늘거나 줄지 않는다. 기기가 잠든 시간은 센다.
enum GuideLimit {
    static let code = "GUIDE_LIMIT_REACHED"

    /// 연속 시계의 지금(초). 부팅 뒤 흐른 시간이고 잠든 동안에도 간다 — 벽시계와 무관하다.
    static func uptime() -> TimeInterval {
        TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) / 1_000_000_000
    }

    enum Tuning {
        /// `Retry-After`·`RateLimit-Reset` 이 이 초 이하면 「N분 뒤」, 넘으면 「내일」·「오늘」.
        /// 1 시간 창은 길어야 3,600초 뒤에 풀리고, 그보다 긴 것은 하루 창뿐이다. 하루 창이 자정 30 분 전에
        /// 걸려도 「30분 뒤」 는 참이다.
        static let shortWindowSeconds = 3600
        /// 남은 양 줄은 남은 수가 한도 크기의 이 몫 이하일 때만 보인다 — 넉넉할 때 숫자를 보이면 쓰기를 망설인다.
        static let lowShare = 0.2
        /// 한도 안내에 「AI 로 짜기」(일정짜기 마법사) 안내를 붙일지. **끄려면 여기 하나.**
        ///
        /// 티켓은 「마법사는 비용이 없다」 는 전제로 안내하라 했는데, 지금 에이전트 설정에서는 마법사도 모델을
        /// 부른다(계획 §1-4 #11, §7-6 — 서버·에이전트 담당의 답을 기다리는 중). 문구는 무료를 약속하지 않는다.
        static let plannerHint = true
    }

    enum Window: Equatable {
        /// 한 시간 안에 풀린다.
        case short
        /// 그보다 멀다.
        case day
        /// 서버가 언제 풀리는지 말하지 않았다(`Retry-After` 없음) — 「내일」 이라고 지어내지 않는다.
        case unknown
    }

    static func window(seconds: Int?) -> Window {
        guard let seconds else { return .unknown }
        return seconds <= Tuning.shortWindowSeconds ? .short : .day
    }

    /// 남은 초를 분으로 — 올림, 적어도 1. 「0분 뒤」 라고 말하지 않는다.
    static func minutes(left seconds: TimeInterval) -> Int {
        max(1, Int((seconds / 60).rounded(.up)))
    }

    /// 한도에 걸린 상태 — 429 를 받은 순간에 만든다.
    struct Block: Equatable {
        let window: Window
        /// 풀리는 때 — **연속 시계의 초**(`GuideLimit.uptime`). 서버가 `Retry-After` 를 주지 않았으면 모른다(nil) —
        /// 그때는 보내는 것을 막지 않는다.
        let until: TimeInterval?

        /// - Parameter now: 연속 시계의 지금.
        init(retryAfter: Int?, now: TimeInterval) {
            window = GuideLimit.window(seconds: retryAfter)
            until = retryAfter.map { now + TimeInterval(max(0, $0)) }
        }

        /// 풀렸나.
        func isOver(at now: TimeInterval) -> Bool {
            until.map { now >= $0 } ?? false
        }

        /// 지금 보내면 뻔히 429 인가 — 그동안은 전송을 잠근다.
        func stopsSending(at now: TimeInterval) -> Bool {
            until != nil && !isOver(at: now)
        }

        /// 대화 안의 안내 한 줄. **안드로이드와 같은 문구여야 한다.**
        func message(at now: TimeInterval) -> String {
            switch (window, until) {
            case let (.short, until?):
                String(
                    format: tr("가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요"),
                    GuideLimit.minutes(left: until - now)
                )
            case (.day, _):
                tr("오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요")
            default:
                // 얼마나 기다릴지 모른다 — 「오늘」 도 「내일」 도 말하지 않는다.
                tr("지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요")
            }
        }
    }

    /// 서버가 마지막으로 알려 준 남은 양 — 다시 차는 때를 연속 시계로 옮겨 든 것.
    struct Quota: Equatable {
        let limit: Int
        let remaining: Int
        /// 다시 차는 때(연속 시계의 초).
        let refillsAt: TimeInterval

        /// 장부의 값(벽시계)을 **읽은 순간에** 연속 시계로 옮긴다 — 그 뒤로는 기기 시계와 무관하다.
        init(_ entry: RateLimitLedger.Entry, wall: Date, now: TimeInterval) {
            limit = entry.limit
            remaining = entry.remaining
            refillsAt = now + entry.resetsAt.timeIntervalSince(wall)
        }

        init(limit: Int, remaining: Int, refillsAt: TimeInterval) {
            self.limit = limit
            self.remaining = remaining
            self.refillsAt = refillsAt
        }
    }

    /// 입력창 위의 남은 양 한 줄.
    enum Remaining: Equatable {
        /// 보이지 않는다 — 헤더가 없다(옛 서버·아직 묻지 않음), 넉넉하다, 또는 그 창이 이미 다시 찼다.
        case hidden
        /// 얼마 안 남았다. `refillMinutes` 가 nil 이면 오늘 안에는 다시 차지 않는다.
        case few(count: Int, refillMinutes: Int?)
        /// 다 썼다 — 다음 질문은 한도 안내를 받는다.
        case none(refillMinutes: Int?)

        var text: String? {
            switch self {
            case .hidden:
                nil
            case let .few(count, minutes?):
                String(format: counted("%1$d번 더 물어볼 수 있어요 · %2$d분 뒤 다시 채워져요", count), count, minutes)
            case let .few(count, nil):
                String(format: counted("오늘 %d번 더 물어볼 수 있어요", count), count)
            case let .none(minutes?):
                String(format: tr("지금은 더 물어볼 수 없어요 · %d분 뒤 다시 채워져요"), minutes)
            case .none(nil):
                tr("오늘은 더 물어볼 수 없어요")
            }
        }

        /// 영어는 하나일 때 낱말이 달라진다(「1 question left」) — 번역 표의 `원문|하나`(`trCount` 와 같은 관례).
        private func counted(_ korean: String, _ count: Int) -> String {
            count == 1 ? tr(korean, at: "하나") : tr(korean)
        }
    }

    /// 서버가 마지막으로 알려 준 값으로 남은 양 줄을 정한다.
    ///
    /// 서버가 주는 것은 **가장 빠듯한 창**의 값이라 1 시간 창일 수도 하루 창일 수도 있다. 「오늘 3번」 이라고
    /// 못 박으면 틀릴 수 있어, 한 시간 안에 다시 차면 그때를 함께 말한다.
    static func remaining(_ quota: Quota?, now: TimeInterval) -> Remaining {
        guard let quota, quota.limit > 0, now < quota.refillsAt else { return .hidden }
        let left = quota.refillsAt - now
        let minutes = window(seconds: Int(left.rounded(.up))) == .short ? minutes(left: left) : nil
        guard quota.remaining > 0 else { return .none(refillMinutes: minutes) }
        guard Double(quota.remaining) <= Double(quota.limit) * Tuning.lowShare else { return .hidden }
        return .few(count: quota.remaining, refillMinutes: minutes)
    }
}
