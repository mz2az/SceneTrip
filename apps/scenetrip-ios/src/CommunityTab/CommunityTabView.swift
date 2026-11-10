import SceneApiClient
import SwiftUI

/// 커뮤니티 — **여행후기 · 자유게시판** (MZ2AZ-547 P8, 웹 시안 `proto/ProtoCommunity.tsx`).
///
/// 머리줄은 왼쪽 「커뮤니티」 · 🔍 · 보라 「✎ 글쓰기」 알약, 그 밑 글자 탭으로 게시판을 고른다(서버가 거른다, `GET /posts?board=`).
/// 🔍 를 누르면 제목 줄이 검색칸이 되고, 두 글자부터 0.35초 멈추면 서버가 제목·본문에서 찾는다(`q`).
/// 목록은 끝에 닿기 전에 다음 쪽을 저절로 받는다(서버가 준 `nextCursor`, 없으면 옛 길 offset).
/// 글 읽기·글쓰기는 시트 대신 오른쪽에서 밀려 들어오는 새 화면이다(NavigationStack).
///
/// 서버 최신 글과 기기에 보관한 옛 글을 함께 보인다(MZ2AZ-396, MZ2AZ-423).
struct CommunityTabView: View {
    @ObservedObject private var store = CommunityStore.shared
    /// 내 글의 글쓴이 이름이 닉네임이다 — 닉네임을 바꾸거나 로그인·로그아웃하면 목록이 다시 그려져야 한다.
    @ObservedObject private var auth = AuthStore.shared

    @State private var path: [CommunityRoute] = []
    @State private var board: TripPostBoard = .review
    /// nil 이면 검색 중이 아니다.
    @State private var searchText: String?
    @FocusState private var searchFocused: Bool
    @State private var deleting: CommunityPost?
    /// 남의 글의 신고·차단(MZ2AZ-461).
    @StateObject private var moderation = ModerationController()
    /// 확인용 뒷문(`-initialTab community` 와 함께) — `-communityBoard free`, `-communitySearch 서울`,
    /// `-communityOpen post|compose`. 합성 클릭이 시뮬레이터에 안 닿아 화면 이동을 기계로 할 길이 이것뿐이다.
    private static var backdoorUsed = false

    private var term: String? {
        CommunityFeedRules.searchTerm(searchText)
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                header
                postList
            }
            .toolbar(.hidden, for: .navigationBar)
            // 글 화면의 뒤로 단추는 꺾쇠만 — 옆에 게시판 이름을 따로 둔다(「‹ 여행후기」).
            .toolbarRole(.editor)
            .navigationDestination(for: CommunityRoute.self) { route in
                switch route {
                case let .post(post):
                    CommunityPostView(post: post.value, page: true)
                case let .compose(board):
                    CommunityComposeView(board: board)
                }
            }
        }
        // 게시판·검색어가 바뀌면 서버에 다시 묻는다 — 검색어는 0.35초 멈춘 뒤에.
        .task(id: FilterKey(board: board, term: term)) {
            let delay = CommunityFeedRules.delay(for: term)
            if delay > .zero {
                try? await Task.sleep(for: delay)
            }
            guard !Task.isCancelled else { return }
            await store.setFilter(board: board, query: term)
        }
        // 탭을 떠나면 조건을 지운다 — 홈 「커뮤니티 지금」 도 같은 목록을 읽는다.
        .onDisappear {
            Task { await CommunityStore.shared.setFilter(board: .review, query: nil) }
        }
        .onAccountChange { await store.refresh() }
        .onAppear {
            guard !Self.backdoorUsed else { return }
            let defaults = UserDefaults.standard
            if defaults.string(forKey: "communityBoard") == "free" {
                board = .free
            }
            if let query = defaults.string(forKey: "communitySearch") {
                searchText = query
            }
            if defaults.string(forKey: "communityOpen") == "compose" {
                Self.backdoorUsed = true
                path.append(.compose(board))
            }
        }
        .onChange(of: store.posts.first?.id) { _, _ in
            guard !Self.backdoorUsed, UserDefaults.standard.string(forKey: "communityOpen") == "post",
                  let post = store.posts.first(where: { $0.remotePhotos?.isEmpty == false }) ?? store.posts.first else { return }
            Self.backdoorUsed = true
            path.append(.post(.init(post)))
        }
        .signInSheet()
        .moderationSheets(moderation) { target in
            if case let .post(id) = target {
                Task { await store.blocked(postId: id) }
            }
        }
        .alert(tr("후기를 지울까요?"), isPresented: Binding(get: { deleting != nil }, set: {
            if !$0 {
                deleting = nil
            }
        })) {
            Button("지우기", role: .destructive) {
                if let post = deleting {
                    Task { await store.remove(post) }
                }
                deleting = nil
            }
            Button("취소", role: .cancel) { deleting = nil }
        }
    }

    // MARK: 머리줄

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            if searchText == nil {
                titleRow
            } else {
                searchRow
            }
            boardTabs
        }
        .background(Color(.systemBackground))
    }

    private var titleRow: some View {
        HStack(spacing: 4) {
            Text("커뮤니티").font(.system(size: 22, weight: .heavy))
            Spacer()
            Button {
                withAnimation(.easeOut(duration: 0.2)) { searchText = "" }
                searchFocused = true
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Color(PinImage.deep))
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr("글 검색"))
            .accessibilityIdentifier("community-search")
            Button {
                path.append(.compose(board))
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "square.and.pencil").font(.system(size: 13, weight: .bold))
                    Text("글쓰기").font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(height: 36)
                .background(Capsule().fill(Color(PinImage.deep)))
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("community-compose")
        }
        .padding(.leading, 16).padding(.trailing, 12).padding(.top, 4)
        .frame(minHeight: 52)
    }

    private var searchRow: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.system(size: 16)).foregroundStyle(.secondary)
                TextField(tr("제목·내용으로 찾기"), text: Binding(get: { searchText ?? "" }, set: { searchText = $0 }))
                    .font(.system(size: 16))
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .accessibilityLabel(tr("글 검색"))
                    .accessibilityIdentifier("community-search-field")
                if let searchText, !searchText.isEmpty {
                    Button {
                        self.searchText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tr("지우기"))
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 38)
            .background(Capsule().fill(Color(.systemGray6)))
            .transition(.move(edge: .trailing).combined(with: .opacity))
            Button {
                searchFocused = false
                withAnimation(.easeOut(duration: 0.2)) { searchText = nil }
            } label: {
                Text("취소").font(.system(size: 16, weight: .semibold)).foregroundStyle(Color(PinImage.deep))
                    .padding(.horizontal, 8).frame(minHeight: 44)
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.top, 4)
        .frame(minHeight: 52)
    }

    /// 게시판 — 밑줄·칸 없이 글자만. 고른 것은 진한 글자, 아닌 것은 옅은 회색.
    private var boardTabs: some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            ForEach(CommunityFeedRules.boards, id: \.self) { item in
                let on = item == board
                Button {
                    board = item
                } label: {
                    Text(tr(CommunityFeedRules.boardLabelKey(item)))
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundStyle(on ? Color.primary : Color(.systemGray3))
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                        .animation(.easeOut(duration: 0.18), value: on)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected, .isHeader] : [])
                .accessibilityIdentifier("community-board-\(item.rawValue)")
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 6)
    }

    // MARK: 글 목록

    private var postList: some View {
        List {
            if let message = store.message {
                Text(message).font(.footnote).foregroundStyle(.red)
                Button("다시 시도") { Task { await store.refresh() } }.disabled(store.loading)
            }
            if store.loading, store.posts.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            }
            if term == nil, !store.legacyPosts.isEmpty, board == .review {
                Section {
                    ForEach(store.legacyPosts) { post in
                        postRow(post, index: nil)
                    }
                } header: {
                    Text("이 기기에만 저장된 옛 글")
                } footer: {
                    Text("기존 사진과 코스를 보관하고 있어요. 이 기기에서만 보입니다")
                }
            }
            if let empty = CommunityFeedRules.emptyState(
                board: board, term: term, posts: store.posts.count,
                legacy: board == .review ? store.legacyPosts.count : 0,
                loading: store.loading, failed: store.message != nil
            ) {
                emptyView(empty).listRowBackground(Color.clear).listRowSeparator(.hidden)
            }

            ForEach(Array(store.posts.enumerated()), id: \.element.id) { index, post in
                postRow(post, index: index)
            }
            if store.loading, !store.posts.isEmpty {
                ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .refreshable { await store.refresh() }
    }

    @ViewBuilder private func emptyView(_ state: CommunityFeedRules.EmptyState) -> some View {
        switch state {
        case let .noMatch(query):
            Text(String(format: tr("「%@」 에 맞는 글이 없어요"), query))
                .font(.system(size: 17, weight: .bold)).multilineTextAlignment(.center)
                .frame(maxWidth: .infinity).padding(.vertical, 60).padding(.horizontal, 32)
        case .freeBoard:
            ContentUnavailableView {
                Label("아직 글이 없어요", systemImage: "bubble.left.and.bubble.right")
            } description: {
                Text("여행 이야기·질문을 자유롭게 남겨 보세요")
            }
        case .reviews:
            ContentUnavailableView {
                Label("아직 후기가 없습니다", systemImage: "bubble.left.and.bubble.right")
            } description: {
                Text("다녀온 코스와 사진으로 첫 후기를 남겨 보세요")
            }
        }
    }

    private func postRow(_ post: CommunityPost, index: Int?) -> some View {
        HStack(alignment: .top, spacing: 4) {
            Button {
                path.append(.post(.init(post)))
            } label: {
                CommunityPostRow(post: post, myNickname: auth.me?.nickname)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            // 단추 밖에 둔다 — 안에 두면 「⋯」 를 눌러도 글이 열린다.
            if let id = post.serverId, ModerationRules.showsMenu(isMine: post.isMine) {
                ModerationMenu(
                    target: .post(id),
                    canBlock: ModerationRules.canBlock(isMine: post.isMine, hasAuthor: post.author != nil),
                    controller: moderation
                )
            }
        }
        .padding(.top, 9).padding(.bottom, 2)
        .onAppear {
            guard let index else { return }
            if CommunityFeedRules.shouldLoadMore(
                appearedIndex: index, loaded: store.posts.count, total: store.total,
                loading: store.loading, failed: store.message != nil
            ) {
                Task { await store.refresh(more: true) }
            }
        }
        .swipeActions {
            // 지우기는 **내 글만** — 남의 글을 밀어서 지울 수 있으면 안 된다.
            if post.isMine, post.serverId != nil {
                Button(role: .destructive) {
                    deleting = post
                } label: {
                    Label("지우기", systemImage: "trash")
                }
            }
        }
    }
}

/// 커뮤니티 탭 안의 새 화면.
enum CommunityRoute: Hashable {
    case post(PostRef)
    case compose(TripPostBoard)

    /// 글은 같음 비교가 없다 — id 로만 비교한다.
    struct PostRef: Hashable {
        let value: CommunityPost

        init(_ value: CommunityPost) {
            self.value = value
        }

        static func == (lhs: PostRef, rhs: PostRef) -> Bool {
            lhs.value.id == rhs.value.id
        }

        func hash(into hasher: inout Hasher) {
            hasher.combine(value.id)
        }
    }
}

private struct FilterKey: Equatable {
    let board: TripPostBoard
    let term: String?
}

/// 글 한 줄 — 왼쪽에 제목·본문·코스, 맨 아래 작성자·날짜. 오른쪽 대표 사진 밑에 「👍 N 💬 M」(사진 없으면 작성자 줄 끝).
struct CommunityPostRow: View {
    let post: CommunityPost
    let myNickname: String?

    private static let coverSize: CGFloat = 76

    private var hasPhoto: Bool {
        post.remotePhotos?.first != nil || post.photos?.first != nil
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(post.title).font(.system(size: 15, weight: .semibold)).lineLimit(2)
                if !post.body.isEmpty {
                    Text(post.body).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(2)
                }
                if let courseTitle = post.course?.title ?? post.courseTitle {
                    Label(courseTitle, systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                        .font(.system(size: 11, weight: .medium)).foregroundStyle(Color(PinImage.deep))
                        .lineLimit(1)
                }
                Spacer(minLength: 6)
                meta.padding(.top, 4)
            }
            .frame(maxWidth: .infinity, minHeight: hasPhoto ? Self.coverSize : nil, alignment: .topLeading)
            if hasPhoto {
                VStack(spacing: 6) {
                    CommunityCoverPhoto(post: post)
                        .frame(width: Self.coverSize, height: Self.coverSize)
                        .clipShape(.rect(cornerRadius: 12))
                    CommunityPostCounts(post: post)
                }
            }
        }
    }

    private var meta: some View {
        HStack(spacing: 8) {
            Text(post.authorName(myNickname: myNickname)).font(.system(size: 11)).foregroundStyle(.tertiary)
            Text(post.createdAt.formatted(.relative(presentation: .named).locale(AppLanguage.currentLocale)))
                .font(.system(size: 11)).foregroundStyle(.tertiary)
            if !hasPhoto {
                Spacer(minLength: 0)
                CommunityPostCounts(post: post)
            }
        }
    }
}

/// 목록 줄의 「👍 N 💬 M」. 서버가 수를 주지 않은 글(옛 서버·기기 옛 글)은 그리지 않는다. 누른 글은 보라 엄지.
struct CommunityPostCounts: View {
    let post: CommunityPost

    var body: some View {
        if let counts = CommunityFeedRules.counts(likeCount: post.likeCount, commentCount: post.commentCount) {
            HStack(spacing: 10) {
                HStack(spacing: 3) {
                    Image(systemName: post.liked == true ? "hand.thumbsup.fill" : "hand.thumbsup")
                        .font(.system(size: 11))
                        .foregroundStyle(post.liked == true ? Color(PinImage.deep) : Color(.tertiaryLabel))
                    Text("\(counts.likes)").font(.system(size: 11)).monospacedDigit().foregroundStyle(.tertiary)
                }
                HStack(spacing: 3) {
                    Image(systemName: "bubble.left").font(.system(size: 11)).foregroundStyle(.tertiary)
                    Text("\(counts.comments)").font(.system(size: 11)).monospacedDigit().foregroundStyle(.tertiary)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(format: tr("좋아요 %d개, 댓글 %d개"), counts.likes, counts.comments))
        }
    }
}
