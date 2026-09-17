import CoreLocation
import Foundation

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

    @Published private(set) var turns: [RouteGuide.Turn] = []
    @Published private(set) var asking = false

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

    private var linkTask: Task<Void, Never>?

    var isEmpty: Bool {
        turns.isEmpty
    }

    func ask(
        _ text: String,
        here: CLLocationCoordinate2D,
        context: RouteGuide.Context?
    ) async {
        let question = text.trimmingCharacters(in: .whitespaces)
        guard !question.isEmpty, !asking else { return }

        failure = nil
        turns.append(.init(role: .user, text: question))
        asking = true
        defer { asking = false }

        do {
            let answer = try await RouteGuide.ask(
                history: turns, here: here, sessionId: sessionId, context: context
            )
            tools = answer.tools
            // **장소를 새로 찾아 왔을 때만 목록을 갈아 끼운다.** 「어디 기준이야?」
            // 같은 되물음에는 장소가 안 실려 오는데, 그때 목록까지 지우면 방금
            // 받은 추천과 ⊕ 담기 단추가 채팅 한 번에 사라진다(2026-08-27 사용자
            // 지적). 고른 것을 놓는 것도 그때만이다 — 목록이 그대로면 고른 것도
            // 그대로가 맞다.
            if !answer.places.isEmpty {
                places = answer.places
                picked = nil
                markLinked(answer.places)
            }
            turns.append(.init(
                role: .assistant,
                text: answer.reply.isEmpty ? "답을 받지 못했습니다." : answer.reply
            ))
            lastAnswer = answer
            answerTick += 1
        } catch {
            // 계약 응답별로 갈라 말한다 — 401 가입 · 503 잠시 뒤 · 50초 초과 · 연결 실패.
            failure = RouteGuideFailure(error)
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

    /// 어느 줄이 네이버에 연결돼 있는지 **뒤에서** 알아 와 표시한다 — 답을 붙들고 기다리지 않는다
    /// (처음 보는 곳 열둘이면 7초쯤 걸린다). 그 사이 새 답이 와서 목록이 바뀌었으면 버린다.
    private func markLinked(_ asked: [RouteGuide.Place]) {
        linkTask?.cancel()
        linkTask = Task { [weak self] in
            let linked = await RouteGuideLinked.linkedIds(of: asked)
            guard let self, !Task.isCancelled, places.map(\.id) == asked.map(\.id) else { return }
            places = RouteGuideLinked.marked(asked, linked: linked)
        }
    }

    /// 대화를 처음부터 다시. **방 번호는 그대로 둔다** — 서버가 기억하는 장소까지
    /// 지울 이유는 없고, 지우려면 시트를 새로 만들면 된다.
    func clear() {
        linkTask?.cancel()
        turns = []
        tools = []
        places = []
        picked = nil
        failure = nil
        lastAnswer = nil
    }
}
