import Foundation
import SceneApiClient

/// 계획 화면이 **보기**인가 **고치기**인가 (MZ2AZ-547 E1, 웹 `route/editor/RouteEditor.tsx` 의 `editing`).
enum RouteEditorMode: Equatable {
    case viewing
    case editing
}

/// 계획 화면의 보기/편집 규칙 (MZ2AZ-547 E1·E2·E3·E16). 화면은 이 답을 그대로 따른다.
enum RouteEditorRules {
    /// 처음 열 때의 상태 — 새 초안(isNew)·방금 「도시로」 만든 코스(fresh)는 편집, 저장된 코스는 보기.
    static func initialMode(isNew: Bool, isFresh: Bool) -> RouteEditorMode {
        isNew || isFresh ? .editing : .viewing
    }

    /// 편집 중 「취소」 를 눌렀을 때 할 일.
    enum LeaveDecision: Equatable {
        /// 묻지 않고 화면을 닫는다 — 새 초안인데 바꾼 것이 없다.
        case close
        /// 묻지 않고 편집 전으로 되돌려 보기로 — 저장된 코스인데 바꾼 것이 없다.
        case revertToView
        /// 「바꾼 내용을 버릴까요?」 → 「버리기」 면 화면을 닫는다(새 초안).
        case confirmDiscardThenClose
        /// 「바꾼 내용을 버릴까요?」 → 「버리기」 면 편집 전으로 되돌려 보기로(저장된 코스).
        case confirmDiscardThenRevert
        /// 「저장하지 않고 나갈까요? / 방금 만든 코스가 지워져요.」 → 「나가기」 면 서버에서 지우고 닫는다(방금 만든 코스).
        case confirmLeaveAndDelete
    }

    /// `changed` — 새 초안이면 빈 틀이 아닌가, 저장된 코스면 편집을 시작한 뒤로 바뀐 것이 있는가.
    /// 방금 만든 코스는 바꾼 것이 없어도 묻는다(저장해야 남는다).
    static func cancel(isNew: Bool, isFresh: Bool, changed: Bool) -> LeaveDecision {
        if isFresh, !isNew {
            return .confirmLeaveAndDelete
        }
        if isNew {
            return changed ? .confirmDiscardThenClose : .close
        }
        return changed ? .confirmDiscardThenRevert : .revertToView
    }

    /// 「저장」/「만들기」 가 성공한 뒤.
    enum SaveOutcome: Equatable {
        /// 화면을 닫는다 — 새 초안의 「만들기」.
        case close
        /// 화면에 남아 보기로 — 저장된 코스(방금 만든 코스 포함)의 「저장」.
        case view
    }

    static func afterSave(isNew: Bool) -> SaveOutcome {
        isNew ? .close : .view
    }

    /// 편집 도구를 보이나 — 일차 ＋/−, 동작 줄, 끌기, 밀어서 빼기, 출발/도착 고정, 편의시설 칩, 떠 있는 ＋ (E2).
    static func showsEditTools(_ mode: RouteEditorMode) -> Bool {
        mode == .editing
    }

    /// 아래 줄(「코스 시작」 등)을 보이나 — 저장된 코스를 고치는 동안은 숨긴다(E3). 새 초안은 「코스 만들기」 가 있어 늘 보인다.
    static func showsBottomBar(_ mode: RouteEditorMode, isNew: Bool) -> Bool {
        isNew || mode == .viewing
    }

    /// 지도의 「내 위치」 단추 윗변(pt) — 편집 중이면 지도 맨 위 칩 줄 아래(62), 보기면 맨 위(10) (E14).
    static func locateTop(_ mode: RouteEditorMode) -> CGFloat {
        mode == .editing ? 62 : 10
    }
}

/// 계획 화면 지도를 **다시 맞추는가** (MZ2AZ-547 E12·E13, 웹 `RouteMap.tsx` 카메라 규칙).
enum RouteCameraRules {
    enum Change: CaseIterable {
        /// 다른 일차를 골랐다.
        case daySwitch
        /// 장소를 넣었다(검색·보관함·핀).
        case add
        /// 끌어서 순서를 바꿨다.
        case reorder
        /// 줄을 뺐다.
        case remove
        /// 동선 최적화.
        case optimize
        /// 일정 서랍 높이만 바뀌었다.
        case sheetHeight
        /// 지도를 처음 펼쳤다.
        case mapFirstOpen
        /// 접었던 지도를 다시 펼쳤다.
        case mapReopen
        /// 장소·편의시설 정보 카드를 ✕ 로 닫았다(고른 표시만 놓는다, 2026-11-10 사용자 지적).
        case cardClose
    }

    /// 일차 바꾸기·장소 넣기·처음 펼치기는 맞춘다. 순서 끌기·빼기·최적화·서랍 높이·다시 펼치기·카드 닫기는 확대와 위치를 그대로 둔다.
    static func refits(_ change: Change) -> Bool {
        switch change {
        case .daySwitch, .add, .mapFirstOpen: true
        case .reorder, .remove, .optimize, .sheetHeight, .mapReopen, .cardClose: false
        }
    }

    /// 지도가 카메라를 옮길까 — 열쇠(`RouteCameraKey`)가 바뀌었거나 `fitToken` 이 올랐을 때.
    /// 단 **고른 곳을 놓기만 한 것**(코스 줄·편의시설 카드 ✕, 다른 것은 그대로)은 옮기지 않는다 — 일차 전체로 다시 맞추면
    /// 사람이 확대해 둔 화면이 풀렸다(웹 `RouteMap.tsx` 2026-11-10). 처음(`previous` nil)은 옮긴다.
    static func moves(from previous: RouteCameraKey?, to next: RouteCameraKey, fitChanged: Bool) -> Bool {
        if fitChanged {
            return true
        }
        guard let previous else { return true }
        if previous == next {
            return false
        }
        guard previous.context == next.context else { return true }
        let focusedKept = previous.focused == next.focused
        let pickedKept = previous.picked == next.picked
        let focusedReleased = previous.focused != nil && next.focused == nil
        let pickedReleased = previous.picked != nil && next.picked == nil
        let onlyReleased = (focusedReleased || focusedKept) && (pickedReleased || pickedKept)
        return !onlyReleased
    }
}

/// 계획 화면 지도의 **카메라 열쇠** — 무엇을 보고 있나. 같으면 카메라를 다시 옮기지 않는다(`RouteMapView`).
struct RouteCameraKey: Equatable {
    /// 목록·번호 핀에서 고른 코스 줄(id).
    var focused: String?
    /// 정보 카드가 떠 있는 편의시설·검색 결과(id).
    var picked: String?
    /// 그 밖의 것 — 내 위치 토글·담을까 보는 곳·AI 장소 목록. 칩으로 거르기 **전** 것이다.
    var context: String
}

/// 계획 화면의 「지도」 알약 (MZ2AZ-547 E4·E5). 기본은 목록 전체(지도 접힘), 여행 중이면 펼친 채로 연다.
struct RouteMapToggle: Equatable {
    private(set) var isOpen: Bool
    /// 코스 전체에 한 번 맞췄나 — 처음 펼칠 때만 맞춘다(접힌 동안 맞춘 화면은 엉뚱하다).
    private(set) var fitted: Bool

    init(running: Bool) {
        isOpen = running
        fitted = running
    }

    /// 펼친다. 이번이 처음 펼치는 것이면 true(그때만 코스 전체에 맞춘다).
    mutating func open() -> Bool {
        isOpen = true
        guard !fitted else { return false }
        fitted = true
        return true
    }

    mutating func close() {
        isOpen = false
    }

    /// 알약을 눌렀다 — 펼쳐져 있으면 접고, 접혀 있으면 펼친다. 처음 펼친 것이면 true.
    mutating func toggle() -> Bool {
        if isOpen {
            close()
            return false
        }
        return open()
    }

    /// 줄을 눌렀다·핀 찍기를 켰다 — 접혀 있으면 펼친다(펼쳐져 있으면 그대로). 처음 펼친 것이면 true.
    mutating func reveal() -> Bool {
        isOpen ? false : open()
    }

    /// 알약 글자 — 접혀 있으면 「지도」, 펼쳐져 있으면 「목록만」.
    enum Pill: Equatable {
        case map
        case listOnly
    }

    var pill: Pill {
        isOpen ? .listOnly : .map
    }
}

/// 「보관함에서 추가」 의 고르기 (MZ2AZ-547 E10) — 고른 순서를 기억한다(넣을 때 그 순서대로).
struct CourseAddPicker: Equatable {
    private(set) var picked: [Int64] = []

    /// 고르거나 푼다. 풀면 뒤의 번호가 하나씩 당겨진다.
    mutating func toggle(_ id: Int64) {
        if let index = picked.firstIndex(of: id) {
            picked.remove(at: index)
        } else {
            picked.append(id)
        }
    }

    /// 고른 순서 번호(1부터). 안 골랐으면 nil.
    func order(of id: Int64) -> Int? {
        picked.firstIndex(of: id).map { $0 + 1 }
    }

    /// 넣을 수 있는 곳(`free`)을 전부 골랐나. 넣을 수 있는 곳이 없으면 false.
    func allPicked(_ free: [Int64]) -> Bool {
        !free.isEmpty && free.allSatisfy(picked.contains)
    }

    /// 「모두 고르기 / 모두 풀기」 — 다 골라져 있으면 전부 풀고, 아니면 `free` 순서대로 전부 고른다(웹과 같다).
    mutating func toggleAll(_ free: [Int64]) {
        picked = allPicked(free) ? [] : free
    }

    /// 고른 곳을 고른 순서대로. `places` 에 없는 id 는 건너뛴다.
    func ordered(_ places: [PlaceSummary]) -> [PlaceSummary] {
        picked.compactMap { id in places.first { $0.id == id } }
    }

    /// 아래 고정 단추 — 고르기 전 「넣을 곳을 골라 주세요」(흐림), 고른 뒤 「N곳 선택 완료」.
    enum Done: Equatable {
        case pickFirst
        case done(count: Int)
    }

    var done: Done {
        picked.isEmpty ? .pickFirst : .done(count: picked.count)
    }
}

/// 장소 넣기 두 갈래(지도에서 추가·보관함에서 추가)가 함께 쓰는 규칙 (MZ2AZ-547 E9·E10).
enum CourseAddRules {
    /// 보관함을 「담아 둔 곳」(넣을 수 있음)과 「이미 코스에 있는 곳」(흐림)으로 가른다. 각자 원래 순서.
    static func split(_ places: [PlaceSummary], taken: Set<Int64>) -> (free: [PlaceSummary], inCourse: [PlaceSummary]) {
        (places.filter { !taken.contains($0.id) }, places.filter { taken.contains($0.id) })
    }

    /// 장소 상세의 「코스에 추가」 단추 상태.
    enum Button: Equatable {
        /// 「＋ 코스에 추가」.
        case add
        /// 「이미 코스에 있어요」(흐림, 못 누름).
        case already
        /// 「✓ N일차에 넣었어요」 — 이 화면에서 방금 넣었다. `day` 는 0부터.
        case added(day: Int)
    }

    /// 방금 넣은 것이 「이미 있음」 보다 먼저다.
    static func button(for placeId: Int64, taken: Set<Int64>, addedTo: [Int64: Int]) -> Button {
        if let day = addedTo[placeId] {
            return .added(day: day)
        }
        return taken.contains(placeId) ? .already : .add
    }

    /// 일차 고르기 줄 오른쪽 — 그 일차에 든 곳 수.
    enum DayCaption: Equatable {
        case empty
        case places(Int)
    }

    static func dayCaption(_ count: Int) -> DayCaption {
        count > 0 ? .places(count) : .empty
    }

    /// 고른 일차의 끝에 고른 순서대로 넣는다. 코스(어느 일차든)에 이미 있는 곳은 거른다(`RouteDedupe`).
    /// 일차가 범위 밖이면 코스를 그대로 두고 0. `added` 는 실제로 넣은 수.
    static func adding(_ places: [PlaceSummary], toDay day: Int, in course: RouteCourse) -> (course: RouteCourse, added: Int) {
        guard course.days.indices.contains(day) else { return (course, 0) }
        let fresh = RouteDedupe.fresh(places.map { RouteStop(place: $0) }, among: course.stops)
        var next = course
        next.days[day].stops.append(contentsOf: fresh)
        return (next, fresh.count)
    }
}
