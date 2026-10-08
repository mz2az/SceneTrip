import SceneApiClient
import SwiftUI

/// 한 대상(촬영지·편의시설)의 사진첩 (MZ2AZ-363 §4) — 받아 둔 사진과 다음 쪽 받기.
///
/// 상세가 앞 20장(`photos`)과 전체 수(`photoCount`)를 실어 준다. 넘겨 보다 끝이 가까워지면
/// `GET …/photos?offset=` 으로 이어 받는다. 대표 사진 줄·크게 보기·격자가 같은 것을 본다 —
/// 어디서 넘기든 받아 둔 사진이 하나다.
///
/// **주소를 저장하지 않는다.** 리뷰 사진의 주소는 한 시간 뒤 죽는다 — 화면이 살아 있는 동안만 쥐고,
/// 리뷰가 바뀌면 통째로 다시 받는다. 같은 사진인지는 `GalleryPhoto.key` 로 가린다.
@MainActor
final class PhotoGallery: ObservableObject {
    /// 무엇을 넘겨 보는가.
    enum Scope {
        /// 우리 사진 + 방문자 사진.
        case all
        /// 방문자 사진만 — 리뷰 시트의 모아 보기.
        case reviews
    }

    @Published private(set) var book = PhotoGalleryRules.Book()
    private(set) var subject: ReviewSubject?
    /// 통째로 다시 받을 때마다 오른다 — 그 전에 떠난 「다음 쪽」 응답을 버린다.
    private var generation = 0
    private var loadingMore = false
    /// 주소를 새로 받는 중인 일 — 여러 칸이 함께 청해도 하나만 나간다.
    private var renewing: Task<Void, Never>?
    private var renewedAt: Date?

    init(subject: ReviewSubject? = nil) {
        self.subject = subject
    }

    func photos(in scope: Scope) -> [GalleryPhoto] {
        scope == .all ? book.photos : book.reviewPhotos
    }

    /// 화면에 적을 전체 수. 방문자 사진의 수를 아직 모르면 가진 만큼.
    func total(in scope: Scope) -> Int {
        scope == .all ? book.total : (book.reviewTotal ?? book.reviewPhotos.count)
    }

    /// 상세가 왔다(또는 리뷰가 바뀌어 다시 왔다) — 그것으로 갈아 끼운다.
    func show(_ fresh: PhotoGalleryRules.Book, subject: ReviewSubject?) {
        generation += 1
        self.subject = subject
        book = fresh
    }

    /// 첫 쪽부터 서버에서 다시. 못 받으면 가진 것을 그대로 둔다.
    func reload() async {
        guard let subject else { return }
        guard let page = try? await subject.photos(offset: 0), !Task.isCancelled, subject == self.subject else {
            return
        }
        var fresh = PhotoGalleryRules.Book()
        fresh.append(page.items, total: page.total, offset: 0)
        show(fresh, subject: subject)
    }

    /// 사진을 못 받았다 — **받아 둔 만큼을 새 주소로 다시 받는다**(리뷰 사진의 주소는 한 시간 뒤 죽는다).
    /// 이름이 같은 사진은 주소만 바뀌므로 이미 그린 사진은 깜빡이지 않고, 실패한 칸만 새 주소로 다시 받는다.
    /// `forced` — 사람이 「다시 시도」 를 눌렀다. 아니면 30초에 한 번만 나간다.
    func renew(forced: Bool) {
        guard renewing == nil, subject != nil,
              PhotoGalleryRules.shouldRenew(forced: forced, last: renewedAt, now: Date())
        else { return }
        renewedAt = Date()
        renewing = Task {
            await refetch()
            renewing = nil
        }
    }

    /// 지금 가진 장수만큼 첫 쪽부터 다시 받아 갈아 끼운다 — 보던 자리가 당겨지지 않게. 중간에 못 받으면 그대로 둔다.
    private func refetch() async {
        guard let subject else { return }
        let asked = generation
        let wanted = max(book.fetched, PhotoGalleryRules.pageSize)
        var fresh = PhotoGalleryRules.Book()
        while fresh.fetched < wanted {
            let offset = fresh.fetched
            guard let page = try? await subject.photos(offset: offset, limit: min(100, wanted - offset)),
                  asked == generation, subject == self.subject
            else { return }
            fresh.append(page.items, total: page.total, offset: offset)
            if !fresh.hasMore {
                break
            }
        }
        show(fresh, subject: subject)
    }

    /// 보는 자리가 끝에 가까우면 다음 쪽을 받는다. 넘길 때마다 부른다.
    func near(_ index: Int, in scope: Scope = .all) {
        let loaded = photos(in: scope).count
        guard PhotoGalleryRules.shouldLoadMore(index: index, loaded: loaded, hasMore: book.hasMore) else { return }
        Task { await loadMore() }
    }

    /// 방문자 사진이 한 장이라도 보일 때까지 받는다 — 우리 사진이 첫 쪽을 다 채운 대상에서.
    /// 세 번까지만 묻는다(한 번에 100장).
    func seekReviews() async {
        for _ in 0 ..< 3 where book.reviewPhotos.isEmpty && book.hasMore {
            await loadMore(limit: 100)
        }
    }

    /// 다음 쪽. 한 번에 하나만 나간다 — 못 받으면 다음에 넘길 때 다시 묻는다.
    func loadMore(limit: Int = PhotoGalleryRules.pageSize) async {
        guard let subject, book.hasMore, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        let asked = (generation: generation, offset: book.fetched)
        guard let page = try? await subject.photos(offset: asked.offset, limit: limit) else { return }
        // 받는 사이 통째로 갈아 끼웠으면 버린다.
        guard asked.generation == generation else { return }
        book.append(page.items, total: page.total, offset: asked.offset)
    }
}

extension PhotoGalleryRules {
    /// 촬영지 상세에서 — `photos` 가 없으면(옛 서버) `imageUrls`, 그것도 없으면 대표 사진 한 장.
    static func book(for detail: PlaceDetail) -> Book {
        book(
            photos: detail.photos, count: detail.photoCount,
            legacy: detail.imageUrls ?? [detail.imageUrl].compactMap { $0 }
        )
    }

    /// 편의시설 상세에서 — `photos` 가 없으면 옛 칸 `images`.
    static func book(for detail: PoiDetail) -> Book {
        book(photos: detail.photos, count: detail.photoCount, legacy: detail.images.map(\.url))
    }
}
