import SwiftUI

/// 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 두 화면의 덮개를 든다.
///
/// 2026-09-01 홈 재편(계획 `docs/project/plans/mobile-home-tab.md`, 목업
/// `docs/product/canvas/home/`): 탭 넷(작품검색·경로여정·커뮤니티·마이페이지)이
/// **셋**(작품검색 · 가운데 동그란 홈 · 커뮤니티)이 됐고 첫 화면은 홈이다. 경로여정은
/// 홈의 「내 여행 이어가기」 카드가, 마이페이지는 홈 오른쪽 위 프로필 단추가 입구다 —
/// 두 화면의 코드는 그대로고 `TabRouter.cover` 로 전체 화면에 띄운다.
///
/// `TabView` 는 선택된 탭만 만들고 전환할 때마다 다시 만든다. 검색 탭은 지도 SDK 와
/// 서버 호출을 들고 있어 그 비용이 크므로 **상태를 유지**해야 한다 — 베타도 같은
/// 이유로 `IndexedStack` 을 썼다. 홈도 같은 이유로 살려 둔다(서버를 다시 부르지
/// 않게). 커뮤니티는 값이 싸서 만들고 버린다.
struct RootTabs: View {
    enum Tab: Int, CaseIterable {
        case search, home, community

        var label: String {
            switch self {
            case .search: "작품검색"
            case .home: "홈"
            case .community: "커뮤니티"
            }
        }

        var symbol: String {
            switch self {
            case .search: "magnifyingglass"
            case .home: "house"
            case .community: "bubble.left.and.bubble.right"
            }
        }
    }

    /// 탭 선택과 덮개를 `TabRouter` 가 든다 — 마이페이지가 「경로여정에서 열기」로,
    /// 홈이 「코스 보기」로 화면을 바꿀 수 있어야 해서다.
    @ObservedObject private var router = TabRouter.shared

    /// 경로여정의 상태는 **여기서 든다.**
    ///
    /// 경로여정 화면은 덮개라 닫으면 사라진다 — 지도를 든 화면이 상시로 떠 있으면
    /// 첫 화면의 비용이 커지고, 코스 목록은 다시 만들어도 값이 싸다. 대신 만든
    /// 코스까지 사라지면 안 되므로 **데이터만** 화면 밖에서 들고, 홈의 「내 여행」
    /// 카드와 「여행자들의 코스」 절도 같은 것을 읽는다.
    @StateObject private var routes = RouteStore()

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다.
                SearchTabView()
                    .opacity(router.selected == .search ? 1 : 0)
                    .allowsHitTesting(router.selected == .search)

                HomeTabView()
                    .environmentObject(routes)
                    .opacity(router.selected == .home ? 1 : 0)
                    .allowsHitTesting(router.selected == .home)

                if router.selected == .community {
                    CommunityTabView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            TabBar(selected: $router.selected)
        }
        .ignoresSafeArea(.keyboard)
        .fullScreenCover(item: $router.cover) { cover in
            switch cover {
            case let .route(market):
                RouteTabView(onClose: { router.cover = nil }, startInMarket: market)
                    .environmentObject(routes)
            case .profile:
                ProfileTabView(onClose: { router.cover = nil })
            }
        }
    }
}

/// 탭바 — 양옆은 얇은 아이콘, 가운데는 **위로 솟은 동그란 홈**(해태 얼굴).
///
/// 목업(`Main.dc.html`)의 배치를 그대로 옮겼다: 62 pt 원에 핀 그러데이션, 바탕색
/// 4 pt 테, 26 pt 만큼 바 위로 나온다. 바 자체는 56 pt — 베타의 52 보다 4 pt 높은
/// 것은 가운데 원의 글자가 들어갈 자리다. 검색 탭 바텀시트의 최대 높이가 이 위까지라
/// 그 이상 키우지 않는다.
struct TabBar: View {
    @Binding var selected: RootTabs.Tab

    /// 목업의 홈 글자색(`#5B49D6`) — 핀 보라보다 한 톤 짙어 흰 바탕에서 읽힌다.
    static let homePurple = Color(red: 0.36, green: 0.29, blue: 0.84)

    var body: some View {
        HStack(spacing: 0) {
            side(.search)
            home
            side(.community)
        }
        .frame(height: 56)
        .background(Color(.systemBackground))
        .overlay(alignment: .top) { Divider() }
    }

    private func side(_ tab: RootTabs.Tab) -> some View {
        Button {
            selected = tab
        } label: {
            VStack(spacing: 3) {
                Image(systemName: tab.symbol).font(.system(size: 20))
                Text(tab.label).font(.system(size: 11))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(selected == tab ? Color.accentColor : .secondary)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private var home: some View {
        Button {
            selected = .home
        } label: {
            VStack(spacing: 3) {
                ZStack {
                    Circle().fill(LinearGradient(
                        colors: [Color(PinImage.light), Color(PinImage.deep)],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    ))
                    Image("haetae-face")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 40, height: 34)
                }
                .frame(width: 62, height: 62)
                .overlay(Circle().stroke(Color(.systemGroupedBackground), lineWidth: 4))
                .shadow(color: Color(PinImage.deep).opacity(0.42), radius: 7, y: 6)
                Text("홈")
                    .font(.system(size: 11, weight: selected == .home ? .bold : .regular))
                    .foregroundStyle(selected == .home ? Self.homePurple : .secondary)
            }
            .offset(y: -18)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
