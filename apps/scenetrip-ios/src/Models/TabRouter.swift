import Foundation

/// 탭 사이를 잇는 **길 안내** (2026-08-28).
///
/// 마이페이지에서 코스를 누르고 「경로여정에서 열기」를 고르면, 탭이 바뀌고
/// 그 코스의 편집 화면이 열려야 한다. 탭 선택은 `RootTabs` 가 들고 있고 코스를
/// 여는 것은 `RouteTabView` 가 하므로, 둘 사이를 이 하나가 잇는다.
///
/// `pendingCourseId` 는 **한 번 쓰고 버리는 쪽지**다 — 경로 탭이 열어 주고 nil 로
/// 되돌린다. 남겨 두면 탭에 올 때마다 같은 코스가 또 열린다.
@MainActor
final class TabRouter: ObservableObject {
    static let shared = TabRouter()

    /// 확인용 뒷문 — `simctl launch … -initialTab profile` 로 첫 탭을 지정한다.
    /// 합성 클릭이 시뮬레이터에 안 닿아(메모 ios-sim-verification) 탭 이동을
    /// 기계로 할 길이 이것뿐이다. 인자를 안 주면 여느 때처럼 검색이다.
    @Published var selected: RootTabs.Tab = switch UserDefaults.standard.string(forKey: "initialTab") {
    case "route": .route
    case "community": .community
    case "profile": .profile
    default: .search
    }

    /// 경로 탭이 열어 줘야 할 코스의 서버 id.
    ///
    /// 확인용 뒷문 — `-openCourseId 26` 으로 켜면 경로 탭이 그 코스 편집을 바로
    /// 연다(`-initialTab route` 와 함께). 합성 클릭이 안 닿는 시뮬레이터에서
    /// 화면 캡쳐·검증에 쓴다(MZ2AZ-292). 쪽지 규칙은 마이페이지와 같다 —
    /// 한 번 읽으면 버린다.
    @Published var pendingCourseId: Int64? = {
        let raw = UserDefaults.standard.integer(forKey: "openCourseId")
        return raw > 0 ? Int64(raw) : nil
    }()

    /// 경로여정 목록의 「이어서 길찾기」 — 코스를 열자마자 **첫 미방문 성지로 안내를
    /// 켠다**(2026-09-03, 계획 trip-mode.md §8 · main 은 MZ2AZ-311). 편집 화면이 읽고 끈다.
    @Published var pendingTripStart = false

    /// 마이페이지가 부른다 — 탭을 바꾸고 쪽지를 남긴다.
    func openCourse(_ serverId: Int64) {
        pendingCourseId = serverId
        selected = .route
    }
}
