import SwiftUI

/// 글 전문 (2026-08-28). 목록 행은 두 줄로 잘리므로 **읽는 자리가 따로** 있어야
/// 한다 — 길게 쓴 글이 잘린 채로만 보이면 쓸 이유가 없다.
struct CommunityPostView: View {
    let post: CommunityPost

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ProfileSheetHeader(title: post.board.rawValue) { dismiss() }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(post.title).font(.title3.weight(.bold))
                    HStack(spacing: 8) {
                        Text("나").font(.caption).foregroundStyle(.secondary)
                        Text(post.createdAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption).foregroundStyle(.tertiary)
                    }
                    if let courseTitle = post.courseTitle {
                        Label(courseTitle,
                              systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Capsule().fill(Color(PinImage.deep).opacity(0.12)))
                            .foregroundStyle(Color(PinImage.deep))
                    }
                    Divider()
                    Text(post.body.isEmpty ? "본문이 없습니다" : post.body)
                        .font(.body)
                        .foregroundStyle(post.body.isEmpty ? .secondary : .primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(16)
            }
        }
    }
}
