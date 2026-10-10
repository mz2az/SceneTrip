import SwiftUI

/// 커뮤니티 — **여행후기** (2026-10-05 재편, MZ2AZ-351).
///
/// 말머리 넷(코스 추천·장소 후기·인증샷·자유)이던 게시판을 **여행후기 하나**로 줄였다
/// (2026-10-03 팀 회의). 후기는 사진과 다녀온 코스를 붙여 쓰고, 읽는 사람은 그 코스를
/// 보고 내 코스로 담는다.
///
/// 서버의 최신 후기를 받는다. 기기에 남은 옛 글은 내 글의 별도 목록에 보존한다(MZ2AZ-396).
struct CommunityTabView: View {
    @ObservedObject private var store = CommunityStore.shared
    /// 내 글의 글쓴이 이름이 닉네임이다 — 닉네임을 바꾸거나 로그인·로그아웃하면 목록이 다시 그려져야 한다.
    @ObservedObject private var auth = AuthStore.shared

    @State private var composing = false

    /// 읽고 있는 글. 목록 행은 두 줄로 잘리므로, 누르면 전문이 큰 팝업으로 뜬다.
    @State private var reading: CommunityPost?
    @State private var deleting: CommunityPost?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                Divider()
                postList
            }
            // 내비게이션 바를 접는다 — 바의 단추에는 시스템이 유리 판을 깔아
            // 테마 색이 묻힌다(코스 추가와 같은 문제).
            .toolbar(.hidden, for: .navigationBar)
            .sheet(item: $reading) { post in
                CommunityPostView(post: post)
                    .presentationDetents([.large])
            }
            .sheet(isPresented: $composing) {
                CommunityComposeView()
            }
            .task { await store.refresh() }
            .onAccountChange { await store.refresh() }
            .signInSheet()
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
    }

    /// 손수 그린 머리줄 — 글쓰기가 피노 색 동그라미로 떠 있다.
    private var header: some View {
        ZStack {
            Text("커뮤니티").font(.headline)
            HStack {
                Spacer()
                Button {
                    if auth.signedIn {
                        composing = true
                    } else {
                        auth.promptSignIn()
                    }
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(
                            Circle().fill(
                                LinearGradient(
                                    colors: [Color(PinImage.light), Color(PinImage.deep)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                        )
                        .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 8)
    }

    // MARK: 글 목록

    private var postList: some View {
        List {
            if let message = store.message {
                Text(message).font(.footnote).foregroundStyle(.red)
                Button("다시 시도") { Task { await store.refresh() } }.disabled(store.loading)
            }
            if store.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            if store.posts.isEmpty, !store.loading, store.message == nil {
                ContentUnavailableView {
                    Label("아직 후기가 없습니다", systemImage: "bubble.left.and.bubble.right")
                } description: {
                    Text("다녀온 코스와 사진으로 첫 후기를 남겨 보세요")
                }
                .listRowBackground(Color.clear)
            }

            ForEach(store.posts) { post in
                myPostRow(post)
            }
            if store.posts.count < store.total {
                Button("더 보기") { Task { await store.refresh(more: true) } }.disabled(store.loading)
            }
        }
        .listStyle(.plain)
        .refreshable { await store.refresh() }
    }

    private func myPostRow(_ post: CommunityPost) -> some View {
        Button {
            reading = post
        } label: {
            myPostBody(post)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .swipeActions {
            // 지우기는 **내 글만** — 남의 글을 밀어서 지울 수 있으면 안 된다.
            if post.isMine {
                Button(role: .destructive) {
                    deleting = post
                } label: {
                    Label("지우기", systemImage: "trash")
                }
            }
        }
    }

    /// 후기 한 줄 — 왼쪽에 제목·본문·글쓴이, 오른쪽에 대표 사진.
    private func myPostBody(_ post: CommunityPost) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(post.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                if !post.body.isEmpty {
                    Text(post.body)
                        .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                HStack(spacing: 8) {
                    Text(post.authorName(myNickname: auth.me?.nickname)).font(.caption2).foregroundStyle(.tertiary)
                    Text(post.createdAt.formatted(.relative(presentation: .named).locale(AppLanguage.currentLocale)))
                        .font(.caption2).foregroundStyle(.tertiary)
                }
                if let courseTitle = post.course?.title ?? post.courseTitle {
                    Label(courseTitle, systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                        .font(.caption2.weight(.medium)).foregroundStyle(Color(PinImage.deep))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if post.remotePhotos?.first != nil || post.photos?.first != nil {
                CommunityCoverPhoto(post: post)
                    .frame(width: 76, height: 76)
                    .clipShape(.rect(cornerRadius: 12))
            }
        }
        .padding(.vertical, 6)
    }
}
