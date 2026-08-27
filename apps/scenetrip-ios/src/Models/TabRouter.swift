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

    @Published var selected: RootTabs.Tab = .search

    /// 경로 탭이 열어 줘야 할 코스의 서버 id.
    @Published var pendingCourseId: Int64?

    /// 마이페이지가 부른다 — 탭을 바꾸고 쪽지를 남긴다.
    func openCourse(_ serverId: Int64) {
        pendingCourseId = serverId
        selected = .route
    }
}
