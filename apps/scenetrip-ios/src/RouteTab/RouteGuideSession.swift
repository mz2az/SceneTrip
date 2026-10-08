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
    }

    @Published private(set) var turns: [RouteGuide.Turn] = []

    /// 보내는 중이거나 다시 보낼 수 있는 턴 (MZ2AZ-366). 답이 왔거나 다시 해도 같은 실패면 비운다.
    @Published private(set) var pending: PendingTurn?

    /// 답을 기다리는 중인가. 그동안은 전송이 잠긴다 — **두 번 누름은 여기서 막힌다.**
    var asking: Bool {
        pending?.stage.isBusy ?? false
    }

    /// 실패한 턴을 다시 보낼 수 있나 — 「다시 시도」 단추를 보일지.
    var canRetry: Bool {
        if case .failed = pending?.stage {
            return true
        }
        return false
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
        guard !question.isEmpty, !asking else { return }

        failure = nil
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
        guard let turn = pending?.retried(lang: environment.lang(), newKey: environment.newKey) else { return }
        failure = nil
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
        let result = await task.result
        // 기다리는 사이에 코스가 바뀌었거나 대화를 지웠다 — 늦게 온 답을 남의 대화에 적지 않는다.
        guard pending?.key == turn.key else { return }
        flight = nil

        switch result {
        case let .success(answer):
            pending = nil
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
    }
}
