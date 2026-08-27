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
    /// **앱에 하나뿐인 대화.** 계획 화면에서 묻던 것을 길찾기에서 이어 묻고,
    /// 돌아와도 그대로다(2026-08-28 사용자 요청 — 화면마다 대화가 갈리지 않게).
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

    @Published private(set) var failure: String?

    private let sessionId = UUID().uuidString

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
            }
            turns.append(.init(
                role: .assistant,
                text: answer.reply.isEmpty ? "답을 받지 못했습니다." : answer.reply
            ))
        } catch {
            failure = error.localizedDescription
        }
    }

    /// 대화를 처음부터 다시. **방 번호는 그대로 둔다** — 서버가 기억하는 장소까지
    /// 지울 이유는 없고, 지우려면 시트를 새로 만들면 된다.
    func clear() {
        turns = []
        tools = []
        places = []
        picked = nil
        failure = nil
    }
}
