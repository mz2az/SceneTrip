import Combine
import Foundation

/// 길찾기 **한도 안내의 규칙** (MZ2AZ-366 D, 계획 `app-retry.md` §5-3·§12).
///
/// 서버는 길찾기를 1 분 창과 하루 창으로 센다(`PaidQuota` — 기본 분당 10, 하루 300). 넘으면
/// `429 NAVIGATION_LIMIT_REACHED` — **두 창의 `code` 가 같다.** 가르는 것은 `Retry-After`(그 창이 끝날
/// 때까지의 초)뿐이다. 챗봇의 `GuideLimit` 과 같은 꼴이고 경계만 다르다.
///
/// 어느 쪽이든 **자동으로 다시 부르지 않고**, 그 구간을 지도 앱으로 넘길 수 있게 한다(`ExternalDirections`).
/// 전부 값만 보는 순수 함수다. 시각은 연속 시계의 초(`GuideLimit.uptime`)로 밖에서 넣는다.
enum NavLimit {
    static let code = "NAVIGATION_LIMIT_REACHED"

    enum Tuning {
        /// `Retry-After` 가 이 초 이하면 「곧 다시」, 넘으면 「오늘은 다 썼어요」.
        ///
        /// 1 분 창은 그 분이 끝날 때 풀리므로 길어야 60초다. 그보다 긴 것은 하루 창뿐이다. 하루 창이 자정
        /// 30초 전에 걸려도 「곧 다시」 는 참이다. (챗봇의 경계가 3,600초인 것은 그쪽 짧은 창이 1 시간이어서다.)
        static let shortWindowSeconds = 60
    }

    enum Window: Equatable {
        /// 1 분 안에 풀린다.
        case short
        /// 그보다 멀다 — 하루 한도.
        case day
        /// 서버가 언제 풀리는지 말하지 않았다(`Retry-After` 없음) — 「오늘」 이라고 지어내지 않는다.
        case unknown
    }

    static func window(seconds: Int?) -> Window {
        guard let seconds else { return .unknown }
        return seconds <= Tuning.shortWindowSeconds ? .short : .day
    }

    /// 한도에 걸린 상태 — 429 를 받은 순간에 만든다.
    struct Block: Equatable {
        let window: Window
        /// 풀리는 때 — 연속 시계의 초. 서버가 `Retry-After` 를 주지 않았으면 모른다(nil) — 그때는 다시 부르는
        /// 것을 막지 않는다(풀 길이 없다).
        let until: TimeInterval?

        init(retryAfter: Int?, now: TimeInterval) {
            window = NavLimit.window(seconds: retryAfter)
            until = retryAfter.map { now + TimeInterval(max(0, $0)) }
        }

        func isOver(at now: TimeInterval) -> Bool {
            until.map { now >= $0 } ?? false
        }

        /// 지금 부르면 뻔히 429 인가 — 그동안은 서버에 묻지 않고 바로 넘기는 단추를 보인다.
        func stopsAsking(at now: TimeInterval) -> Bool {
            until != nil && !isOver(at: now)
        }

        /// 풀릴 때까지 남은 초(올림). 모르거나 이미 풀렸으면 nil.
        func secondsLeft(at now: TimeInterval) -> Int? {
            guard let until, until > now else { return nil }
            return Int((until - now).rounded(.up))
        }
    }

    /// 안내 한 줄. **안드로이드와 같은 문구여야 한다.**
    static func message(_ window: Window) -> String {
        switch window {
        case .short:
            tr("길찾기를 잠시 많이 썼어요. 1분 안에 다시 찾을 수 있어요. 지도 앱에서 이어서 볼 수도 있어요")
        case .day:
            tr("오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요")
        case .unknown:
            tr("지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요")
        }
    }
}

/// 길찾기 한도의 **상태** — 앱에 하나, 계정의 것이다 (MZ2AZ-366 D).
///
/// 안내(`TripSession`)는 편집 화면마다 새로 생기지만 한도는 계정에 걸린다. 다른 코스를 열어도 남아 있어야
/// 뻔히 429 인 요청을 또 보내지 않는다. 메모리에만 든다 — 앱을 껐다 켜면 잊고, 다음 요청이 다시 429 를 받아
/// 같은 안내가 뜬다. 로그인·로그아웃·탈퇴 때 잊는다(`AuthStore.accountChanged`).
///
/// 챗봇의 `RouteGuideSession.limit` 과 같은 방식이다 — 연속 시계, 풀리는 때에 깨어나기, 계정이 바뀐 횟수.
@MainActor
final class NavLimitStore: ObservableObject {
    static let shared = NavLimitStore()

    enum State: Equatable {
        case clear
        /// 걸려 있다. 풀리는 때를 알면 그때까지 서버에 묻지 않는다.
        case reached(NavLimit.Block)
        /// 풀렸다 — 「이제 앱 안에서 다시 길을 찾을 수 있어요」. 사람이 「다시 시도」 를 눌러야 다시 묻는다.
        case lifted
    }

    struct Environment {
        /// 연속 시계의 지금(초). 기기 시계를 돌려도 변하지 않고, 잠든 동안에도 간다.
        var now: () -> TimeInterval = GuideLimit.uptime
        /// 풀릴 때까지 쉰다. 기기가 잠든 시간도 센다.
        var sleep: (TimeInterval) async -> Void = { try? await Task.sleep(for: .seconds($0), clock: .continuous) }
    }

    @Published private(set) var state: State = .clear

    /// 계정이 바뀐 횟수 — 보낸 뒤에 계정이 바뀐 길찾기 요청의 결과는 버린다(`TripSession.load`).
    private(set) var epoch = 0

    private let environment: Environment
    private var wake: Task<Void, Never>?

    init(environment: Environment = Environment()) {
        self.environment = environment
    }

    /// 지금 부르면 뻔히 429 인가. 풀리는 때가 지났으면 먼저 푼다 — 타이머는 앱이 멈춰 있으면 늦는다.
    var stopsAsking: Bool {
        refresh()
        if case let .reached(block) = state {
            return block.stopsAsking(at: environment.now())
        }
        return false
    }

    /// 걸려 있다면 풀릴 때까지 남은 초. 요청을 보내지 않고 한도 안내를 다시 세울 때 쓴다.
    var secondsLeft: Int? {
        if case let .reached(block) = state {
            return block.secondsLeft(at: environment.now())
        }
        return nil
    }

    /// 서버가 429 `NAVIGATION_LIMIT_REACHED` 로 답했다.
    func reach(retryAfter: Int?) {
        set(.reached(NavLimit.Block(retryAfter: retryAfter, now: environment.now())))
    }

    /// 길찾기가 됐다 — 한도 안이다.
    func succeeded() {
        set(.clear)
    }

    /// 풀리는 때가 지났으면 푼다.
    func refresh() {
        guard case let .reached(block) = state, block.isOver(at: environment.now()) else { return }
        set(.lifted)
    }

    /// 계정이 바뀌었다 — 한도는 앞 계정의 것이다. 떠 있던 요청의 늦은 결과는 부른 쪽이 `epoch` 을 견줘 버린다.
    func forget() {
        epoch += 1
        set(.clear)
    }

    private func set(_ new: State) {
        wake?.cancel()
        wake = nil
        if state != new {
            state = new
        }
        guard case let .reached(block) = new, let until = block.until else { return }
        let (now, sleep) = (environment.now, environment.sleep)
        wake = Task { [weak self] in
            // 시계가 어긋나 일찍 깨면 남은 만큼 더 쉰다.
            while !Task.isCancelled {
                let left = until - now()
                if left <= 0 {
                    break
                }
                await sleep(left)
            }
            guard !Task.isCancelled else { return }
            self?.refresh()
        }
    }
}
