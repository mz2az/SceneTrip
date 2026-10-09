import CoreLocation
import Foundation
import os
import SceneApiClient

/// 가이드와의 **한 대화.**
///
/// ## 왜 시트 밖에 두나
///
/// 시트 안에 `@State` 로 두었더니 **닫을 때마다 대화가 사라졌다**(2026-08-27 사용자
/// 지적). 물어보고 지도를 보려면 시트를 내려야 하는데, 내리면 방금 물은 것이 없어져
/// 처음부터 다시 물어야 했다.
///
/// 화면 밖에서 들면 시트는 그것을 **보여 주기만** 한다 — 열고 닫는 것과 대화가
/// 이어지는 것이 따로 논다.
///
/// ## 방 번호(`sessionId`)도 여기 있다
///
/// 서버가 이 값으로 「앞 턴에 보여 준 장소」를 기억한다(`_GUIDE_SEEN`). 시트가
/// 들고 있으면 닫을 때마다 새 방이 되어, 「거기 어떻게 가요」가 무엇을 가리키는지
/// 서버도 잊는다.
@MainActor
final class RouteGuideSession: ObservableObject {
    /// **앱에 하나뿐인 대화 창구.** 계획 화면에서 묻던 것을 길찾기에서 이어 묻고,
    /// 돌아와도 그대로다(2026-08-28 사용자 요청 — 화면마다 대화가 갈리지 않게).
    ///
    /// 다만 **대화는 코스의 것이다**(`bind`, 2026-09-17). 창구가 하나라고 대화까지 하나면 새 코스를
    /// 열었는데 앞 코스에서 추천받은 「AI 장소」가 지도에 찍힌다.
    static let shared = RouteGuideSession()

    /// 밖과 닿는 자리들 — 시험이 가짜로 바꿔 끼운다.
    struct Environment {
        /// 만들어 둔 요청을 보낸다. 인자는 요청·멱등 키·「서버가 키를 아는가」.
        var send: (GuideChatRequest, UUID, Bool) async throws -> RouteGuide.Answer = RouteGuide.send
        /// 서버가 멱등 키를 아는가 — 이번 실행에서 `RateLimit-*` 를 본 적이 있는가(계획 §4-5).
        var serverKeepsKeys: () -> Bool = { RateLimitLedger.shared.serverKnowsLimits }
        var lang: () -> Lang = { AppLanguage.current }
        var newKey: () -> UUID = UUID.init
        /// 사람이 질문을 보냈다(분석). 「다시 시도」 는 같은 질문이라 세지 않는다.
        var asked: () -> Void = { AppAnalytics.log(.askGuide) }
        /// **연속 시계**의 지금(초) — 한도의 잠금과 「N분 뒤」 는 이것으로 잰다. 기기 시계를 돌려도 변하지 않는다.
        var now: () -> TimeInterval = GuideLimit.uptime
        /// 벽시계 — 장부의 「다시 차는 때」(벽시계로 적혀 있다)를 읽는 순간에 연속 시계로 옮기는 데만 쓴다.
        var wall: () -> Date = Date.init
        /// 서버가 마지막 챗봇 응답에 실어 준 남은 양(`RateLimit-*`). 없으면(옛 서버) nil.
        var quota: () -> RateLimitLedger.Entry? = { RateLimitLedger.shared.entry(for: .guide) }
        /// 한도가 풀릴 때까지 쉰다. 기기가 잠든 시간도 센다 — 깨어나서 30 분을 더 기다리지 않게.
        var sleep: (TimeInterval) async -> Void = { try? await Task.sleep(for: .seconds($0), clock: .continuous) }
    }

    /// 챗봇 한도(429 `GUIDE_LIMIT_REACHED`)에 걸렸나 (MZ2AZ-366 C).
    enum Limit: Equatable {
        case clear
        /// 걸려 있다. 풀리는 때를 알면 그때까지 전송이 잠긴다.
        case reached(GuideLimit.Block)
        /// 풀렸다 — 「이제 다시 물어볼 수 있어요」. 걸렸던 질문이 남아 있으면 「다시 시도」 가 같은 키로 보낸다.
        case lifted
    }

    @Published private(set) var turns: [RouteGuide.Turn] = []

    /// 보내는 중이거나 다시 보낼 수 있는 턴 (MZ2AZ-366). 답이 왔거나 다시 해도 같은 실패면 비운다.
    @Published private(set) var pending: PendingTurn?

    /// 챗봇 한도의 상태. **대화가 아니라 계정의 것이다** — 코스를 바꿔도(`bind`·`clear`) 남는다.
    @Published private(set) var limit: Limit = .clear

    /// 서버가 마지막으로 알려 준 남은 양 — 입력창 위 한 줄의 재료(`GuideLimit.remaining`). 이것도 계정의 것이다.
    @Published private(set) var quota: GuideLimit.Quota?
    /// `quota` 를 만든 장부의 값. 같은 값을 다시 읽으면(헤더 없는 답) 옮겨 둔 시각을 그대로 둔다.
    private var quotaSource: RateLimitLedger.Entry?
    /// 계정이 바뀐 횟수 — 보낸 뒤에 계정이 바뀐 요청의 결과는 버린다(`fly`).
    private var accountEpoch = 0

    /// 답을 기다리는 중인가. 그동안은 전송이 잠긴다 — **두 번 누름은 여기서 막힌다.**
    var asking: Bool {
        pending?.stage.isBusy ?? false
    }

    /// 한도가 풀리기를 기다리는 중인가. 그동안은 전송이 잠긴다 — 뻔히 429 인 요청을 보내지 않는다.
    /// 풀리는 때를 모르면(서버가 `Retry-After` 를 안 줌) 잠그지 않는다.
    var isLimited: Bool {
        if case let .reached(block) = limit {
            return block.stopsSending(at: environment.now())
        }
        return false
    }

    /// 실패한 턴을 다시 보낼 수 있나 — 「다시 시도」 단추를 보일지. 한도에 걸린 턴은 **풀린 뒤에만.**
    /// 풀리는 때를 모르면 잠그지 않으므로 단추도 바로 뜬다.
    var canRetry: Bool {
        guard case .failed = pending?.stage else { return false }
        return !isLimited
    }

    /// 마지막 답이 부른 도구. **화면에 보여 준다** — 근거 없이 답한 것을 알아볼 수
    /// 있어야 한다.
    @Published private(set) var tools: [String] = []

    /// 마지막 답이 찾아 준 곳.
    @Published private(set) var places: [RouteGuide.Place] = []

    /// 그중 사용자가 고른 것. 지도에서 **빨갛고 크게** 그려진다.
    @Published var picked: RouteGuide.Place?

    @Published private(set) var failure: RouteGuideFailure?

    /// 마지막 답 전체 — 편집 화면이 `effects`·`ui` 를 적용한다. `answerTick` 이 바뀔 때 읽는다.
    /// 같은 답을 두 번 적용하지 않도록 값이 아니라 **횟수**로 알린다.
    @Published private(set) var lastAnswer: RouteGuide.Answer?
    @Published private(set) var answerTick = 0

    /// 대화 하나의 열쇠. 계약이 UUID 를 요구한다(`GuideChatRequest.sessionId`).
    /// 코스가 바뀌면 새로 뽑는다 — 서버가 기억하는 「앞 턴에 보여 준 장소」도 앞 코스의 것이다.
    private var sessionId = UUID()

    /// 지금 대화가 묶인 코스. `course-<서버 id>` 또는 저장 전이면 `draft-<화면 id>`.
    private(set) var courseKey: String?

    private let environment: Environment
    /// 떠 있는 요청. 코스가 바뀌거나, 다시 보내기를 기다리는 중에 창이 닫히면 끊는다.
    private var flight: Task<RouteGuide.Answer, Error>?
    /// 지금 떠 있는 가이드 창들. 비어 있으면 다시 보내기·되묻기를 기다리지 않는다(`setAttended`).
    ///
    /// 참·거짓 하나가 아니라 **누가 떠 있는지**를 든다 — 편집 화면과 길찾기 화면이 같은 대화를 쓰는데, 한쪽
    /// 창이 사라지며 「닫혔다」 고 알리면 다른 쪽 창이 떠 있어도 닫힌 것으로 남는다.
    private var attendants: Set<UUID> = []
    private var attended: Bool {
        !attendants.isEmpty
    }

    /// 한도가 풀리는 때에 깨어나 잠금을 푼다.
    private var limitWake: Task<Void, Never>?
    private var resendObserver: NSObjectProtocol?
    private static let log = Logger(subsystem: "com.mz2az.scenetrip", category: "retry")

    init(environment: Environment = Environment()) {
        self.environment = environment
        // 공통 계층이 이 턴을 다시 보내려 한다 — 화면 말을 바꾸고, 창이 닫혀 있으면 끊는다.
        resendObserver = NotificationCenter.default.addObserver(
            forName: RetryingSession.willResend, object: nil, queue: nil
        ) { [weak self] note in
            guard let resend = note.object as? RetryingSession.Resend else { return }
            Task { @MainActor in self?.noteResend(key: resend.key, reason: resend.reason) }
        }
    }

    deinit {
        if let resendObserver {
            NotificationCenter.default.removeObserver(resendObserver)
        }
    }

    var isEmpty: Bool {
        turns.isEmpty
    }

    /// 「전송」. **새 턴이고 새 멱등 키다.** 실패해 남아 있던 턴은 버린다 — 그 질문은 대화에 남는다.
    func ask(
        _ text: String,
        here: CLLocationCoordinate2D,
        context: RouteGuide.Context?
    ) async {
        let question = text.trimmingCharacters(in: .whitespaces)
        refreshLimit()
        guard !question.isEmpty, !asking, !isLimited else { return }

        failure = nil
        setLimit(.clear) // 새 질문이다 — 한도 안내는 답(또는 또 한 번의 429)이 다시 정한다
        turns.append(.init(role: .user, text: question))
        // 요청은 여기서 한 번 만든다 — 위치·화면 상태·이력이 박힌 채로. 다시 보낼 때 이것을 그대로 쓴다.
        pending = PendingTurn(
            key: environment.newKey(),
            request: RouteGuide.request(history: turns, here: here, sessionId: sessionId, context: context),
            lang: environment.lang(),
            keyed: environment.serverKeepsKeys()
        )
        environment.asked()
        await fly()
    }

    /// 「다시 시도」. **같은 턴을 같은 키로** 다시 보낸다 — 질문을 대화에 또 적지 않는다.
    func retry() async {
        refreshLimit()
        guard canRetry,
              let turn = pending?.retried(lang: environment.lang(), newKey: environment.newKey)
        else { return }
        failure = nil
        setLimit(.clear)
        pending = turn
        await fly()
    }

    /// 가이드 창이 열렸다·닫혔다. **닫히면 기다리던 다시 보내기·되묻기를 끊는다** — 보는 사람이 없는데 뒤에서
    /// 몇십 초씩 되묻지 않는다. 처음 보낸 요청은 끊지 않는다(묻고 지도를 보려고 창을 내린다). 끊긴 턴은 남아
    /// 있어, 다시 열어 「다시 시도」 를 누르면 같은 키로 이어진다.
    ///
    /// - Parameter panel: 알리는 창. 창마다 제 값을 든다 — 마지막 창이 사라져야 닫힌 것이다.
    func setAttended(_ open: Bool, by panel: UUID) {
        if open {
            attendants.insert(panel)
            refreshLimit() // 앱이 뒤에 있던 사이에 풀렸을 수 있다
        } else {
            attendants.remove(panel)
        }
        if !attended, pending?.stage.isResending == true {
            flight?.cancel()
        }
    }

    /// 공통 계층이 이 턴을 다시 보내려고(또는 다시 물으려고) 쉬기 시작했다.
    func noteResend(key: String, reason: RetryingSession.Resend.Reason) {
        guard let turn = pending, turn.key.uuidString == key, turn.stage.isBusy else { return }
        pending = turn.resending(reason)
        if !attended {
            flight?.cancel()
        }
    }

    private func fly() async {
        guard let turn = pending else { return }
        let send = environment.send
        let task = Task { try await send(turn.request, turn.key, turn.keyed) }
        flight = task
        let epoch = accountEpoch
        let result = await task.result
        // 기다리는 사이에 코스가 바뀌었거나 대화를 지웠다 — 늦게 온 답을 남의 대화에 적지 않는다.
        guard pending?.key == turn.key else { return }
        flight = nil
        // 기다리는 사이에 **계정이 바뀌었다** — 앞 계정의 답도 한도(429)도 지금 계정의 것이 아니다. 통째로 버린다.
        guard epoch == accountEpoch else {
            pending = nil
            return
        }
        readQuota()

        switch result {
        case let .success(answer):
            pending = nil
            setLimit(.clear)
            tools = answer.tools
            // **장소를 새로 찾아 왔을 때만 목록을 갈아 끼운다.** 「어디 기준이야?」
            // 같은 되물음에는 장소가 안 실려 오는데, 그때 목록까지 지우면 방금
            // 받은 추천과 ⊕ 담기 단추가 채팅 한 번에 사라진다(2026-08-27 사용자
            // 지적). 고른 것을 놓는 것도 그때만이다 — 목록이 그대로면 고른 것도
            // 그대로가 맞다.
            if !answer.places.isEmpty {
                places = answer.places
                picked = nil
            }
            turns.append(.init(
                role: .assistant,
                text: answer.reply.isEmpty ? tr("답을 받지 못했습니다.") : answer.reply
            ))
            lastAnswer = answer
            answerTick += 1
        case let .failure(error):
            // 계약 응답별로 갈라 말한다 — 401 가입 · 503 잠시 뒤 · 시간 초과 · 오프라인 · 연결 실패.
            let reason = RouteGuideFailure(error)
            if reason == .keyReused {
                // 같은 키에 다른 내용이 나갔다 — 요청을 다시 만들었거나 키를 돌려 쓴 것이다. 앱 버그.
                Self.log.fault("챗봇 멱등 키 재사용(422) — 같은 키에 다른 본문이 나갔다")
            }
            failure = reason
            // 다시 해 볼 만한 실패면 턴을 남긴다 — 「다시 시도」 가 같은 키로 보낸다.
            pending = turn.failing(reason)
            if case let .limitReached(retryAfter) = reason {
                setLimit(.reached(GuideLimit.Block(retryAfter: retryAfter, now: environment.now())))
            } else {
                setLimit(.clear)
            }
        }
    }

    // MARK: 챗봇 한도

    /// 풀리는 때가 지났으면 잠금을 푼다. 창을 열 때와 보내기 직전에도 본다 — 타이머는 앱이 멈춰 있으면 늦는다.
    func refreshLimit() {
        guard case let .reached(block) = limit, block.isOver(at: environment.now()) else { return }
        setLimit(.lifted)
    }

    /// 계정이 바뀌었다 — 한도와 남은 양은 앞 계정의 것이다. 떠 있는 요청도 앞 계정의 것이라 끊고, 늦게 돌아온
    /// 결과는 `fly` 가 버린다.
    func forgetLimit() {
        accountEpoch += 1
        flight?.cancel()
        setLimit(.clear)
        quota = nil
        quotaSource = nil
        if case .limitReached = failure {
            failure = nil
            pending = nil
        }
    }

    /// 응답이 실어 온 남은 양. 헤더가 없었으면(옛 서버, 멱등 키로 되돌려 준 답) 장부가 앞의 값을 그대로 든다 —
    /// 그때는 옮겨 둔 시각을 다시 계산하지 않는다(그 사이 기기 시계가 바뀌었을 수 있다).
    private func readQuota() {
        let entry = environment.quota()
        guard entry != quotaSource else { return }
        quotaSource = entry
        quota = entry.map { GuideLimit.Quota($0, wall: environment.wall(), now: environment.now()) }
    }

    private func setLimit(_ new: Limit) {
        limitWake?.cancel()
        limitWake = nil
        limit = new
        guard case let .reached(block) = new, let until = block.until else { return }
        let (now, sleep) = (environment.now, environment.sleep)
        limitWake = Task { [weak self] in
            // 시계가 어긋나 일찍 깨면 남은 만큼 더 쉰다.
            while !Task.isCancelled {
                let left = until - now()
                if left <= 0 {
                    break
                }
                await sleep(left)
            }
            guard !Task.isCancelled else { return }
            self?.refreshLimit()
        }
    }

    /// 편집 화면이 뜰 때 부른다. **다른 코스면 대화·AI 장소·방 번호를 새로 시작한다.**
    /// 같은 코스를 다시 열면 그대로 이어진다.
    func bind(to key: String) {
        guard key != courseKey else { return }
        courseKey = key
        clear()
        sessionId = UUID()
    }

    /// 저장 전 코스가 서버 id 를 얻었다 — **같은 코스다.** 열쇠만 바꾸고 대화는 둔다.
    /// 안 그러면 「코스 만들기」로 저장한 뒤 다시 열 때 다른 코스로 보고 대화를 지운다.
    func rekey(to key: String) {
        courseKey = key
    }

    /// 대화를 처음부터 다시. **방 번호는 그대로 둔다** — 서버가 기억하는 장소까지
    /// 지울 이유는 없고, 지우려면 시트를 새로 만들면 된다.
    func clear() {
        // 보내던 턴도 버린다 — 떠 있는 요청을 끊고, 늦게 온 답은 `fly` 가 적지 않는다.
        flight?.cancel()
        flight = nil
        pending = nil
        turns = []
        tools = []
        places = []
        picked = nil
        failure = nil
        lastAnswer = nil
        // 한도는 계정의 것이라 남는다. 다만 「이제 다시 물어볼 수 있어요」 는 지운 대화의 말이다.
        if limit == .lifted {
            limit = .clear
        }
    }
}
