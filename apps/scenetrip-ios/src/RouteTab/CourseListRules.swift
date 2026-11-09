import Foundation

// 코스 목록(「내 여행」)의 순수 규칙 (MZ2AZ-547 P7 — L1–L5·E17, 계획 docs/project/plans/ios-web-parity.md).
// 웹 기준: `features/proto/ProtoCourse.tsx`. 화면(`RouteTabView`)은 얇게 두고 판단은 여기서 한다.

/// 여러 개 고르기(L4). 줄을 꾹(0.2초) 누르면 켜지고 그 줄이 골라진다. 켜진 동안 줄을 누르면 체크만 바뀐다.
///
/// 줄은 열쇠(`CourseListRules.key`)로 가린다 — 지울 때마다 목록을 다시 받아 `RouteCourse.id`(UUID)가 새로 생기기 때문이다.
struct CourseListPicking: Equatable {
    /// 고른 줄의 열쇠. **nil 이면 꺼져 있다** — 켜져 있는데 하나도 안 고른 것(빈 집합)과 다르다.
    private(set) var picked: Set<String>?

    var isOn: Bool {
        picked != nil
    }

    var count: Int {
        picked?.count ?? 0
    }

    func isPicked(_ key: String) -> Bool {
        picked?.contains(key) ?? false
    }

    /// 꾹 눌렀다 — 꺼져 있으면 켜고 그 줄을 고른다. 이미 켜져 있으면 그대로다.
    mutating func hold(_ key: String) {
        guard picked == nil else { return }
        picked = [key]
    }

    /// 켜진 동안 줄을 눌렀다 — 그 줄의 체크를 뒤집는다. 꺼져 있으면 아무 일도 없다.
    mutating func toggle(_ key: String) {
        guard var current = picked else { return }
        if current.remove(key) == nil {
            current.insert(key)
        }
        picked = current
    }

    /// 지금 목록을 다 골랐나. 꺼져 있거나 목록이 비면 아니다.
    func allPicked(_ keys: [String]) -> Bool {
        guard let picked, !keys.isEmpty else { return false }
        return keys.allSatisfy(picked.contains)
    }

    /// 머리의 [전체 선택 / 선택 해제] — 다 골랐으면 모두 풀고(켜진 채 0개), 아니면 목록 전부를 고른다.
    mutating func toggleAll(_ keys: [String]) {
        picked = allPicked(keys) ? [] : Set(keys)
    }

    /// [취소]·지우기를 마쳤다 — 끈다.
    mutating func cancel() {
        picked = nil
    }

    /// 목록이 바뀌었다(지워짐·새로 받음) — 목록에 없는 열쇠를 뺀다. 꺼져 있으면 그대로다.
    mutating func keep(only keys: [String]) {
        guard let current = picked else { return }
        picked = current.intersection(keys)
    }
}

/// 코스 목록의 글자.
enum CourseListText {
    /// 머리 가운데 — 꺼져 있으면 「내 여행」, 켜져 있으면 「N개 선택」(0개면 「코스 선택」).
    static func headerTitle(_ picking: CourseListPicking) -> String {
        guard picking.isOn else { return tr("내 여행") }
        return picking.count > 0 ? String(format: tr("%d개 선택"), picking.count) : tr("코스 선택")
    }

    /// 머리 오른쪽 — 다 골랐으면 「선택 해제」, 아니면 「전체 선택」.
    static func allButton(allPicked: Bool) -> String {
        allPicked ? tr("선택 해제") : tr("전체 선택")
    }

    /// 아래 빨간 단추 — 「N개 삭제」, 지우는 동안은 「지우는 중…」.
    static func deleteButton(count: Int, removing: Bool) -> String {
        removing ? tr("지우는 중…") : String(format: tr("%d개 삭제"), count)
    }

    /// 여러 개 지우기 확인 제목 — 「코스 N개를 지울까요?」.
    static func bulkConfirmTitle(count: Int) -> String {
        String(format: tr("코스 %d개를 지울까요?"), count)
    }

    /// 줄의 둘째 줄 — 「2일차 · 1박 2일 · 5곳」. 「N일차」 는 여행 중에만(모르면 1일차), 기간은 당일치기·N박 N+1일.
    static func subtitle(isRunning: Bool, currentDayNo: Int?, dayCount: Int, placeCount: Int) -> String {
        var parts: [String] = []
        if isRunning {
            parts.append(String(format: tr("%d일차"), currentDayNo ?? 1))
        }
        parts.append(NewCourseText.days(dayCount))
        parts.append(String(format: tr("%d곳"), placeCount))
        return parts.joined(separator: " · ")
    }
}

enum CourseListRules {
    /// 웹 `ProtoCourse.tsx` 와 같은 0.2초(해태 단추 끌기와 같다).
    static let holdSeconds = 0.2

    /// 작품 줄에 적는 작품 수의 상한.
    static let workLimit = 2

    /// 줄의 열쇠 — 서버 id 가 있으면 그것(「s123」), 아직 저장 전이면 화면 id.
    static func key(_ course: RouteCourse) -> String {
        course.serverId.map { "s\($0)" } ?? "u\(course.id.uuidString)"
    }

    /// 「여행 중」·「다가오는 여행」 두 묶음. 목록 순서를 지킨다.
    static func sections(_ courses: [RouteCourse]) -> (running: [RouteCourse], upcoming: [RouteCourse]) {
        (courses.filter(\.isRunning), courses.filter { !$0.isRunning })
    }

    /// 지울 코스 — 고른 것만, **목록 순서대로**.
    static func doomed(_ courses: [RouteCourse], picking: CourseListPicking) -> [RouteCourse] {
        courses.filter { picking.isPicked(key($0)) }
    }

    /// 작품 줄 — 장소마다 첫 작품을 차례로 모아 겹치지 않게, 최대 `workLimit` 개. 작품을 모르는 곳(nil·빈 글)은 건너뛴다.
    static func works(_ stops: [RouteStop], titleOf: (RouteStop) -> String?) -> [String] {
        var found: [String] = []
        for stop in stops {
            guard found.count < workLimit else { break }
            guard let title = titleOf(stop), !title.isEmpty, !found.contains(title) else { continue }
            found.append(title)
        }
        return found
    }

    /// 줄 사진 — 서버가 준 대표 사진(`thumbnailUrl`)이 먼저, 없으면 상세의 첫 사진 있는 장소.
    static func photo(thumbnail: String?, stops: [RouteStop]) -> String? {
        if let thumbnail, !thumbnail.isEmpty {
            return thumbnail
        }
        return stops.lazy.compactMap(\.place.imageUrl).first { !$0.isEmpty }
    }

    /// 고른 코스를 **하나씩 차례로** 지운다 — 서버에 여러 개 지우기가 없다. 앞의 것이 끝나야 다음을 부른다.
    static func removeOneByOne(_ doomed: [RouteCourse], delete: (RouteCourse) async -> Void) async {
        for course in doomed {
            await delete(course)
        }
    }
}
