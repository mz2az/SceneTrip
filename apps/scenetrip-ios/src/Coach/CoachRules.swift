import CoreGraphics
import Foundation

// 처음 보는 사람을 위한 짧은 안내(코치 마크)의 **규칙** (MZ2AZ-547 P6 C1–C5, 웹 `proto/CoachTour.tsx`).
//
// 단추 하나만 밝게 남기고 나머지를 어둡게 덮은 뒤, 말풍선이 그 단추를 가리키며 무엇을 하는지 말한다.
// 여기에는 화면을 그리지 않는 규칙만 둔다 — 어떤 안내가 있나, 한 번에 하나, 다음·건너뛰기·끝, 본 것 기억,
// 가리킬 단추가 없을 때 건너뛰기, 말풍선 자리. 화면은 `CoachOverlay.swift`, 이어 붙이기는 `CoachCenter.swift`.

/// 안내 한 단계 — 무엇을 가리키고(`target`) 무슨 말을 하나. 제목·본문은 한국어 원문이 곧 번역 열쇠다(`tr`).
struct CoachStep: Equatable {
    /// 가리킬 단추의 표식(`coachTarget(_:)` 에 준 이름). 예: `"plan-trip"`.
    let target: String
    let title: String
    let text: String
}

/// 안내 하나 — 이름(`id`, 본 것 기억의 열쇠), 화면이 뜬 뒤 기다리는 시간, 단계들.
struct CoachTour: Equatable {
    let id: String
    /// 조건이 맞은 뒤 이만큼(초) 기다렸다 띄운다 — 시트가 다 올라오고 화면이 자리 잡은 뒤.
    let delay: TimeInterval
    let steps: [CoachStep]

    /// 홈 — 떠 있는 「여행 코스 짜기」 단추(C2).
    static let homePlanTrip = CoachTour(id: "home-plan-trip", delay: 0.9, steps: [
        CoachStep(
            target: "plan-trip", title: "첫 여행 계획을 짜 보세요",
            text: "여기를 누르면 도시·작품·담은 곳 중 하나로 여행 코스를 만들 수 있어요."
        ),
    ])
    /// 「새 여행 코스」 시트의 세 줄(C3).
    static let forkOptions = CoachTour(id: "fork-options", delay: 0.55, steps: [
        CoachStep(
            target: "fork-city", title: "도시로 시작",
            text: "어디로, 며칠 가는지만 고르면 그 도시 인기 촬영지를 동선대로 이어 코스를 바로 만들어 드려요."
        ),
        CoachStep(
            target: "fork-shows", title: "작품으로",
            text: "좋아하는 드라마·영화를 고르면 AI 가 그 작품들의 촬영지로 코스를 짜 줘요."
        ),
        CoachStep(
            target: "fork-saved", title: "담은 곳으로",
            text: "보관함에 담아 둔 곳을 날마다 나눠 넣어요. 담은 곳이 없으면 빈 코스에서 하나씩 직접 넣어요."
        ),
    ])
    /// 처음 만든 계획 화면 — 지도 · 해태 AI · ＋ · 저장(C4).
    static let editorFirst = CoachTour(id: "editor-first", delay: 1.2, steps: [
        CoachStep(
            target: "editor-map", title: "지도",
            text: "눌러서 코스를 지도로 펼쳐 동선을 확인해요. 다시 누르면 목록만 봐요."
        ),
        CoachStep(
            target: "editor-guide", title: "해태 AI",
            text: "「하루 줄여 줘」 「카페 하나 넣어 줘」처럼 말로 부탁하면 코스를 고쳐 줘요."
        ),
        CoachStep(
            target: "editor-add", title: "장소 검색해서 담기",
            text: "지금은 편집 중이라 장소를 검색해서 더 넣을 수 있어요. 보관함에 담아 둔 곳도 여기서 넣어요."
        ),
        CoachStep(
            target: "editor-save", title: "다 고쳤으면 저장",
            text: "저장해야 코스가 남아요. 저장하지 않고 나가면 한 번 더 물어봐요."
        ),
    ])
    /// 계획 화면 지도를 처음 펼쳤을 때 — 🔍 · 전체 칩(C5, M8).
    static let mapSearch = CoachTour(id: "map-search", delay: 0.7, steps: [
        CoachStep(
            target: "map-search", title: "지도에서 검색",
            text: "촬영지가 아닌 곳도 찾을 수 있어요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요."
        ),
        CoachStep(
            target: "map-chips", title: "주변 둘러보기",
            text: "전체·음식·관광지·숙소 칩을 누르면 지금 보이는 지도 안의 장소가 핀으로 떠요."
        ),
    ])

    static let all: [CoachTour] = [homePlanTrip, forkOptions, editorFirst, mapSearch]
}

// MARK: - 본 것 기억

/// 본 안내 이름을 담는 곳. `UserDefaults` 가 그대로 맞는다 — 시험은 메모리 가짜를 쓴다.
protocol CoachSeenStorage: AnyObject {
    func stringArray(forKey key: String) -> [String]?
    func set(_ value: Any?, forKey key: String)
}

extension UserDefaults: CoachSeenStorage {}

/// 끝까지 보거나 「건너뛰기」 한 안내는 이 기기에서 다시 띄우지 않는다.
final class CoachSeenStore {
    static let key = "scenetrip.coach.seen"
    private let storage: CoachSeenStorage

    init(storage: CoachSeenStorage = UserDefaults.standard) {
        self.storage = storage
    }

    private var ids: [String] {
        storage.stringArray(forKey: Self.key) ?? []
    }

    func isSeen(_ id: String) -> Bool {
        ids.contains(id)
    }

    func markSeen(_ id: String) {
        let now = ids
        guard !now.contains(id) else { return }
        storage.set(now + [id], forKey: Self.key)
    }

    /// 본 기록을 다 지운다 — 확인용 뒷문 `-coachReset 1`.
    func reset() {
        storage.set([String](), forKey: Self.key)
    }
}

// MARK: - 한 번에 하나

/// 떠 있는 안내는 언제나 하나다 — 다른 안내가 떠 있으면 그것이 끝날 때까지 기다린다.
struct CoachQueue: Equatable {
    private(set) var active: String?

    /// 아직 안 봤고(`seen == false`) 다른 안내가 떠 있지 않으면 `id` 를 띄우고 true. 같은 안내가 이미 떠 있으면 false.
    mutating func start(_ id: String, seen: Bool) -> Bool {
        guard !seen, active == nil else { return false }
        active = id
        return true
    }

    /// `id` 가 떠 있는 안내일 때만 내린다 — 다른 안내의 끝내기가 지금 안내를 지우지 않게.
    mutating func end(_ id: String) {
        if active == id {
            active = nil
        }
    }
}

// MARK: - 단계 넘기기

enum CoachAction: Equatable {
    /// 「다음」/「알겠어요」.
    case next
    /// 「건너뛰기」 — 남은 단계를 모두 넘기고 본 것으로 친다.
    case skip
    /// 어두운 덮개를 눌렀다 — 「다음」 과 같다.
    case tapScrim
    /// 가리킬 단추가 `CoachRules.missingTargetWait` 넘게 안 보였다 — 그 단계를 건너뛴다.
    case targetMissing
    /// 화면을 떠났다(시트를 닫는 등) — 접되 본 것으로 치지 않는다. 다음에 다시 띄운다.
    case leave
}

enum CoachOutcome: Equatable {
    /// 이 단계(0부터)를 보인다.
    case step(Int)
    /// 안내를 접는다. `markSeen` 이면 다시 띄우지 않는다.
    case finished(markSeen: Bool)
}

// MARK: - 규칙

enum CoachRules {
    /// 구멍이 단추보다 사방으로 넓은 만큼.
    static let pad: CGFloat = 6
    static let bubbleWidth: CGFloat = 280
    /// 구멍과 말풍선 사이.
    static let gap: CGFloat = 12
    /// 화면 가장자리에서 말풍선·구멍이 떨어지는 거리. 노치·홈 막대(안전 영역)는 따로 더한다.
    static let edge: CGFloat = 12
    /// 가리킬 단추를 기다리는 시간(초) — 넘으면 그 단계를 건너뛴다.
    static let missingTargetWait: TimeInterval = 4
    /// 다른 안내가 떠 있을 때 다시 볼 간격(초).
    static let retryInterval: TimeInterval = 0.4

    static func advance(index: Int, count: Int, action: CoachAction) -> CoachOutcome {
        switch action {
        case .skip:
            .finished(markSeen: true)
        case .leave:
            .finished(markSeen: false)
        case .next, .tapScrim, .targetMissing:
            index + 1 < count ? .step(index + 1) : .finished(markSeen: true)
        }
    }

    /// 단추가 안 보인 지 `waited` 초 — 건너뛸 때인가.
    static func shouldSkipMissing(waited: TimeInterval) -> Bool {
        waited > missingTargetWait
    }

    static func isLast(index: Int, count: Int) -> Bool {
        index >= count - 1
    }

    /// 「1 / 3」.
    static func counter(index: Int, count: Int) -> String {
        "\(index + 1) / \(count)"
    }

    /// 「건너뛰기」 는 마지막 단계에 없다.
    static func showsSkip(index: Int, count: Int) -> Bool {
        !isLast(index: index, count: count)
    }

    /// 오른쪽 단추 글자(번역 열쇠) — 마지막이면 「알겠어요」, 아니면 「다음」.
    static func primaryTitle(index: Int, count: Int) -> String {
        isLast(index: index, count: count) ? "알겠어요" : "다음"
    }

    /// 구멍과 말풍선 자리. 좌표는 모두 화면(덮개) 기준이다.
    /// - Parameters:
    ///   - target: 가리킬 단추의 틀.
    ///   - container: 덮개 크기(화면 전체).
    ///   - safeTop: 위 안전영역(노치·상태줄).
    ///   - safeBottom: 아래 안전영역(홈 막대).
    ///   - bubbleHeight: 잰 말풍선 높이.
    static func layout(
        target: CGRect, container: CGSize, safeTop: CGFloat, safeBottom: CGFloat, bubbleHeight: CGFloat
    ) -> CoachLayout {
        // 화면 가장자리·안전영역 안쪽.
        let innerTop = edge + safeTop
        let innerLeft = edge
        let innerRight = container.width - edge
        let innerBottom = container.height - edge - safeBottom
        // 구멍 — 단추보다 사방 6 넓게, 화면 안으로 가둔다.
        let holeTop = clamp(target.minY - pad, innerTop - edge / 2, innerBottom)
        let holeLeft = clamp(target.minX - pad, 4, innerRight)
        let holeRight = clamp(target.maxX + pad, holeLeft, container.width - 4)
        let holeBottom = clamp(target.maxY + pad, holeTop, innerBottom + edge / 2)
        let hole = CGRect(x: holeLeft, y: holeTop, width: holeRight - holeLeft, height: holeBottom - holeTop)
        let width = min(bubbleWidth, innerRight - innerLeft)
        // 아래에 자리가 있으면 아래, 아니면 위. 위에도 자리가 없으면 아래로 두고 화면 안으로 밀어 넣는다.
        let below = hole.maxY + gap + bubbleHeight <= innerBottom || hole.minY - gap - bubbleHeight < innerTop
        let rawTop = below ? hole.maxY + gap : hole.minY - gap - bubbleHeight
        let top = clamp(rawTop, innerTop, max(innerTop, innerBottom - bubbleHeight))
        let left = clamp(target.midX - width / 2, innerLeft, innerRight - width)
        let arrowX = clamp(target.midX - left - 8, 18, width - 34)
        // 밀어 넣느라 말풍선이 구멍과 겹치면 화살표는 숨긴다(가리킬 곳이 말풍선 밑이다).
        let showsArrow = below ? top >= hole.maxY : top + bubbleHeight <= hole.minY
        return CoachLayout(
            hole: hole, holeRadius: min(22, hole.height / 2),
            bubble: CGRect(x: left, y: top, width: width, height: bubbleHeight),
            below: below, arrowX: arrowX, showsArrow: showsArrow
        )
    }

    private static func clamp(_ value: CGFloat, _ low: CGFloat, _ high: CGFloat) -> CGFloat {
        max(low, min(high, value))
    }

    // MARK: 언제 띄우나

    /// 홈(C2) — 여행 중 코스가 없고, 떠 있는 줄이 접혀 있고, 홈이 가려져 있지 않을 때(다른 탭·덮개·시트).
    static func homeActive(running: Bool, floatOpen: Bool, covered: Bool) -> Bool {
        !running && !floatOpen && !covered
    }

    /// 계획 화면(C4) — 편집 중이고 새 코스·방금 만든 코스이며 묻는 창이 떠 있지 않을 때.
    static func editorFirstActive(editing: Bool, isNew: Bool, fresh: Bool, confirming: Bool) -> Bool {
        editing && (isNew || fresh) && !confirming
    }

    /// 지도 검색(C5) — 칩 줄이 보이고(편집·지도 펼침·여행 아님) 검색칸·결과 지도가 열려 있지 않을 때.
    static func mapSearchActive(barShown: Bool, searching: Bool, resultsOpen: Bool) -> Bool {
        barShown && !searching && !resultsOpen
    }
}

/// 구멍과 말풍선이 놓일 자리.
struct CoachLayout: Equatable {
    /// 밝게 남길 구멍(단추 + 여백, 화면 안으로 가둔 것).
    let hole: CGRect
    /// 구멍 모서리 — 높이의 반, 최대 22.
    let holeRadius: CGFloat
    /// 말풍선 틀(높이는 잰 값 그대로).
    let bubble: CGRect
    /// 말풍선이 구멍 아래에 있나.
    let below: Bool
    /// 화살표의 왼쪽 끝 — 말풍선 왼쪽에서부터.
    let arrowX: CGFloat
    /// 화살표를 그리나 — 화면 안으로 밀어 넣느라 말풍선이 구멍과 겹치면 숨긴다.
    let showsArrow: Bool
}
