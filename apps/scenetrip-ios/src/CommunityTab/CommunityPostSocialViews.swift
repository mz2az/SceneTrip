import SceneApiClient
import SwiftUI

/// 글 끝의 좋아요 — 가운데 알약 「👍 좋아요 N」. 누르면 보라로 채워지며 엄지가 톡 커진다(웹 `PostLike`).
/// 비로그인이면 로그인으로 안내한다.
struct CommunityLikeButton: View {
    @ObservedObject var social: PostSocialModel
    @ObservedObject private var auth = AuthStore.shared
    @State private var pop = 0

    var body: some View {
        Button {
            Task {
                let outcome = await social.toggleLike()
                if outcome == .needsSignIn {
                    auth.promptSignIn()
                }
            }
            if auth.signedIn {
                pop += 1
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: social.liked ? "hand.thumbsup.fill" : "hand.thumbsup")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(social.liked ? Color.white : Color(PinImage.deep))
                    .symbolEffect(.bounce, value: pop)
                Text(String(format: tr("좋아요 %d"), social.likeCount))
                    .font(.system(size: 15, weight: .bold)).monospacedDigit()
                    .foregroundStyle(social.liked ? Color.white : Color.primary)
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 44)
            .background(
                Capsule().fill(social.liked ? Color(PinImage.deep) : Color(.systemBackground))
            )
            .overlay(
                Capsule().strokeBorder(social.liked ? Color.clear : Color(.systemGray4), lineWidth: 1)
            )
            .animation(.easeOut(duration: 0.16), value: social.liked)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(social.liked ? .isSelected : [])
        .accessibilityIdentifier("community-like")
        .frame(maxWidth: .infinity)
        .padding(.top, 8).padding(.bottom, 4)
    }
}

/// 좋아요 아래 댓글 — 얇은 선 위 「댓글 N」, 줄마다 이름·시간·내용(오래된 순), 맨 끝에 입력칸(글에 붙는다, 바닥 고정 아님).
/// 내 댓글은 「지우기」, 남의 댓글은 글과 같은 「⋯」 신고·차단. 차단하면 서버가 그 사람의 댓글을 빼므로 다시 받는다.
struct CommunityCommentsSection: View {
    @ObservedObject var social: PostSocialModel
    @ObservedObject private var auth = AuthStore.shared
    @StateObject private var moderation = ModerationController()
    @State private var text = ""
    @FocusState private var focused: Bool

    private var canSend: Bool {
        CommunityFeedRules.commentBody(text) != nil && !social.sending
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider()
            Text(String(format: tr("댓글 %d"), social.commentTotal))
                .font(.system(size: 15, weight: .bold))
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)
            if social.comments == nil, !social.commentsFailed {
                ProgressView().frame(maxWidth: .infinity).padding(16)
            }
            if social.commentsFailed {
                Button {
                    Task { await social.loadComments() }
                } label: {
                    Text("댓글을 불러오지 못했어요 — 다시 시도").font(.system(size: 13)).foregroundStyle(.secondary)
                        .frame(minHeight: 40)
                }
                .buttonStyle(.plain)
            }
            if let comments = social.comments, !comments.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(comments, id: \.id) { comment in
                        row(comment)
                    }
                }
                .padding(.top, 6)
            }
            composer.padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task { await social.loadComments() }
        .moderationSheets(moderation) { target in
            if case .comment = target {
                Task { await social.blocked() }
            }
        }
    }

    private func row(_ comment: TripPostComment) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(comment.author?.nickname ?? tr("탈퇴한 사용자")).font(.system(size: 13, weight: .semibold))
                Text(comment.createdAt.formatted(.relative(presentation: .named).locale(AppLanguage.currentLocale)))
                    .font(.system(size: 12)).foregroundStyle(.tertiary)
                Spacer(minLength: 0)
                if comment.isMine {
                    Button {
                        Task { await social.remove(comment) }
                    } label: {
                        Text("지우기").font(.system(size: 12)).foregroundStyle(.tertiary)
                            .padding(.horizontal, 4).frame(minHeight: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tr("댓글 지우기"))
                } else {
                    ModerationMenu(
                        target: .comment(comment.id),
                        canBlock: ModerationRules.canBlock(isMine: comment.isMine, hasAuthor: comment.author != nil),
                        controller: moderation
                    )
                }
            }
            Text(verbatim: comment.body).font(.system(size: 15))
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
        .padding(.vertical, 10)
        .overlay(alignment: .bottom) { Divider() }
    }

    private var composer: some View {
        HStack(spacing: 8) {
            TextField(tr(CommunityFeedRules.commentPlaceholderKey(total: social.commentTotal)), text: $text, axis: .vertical)
                .font(.system(size: 16))
                .lineLimit(1 ... 4)
                .focused($focused)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .frame(minHeight: 40)
                .background(RoundedRectangle(cornerRadius: 20).fill(Color(.systemGray6)))
                .onChange(of: text) { _, value in
                    if value.count > CommunityFeedRules.commentLimit {
                        text = String(value.prefix(CommunityFeedRules.commentLimit))
                    }
                }
                .accessibilityLabel(tr("댓글"))
                .accessibilityIdentifier("community-comment-field")
            Button {
                Task {
                    switch await social.send(text) {
                    case .done:
                        text = ""
                        focused = false
                    case .needsSignIn:
                        auth.promptSignIn()
                    case .ignored, .failed:
                        break
                    }
                }
            } label: {
                Text("등록").font(.system(size: 15, weight: .bold))
                    .foregroundStyle(canSend ? Color(PinImage.deep) : Color(.tertiaryLabel))
                    .padding(.horizontal, 6).frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .accessibilityIdentifier("community-comment-send")
        }
    }
}
