import SceneApiClient
import SwiftUI

/// 홈 — 첫 화면 (2026-09-01, 목업 `docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
///
/// 위에서부터: 인사 · **어디로 여행 가세요?** 사진 카드(도시 칩) · 지금 뜨는 작품 · 오늘의
/// 성지 · 커뮤니티 지금 · **내 기록**(마이페이지의 입구). 경로여정의 입구는 오른쪽 아래 떠 있는
/// 「여행 코스 짜기」 단추다(MZ2AZ-547, 웹 시안 — 「내 여행 이어가기」 카드를 대신한다). 목업의 맨 윗줄
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
    /// 「커뮤니티 지금」 의 신고·차단(MZ2AZ-461). 비회원의 차단은 루트의 로그인 화면이 받는다.
    @StateObject private var moderation = ModerationController()

    /// 「오늘의 성지」의 담기. 검색 탭과 같은 장바구니(설치 id 가 같다).
    @StateObject private var cart = CartStore()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HomeHeader(onProfile: { router.openProfile() })

                // 「어디로 여행 가세요?」 사진 카드(MZ2AZ-547 H1–H3). 「내 여행 이어가기」 카드는 숨겼다 —
                // 아래에 떠 있는 「여행 코스 짜기」 단추가 대신한다(H5).
                HomeCityCard(cities: model.cities) { city in
                    // 그 도시의 「며칠 머무세요?」(Q2)부터(`TabRouter.startNewCourse`, MZ2AZ-547 P3).
                    router.startNewCourse(city: city.key)
                }

                HomeWorkShelf(
                    works: model.works,
                    failed: model.failed,
                    onOpen: { router.openContent($0) },
                    // 「전체 보기」 는 인기순 포스터 격자(MZ2AZ-547 H6, 웹 MZ2AZ-534) — 전에는 작품검색 탭이었다.
                    onAll: { router.openTrending() }
                )

                if let place = model.today {
                    // navi-proto 는 여기서 별도 길찾기 창을 열었다. main 의 계약은 목적지를
                    // **코스 항목**으로만 받아 코스 없는 길찾기가 없다(MZ2AZ-313) — 대신
                    // 장바구니에 담아 코스로 이어지게 한다.
                    HomeTodayCard(
                        place: place, saved: cart.contains(place.id),
                        // 카드 본체를 누르면 그 촬영지의 상세로(2026-09-16 사용자 요청).
                        onOpen: { router.openPlace(place) }
                    ) {
                        Task {
                            if cart.contains(place.id) {
                                await cart.remove(placeId: place.id)
                            } else {
                                await cart.add(placeId: place.id)
                            }
                        }
                    }
                }

                HomeCommunityNow(
                    posts: Array(posts.posts.prefix(2)),
                    moderation: moderation,
                    onOpen: { router.selected = .community }
                )

                HomeMyRecord(
                    stamps: model.stamps,
                    likeCount: likes.contentIds.count,
                    onOpen: { router.openProfile() }
                )
            }
            .padding(.top, 8)
            // 떠 있는 「여행 코스 짜기」 단추에 마지막 줄이 가리지 않게(웹 84). 탭바 몫은 `RootTabs` 의 안전영역이 따로 비운다.
            .padding(.bottom, 84)
        }
        .background(Color(.systemGroupedBackground))
        .overlay(alignment: .bottom) {
            HomeTripFloat(
                courses: HomeTripFloatRules.courses(routes.courses),
                courseList: routes.courseList,
                onOpenCourse: { router.openCourse($0) },
                onAll: { router.openRoute() },
                // 「새 여행 코스」 시트(F1, `TabRouter.startNewCourse`).
                onNew: { router.startNewCourse(city: nil) }
            )
            // 단추 아래끝이 탭바 위 약 18 — 웹 `탭바 + 22` 자리.
            .padding(.bottom, 12)
        }
        .task { await reload() }
        .refreshable { await reload() }
        .onAccountChange { await reload() }
        .moderationSheets(moderation) { target in
            if case let .post(id) = target {
                Task { await posts.blocked(postId: id) }
            }
        }
        // 덮개(경로여정·마이페이지)를 닫고 돌아오면 코스·스탬프가 달라졌을 수 있다.
        .onChange(of: router.cover) { old, new in
            if old != nil, new == nil {
                Task { await reload() }
            }
        }
    }

    /// **서로 기다리지 않는다** (MZ2AZ-372). 전에는 `routes.refresh()`(코스 → 촬영지 200 → 작품 30, 차례로)와
    /// 장바구니를 다 기다린 뒤에야 홈의 조회를 시작해서, 작품 조회 하나가 느리면 홈 전체가 그만큼 비어 있었다.
    ///
    /// 코스 카드는 코스 목록만 있으면 된다 — 목록이 오는 대로 그린다. 촬영지·작품(초안의 재료)은 홈이
    /// 쓰지 않으므로 옆에서 받는다.
    private func reload() async {
        async let coursesJob: Void = routes.refreshCourses()
        async let restJob: Void = model.loadRest()
        async let cartJob: Void = cart.refresh()
        async let materialsJob: Void = routes.refreshMaterials()
        async let postsJob: Void = posts.refresh()
        _ = await (coursesJob, restJob, cartJob, materialsJob, postsJob)
    }
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
