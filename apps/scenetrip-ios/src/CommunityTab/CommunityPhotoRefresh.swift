import SwiftUI

/// MZ2AZ-428: 같은 상세 응답의 사진·본문·출처·AI 표시를 함께 갱신한다. 기기 옛 글은 그대로 둔다.
@MainActor
final class CommunityPhotoRefresh: ObservableObject {
    @Published private(set) var post: CommunityPost?
    private var seed: CommunityPost
    private let detail: (CommunityPost) async throws -> CommunityPost
    private let accountEpoch: () -> Int
    private let renewal: PhotoRenewal
    private var generation = 0

    init(post: CommunityPost, accountEpoch: (() -> Int)? = nil,
         detail: ((CommunityPost) async throws -> CommunityPost)? = nil,
         renewal: PhotoRenewal? = nil)
    {
        seed = post
        self.accountEpoch = accountEpoch ?? { AuthStore.shared.epoch }
        self.detail = detail ?? { try await CommunityStore.shared.detail($0) }
        self.renewal = renewal ?? PhotoRenewal()
    }

    func show(_ post: CommunityPost) {
        generation += 1
        renewal.cancel()
        seed = post
        self.post = post
    }

    func invalidate() {
        generation += 1
        renewal.cancel()
        post = nil
    }

    func cancelPending() {
        generation += 1
        renewal.cancel()
    }

    func renew(forced: Bool) async -> Bool {
        guard let id = seed.serverId else { return false }
        let requestedPost = seed
        let asked = (generation: generation, epoch: accountEpoch())
        return await renewal.run(forced: forced) {
            guard asked.generation == self.generation, asked.epoch == self.accountEpoch() else { throw CancellationError() }
            let fresh = try await self.detail(requestedPost)
            try Task.checkCancellation()
            guard asked.generation == self.generation, asked.epoch == self.accountEpoch() else { throw CancellationError() }
            guard fresh.serverId == id, fresh.id == requestedPost.id else { throw URLError(.badServerResponse) }
            self.post = fresh
        }
    }
}
