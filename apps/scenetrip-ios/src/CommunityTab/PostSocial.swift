import Foundation
import SceneApiClient

/// 좋아요·댓글 창구 (계약 1.24.0, MZ2AZ-538) — 생성 클라이언트(`PostsAPI`)만 부른다. 시험에서 가짜로 바꾼다.
@MainActor
protocol CommunitySocialClient {
    func like(_ postId: Int64, on: Bool) async throws
    func comments(_ postId: Int64) async throws -> TripPostCommentList
    func addComment(_ postId: Int64, body: String) async throws -> TripPostComment
    func deleteComment(_ postId: Int64, commentId: Int64) async throws
}

@MainActor
struct CommunitySocialServerClient: CommunitySocialClient {
    func like(_ postId: Int64, on: Bool) async throws {
        await AuthRefresher.refreshIfStale()
        if on {
            try await PostsAPI.likePost(postId: postId)
        } else {
            try await PostsAPI.unlikePost(postId: postId)
        }
    }

    func comments(_ postId: Int64) async throws -> TripPostCommentList {
        await AuthRefresher.refreshIfStale()
        return try await PostsAPI.listPostComments(postId: postId, limit: 100, offset: 0)
    }

    func addComment(_ postId: Int64, body: String) async throws -> TripPostComment {
        await AuthRefresher.refreshIfStale()
        return try await PostsAPI.createPostComment(postId: postId, tripPostCommentCreate: TripPostCommentCreate(body: body))
    }

    func deleteComment(_ postId: Int64, commentId: Int64) async throws {
        await AuthRefresher.refreshIfStale()
        try await PostsAPI.deletePostComment(postId: postId, commentId: commentId)
    }
}

/// 글 하나의 좋아요·댓글 상태 (웹 `protoPostSocial.tsx`).
/// - 좋아요: 누르면 바로 바뀌고(낙관적) 실패하면 되돌린다. 로그인하지 않았으면 부르지 않고 `.needsSignIn`. 보내는 중에 또 누르면 `.ignored`.
/// - 댓글: 오래된 순(서버 순서 그대로). 쓰면 끝에 붙고 수 +1, 지우면 바로 빼고 수 −1(실패하면 다시 받는다).
/// - 바뀔 때마다 `onChange(liked, likeCount, commentTotal)` 로 목록 줄에 알린다.
@MainActor
final class PostSocialModel: ObservableObject {
    enum Outcome: Equatable {
        case done
        case needsSignIn
        /// 보낼 것이 없거나 이미 보내는 중.
        case ignored
        case failed
    }

    @Published private(set) var liked = false
    @Published private(set) var likeCount = 0
    /// nil 이면 아직 받지 않았다.
    @Published private(set) var comments: [TripPostComment]?
    @Published private(set) var commentTotal = 0
    @Published private(set) var commentsFailed = false
    @Published private(set) var sending = false
    @Published private(set) var likeBusy = false

    let postId: Int64
    private let client: CommunitySocialClient
    private let signedIn: @MainActor () -> Bool
    /// 좋아요·댓글 수가 바뀌면 목록 줄에도 알린다(liked, likeCount, commentTotal).
    var onChange: ((Bool, Int, Int) -> Void)?

    /// 처음 값은 목록 줄·상세의 값이다(nil 이면 false·0).
    init(postId: Int64, liked: Bool?, likeCount: Int?, commentCount: Int? = nil,
         client: CommunitySocialClient? = nil, signedIn: (@MainActor () -> Bool)? = nil)
    {
        self.postId = postId
        self.client = client ?? CommunitySocialServerClient()
        self.signedIn = signedIn ?? { AuthStore.shared.signedIn }
        self.liked = liked ?? false
        self.likeCount = max(0, likeCount ?? 0)
        commentTotal = max(0, commentCount ?? 0)
    }

    /// 상세가 새로 왔다 — 서버 값으로 맞춘다(nil 이면 false·0). 보내는 중이면 내 값이 이긴다.
    func sync(liked: Bool?, likeCount: Int?) {
        guard !likeBusy else { return }
        self.liked = liked ?? false
        self.likeCount = max(0, likeCount ?? 0)
    }

    func toggleLike() async -> Outcome {
        guard signedIn() else { return .needsSignIn }
        guard !likeBusy else { return .ignored }
        let before = (liked: liked, count: likeCount)
        let next = CommunityFeedRules.toggledLike(liked: liked, count: likeCount)
        likeBusy = true
        liked = next.liked
        likeCount = next.count
        notify()
        defer { likeBusy = false }
        do {
            try await client.like(postId, on: next.liked)
            return .done
        } catch {
            liked = before.liked
            likeCount = before.count
            notify()
            return .failed
        }
    }

    /// 댓글을 받는다. 실패하면 `commentsFailed`(받아 둔 목록은 그대로).
    func loadComments() async {
        commentsFailed = false
        do {
            let page = try await client.comments(postId)
            comments = page.items
            commentTotal = page.total
            notify()
        } catch {
            commentsFailed = true
        }
    }

    /// 댓글 쓰기(`CommunityFeedRules.commentBody` 로 다듬어 보낸다). 빈 글이거나 보내는 중이면 `.ignored`,
    /// 로그인 안 했으면 `.needsSignIn`(부르지 않는다). 성공하면 `.done`, 실패하면 `.failed`.
    func send(_ text: String) async -> Outcome {
        guard let body = CommunityFeedRules.commentBody(text), !sending else { return .ignored }
        guard signedIn() else { return .needsSignIn }
        sending = true
        defer { sending = false }
        do {
            let created = try await client.addComment(postId, body: body)
            comments = (comments ?? []) + [created]
            commentTotal += 1
            notify()
            return .done
        } catch {
            return .failed
        }
    }

    /// 내 댓글 지우기 — 바로 빼고 수 −1, 실패하면 다시 받는다.
    func remove(_ comment: TripPostComment) async {
        comments = (comments ?? []).filter { $0.id != comment.id }
        commentTotal = max(0, commentTotal - 1)
        notify()
        do {
            try await client.deleteComment(postId, commentId: comment.id)
        } catch {
            await loadComments()
        }
    }

    /// 댓글 작성자를 차단했다 — 서버가 그 사람의 댓글을 빼므로 다시 받는다.
    func blocked() async {
        await loadComments()
    }

    private func notify() {
        onChange?(liked, likeCount, commentTotal)
    }
}
