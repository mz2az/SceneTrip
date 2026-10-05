import SwiftUI

/// 커뮤니티 — **여행후기** (2026-10-05 재편, MZ2AZ-351).
///
/// 말머리 넷(코스 추천·장소 후기·인증샷·자유)이던 게시판을 **여행후기 하나**로 줄였다
/// (2026-10-03 팀 회의). 후기는 사진과 다녀온 코스를 붙여 쓰고, 읽는 사람은 그 코스를
/// 보고 내 코스로 담는다.
///
/// 게시판 서버가 아직 없어 글은 **기기에만** 있다 — 남의 글을 받아 올 길이 없다.
/// 지어낸 글을 앱에 박지 않는다(시험용 글은 기기에 직접 넣어 본다).
struct CommunityTabView: View {
    @ObservedObject private var store = CommunityStore.shared

    @State private var composing = false

    /// 읽고 있는 글. 목록 행은 두 줄로 잘리므로, 누르면 전문이 큰 팝업으로 뜬다.
    @State private var reading: CommunityPost?

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
        }
    }

    /// 손수 그린 머리줄 — 글쓰기가 피노 색 동그라미로 떠 있다.
    private var header: some View {
        ZStack {
            Text("커뮤니티").font(.headline)
            HStack {
                Spacer()
                Button {
                    composing = true
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
            if store.posts.isEmpty {
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
        }
        .listStyle(.plain)
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
                    store.remove(post)
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
                    Text(post.author ?? tr("나")).font(.caption2).foregroundStyle(.tertiary)
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
            if let name = post.photos?.first, let photo = CommunityStore.photo(name) {
                Image(uiImage: photo)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 76, height: 76)
                    .clipShape(.rect(cornerRadius: 12))
            }
        }
        .padding(.vertical, 6)
    }
}
