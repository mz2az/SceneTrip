import SceneApiClient
import SwiftUI

/// 방문 스탬프 하나 — 여행 중 성지 반경 100 m 에 들어(또는 「여기 도착함」을 눌러)
/// 서버에 남은 `visitedAt` 이 근거다. **지어낸 기록이 아니다.**
struct VisitStamp: Identifiable {
    let id: Int64
    let name: String
    let workTitle: String?
    let courseTitle: String
    let visitedAt: Date
}

/// 방문 스탬프첩 (2026-08-28).
///
/// 도장첩처럼 격자로 찍힌다 — 목록이 아니라 **모은 것**으로 보여야 다음 성지를
/// 찍으러 가고 싶어진다.
struct StampsSheet: View {
    let stamps: [VisitStamp]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: "방문 스탬프") { dismiss() }
            if stamps.isEmpty {
                ContentUnavailableView(
                    "아직 스탬프가 없습니다",
                    systemImage: "checkmark.seal",
                    description: Text("여행 중 성지 100 m 안에 들어가면 저절로 찍혀요")
                )
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 3),
                        spacing: 18
                    ) {
                        ForEach(stamps) { stamp in
                            stampCell(stamp)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }

    private func stampCell(_ stamp: VisitStamp) -> some View {
        VStack(spacing: 6) {
            ZStack {
                // 도장 테 — 피노 색 이중 원. 살짝 기울여 「찍었다」는 손맛을 준다.
                Circle()
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color(PinImage.light), Color(PinImage.deep)],
                            startPoint: .topLeading, endPoint: .bottomTrailing
                        ),
                        lineWidth: 3
                    )
                Circle()
                    .strokeBorder(Color(PinImage.deep).opacity(0.35), lineWidth: 1)
                    .padding(5)
                VStack(spacing: 2) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(Color(PinImage.deep))
                    Text(stamp.name)
                        .font(.system(size: 9, weight: .bold))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .padding(.horizontal, 8)
                }
            }
            .frame(width: 88, height: 88)
            .rotationEffect(.degrees(Double(stamp.id % 7) - 3))

            Text(stamp.visitedAt.formatted(date: .abbreviated, time: .omitted))
                .font(.caption2).foregroundStyle(.secondary)
            if let workTitle = stamp.workTitle {
                Text(workTitle)
                    .font(.caption2).foregroundStyle(Color.accentColor).lineLimit(1)
            }
        }
    }
}

/// 장바구니 팝업 — 검색 탭에서 담아 둔 촬영지. **보는 자리**다: 빼고 담는 것은
/// 검색 탭의 장바구니가 맡는다.
struct ProfileCartSheet: View {
    let items: [CartItem]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: "장바구니") { dismiss() }
            if items.isEmpty {
                ContentUnavailableView(
                    "장바구니가 비었습니다",
                    systemImage: "bag",
                    description: Text("작품검색 탭에서 촬영지를 담아 보세요")
                )
            } else {
                List(items, id: \.placeId) { item in
                    HStack(spacing: 10) {
                        RemoteImage(url: item.imageUrl, symbol: "mappin.and.ellipse")
                            .frame(width: 40, height: 40)
                            .clipShape(.rect(cornerRadius: 7))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).font(.subheadline.weight(.medium))
                            Text([item.sourceContentTitle, item.address]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 2)
                }
                .listStyle(.plain)
                Text("담고 빼는 것은 작품검색 탭의 장바구니에서")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .padding(.vertical, 8)
            }
        }
    }
}

/// 내가 쓴 글 (2026-08-28). 커뮤니티의 기기 저장 글을 마이페이지에서 되짚는다 —
/// 누르면 커뮤니티와 **같은 전문 화면**이 열린다. 지우기도 여기서 된다(같은 저장소).
struct MyPostsSheet: View {
    @ObservedObject private var store = CommunityStore.shared

    @Environment(\.dismiss) private var dismiss

    @State private var reading: CommunityPost?

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: "내가 쓴 글") { dismiss() }
            if store.posts.isEmpty {
                ContentUnavailableView(
                    "아직 쓴 글이 없습니다",
                    systemImage: "square.and.pencil",
                    description: Text("커뮤니티 탭에서 첫 글을 남겨 보세요")
                )
            } else {
                List(store.posts) { post in
                    Button {
                        reading = post
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 6) {
                                Text(post.board.rawValue)
                                    .font(.caption2.weight(.semibold))
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(
                                        RoundedRectangle(cornerRadius: 5)
                                            .fill(Color.accentColor.opacity(0.13))
                                    )
                                    .foregroundStyle(Color.accentColor)
                                Text(post.title)
                                    .font(.subheadline.weight(.medium)).lineLimit(1)
                            }
                            Text(post.createdAt.formatted(date: .abbreviated, time: .shortened))
                                .font(.caption2).foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 2)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button(role: .destructive) {
                            store.remove(post)
                        } label: {
                            Label("지우기", systemImage: "trash")
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .sheet(item: $reading) { post in
            CommunityPostView(post: post)
        }
    }
}
