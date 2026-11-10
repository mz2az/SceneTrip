import Foundation
import SceneApiClient

/// 탭 사이를 잇는 **길 안내** (2026-08-28, 2026-09-01 홈 재편).
///
/// 탭은 셋이다 — 작품검색 · 홈 · 커뮤니티(계획 `docs/project/plans/mobile-home-tab.md`).
/// 경로여정과 마이페이지는 탭에서 내려와 **홈이 띄우는 전체 화면 덮개**(`cover`)가 됐다.
/// 덮개는 한 번에 하나다 — 마이페이지에서 「경로여정에서 열기」를 누르면 값이
/// `.profile` 에서 `.route` 로 바뀌고 SwiftUI 가 덮개를 갈아 끼운다. 둘을 겹치지 않는다.
///
/// `pendingCourseId`·`pendingContentId` 는 **한 번 쓰고 버리는 쪽지**다 — 받는 화면이
/// 열어 주고 nil 로 되돌린다. 남겨 두면 그 화면에 올 때마다 같은 것이 또 열린다.
@MainActor
final class TabRouter: ObservableObject {
    static let shared = TabRouter()

    /// 홈이 띄우는 전체 화면. 탭바에 없는 화면은 여기로 연다.
    enum Cover: Identifiable, Equatable {
        /// 경로여정.
        case route
        case profile
        /// 「지금 뜨는 작품」 전체 보기 — 인기순 포스터 격자(MZ2AZ-547 H6, 웹 MZ2AZ-534).
        case trending
        /// 새 코스 만들기 — 도시로 시작(질문)·작품으로(AI)·담은 곳으로(MZ2AZ-547 P3).
        case newCourse(NewCourseStart)

        var id: String {
            switch self {
            case .route: "route"
            case .profile: "profile"
            case .trending: "trending"
            case let .newCourse(start):
                switch start {
                case let .city(key): "newCourse.city.\(key ?? "-")"
                case .works: "newCourse.works"
                case .saved: "newCourse.saved"
                }
            }
        }
    }

    /// 확인용 뒷문 — `simctl launch … -initialTab profile` 로 첫 화면을 지정한다.
    /// 합성 클릭이 시뮬레이터에 안 닿아(메모 ios-sim-verification) 화면 이동을
    /// 기계로 할 길이 이것뿐이다. `route`·`profile` 은 이제 탭이 아니라 홈 위의
    /// 덮개이므로 홈 + 덮개로 푼다. 인자를 안 주면 **홈**이다.
    @Published var selected: RootTabs.Tab

    @Published var cover: Cover?

    /// 경로 탭이 열어 줘야 할 코스의 서버 id.
    ///
    /// 확인용 뒷문 — `-openCourseId 26` 으로 켜면 경로여정이 그 코스 편집을 바로
    /// 연다(`-initialTab route` 와 함께). 화면 캡쳐·검증에 쓴다(MZ2AZ-292).
    @Published var pendingCourseId: Int64?

    /// 홈 「이어서 길찾기」 — 코스를 열자마자 **첫 미방문 성지로 안내를 켠다**(2026-09-03,
    /// 계획 trip-mode.md §8). 편집 화면이 읽고 끈다.
    @Published var pendingTripStart = false

    /// 경로여정이 띄워 줘야 할 **일정짜기 마법사**(「AI 로 짜기」) — 가이드 챗봇의 한도 안내가 남긴다(MZ2AZ-366).
    /// 편집 화면이 닫힌 뒤에 코스 목록이 읽고 끈다.
    @Published var pendingPlanner = false

    /// 「새 여행 코스」 시트(F1, 세 갈래)를 홈 위에 띄우는가 — 홈 떠 있는 줄의 「+ 새 코스」·코스 0개의 「여행 코스 짜기」.
    /// 코스 목록(경로여정 덮개)의 「+ 새 코스」 는 그 덮개가 같은 시트를 직접 띄운다.
    @Published var newCourseSheet = false

    /// 방금 「도시로」 만든 코스의 안내 띠(R6) — 계획 화면 위에 6초 뜬다.
    struct JustCreated: Equatable {
        /// 보이는 도시 이름.
        let city: String
        let days: Int
    }

    @Published var justCreated: JustCreated?

    /// 방금 「도시로」 만든 코스(서버 id) — 계획 화면이 편집 상태로 열고, 저장 없이 나가면 묻고 지운다(E16, P4 가 마무리).
    @Published private(set) var freshCourseId: Int64?

    /// 작품검색 탭이 열어 줘야 할 작품 — 홈의 「지금 뜨는 작품」이 남긴다.
    ///
    /// id 가 아니라 **요약 자체**를 넘긴다(2026-09-16). id 만 넘겼더니 검색 탭이 자기
    /// 목록(`data.contents`)에서 찾았는데, 그 탭에서 한 번이라도 검색한 뒤면 목록이
    /// 걸러져 있어 못 찾고 조용히 끝났다 — 홈에서 눌러도 아무 일이 없었다.
    @Published var pendingContent: ContentSummary?

    /// 작품검색 탭이 열어 줘야 할 촬영지 — 홈의 「오늘의 성지」가 남긴다.
    @Published var pendingPlace: PlaceSummary?

    private init() {
        let defaults = UserDefaults.standard
        switch defaults.string(forKey: "initialTab") {
        case "search":
            selected = .search
        case "community":
            selected = .community
        case "route":
            selected = .home
            cover = .route
        case "profile":
            selected = .home
            cover = .profile
        case "trending":
            selected = .home
            cover = .trending
        default:
            selected = .home
        }
        // 확인용 뒷문(MZ2AZ-547 P3) — `-newCourse sheet|city|works|saved` 는 새 코스 시트·흐름을, `-cityFlow 부산` 은
        // 그 도시의 「며칠?」 부터 연다(시뮬레이터에 합성 탭이 닿지 않아 화면 확인용).
        switch defaults.string(forKey: "newCourse") {
        case "sheet": newCourseSheet = true
        case "city": cover = .newCourse(.city(nil))
        case "works": cover = .newCourse(.works)
        case "saved": cover = .newCourse(.saved)
        default: break
        }
        if let city = defaults.string(forKey: "cityFlow"), !city.isEmpty {
            selected = .home
            cover = .newCourse(.city(city))
        }
        let raw = defaults.integer(forKey: "openCourseId")
        pendingCourseId = raw > 0 ? Int64(raw) : nil
        // 확인용 뒷문(MZ2AZ-547 P4) — `-freshCourse 1` 은 `-openCourseId` 로 여는 코스를 「방금 만든 코스」 로 연다(E16).
        if defaults.bool(forKey: "freshCourse") {
            freshCourseId = pendingCourseId
        }
    }

    /// 마이페이지·홈이 부른다 — 경로여정을 열고 그 코스의 편집으로 들어간다.
    func openCourse(_ serverId: Int64) {
        pendingCourseId = serverId
        cover = .route
    }

    func openRoute() {
        cover = .route
    }

    func openProfile() {
        cover = .profile
    }

    /// 새 코스 만들기의 입구 하나 — 홈의 도시 칩(`city`)은 「며칠?」(Q2)부터, 「+ 새 코스」(nil)는 새 코스 시트(F1).
    func startNewCourse(city: String?) {
        if let city {
            cover = .newCourse(.city(city))
        } else {
            newCourseSheet = true
        }
    }

    /// 새 코스 시트에서 한 갈래를 골랐다 — 시트를 닫고 그 흐름을 덮개로 연다.
    func chooseNewCourse(_ start: NewCourseStart) {
        newCourseSheet = false
        cover = .newCourse(start)
    }

    /// 「이 코스로 만들기」·「N곳으로 코스 만들기」 로 서버에 만든 직후 — 방금 만든 코스로 표시하고 안내 띠를 켠 뒤 계획 화면으로.
    func courseCreated(id: Int64, city: String, days: Int) {
        freshCourseId = id
        justCreated = JustCreated(city: city, days: days)
        openCourse(id)
    }

    /// 이 코스가 방금 「도시로」 만든 코스인가.
    func isFreshCourse(_ id: Int64?) -> Bool {
        id != nil && id == freshCourseId
    }

    /// 저장했거나 지웠다 — 더는 방금 만든 코스가 아니다.
    func clearFreshCourse() {
        freshCourseId = nil
    }

    /// 홈 「지금 뜨는 작품」 의 「전체 보기」.
    func openTrending() {
        cover = .trending
    }

    /// 홈이 부른다 — 작품검색 탭으로 가서 그 작품의 상세를 연다.
    func openContent(_ content: ContentSummary) {
        pendingContent = content
        selected = .search
    }

    /// 홈이 부른다 — 작품검색 탭으로 가서 그 촬영지의 상세를 연다.
    func openPlace(_ place: PlaceSummary) {
        pendingPlace = place
        selected = .search
    }
}
