import Foundation

/// 홈 오른쪽 아래 떠 있는 「여행 코스 짜기」 단추의 규칙 (MZ2AZ-547 H4, 웹 `proto/ProtoScreens.tsx` `TripFloatBar`).
///
/// - 여행 중 코스가 없으면 단추는 「여행 코스 짜기」. 코스가 0개(목록을 받아 본 뒤)면 누르자마자 새 코스로,
///   있으면 단추가 오그라들고 오른쪽부터 알약 줄이 미끄러져 들어온다.
/// - 여행 중 코스가 있으면 단추가 곧 그 코스(누르면 바로 열림)이고 옆 ≡ 로 줄을 펼친다.
enum HomeTripFloatRules {
    /// 줄에 올리는 내 코스의 최대 수.
    static let maxCourses = 5
    /// 펼친 줄의 알약이 하나씩 들어오는 간격(초).
    static let stagger: Double = 0.045

    /// 줄에 올릴 코스 — 서버에 저장된 것만, 여행 중인 것 먼저(그 안에서는 받은 순서), 최대 `maxCourses`.
    static func courses(_ all: [RouteCourse]) -> [RouteCourse] {
        let saved = all.filter { $0.serverId != nil }
        return Array((saved.filter(\.isRunning) + saved.filter { !$0.isRunning }).prefix(maxCourses))
    }

    /// 접힌 단추의 모습.
    enum Collapsed: Equatable {
        /// 「여행 코스 짜기」.
        case plan
        /// 여행 중인 그 코스 — 누르면 바로 열린다.
        case running(RouteCourse)
    }

    /// 줄에 올릴 코스(`courses` 의 결과) 중 여행 중인 첫 코스가 있으면 `.running`, 없으면 `.plan`.
    static func collapsed(_ courses: [RouteCourse]) -> Collapsed {
        if let running = courses.first(where: { $0.isRunning && $0.serverId != nil }) {
            return .running(running)
        }
        return .plan
    }

    /// 「여행 코스 짜기」 를 눌렀을 때.
    enum PlanTap: Equatable {
        /// 새 코스 만들기로 바로 간다.
        case newCourse
        /// 줄을 펼친다.
        case expand
    }

    /// 코스가 **정말 없을 때**(목록을 받았고 0개)만 바로 새 코스. 아직 모르거나 못 받았으면 줄을 펼친다 —
    /// 코스가 있는 사람에게 새로 만들기를 들이밀지 않는다(MZ2AZ-372 와 같은 이유).
    static func planTap(courseList: RouteStore.CourseListState, courseCount: Int) -> PlanTap {
        courseList == .loaded && courseCount == 0 ? .newCourse : .expand
    }

    /// 펼친 줄의 알약 — **오른쪽 끝(단추 자리)부터** 이 순서로 놓인다.
    enum Item: Equatable {
        /// ✕ 접기.
        case close
        /// ≡ 전체 코스.
        case all
        /// 「+ 새 코스」.
        case newCourse
        /// 내 코스 하나.
        case course(RouteCourse)
    }

    /// ✕ · ≡ · 「+ 새 코스」 · 내 코스들(받은 순서).
    static func items(_ courses: [RouteCourse]) -> [Item] {
        [.close, .all, .newCourse] + courses.map(Item.course)
    }

    /// i 번째 알약이 들어오기까지 기다리는 시간(초). 접을 때는 모두 0.
    static func delay(index: Int, open: Bool) -> Double {
        open ? Double(max(0, index)) * stagger : 0
    }
}
