import SceneApiClient
import SwiftUI

/// 홈 — 첫 화면 (2026-09-01, 목업 `docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
///
/// 위에서부터: 인사 · **내 여행 이어가기**(경로여정의 입구) · 지금 뜨는 작품 · 오늘의
/// 성지 · 여행자들의 코스 · 커뮤니티 지금 · **내 기록**(마이페이지의 입구). 목업의 맨 윗줄
/// 검색칸은 **뺐다** — 검색은 작품검색 탭이 맡는다(사용자 지시).
///
/// 숫자·문구는 전부 서버·기기 값이다. 목업이 "카드 속 숫자·글은 배치용 예시" 라고
/// 못 박았다.
struct HomeTabView: View {
    @EnvironmentObject private var routes: RouteStore
    @ObservedObject private var router = TabRouter.shared
    @ObservedObject private var posts = CommunityStore.shared
    @ObservedObject private var likes = LikeStore.shared
    @StateObject private var model = HomeTabModel()

    /// 「이어서 길찾기」·「오늘의 성지 길찾기」 가 여는 길찾기 화면.
    @State private var nav: HomeNavTarget?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HomeHeader(onProfile: { router.openProfile() })

                HomeTripPager(
                    trips: model.trips,
                    hasCourses: !routes.courses.isEmpty,
                    loading: model.loading && model.trips.isEmpty,
                    onNavigate: { trip in
                        guard let stop = trip.nextStop else { return }
                        nav = HomeNavTarget(
                            stop: stop, dayStops: trip.dayStops, courseId: trip.course.serverId
                        )
                    },
                    // 「코스 보기」는 **코스 전체 목록**으로 간다(2026-09-02 사용자
                    // 결정). 처음엔 그 코스의 편집으로 바로 들어갔는데, 홈에서
                    // 누르는 사람은 "내 코스들이 뭐가 있나" 를 보려는 것이다.
                    onOpenCourse: { _ in router.openRoute() },
                    onCreate: { router.openRoute() }
                )

                HomeWorkShelf(
                    works: model.works,
                    failed: model.failed,
                    onOpen: { router.openContent($0.id) },
                    onAll: { router.selected = .search }
                )

                if let place = model.today {
                    HomeTodayCard(place: place) {
                        nav = HomeNavTarget(stop: RouteStop(place: place), dayStops: [], courseId: nil)
                    }
                }

                HomeMarketPreview(
                    courses: Array(routes.marketCourses.prefix(2)),
                    onOpen: { router.openRoute(market: true) }
                )

                HomeCommunityNow(
                    posts: Array(posts.posts.prefix(2)),
                    onOpen: { router.selected = .community }
                )

                HomeMyRecord(
                    stamps: model.stamps,
                    likeCount: likes.contentIds.count,
                    onOpen: { router.openProfile() }
                )
            }
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
        .background(Color(.systemGroupedBackground))
        .task { await reload() }
        .refreshable { await reload() }
        // 덮개(경로여정·마이페이지)를 닫고 돌아오면 코스·스탬프가 달라졌을 수 있다.
        .onChange(of: router.cover) { old, new in
            if old != nil, new == nil {
                Task { await reload() }
            }
        }
        .fullScreenCover(item: $nav, onDismiss: { Task { await reload() } }) { target in
            RouteNavView(stop: target.stop, dayStops: target.dayStops, courseId: target.courseId)
                .environmentObject(routes)
        }
    }

    private func reload() async {
        await routes.refresh()
        await routes.refreshMarket()
        await model.load(courses: routes.courses)
    }
}

/// 홈에서 여는 길찾기의 목적지.
struct HomeNavTarget: Identifiable {
    let id = UUID()
    let stop: RouteStop
    let dayStops: [RouteStop]
    let courseId: Int64?
}

/// 절 머리줄 — 제목 · 흐린 부제 · 오른쪽 파란 링크. 홈의 절이 전부 이 모양이다.
struct HomeSectionHeader: View {
    let title: String
    var subtitle: String?
    var action: String?
    var onAction: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title).font(.system(size: 17, weight: .heavy))
            if let subtitle {
                Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if let action, let onAction {
                Button(action: onAction) {
                    Text(action).font(.system(size: 13, weight: .semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
        }
        .padding(.horizontal, 20)
    }
}

/// 흰 카드 바탕 — 목업의 `radius 18 · shadow 0 2 8 rgba(0,0,0,.07)`.
struct HomeCardBackground: ViewModifier {
    var radius: CGFloat = 18

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(Color(.systemBackground))
                    .shadow(color: .black.opacity(0.07), radius: 4, y: 2)
            )
    }
}

extension View {
    func homeCard(radius: CGFloat = 18) -> some View {
        modifier(HomeCardBackground(radius: radius))
    }
}
