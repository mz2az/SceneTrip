import SwiftUI

/// 글 전문 (2026-08-28, 2차).
///
/// 1차는 흰 바탕에 글자만 흘려 놓아 「읽는 화면」으로 안 보였다(사용자 지적 —
/// *"너무 흰 멀건~ 밋밋한 창"*). 게시판의 글은 **종이 위의 게시물**처럼 보여야
/// 한다: 말머리 색 띠, 글쓴이 줄, 본문 카드, 첨부 카드가 각자 제 칸을 가진다.
struct CommunityPostView: View {
    let post: CommunityPost

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            // 말머리가 곧 창의 얼굴이다 — 피노 그라데이션 띠 위에 흰 글자.
            ZStack {
                LinearGradient(
                    colors: [Color(PinImage.light), Color(PinImage.deep)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                HStack {
                    Text(post.board.rawValue)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.9))
                            .frame(width: 32, height: 32)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
            }
            .frame(height: 52)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // 제목 + 글쓴이 줄
                    VStack(alignment: .leading, spacing: 10) {
                        Text(post.title)
                            .font(.title3.weight(.bold))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        HStack(spacing: 8) {
                            // 아바타 — 로그인이 없으니 「나」 한 글자다.
                            Text("나")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 28)
                                .background(Circle().fill(Color(PinImage.deep)))
                            VStack(alignment: .leading, spacing: 1) {
                                Text("나 · 비회원")
                                    .font(.caption.weight(.medium))
                                Text(post.createdAt.formatted(
                                    date: .abbreviated, time: .shortened
                                ))
                                .font(.caption2).foregroundStyle(.tertiary)
                            }
                            Spacer()
                        }
                    }
                    .padding(16)
                    .background(card)

                    // 첨부한 코스
                    if let courseTitle = post.courseTitle {
                        HStack(spacing: 10) {
                            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                                .font(.system(size: 15))
                                .foregroundStyle(.white)
                                .frame(width: 30, height: 30)
                                .background(
                                    RoundedRectangle(cornerRadius: 7)
                                        .fill(Color(PinImage.deep))
                                )
                            VStack(alignment: .leading, spacing: 1) {
                                Text("붙인 코스").font(.caption2).foregroundStyle(.tertiary)
                                Text(courseTitle).font(.subheadline.weight(.semibold))
                            }
                            Spacer()
                        }
                        .padding(12)
                        .background(card)
                    }

                    // 본문
                    Text(post.body.isEmpty ? "본문이 없습니다" : post.body)
                        .font(.body)
                        .lineSpacing(5)
                        .foregroundStyle(post.body.isEmpty ? .secondary : .primary)
                        .frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
                        .padding(16)
                        .background(card)

                    // 반응 줄 — 서버가 열리면 여기가 살아난다. 자리를 보여 준다.
                    HStack(spacing: 18) {
                        Label("좋아요", systemImage: "hand.thumbsup")
                        Label("댓글", systemImage: "bubble.left")
                        Spacer()
                        Text("서버가 열리면 함께 열려요")
                            .font(.caption2)
                    }
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 4)
                }
                .padding(14)
            }
            .background(Color(.systemGroupedBackground))
        }
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: 14)
            .fill(Color(.systemBackground))
            .shadow(color: .black.opacity(0.05), radius: 4, y: 1)
    }
}
