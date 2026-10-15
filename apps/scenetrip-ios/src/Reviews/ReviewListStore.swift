import SceneApiClient
import SwiftUI

/// MZ2AZ-363: 리뷰 첫 쪽과 이어 받기. 사진첩 상태는 PhotoGallery가 별도로 관리한다.
@MainActor
final class ReviewListStore: ObservableObject {
    enum Phase { case loading, loaded, failed }

    @Published private(set) var reviews: [Review] = []
    @Published private(set) var summary: RatingSummary?
    @Published private(set) var total = 0
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var generation = 0
    @Published private(set) var moreFailed = false

    private let auth: AuthStore
    private let load: (ReviewSort, Int) async throws -> ReviewList
    private var requestedSort: ReviewSort = .recent
    private var loadedEpoch: Int?
    private var loadingMoreGeneration: Int?
    private var renewingPhotos = false
    private let photoRenewal = PhotoRenewal()

    init(subject: ReviewSubject, auth: AuthStore? = nil,
         load: ((ReviewSort, Int) async throws -> ReviewList)? = nil)
    {
        self.auth = auth ?? .shared
        self.load = load ?? { try await subject.reviews(sort: $0, offset: $1) }
    }

    func resetForAccount() {
        photoRenewal.cancel()
        renewingPhotos = false
        generation += 1
        reviews = []
        summary = nil
        total = 0
        moreFailed = false
        phase = .loading
        loadedEpoch = nil
        loadingMoreGeneration = nil
    }

    /// 차단한 사람의 리뷰를 다시 받기 전에 바로 뺀다(MZ2AZ-461). 앱은 작성자 번호를 몰라 그 사람의 다른 리뷰는
    /// 다시 받은 목록에서 빠진다.
    func drop(reviewId: Int64) {
        guard reviews.contains(where: { $0.id == reviewId }) else { return }
        reviews.removeAll { $0.id == reviewId }
        total = max(reviews.count, total - 1)
    }

    func prepareSortChange() {
        photoRenewal.cancel()
        renewingPhotos = false
        generation += 1
        total = reviews.count
        phase = .loading
    }

    func prepareRetry() {
        phase = .loading
    }

    func retryMore() {
        moreFailed = false
    }

    func reload(sort: ReviewSort) async {
        guard !Task.isCancelled else { return }
        photoRenewal.cancel()
        renewingPhotos = false
        generation += 1
        phase = .loading
        requestedSort = sort
        let asked = (generation: generation, epoch: auth.epoch)
        do {
            let page = try await load(sort, 0)
            guard !Task.isCancelled, asked.generation == generation, asked.epoch == auth.epoch else { return }
            reviews = page.items
            summary = page.summary
            total = page.total
            moreFailed = false
            loadedEpoch = asked.epoch
            phase = .loaded
        } catch {
            guard !Task.isCancelled, asked.generation == generation, asked.epoch == auth.epoch else { return }
            phase = .failed
        }
    }

    func loadMore(sort: ReviewSort) async {
        guard case .loaded = phase, !renewingPhotos,
              !Task.isCancelled, sort == requestedSort, loadedEpoch == auth.epoch,
              ReviewRules.hasMore(loaded: reviews.count, total: total), loadingMoreGeneration != generation else { return }
        let asked = (generation: generation, offset: reviews.count, epoch: auth.epoch)
        loadingMoreGeneration = generation
        defer {
            if loadingMoreGeneration == asked.generation {
                loadingMoreGeneration = nil
            }
        }
        do {
            let page = try await load(sort, asked.offset)
            guard !Task.isCancelled, asked.generation == generation, asked.epoch == auth.epoch,
                  asked.offset == reviews.count else { return }
            let known = Set(reviews.map(\.id))
            let fresh = page.items.filter { !known.contains($0.id) }
            reviews += fresh
            total = fresh.isEmpty ? reviews.count : page.total
        } catch {
            guard !Task.isCancelled, asked.generation == generation, asked.epoch == auth.epoch else { return }
            moreFailed = true
        }
    }

    func renewPhotos(forced: Bool) async {
        guard case .loaded = phase, loadedEpoch == auth.epoch, !reviews.isEmpty else { return }
        _ = await photoRenewal.run(forced: forced) {
            guard case .loaded = self.phase, self.loadedEpoch == self.auth.epoch else { throw CancellationError() }
            self.generation += 1
            let asked = (generation: self.generation, epoch: self.auth.epoch, sort: self.requestedSort, count: self.reviews.count)
            self.renewingPhotos = true
            defer {
                if asked.generation == self.generation {
                    self.renewingPhotos = false
                    // 새 순서에 맞춰 다음 쪽 footer가 다시 실행된다.
                    self.generation += 1
                }
            }
            var freshSummary: RatingSummary?
            let page = try await PhotoPages.refetch(wanted: asked.count, id: { $0.id }) { offset in
                guard asked.generation == self.generation, asked.epoch == self.auth.epoch else { throw CancellationError() }
                let fresh = try await self.load(asked.sort, offset)
                if offset == 0 {
                    freshSummary = fresh.summary
                }
                return PhotoPages.Page(items: fresh.items, total: fresh.total)
            }
            try Task.checkCancellation()
            guard asked.generation == self.generation, asked.epoch == self.auth.epoch,
                  asked.count == self.reviews.count else { throw CancellationError() }
            self.reviews = page.items
            self.summary = freshSummary
            self.total = page.total
            self.moreFailed = false
        }
    }
}
