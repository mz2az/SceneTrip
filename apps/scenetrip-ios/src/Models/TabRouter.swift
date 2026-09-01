import Foundation

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
        /// 경로여정. `market` 이면 「둘러보기」(옛 코스마켓) 세그먼트로 연다.
        case route(market: Bool)
        case profile

        var id: String {
            switch self {
            case let .route(market): market ? "route-market" : "route"
            case .profile: "profile"
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

    /// 작품검색 탭이 열어 줘야 할 작품의 서버 id — 홈의 「지금 뜨는 작품」이 남긴다.
    @Published var pendingContentId: Int64?

    private init() {
        let defaults = UserDefaults.standard
        switch defaults.string(forKey: "initialTab") {
        case "search":
            selected = .search
        case "community":
            selected = .community
        case "route":
            selected = .home
            cover = .route(market: false)
        case "profile":
            selected = .home
            cover = .profile
        default:
            selected = .home
        }
        let raw = defaults.integer(forKey: "openCourseId")
        pendingCourseId = raw > 0 ? Int64(raw) : nil
    }

    /// 마이페이지·홈이 부른다 — 경로여정을 열고 그 코스의 편집으로 들어간다.
    func openCourse(_ serverId: Int64) {
        pendingCourseId = serverId
        cover = .route(market: false)
    }

    func openRoute(market: Bool = false) {
        cover = .route(market: market)
    }

    func openProfile() {
        cover = .profile
    }

    /// 홈이 부른다 — 작품검색 탭으로 가서 그 작품의 상세를 연다.
    func openContent(_ contentId: Int64) {
        pendingContentId = contentId
        selected = .search
    }
}
