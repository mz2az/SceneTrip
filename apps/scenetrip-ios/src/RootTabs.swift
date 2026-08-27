import SwiftUI

/// 앱의 최상위 — 하단 탭 넷을 든다.
///
/// 계획서 §2 는 「작품검색만 만들고 나머지는 자리만」이었다 — 이제 넷 다 화면이
/// 있다. 경로여정은 본편이고, 커뮤니티·마이페이지는 **임시판**이다(2026-08-28,
/// 각 파일 머리말 참고).
///
/// 비어 있어도 지금 만드는 이유는 두 가지다. 화면이 하나뿐인 앱과 넷 중 하나인 앱은
/// **검색 탭이 차지하는 세로 공간이 다르다** — 바텀시트의 최대 높이가 탭바 위까지이므로
/// 나중에 붙이면 §3-1 의 스냅 비율을 다시 잡아야 한다. 그리고 베타가 이 구조였으므로
/// 나란히 비교할 때 기준이 맞는다.
///
/// `TabView` 는 선택된 탭만 만들고 전환할 때마다 다시 만든다. 검색 탭은 지도 SDK 와
/// 서버 호출을 들고 있어 그 비용이 크므로 **상태를 유지**해야 한다 — 베타도 같은
/// 이유로 `IndexedStack` 을 썼다.
struct RootTabs: View {
    enum Tab: Int, CaseIterable {
        case search, route, community, profile

        var label: String {
            switch self {
            case .search: "작품검색"
            case .route: "경로여정"
            case .community: "커뮤니티"
            case .profile: "마이페이지"
            }
        }

        var symbol: String {
            switch self {
            case .search: "magnifyingglass"
            case .route: "point.topleft.down.to.point.bottomright.curvepath"
            case .community: "bubble.left.and.bubble.right"
            case .profile: "person"
            }
        }
    }

    /// 탭 선택을 `TabRouter` 가 든다 — 마이페이지가 「경로여정에서 열기」로
    /// 탭을 바꿀 수 있어야 해서다(2026-08-28).
    @ObservedObject private var router = TabRouter.shared

    /// 경로여정 탭의 상태는 **여기서 든다.**
    ///
    /// 그 탭은 검색 탭과 달리 살려 두지 않는다 — 지도를 든 화면이 둘 다 상시로 떠
    /// 있으면 첫 화면의 비용이 두 배가 되고, 코스 목록은 다시 만들어도 값이 싸다.
    /// 대신 만든 코스까지 사라지면 안 되므로 **데이터만** 탭 밖에서 든다.
    @StateObject private var routes = RouteStore()

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다.
                SearchTabView()
                    .opacity(router.selected == .search ? 1 : 0)
                    .allowsHitTesting(router.selected == .search)

                if router.selected == .route {
                    RouteTabView().environmentObject(routes)
                } else if router.selected == .community {
                    CommunityTabView()
                } else if router.selected == .profile {
                    ProfileTabView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            TabBar(selected: $router.selected)
        }
        .ignoresSafeArea(.keyboard)
    }
}

/// 얇고 칸이 나뉜 탭바. 베타와 같은 52pt 높이다 — iOS 기본 탭바(약 83pt)보다 낮게 잡아
/// 지도에 주는 세로 공간을 지킨다.
struct TabBar: View {
    @Binding var selected: RootTabs.Tab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(RootTabs.Tab.allCases.enumerated()), id: \.element) { index, tab in
                if index > 0 {
                    Divider().frame(height: 28)
                }
                Button {
                    selected = tab
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: tab.symbol).font(.system(size: 16))
                        Text(tab.label).font(.system(size: 10))
                    }
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(selected == tab ? Color.accentColor : .secondary)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 52)
        .background(Color(.systemBackground))
        .overlay(alignment: .top) { Divider() }
    }
}
