import SceneApiClient
import SwiftUI

/// **여행자들의 코스** — 마켓에서 담기 많은 순으로 둘. 카드·링크 모두 경로여정의 마켓으로.
struct HomeMarketPreview: View {
    let courses: [MarketCourseSummary]
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: "여행자들의 코스", subtitle: "담기 많은 순", action: "둘러보기", onAction: onOpen)
            if courses.isEmpty {
                Text("아직 올라온 코스가 없습니다").font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
            } else {
                HStack(spacing: 10) {
                    ForEach(courses, id: \.id) { course in
                        Button(action: onOpen) { card(course) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private func card(_ course: MarketCourseSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(course.contents?.first?.title ?? "코스")
                .font(.system(size: 12, weight: .bold)).foregroundStyle(TabBar.homePurple).lineLimit(1)
            Text("\(course.title)\n\(RouteSpan(days: course.dayCount).label)")
                .font(.system(size: 15, weight: .bold)).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 10) {
                Text("\(course.placeCount)곳")
                Text("담기 \(course.saveCount)")
                Text("♥ \(course.likeCount)").foregroundStyle(.red)
            }
            .font(.system(size: 12)).foregroundStyle(.secondary)
            .padding(.top, 2)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .homeCard()
    }
}

/// **커뮤니티 지금** — 최근 글 둘. 게시판 배지 · 제목 · 하트 자리.
struct HomeCommunityNow: View {
    let posts: [CommunityPost]
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: "커뮤니티 지금", subtitle: "방금 올라온 글", action: "더 보기", onAction: onOpen)
            if posts.isEmpty {
                Text("첫 글을 남겨 보세요").font(.footnote).foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(posts.enumerated()), id: \.element.id) { index, post in
                        if index > 0 {
                            Divider().padding(.leading, 16)
                        }
                        Button(action: onOpen) { row(post) }.buttonStyle(.plain)
                    }
                }
                .homeCard()
                .padding(.horizontal, 20)
            }
        }
    }

    private func row(_ post: CommunityPost) -> some View {
        HStack(spacing: 10) {
            Text(post.board.rawValue)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(badgeTone(post.board))
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(badgeTone(post.board).opacity(0.12))
                )
                .fixedSize()
            Text(post.title).font(.system(size: 14)).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "heart").font(.system(size: 12)).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 16).padding(.vertical, 13)
        .contentShape(.rect)
    }

    private func badgeTone(_ board: CommunityPost.Board) -> Color {
        switch board {
        case .photo: TabBar.homePurple
        case .review: Color(red: 0.18, green: 0.49, blue: 0.27)
        case .course: Color.accentColor
        case .chat: Color.secondary
        }
    }
}

/// **내 기록** — 방문 스탬프 셋과 「+N」, 찜한 작품 수. 둘 다 마이페이지로 이어진다.
struct HomeMyRecord: View {
    let stamps: [VisitStamp]
    let likeCount: Int
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: "내 기록", subtitle: "프로필에서 전부 보기")
            HStack(spacing: 10) {
                Button(action: onOpen) { stampsCard }.buttonStyle(.plain)
                Button(action: onOpen) { likesCard }.buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
        }
    }

    private var stampsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("방문 스탬프").font(.system(size: 13, weight: .bold))
            if stamps.isEmpty {
                Text("여행 중 성지에 닿으면 도장이 찍힙니다")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            } else {
                HStack(spacing: 6) {
                    ForEach(Array(stamps.prefix(3).enumerated()), id: \.element.id) { index, _ in
                        Circle()
                            .fill(LinearGradient(colors: [Color(PinImage.light), Color(PinImage.deep)],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 34, height: 34)
                            .opacity(1 - Double(index) * 0.25)
                    }
                    if stamps.count > 3 {
                        Text("+\(stamps.count - 3)")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                            .frame(width: 34, height: 34)
                            .overlay(Circle().stroke(style: StrokeStyle(lineWidth: 1.5, dash: [3]))
                                .foregroundStyle(Color(.systemGray3)))
                    }
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .homeCard()
    }

    private var likesCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("찜한 작품").font(.system(size: 13, weight: .bold))
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(likeCount)").font(.system(size: 22, weight: .heavy)).foregroundStyle(.red)
                Text("편").font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(width: 132, alignment: .leading)
        .homeCard()
    }
}
