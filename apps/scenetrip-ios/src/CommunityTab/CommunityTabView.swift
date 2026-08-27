import SceneApiClient
import SwiftUI

/// 커뮤니티 — **게시판 임시판** (2026-08-28, 2차).
///
/// 1차는 마켓 피드만 보여 줬는데 방향이 잡혔다 — *"디씨인사이드같은? 혹은 카페
/// 같은"* **말머리가 있는 게시판**이다. 글의 갈래(코스 추천·장소 후기·인증샷·자유)를
/// 칩으로 고르고, 글을 쓰고, 내 코스를 첨부한다.
///
/// ## 지어낸 글은 없다
///
/// 게시판 서버가 아직 없어서 남의 글을 만들어 낼 방법이 없다 — 만들어 내면 안 된다.
/// 그래서 이 판의 글은 둘뿐이다: **내가 쓴 글**(기기 저장, `CommunityStore`)과
/// **마켓에 올라온 코스**(실서버 — 이것이 지금 있는 유일한 「남의 게시물」이다).
/// 사진 첨부·댓글은 서버와 함께 온다 — 글쓰기 화면이 그렇게 말한다.
struct CommunityTabView: View {
    @StateObject private var store = CommunityStore()

    /// nil = 전체.
    @State private var board: CommunityPost.Board?
    @State private var composing = false
    @State private var marketCourses: [MarketCourseSummary] = []

    private let deviceId = InstallIdentity.current

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                boardChips
                Divider()
                postList
            }
            .navigationTitle("커뮤니티")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        composing = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .task {
                if let list = try? await MarketAPI.listMarketCourses(
                    xDeviceId: deviceId, sort: .likes, limit: 30
                ) {
                    marketCourses = list.items
                }
            }
            .sheet(isPresented: $composing) {
                CommunityComposeView { newBoard, title, body, courseTitle in
                    store.add(
                        board: newBoard, title: title, body: body, courseTitle: courseTitle
                    )
                }
            }
        }
    }

    // MARK: 말머리

    private var boardChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                boardChip(nil, label: "전체")
                ForEach(CommunityPost.Board.allCases) { item in
                    boardChip(item, label: item.rawValue)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
        .background(Color(.systemBackground))
    }

    private func boardChip(_ value: CommunityPost.Board?, label: String) -> some View {
        let isOn = board == value
        return Button {
            board = value
        } label: {
            Text(label)
                .font(.caption.weight(isOn ? .semibold : .regular))
                .padding(.horizontal, 11).padding(.vertical, 6)
                .background(
                    Capsule().fill(isOn ? Color.accentColor.opacity(0.14) : Color(.systemGray6))
                )
                .overlay(
                    Capsule().strokeBorder(
                        isOn ? Color.accentColor.opacity(0.5) : .clear, lineWidth: 1
                    )
                )
                .foregroundStyle(isOn ? Color.primary : Color.secondary)
        }
        .buttonStyle(.plain)
    }

    // MARK: 글 목록

    private var minePosts: [CommunityPost] {
        board == nil ? store.posts : store.posts.filter { $0.board == board }
    }

    private var showsMarket: Bool {
        board == nil || board == .course
    }

    private var postList: some View {
        List {
            if minePosts.isEmpty, !(showsMarket && !marketCourses.isEmpty) {
                ContentUnavailableView {
                    Label("아직 글이 없습니다", systemImage: "bubble.left.and.bubble.right")
                } description: {
                    Text("첫 글을 남겨 보세요. 다른 여행자의 글은 서버가 열리면 보입니다.")
                }
                .listRowBackground(Color.clear)
            }

            ForEach(minePosts) { post in
                myPostRow(post)
            }

            // 마켓의 코스가 「코스 추천」 게시물이다 — 지금 있는 유일한 남의 글.
            if showsMarket {
                ForEach(marketCourses, id: \.id) { course in
                    marketRow(course)
                }
            }
        }
        .listStyle(.plain)
    }

    private func myPostRow(_ post: CommunityPost) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                badge(post.board.rawValue, tint: .accentColor)
                Text(post.title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            if !post.body.isEmpty {
                Text(post.body)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: 8) {
                Text("나").font(.caption2).foregroundStyle(.tertiary)
                Text(post.createdAt.formatted(.relative(presentation: .named)))
                    .font(.caption2).foregroundStyle(.tertiary)
                if let courseTitle = post.courseTitle {
                    Label(courseTitle, systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                        .font(.caption2).foregroundStyle(Color(PinImage.deep))
                        .lineLimit(1)
                }
                Spacer()
            }
        }
        .padding(.vertical, 4)
        .swipeActions {
            Button(role: .destructive) {
                store.remove(post)
            } label: {
                Label("지우기", systemImage: "trash")
            }
        }
    }

    private func marketRow(_ course: MarketCourseSummary) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                badge("코스 추천", tint: Color(PinImage.deep))
                Text(course.title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            Text("\(course.dayCount)일 · \(course.placeCount)곳"
                + ((course.contents?.isEmpty == false)
                    ? " · " + course.contents!.map(\.title).prefix(2).joined(separator: " · ")
                    : ""))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            HStack(spacing: 10) {
                Text("여행자").font(.caption2).foregroundStyle(.tertiary)
                Label("\(course.likeCount)", systemImage: "heart")
                    .font(.caption2).foregroundStyle(.tertiary)
                Label("\(course.saveCount)", systemImage: "square.and.arrow.down")
                    .font(.caption2).foregroundStyle(.tertiary)
                Spacer()
                Text("담기는 경로여정 탭에서")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
    }

    private func badge(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 5).fill(tint.opacity(0.13)))
            .foregroundStyle(tint)
    }
}
