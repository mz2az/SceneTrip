import SwiftUI

/// 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 두 화면의 덮개를 든다.
///
/// 2026-11-10 웹 시안 반영(MZ2AZ-547 T1·T2): 탭바는 화면 바닥에 떠 있는 알약이고 탭 내용이 그 뒤까지 내려간다.
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
            case .search: tr("작품검색")
            case .home: tr("홈")
            case .community: tr("커뮤니티")
            }
        }

        var symbol: String {
            switch self {
            // 지도 — 「지도에서 촬영지를 찾는 탭」(웹 시안과 같다, MZ2AZ-547 T1).
            case .search: "map"
            case .home: "house"
            case .community: "bubble.left.and.bubble.right"
            }
        }
    }

    /// 탭 선택과 덮개를 `TabRouter` 가 든다 — 마이페이지가 「경로여정에서 열기」로,
    /// 홈이 「코스 보기」로 화면을 바꿀 수 있어야 해서다.
    @ObservedObject var router = TabRouter.shared

    /// 경로여정의 상태는 **여기서 든다.**
    ///
    /// 경로여정 화면은 덮개라 닫으면 사라진다 — 지도를 든 화면이 상시로 떠 있으면
    /// 첫 화면의 비용이 커지고, 코스 목록은 다시 만들어도 값이 싸다. 대신 만든
    /// 코스까지 사라지면 안 되므로 **데이터만** 화면 밖에서 들고, 홈의 「내 여행」
    /// 카드와 「여행자들의 코스」 절도 같은 것을 읽는다.
    @StateObject private var routes = RouteStore()

    var body: some View {
        GeometryReader { root in
            ZStack {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다.
                // 촬영지 상세 「길찾기」가 코스를 만들고 연다(MZ2AZ-547 G3) — 홈·경로여정과 같은 코스 목록을 쓴다.
                SearchTabView()
                    .environmentObject(routes)
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
            // **탭 내용이 알약 뒤까지 내려간다**(MZ2AZ-547 T2). 알약을 바닥 안전영역 쪽 여백으로 깔면 스크롤 목록은
            // 알약 뒤로 비치며 내려가고, 마지막 줄은 알약 위에서 멈춘다(위 8 + 알약 58 + 아래 8 = 74). 지도처럼 안전영역을
            // 무시하는 것은 알약 뒤까지 그린다. 알약 밖의 빈 여백은 손가락을 받지 않아 밑으로 통과한다.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                TabBar(selected: $router.selected)
                    .padding(.horizontal, FloatingTabBarRules.sideMargin)
                    .padding(.top, FloatingTabBarRules.clearance - FloatingTabBarRules.height - FloatingTabBarRules.bottomGap)
                    .padding(.bottom, FloatingTabBarRules.bottomGap)
            }
            // 안전영역을 무시하고 바닥까지 그리는 것(검색 탭 서랍)이 비워야 할 높이 — 알약 몫 + 기기 안전영역.
            .environment(\.tabBarClearance, FloatingTabBarRules.clearance + root.safeAreaInsets.bottom)
        }
        .ignoresSafeArea(.keyboard)
        // 어느 화면을 보는가 — 탭과 덮개 단위로 적는다 (MZ2AZ-353).
        .onAppear { AppAnalytics.log(.screenView(screenName)) }
        .onChange(of: screenName) { _, name in AppAnalytics.log(.screenView(name)) }
        // 계정이 바뀌면 코스가 다른 사람 것이다 — 홈과 경로여정이 같이 보는 목록을 다시 읽는다.
        .onAccountChange { await routes.refresh() }
        // 덮개(경로여정·마이페이지)가 떠 있으면 그쪽이 로그인 화면을 올린다.
        .signInSheet(active: router.cover == nil)
        .nicknameSheet(active: router.cover == nil)
        .fullScreenCover(item: $router.cover) { cover in
            switch cover {
            case .route:
                RouteTabView(onClose: { router.cover = nil })
                    .environmentObject(routes)
            case .profile:
                ProfileTabView(onClose: { router.cover = nil })
            case .trending:
                TrendingWorksView(
                    onClose: { router.cover = nil },
                    onOpen: { work in
                        router.cover = nil
                        router.openContent(work)
                    }
                )
            }
        }
    }
}

extension RootTabs {
    /// 지금 보이는 화면의 이름. 덮개가 떠 있으면 덮개가 이긴다.
    var screenName: String {
        switch router.cover {
        case .route: return "courses"
        case .profile: return "profile"
        // 전체 보기는 홈의 일부로 센다 — 새 화면 이름을 늘리지 않는다(웹 MZ2AZ-534 와 같다).
        case .trending: return "home"
        case nil: break
        }
        switch router.selected {
        case .search: return "search"
        case .home: return "home"
        case .community: return "community"
        }
    }
}

/// 탭바 — **떠 있는 알약 하나**에 세 탭이 같은 폭으로 (MZ2AZ-547 T1, 웹 `features/proto/ProtoTabBar.tsx`).
///
/// 전에는 가운데 홈만 위로 솟은 동그라미였다(2026-09-01 목업). 탭이 셋뿐인데 홈만 솟아 어색하다는 사용자 지적(웹 11-07)으로
/// 셋을 같은 폭으로 두고, 고른 탭 뒤에 보라 단색 알약이 0.38초에 걸쳐 미끄러진다. 반투명 유리(바탕 88% + 흐림)는 앱에서
/// 이 탭바 한 곳에만 쓴다. 탭 내용은 이 알약 뒤까지 내려가 비친다 — 자리 잡기는 `RootTabs` 가 한다(T2).
struct TabBar: View {
    @Binding var selected: RootTabs.Tab

    /// 목업의 홈 글자색(`#5B49D6`) — 앱의 시그니처 보라 단색. 흰 글씨 대비 약 6:1 이라 고른 탭 알약·주 단추 바탕에 쓴다.
    static let homePurple = Color(red: 0.36, green: 0.29, blue: 0.84)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let tabs = RootTabs.Tab.allCases
            let index = tabs.firstIndex(of: selected) ?? 0
            let inner = FloatingTabBarRules.innerPadding
            ZStack(alignment: .leading) {
                // 고른 탭 뒤의 알약 — 탭을 바꾸면 미끄러져 옮겨 간다.
                Capsule()
                    .fill(Self.homePurple)
                    .frame(
                        width: FloatingTabBarRules.selectionWidth(barWidth: geo.size.width, tabCount: tabs.count),
                        height: max(0, geo.size.height - inner * 2)
                    )
                    .offset(x: FloatingTabBarRules.selectionOffset(
                        index: index, barWidth: geo.size.width, tabCount: tabs.count
                    ))
                    .animation(
                        reduceMotion ? nil : .timingCurve(0.22, 1, 0.36, 1, duration: FloatingTabBarRules.slideDuration),
                        value: selected
                    )
                HStack(spacing: 0) {
                    ForEach(tabs, id: \.self) { tab in
                        button(tab)
                    }
                }
                .padding(.horizontal, inner)
            }
        }
        .frame(height: FloatingTabBarRules.height)
        .background {
            ZStack {
                Capsule().fill(.ultraThinMaterial)
                Capsule().fill(Color(.systemBackground).opacity(0.88))
            }
            .shadow(color: .black.opacity(0.14), radius: 10, y: 6)
        }
    }

    private func button(_ tab: RootTabs.Tab) -> some View {
        let on = selected == tab
        return Button {
            selected = tab
        } label: {
            HStack(spacing: 6) {
                icon(tab, on: on)
                Text(tab.label)
                    .font(.system(size: 13, weight: on ? .heavy : .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .foregroundStyle(on ? Color.white : Color.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Capsule())
            .animation(.easeInOut(duration: 0.2), value: on)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    @ViewBuilder
    private func icon(_ tab: RootTabs.Tab, on: Bool) -> some View {
        if tab == .home {
            // 홈은 해태 얼굴을 작게 — 고르지 않으면 흐리게(웹 `grayscale(0.6) opacity(0.8)`).
            Image("haetae-face")
                .resizable()
                .scaledToFit()
                .frame(width: 26, height: 22)
                .saturation(on ? 1 : 0.4)
                .opacity(on ? 1 : 0.8)
                .accessibilityHidden(true)
        } else {
            Image(systemName: tab.symbol)
                .font(.system(size: 18))
                .foregroundStyle(on ? Color.white : Color.secondary)
                .accessibilityHidden(true)
        }
    }
}

extension EnvironmentValues {
    /// 화면 바닥에서 떠 있는 탭바가 차지하는 높이(알약 몫 74 + 기기 안전영역). 탭 밖(덮개)에서는 0.
    /// 안전영역을 무시하고 바닥까지 그리는 것만 읽는다 — 스크롤 목록은 `safeAreaInset` 으로 저절로 비운다.
    @Entry var tabBarClearance: CGFloat = 0
}
