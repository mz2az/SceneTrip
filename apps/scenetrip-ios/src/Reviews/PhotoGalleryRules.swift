import Foundation
import SceneApiClient

/// 사진첩의 한 장 (MZ2AZ-363). 서버의 `Photo`(사진첩)와 `ReviewPhoto`(리뷰 한 건의 사진)를 한 모양으로 든다.
///
/// **`key` 가 이 사진의 이름이다 — 주소가 아니다.** 리뷰 사진의 주소는 서명된 것이라 받을 때마다 다르고
/// 한 시간 뒤 죽는다. 주소로 사진을 가리면 다시 받을 때마다 같은 사진이 새 사진이 돼, 화면이 깜빡이고
/// 다음 쪽을 붙일 때 겹친다.
struct GalleryPhoto: Equatable, Identifiable {
    let key: String
    /// 지금 쓸 수 있는 주소. 저장하지 않는다 — 받은 화면에서만 쓴다.
    let url: String
    /// 리뷰어가 올린 사진인가 — 「방문자 사진」.
    var isReview = false
    var reviewId: Int64?
    /// 우리 사진의 저작자·라이선스 표기. 있으면 사진 곁에 보인다(계약).
    var credit: String?

    var id: String {
        key
    }
}

/// 사진첩의 순수 규칙 (MZ2AZ-363 §4). 화면 없이 시험한다.
enum PhotoGalleryRules {
    /// 관광공사 공개 원본 호스트만 받는다. 쿼리·계정 정보가 있는 주소는 일반 경로에 둔다.
    static func tourImage(_ url: String) -> Bool {
        guard let parts = URLComponents(string: url) else { return false }
        return parts.scheme?.lowercased() == "https" && parts.host?.lowercased() == "tong.visitkorea.or.kr"
            && parts.port == nil && parts.user == nil && parts.password == nil
            && parts.query == nil && parts.fragment == nil
    }

    /// 전체 그림을 보전해야 하는 공식 사진인가.
    static func preservesFrame(_ photo: GalleryPhoto) -> Bool {
        guard !signed(photo) else { return false }
        return tourImage(photo.url) || photo.credit?.contains("공공누리 제3유형") == true
    }

    /// 사진 줄 아래에 적을 원문 출처 목록.
    static func credits(_ photos: [GalleryPhoto]) -> [String] {
        var seen = Set<String>()
        return photos.compactMap(\.credit).filter {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && seen.insert($0).inserted
        }
    }

    /// 한 번에 받는 장수 — 상세가 실어 주는 앞 20장과 같다.
    static let pageSize = 20
    /// 끝에서 이만큼 남으면 다음 쪽을 받는다 — 끝에 닿아 기다리지 않게.
    static let lookahead = 5

    // MARK: 이름

    /// 사진의 이름. 우리 사진은 주소가 곧 이름이다(쿼리에 그림이 실린 주소도 있어 통째로 쓴다).
    /// **리뷰 사진은 서명(쿼리)을 뗀 경로와 리뷰 id** — 서명은 부를 때마다 달라진다.
    static func key(url: String, source: PhotoSource, reviewId: Int64?) -> String {
        guard source == .review else { return "o:\(url)" }
        let owner = reviewId.map(String.init) ?? "-"
        return "r:\(owner):\(unsigned(url))"
    }

    /// 쿼리·조각을 뗀 주소. 못 읽는 주소면 그대로 둔다.
    static func unsigned(_ url: String) -> String {
        guard var parts = URLComponents(string: url) else { return url }
        parts.query = nil
        parts.fragment = nil
        return parts.string ?? url
    }

    /// 서명된 주소인가(리뷰어가 올린 사진) — 그런 주소는 디스크에 남기지 않는다.
    static func signed(_ photo: GalleryPhoto) -> Bool {
        !photo.key.hasPrefix("o:")
    }

    static func photo(_ photo: Photo) -> GalleryPhoto {
        GalleryPhoto(
            key: key(url: photo.url, source: photo.source, reviewId: photo.reviewId),
            url: photo.url,
            isReview: photo.source == .review,
            reviewId: photo.reviewId,
            credit: photo.credit
        )
    }

    /// 리뷰 한 건에 붙은 사진 — 저장소 키가 이름이다. 그 리뷰 안에서 보는 것이라 「방문자 사진」 표시는 없다.
    static func photo(_ photo: ReviewPhoto) -> GalleryPhoto {
        GalleryPhoto(key: "k:\(photo.key)", url: photo.url)
    }

    /// 옛 서버(`photos` 가 없다)의 주소들 — 우리 사진뿐이다.
    static func photos(urls: [String]) -> [GalleryPhoto] {
        unique(urls.map { GalleryPhoto(key: key(url: $0, source: .official, reviewId: nil), url: $0) })
    }

    /// 상세가 준 것에서 그릴 사진을 고른다 — `photos` 가 있으면 그것, 없으면 옛 칸(review-app.md §8-3).
    /// 「전체 수」 는 서버가 준 것, 없으면 가진 만큼.
    static func book(photos: [Photo]?, count: Int?, legacy: [String]) -> Book {
        guard let photos else {
            let old = Self.photos(urls: legacy)
            return Book(photos: old, fetched: old.count, serverTotal: old.count)
        }
        var book = Book()
        book.append(photos, total: count ?? photos.count, offset: 0)
        return book
    }

    /// 같은 이름은 앞의 것만 남긴다.
    static func unique(_ photos: [GalleryPhoto]) -> [GalleryPhoto] {
        var seen = Set<String>()
        return photos.filter { seen.insert($0.key).inserted }
    }

    // MARK: 쪽을 이어 붙이기

    /// 받아 둔 사진첩. 서버에서 몇 줄을 읽었는지(`fetched`)와 화면에 든 장수(`photos.count`)는 다를 수 있다 —
    /// 같은 사진이 두 번 오면(받는 사이 새 리뷰가 끼어들어 한 칸씩 밀림) 한 번만 든다.
    struct Book: Equatable {
        private(set) var photos: [GalleryPhoto] = []
        /// 서버 목록에서 읽은 줄 수 — 다음 쪽의 `offset`.
        private(set) var fetched = 0
        /// 서버가 말한 전체 수.
        private(set) var serverTotal = 0
        /// 마지막에 받은 쪽이 비어 있었다 — 서버의 수가 뭐라 하든 더 묻지 않는다(끝에서 계속 묻지 않게).
        private(set) var drained = false

        init() {}

        init(photos: [GalleryPhoto], fetched: Int, serverTotal: Int) {
            self.photos = photos
            self.fetched = fetched
            self.serverTotal = serverTotal
        }

        var hasMore: Bool {
            !drained && fetched < serverTotal
        }

        /// 화면에 적을 전체 수(「3 / 19」 의 19). 겹쳐서 버린 만큼 뺀다 — 안 그러면 마지막 쪽이 「18 / 19」 에서 끝난다.
        /// 더 받을 것이 없으면 가진 만큼이다.
        var total: Int {
            hasMore ? max(photos.count, serverTotal - (fetched - photos.count)) : photos.count
        }

        /// 한 쪽을 붙인다. `offset` 이 지금 읽은 자리와 다르면(늦게 온 옛 응답) 버린다 — 붙였으면 `true`.
        @discardableResult
        mutating func append(_ items: [Photo], total: Int, offset: Int) -> Bool {
            guard offset == fetched else { return false }
            let known = Set(photos.map(\.key))
            let fresh = PhotoGalleryRules.unique(items.map(PhotoGalleryRules.photo)).filter { !known.contains($0.key) }
            photos += fresh
            fetched += items.count
            serverTotal = total
            drained = items.isEmpty
            return true
        }

        /// 방문자 사진만.
        var reviewPhotos: [GalleryPhoto] {
            photos.filter(\.isReview)
        }

        /// 방문자 사진의 전체 수. **우리 사진이 전부 먼저 온다**(계약) — 첫 방문자 사진의 자리 뒤는 전부 방문자 사진이다.
        /// 아직 한 장도 못 봤고 더 받을 것이 남았으면 모른다(nil).
        var reviewTotal: Int? {
            if let first = photos.firstIndex(where: \.isReview) {
                return max(photos.count - first, total - first)
            }
            return hasMore ? nil : 0
        }
    }

    /// 다음 쪽을 받을 때인가 — 보는 자리가 끝에서 `lookahead` 안으로 들어왔고 더 있을 때.
    static func shouldLoadMore(index: Int, loaded: Int, hasMore: Bool) -> Bool {
        hasMore && index >= loaded - lookahead
    }

    // MARK: 못 받았을 때

    /// 사진을 못 받았다 — 사진첩을 다시 받아(새 주소) 한 번 더 해 볼 것인가.
    /// **서명된 주소일 때만**(한 시간 뒤 죽는다), 그리고 **이 사진으로는 아직 안 해 봤을 때만** — 끝없이 되풀이하지 않는다.
    /// 그 밖에는 「다시 시도」 를 보이고 사람이 누르기를 기다린다.
    static func renewsAfterFailure(signed: Bool, alreadyRenewed: Bool) -> Bool {
        signed && !alreadyRenewed
    }

    /// 사진첩을 지금 다시 받을 것인가. 사람이 누른 것(`forced`)이면 언제나, 저절로 하는 것은 `gap` 초에 한 번 —
    /// 여러 칸이 한꺼번에 실패해도 한 번만 나간다.
    static func shouldRenew(forced: Bool, last: Date?, now: Date, gap: TimeInterval = 30) -> Bool {
        guard !forced, let last else { return true }
        return now.timeIntervalSince(last) >= gap
    }

    // MARK: 넘겨 보기

    /// 넘김과 쪽 표시를 보일 것인가 — **한 장뿐이면 전과 같은 모습**이다.
    static func pages(total: Int) -> Bool {
        total > 1
    }

    /// 이 쪽의 사진을 받을 것인가 — 보이는 쪽과 양옆만. 열아홉 장을 한꺼번에 받지 않는다.
    static func loads(page: Int, current: Int) -> Bool {
        abs(page - current) <= 1
    }

    /// 사진이 줄었을 때(다시 받음) 보던 자리를 안으로 당긴다.
    static func clamped(_ index: Int, count: Int) -> Int {
        min(max(0, index), max(0, count - 1))
    }

    /// 「3 / 19」.
    static func position(index: Int, total: Int) -> String {
        "\(index + 1) / \(max(total, index + 1))"
    }

    /// 낭독 — 「사진 3 / 19」, 방문자 사진이면 「사진 3 / 19, 방문자 사진」.
    static func spoken(index: Int, total: Int, isReview: Bool) -> String {
        let place = String(format: tr("사진 %d / %d"), index + 1, max(total, index + 1))
        return isReview ? "\(place), \(tr("방문자 사진"))" : place
    }

    /// 「방문자 사진 10」 — 수를 아직 모르면 수 없이.
    static func visitorTitle(_ count: Int?) -> String {
        guard let count else { return tr("방문자 사진", at: "모음") }
        return String(format: count == 1 ? tr("방문자 사진 %d", at: "하나") : tr("방문자 사진 %d"), count)
    }

    /// 격자의 머리줄 「사진 19」. 영어는 하나일 때 「1 photo」 — 번역 표의 `사진 %d|전체 하나`(`리뷰 %d|하나` 와 같은 관례).
    static func allTitle(_ count: Int) -> String {
        String(format: count == 1 ? tr("사진 %d", at: "전체 하나") : tr("사진 %d", at: "전체"), count)
    }
}
