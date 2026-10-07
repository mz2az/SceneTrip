import SceneApiClient
import SwiftUI

/// 마이페이지 — **임시판** (2026-08-28).
///
/// 로그인이 아직 없다(비회원 승격은 MZ2AZ-256). 그래서 이 화면은 「내 계정」이
/// 아니라 **「이 설치본에 쌓인 것」**을 보여 준다 — 찜한 작품, 내 코스, 사용법.
/// 로그인이 서면 머리만 계정으로 갈아 끼우고 아래 목록은 그대로 간다.
///
/// 지어낸 숫자는 없다 — 찜(`LikeStore`)도 코스 수도 서버에서 온다.
/// 아직 못 하는 것(알림)은 흐리게 두고 「준비 중」이라고 적는다 —
/// 눌리는데 아무 일도 없는 것이 제일 나쁘다.
struct ProfileTabView: View {
    /// 홈이 덮개로 띄울 때 넘긴다 — 있으면 왼쪽 위에 닫기 단추가 생긴다
    /// (2026-09-01 홈 재편: 이 화면은 탭이 아니라 홈의 프로필 단추가 연다).
    var onClose: (() -> Void)?

    @ObservedObject private var likes = LikeStore.shared
    @ObservedObject private var auth = AuthStore.shared
    @State private var confirmingSignOut = false
    @State private var confirmingDelete = false
    /// 닉네임 바꾸기 시트 (MZ2AZ-363).
    @State private var editingNickname = false
    @State private var deleteFailed = false

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

    private let installId = InstallIdentity.current

    /// 커뮤니티에 쓴 글 — 기기 저장소를 마이페이지가 같이 본다.
    @ObservedObject private var posts = CommunityStore.shared
    @ObservedObject private var footprints = FootprintStore.shared
    @State private var clearingFootprints = false
    @State private var askingFootprintConsent = false
    @State private var showingPosts = false

    /// 앱 언어 (MZ2AZ-343). 바꾸면 루트가 화면을 통째로 다시 만들어 이 덮개도 닫힌다.
    @ObservedObject private var language = AppLanguage.shared
    @State private var choosingLanguage = false

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
                            tint: Color(PinImage.deep), title: tr("내 코스"),
                            value: courseCount.map { String(format: tr("%d개"), $0) } ?? "…",
                            chevron: true)
                    }
                    .buttonStyle(.plain)
                    Button {
                        showingLikes = true
                    } label: {
                        row(symbol: "heart.fill", tint: .red, title: tr("찜한 작품"),
                            value: String(format: tr("%d개"), likes.contentIds.count), chevron: true)
                    }
                    .buttonStyle(.plain)
                    Button {
                        showingCart = true
                    } label: {
                        row(symbol: "bag.fill", tint: .orange, title: tr("장바구니"),
                            value: String(format: tr("%d곳"), cartItems.count), chevron: true)
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
                        row(symbol: "square.and.pencil", tint: .indigo, title: tr("내가 쓴 글"),
                            value: String(format: tr("%d개"), posts.mine.count), chevron: true)
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

                footprintSection

                Section("도움") {
                    Button {
                        choosingLanguage = true
                    } label: {
                        row(symbol: "globe", tint: .blue, title: tr("언어"),
                            value: AppLanguage.name(of: language.lang), chevron: true)
                    }
                    .buttonStyle(.plain)
                    .confirmationDialog(
                        tr("언어"), isPresented: $choosingLanguage, titleVisibility: .visible
                    ) {
                        // 언어 이름은 그 언어로 적는다 — 번역하지 않는다.
                        ForEach(AppLanguage.choices, id: \.self) { lang in
                            Button {
                                AppLanguage.shared.choose(lang)
                            } label: {
                                Text(verbatim: AppLanguage.name(of: lang))
                            }
                        }
                    }
                    Button {
                        replaying = true
                    } label: {
                        row(symbol: "questionmark.circle", tint: .blue,
                            title: tr("사용법 다시 보기"), value: "")
                    }
                    .buttonStyle(.plain)
                }

                accountSection

                Section("준비 중") {
                    // 자리를 미리 보여 준다 — 없는 척하다 갑자기 생기는 것보다
                    // 「여기 온다」가 보이는 쪽이 낫다.
                    row(symbol: "bell", tint: .gray, title: tr("알림"), value: tr("준비 중"))
                        .foregroundStyle(.tertiary)
                }

                Section {
                    Text(String(format: tr("설치 식별자 %@…"), String(installId.uuidString.prefix(8))))
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
            .onAccountChange { await load() }
            .signInSheet()
            .nicknameSheet()
            .sheet(isPresented: $editingNickname) {
                NicknameView(mode: .edit) { editingNickname = false }
                    .presentationDetents([.medium])
            }
            // 세션이 풀리면 바꾸기 시트를 내린다 — 그 밑에서는 로그인 화면이 못 뜬다.
            .onChange(of: auth.signedIn) { _, signedIn in
                if !signedIn {
                    editingNickname = false
                }
            }
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
        let cartTask = Task { try? await CartAPI.getCart(xInstallId: installId) }
        let coursesTask = Task { try? await CoursesAPI.listCourses(xInstallId: installId) }

        switch await worksTask.value {
        case let .success(works):
            allWorks = works
            likesFailure = nil
        case let .failure(error):
            // 오류 원문을 그대로 보이지 않는다 — 서버 문장은 한국어이고 내부 정보가 섞인다 (MZ2AZ-345).
            likesFailure = tr("찜한 작품을 불러오지 못했어요. 잠시 뒤 다시 해 주세요")
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
        stamps = await VisitStamp.collect(courses: courses, installId: installId)
    }
}

/// 계정 카드와 계정 절 — 본문이 길어져 떼어 냈다(같은 파일이라 상태를 그대로 본다).
extension ProfileTabView {
    /// 피노와 계정 카드 — 비회원이면 로그인 단추, 로그인했으면 닉네임(누르면 바꾸기)과 메일 (MZ2AZ-336·363).
    var header: some View {
        VStack(spacing: 8) {
            PinoMascot(width: 96)
            if auth.signedIn {
                // 닉네임이 남에게 보이는 이름이다(MZ2AZ-363). 옛 서버는 닉네임이 없어 전처럼 계정 이름을 보인다.
                if let nickname = auth.me?.nickname {
                    Button {
                        editingNickname = true
                    } label: {
                        HStack(spacing: 5) {
                            Text(nickname).font(.headline).foregroundStyle(.primary)
                            Image(systemName: "pencil")
                                .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        }
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tr("닉네임 바꾸기"))
                    .accessibilityValue(nickname)
                } else {
                    Text(auth.me?.displayName ?? tr("여행자"))
                        .font(.headline)
                }
                if let email = auth.me?.email {
                    Text(email).font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Text("비회원으로 여행 중")
                    .font(.headline)
                Text("로그인하면 코스와 찜이 계정에 저장돼요")
                    .font(.caption).foregroundStyle(.secondary)
                Button {
                    auth.promptSignIn()
                } label: {
                    Text("로그인")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 22).padding(.vertical, 8)
                        .background(Capsule().fill(Color.accentColor))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    /// 계정 — 로그아웃과 탈퇴. 탈퇴는 App Store 요건이라 앱 안에 있어야 한다.
    @ViewBuilder var accountSection: some View {
        if auth.signedIn {
            Section("계정") {
                Button {
                    confirmingSignOut = true
                } label: {
                    row(symbol: "rectangle.portrait.and.arrow.right", tint: .gray,
                        title: tr("로그아웃"), value: "")
                }
                .buttonStyle(.plain)
                .confirmationDialog(
                    "로그아웃할까요? 이 기기는 비회원으로 돌아가요.",
                    isPresented: $confirmingSignOut, titleVisibility: .visible
                ) {
                    Button("로그아웃", role: .destructive) { Task { await auth.signOut() } }
                }
                Button(role: .destructive) {
                    confirmingDelete = true
                } label: {
                    Text("회원 탈퇴").font(.subheadline)
                }
                .alert("정말 탈퇴할까요?", isPresented: $confirmingDelete) {
                    Button("탈퇴", role: .destructive) {
                        Task {
                            if await auth.deleteAccount() {
                                onClose?()
                            } else {
                                deleteFailed = true
                            }
                        }
                    }
                    Button("취소", role: .cancel) {}
                } message: {
                    Text("장바구니·코스·찜과 이 기기의 발자취가 모두 지워지고 되돌릴 수 없어요.")
                }
                .alert("탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요", isPresented: $deleteFailed) {
                    Button("확인", role: .cancel) {}
                }
            }
            .disabled(auth.busy)
        }
    }

    /// 발자취 — 이동 경로는 개인정보다 (MZ2AZ-348). 기본은 꺼짐이고, 켤 때 무엇이 어디에
    /// 저장되는지 알리고 동의를 받는다. 기기에만 있는 기록이라 지우기도 여기서만 한다.
    var footprintSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { footprints.recording },
                set: { on in
                    if on {
                        askingFootprintConsent = true
                    } else {
                        footprints.recording = false
                    }
                }
            )) {
                Text("발자취 기록하기").font(.subheadline)
            }
            .alert("발자취를 기록할까요?", isPresented: $askingFootprintConsent) {
                Button("기록하기") { footprints.recording = true }
                Button("취소", role: .cancel) {}
            } message: {
                Text("여행 모드에서 지나간 길을 이 기기에만 저장해요. 서버로 보내지 않고, 로그아웃하거나 탈퇴하면 지워집니다.")
            }
            row(symbol: "shoeprints.fill", tint: Color(PinImage.deep), title: tr("기록한 거리"),
                value: String(
                    format: tr("%.1f km · %d점"), footprints.kilometers, footprints.points.count
                ),
                chevron: false)
            Toggle(isOn: $footprints.enabled) {
                Text("지도에 발자취 보기").font(.subheadline)
            }
            Button(role: .destructive) {
                clearingFootprints = true
            } label: {
                Text("발자취 지우기").font(.subheadline)
            }
            .disabled(footprints.points.isEmpty)
            .confirmationDialog(
                "발자취를 모두 지울까요? 복구할 수 없어요.",
                isPresented: $clearingFootprints, titleVisibility: .visible
            ) {
                Button("지우기", role: .destructive) { footprints.clear() }
            }
        } header: {
            Text("발자취")
        } footer: {
            Text("이 기기에만 저장돼요. 서버로 보내지 않아요.")
        }
    }
}
