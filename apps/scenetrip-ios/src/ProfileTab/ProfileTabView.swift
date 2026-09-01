import SceneApiClient
import SwiftUI

/// 마이페이지 — **임시판** (2026-08-28).
///
/// 로그인이 아직 없다(비회원 승격은 MZ2AZ-256). 그래서 이 화면은 「내 계정」이
/// 아니라 **「이 설치본에 쌓인 것」**을 보여 준다 — 찜한 작품, 내 코스, 사용법.
/// 로그인이 서면 머리만 계정으로 갈아 끼우고 아래 목록은 그대로 간다.
///
/// 지어낸 숫자는 없다 — 찜은 기기 저장소(`LikeStore`), 코스 수는 서버에서 센다.
/// 아직 못 하는 것(로그인·알림·언어)은 흐리게 두고 「준비 중」이라고 적는다 —
/// 눌리는데 아무 일도 없는 것이 제일 나쁘다.
struct ProfileTabView: View {
    /// 홈이 덮개로 띄울 때 넘긴다 — 있으면 왼쪽 위에 닫기 단추가 생긴다
    /// (2026-09-01 홈 재편: 이 화면은 탭이 아니라 홈의 프로필 단추가 연다).
    var onClose: (() -> Void)?

    @ObservedObject private var likes = LikeStore.shared

    @State private var courses: [CourseSummary] = []
    @State private var courseCount: Int?
    /// 서버의 작품 전체. 찜 목록은 여기서 **그때그때 걸러 낸다** — 상태로 박아
    /// 두면 하트를 새로 눌러도 다시 받기 전까지 목록이 낡는다(2026-08-28 버그).
    @State private var allWorks: [ContentSummary] = []
    @State private var likesLoading = true
    @State private var likesFailure: String?

    private var likedWorks: [ContentSummary] {
        allWorks.filter { likes.contentIds.contains($0.id) }
    }

    @State private var cartItems: [CartItem] = []
    @State private var stamps: [VisitStamp] = []
    @State private var showingCart = false
    @State private var showingStamps = false
    @State private var replaying = false
    @State private var showingReels = false
    @State private var showingCourses = false
    @State private var showingLikes = false

    private let deviceId = InstallIdentity.current

    /// 커뮤니티에 쓴 글 — 기기 저장소를 마이페이지가 같이 본다.
    @ObservedObject private var posts = CommunityStore.shared
    @State private var showingPosts = false

    /// 뒷문은 프로세스당 한 번. 화면 상태가 아니라 **프로세스 상태**라 static 이다.
    private static var likesBackdoorUsed = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    header
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section("내 여행") {
                    // 누르면 목록이 바로 뜬다 — 숫자만 보여 주고 끝나면 「그래서
                    // 뭐가 있는데」를 경로여정 탭까지 가서 확인해야 한다.
                    Button {
                        showingCourses = true
                    } label: {
                        row(symbol: "point.topleft.down.to.point.bottomright.curvepath",
                            tint: Color(PinImage.deep), title: "내 코스",
                            value: courseCount.map { "\($0)개" } ?? "…", chevron: true)
                    }
                    .buttonStyle(.plain)
                    Button {
                        showingLikes = true
                    } label: {
                        row(symbol: "heart.fill", tint: .red, title: "찜한 작품",
                            value: "\(likes.contentIds.count)개", chevron: true)
                    }
                    .buttonStyle(.plain)
                    Button {
                        showingCart = true
                    } label: {
                        row(symbol: "bag.fill", tint: .orange, title: "장바구니",
                            value: "\(cartItems.count)곳", chevron: true)
                    }
                    .buttonStyle(.plain)
                }

                // 스탬프는 목록 줄이 아니라 **첫 화면에 도장으로 바로 보인다** —
                // 모은 것은 세는 게 아니라 자랑하는 것이다(2026-08-28 사용자 의견).
                Section("방문 스탬프") {
                    if stamps.isEmpty {
                        HStack(spacing: 10) {
                            Circle()
                                .strokeBorder(
                                    Color(.systemGray4),
                                    style: StrokeStyle(lineWidth: 2, dash: [4, 3])
                                )
                                .frame(width: 44, height: 44)
                            Text("여행 중 성지 100 m 안에 들어가면 도장이 찍혀요")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    } else {
                        Button {
                            showingStamps = true
                        } label: {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) {
                                    ForEach(stamps.prefix(12)) { stamp in
                                        StampBadge(stamp: stamp, size: 62)
                                    }
                                    if stamps.count > 12 {
                                        Text("+\(stamps.count - 12)")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .padding(.vertical, 6)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section("커뮤니티") {
                    Button {
                        showingPosts = true
                    } label: {
                        row(symbol: "square.and.pencil", tint: .indigo, title: "내가 쓴 글",
                            value: "\(posts.posts.count)개", chevron: true)
                    }
                    .buttonStyle(.plain)
                }

                Section("AI 여행 릴스") {
                    // 다녀온 코스와 사진으로 릴스를 자동으로 만들어 주는 기능의
                    // **자리**다(2026-08-28 아이디어). 눌리면 무엇이 올지 보여
                    // 준다 — 눌리는데 아무 일도 없는 단추가 제일 나쁘다.
                    Button {
                        showingReels = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "sparkles.rectangle.stack")
                                .font(.system(size: 15))
                                .foregroundStyle(.white)
                                .frame(width: 26, height: 26)
                                .background(
                                    RoundedRectangle(cornerRadius: 6).fill(
                                        LinearGradient(
                                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                        )
                                    )
                                )
                            VStack(alignment: .leading, spacing: 1) {
                                Text("내 여행으로 릴스 만들기").font(.subheadline)
                                Text("다녀온 코스와 사진을 AI 가 15초 영상으로")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("곧").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }

                Section("도움") {
                    Button {
                        replaying = true
                    } label: {
                        row(symbol: "questionmark.circle", tint: .blue,
                            title: "사용법 다시 보기", value: "")
                    }
                    .buttonStyle(.plain)
                }

                Section("준비 중") {
                    // 자리를 미리 보여 준다 — 없는 척하다 갑자기 생기는 것보다
                    // 「여기 온다」가 보이는 쪽이 낫다.
                    row(symbol: "person.crop.circle.badge.plus", tint: .gray,
                        title: "로그인 · 계정", value: "준비 중")
                        .foregroundStyle(.tertiary)
                    row(symbol: "globe", tint: .gray,
                        title: "언어 (English · 日本語)", value: "준비 중")
                        .foregroundStyle(.tertiary)
                    row(symbol: "bell", tint: .gray, title: "알림", value: "준비 중")
                        .foregroundStyle(.tertiary)
                }

                Section {
                    Text("설치 식별자 \(deviceId.uuidString.prefix(8))…")
                        .font(.caption2).foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("마이페이지")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let onClose {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(action: onClose) { Image(systemName: "xmark") }
                            .accessibilityLabel("닫기")
                    }
                }
            }
            .task {
                await load()
                // 확인용 뒷문(`-openLikes 1`) — 합성 클릭이 안 닿는 시뮬레이터에서
                // 팝업 속까지 찍어 보기 위한 것. **한 번만 발동한다** — 안 그러면
                // 그 인자로 뜬 프로세스가 살아 있는 동안 마이페이지에 들어갈
                // 때마다 팝업이 저절로 열린다(2026-08-28 사용자 발견).
                if UserDefaults.standard.bool(forKey: "openLikes"), !Self.likesBackdoorUsed {
                    Self.likesBackdoorUsed = true
                    showingLikes = true
                }
            }
            .refreshable { await load() }
            .fullScreenCover(isPresented: $replaying) {
                OnboardingView { replaying = false }
            }
            .sheet(isPresented: $showingReels) {
                ReelsTeaserView()
                    .presentationDetents([.medium])
            }
            .sheet(isPresented: $showingCourses) {
                MyCoursesSheet(courses: courses)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingLikes) {
                LikedWorksSheet(
                    works: likedWorks, loading: likesLoading, failure: likesFailure
                )
                .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingCart) {
                ProfileCartSheet(items: cartItems)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingStamps) {
                StampsSheet(stamps: stamps)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showingPosts) {
                MyPostsSheet()
                    .presentationDetents([.medium, .large])
            }
        }
    }

    /// 피노와 비회원 안내. 로그인이 서면 이 자리가 계정 카드가 된다.
    private var header: some View {
        VStack(spacing: 8) {
            PinoMascot(width: 96)
            Text("비회원으로 여행 중")
                .font(.headline)
            Text("로그인이 생기면 코스와 찜을 계정으로 옮겨 드릴게요")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private func row(
        symbol: String, tint: Color, title: String, value: String, chevron: Bool = false
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15))
                .foregroundStyle(tint)
                .frame(width: 26)
            Text(title).font(.subheadline)
            Spacer()
            Text(value).font(.subheadline).foregroundStyle(.secondary)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .contentShape(.rect)
    }

    private func load() async {
        // **넷을 나란히 받는다.** 앞서 줄줄이 기다렸더니 코스 상세(스탬프용)
        // 하나가 느리면 찜 목록이 그동안 「없습니다」로 보였다(2026-08-28 버그 —
        // 개수는 기기 값이라 바로 3인데 목록만 비어 있던 이유).
        likesLoading = true

        let worksTask = Task { () -> Result<[ContentSummary], Error> in
            do {
                return try await .success(ContentsAPI.listContents(limit: 100).items)
            } catch {
                return .failure(error)
            }
        }
        let cartTask = Task { try? await CartAPI.getCart(xDeviceId: deviceId) }
        let coursesTask = Task { try? await CoursesAPI.listCourses(xDeviceId: deviceId) }

        switch await worksTask.value {
        case let .success(works):
            allWorks = works
            likesFailure = nil
        case let .failure(error):
            likesFailure = String(describing: error).prefix(300) + ""
        }
        likesLoading = false

        if let cart = await cartTask.value {
            cartItems = cart.items
        }
        if let list = await coursesTask.value {
            courses = list.items
            courseCount = list.items.count
        }

        // 방문 스탬프 — 코스마다 상세를 받아 visitedAt 이 찍힌 것만 모은다.
        // 홈의 「내 기록」도 같은 것을 부른다(`VisitStamp.collect`).
        stamps = await VisitStamp.collect(courses: courses, deviceId: deviceId)
    }
}
