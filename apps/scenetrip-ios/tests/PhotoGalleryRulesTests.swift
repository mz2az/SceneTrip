import SceneApiClient
@testable import SceneTrip
import UIKit
import XCTest

/// 사진첩의 규칙 (MZ2AZ-363 §4) — 사진의 이름, 쪽 붙이기·겹침 거르기, 다음 쪽을 받을 때, 쪽 표시.
final class PhotoGalleryRulesTests: XCTestCase {
    func testTourApiHostIsExactAndPublicHttpsOnly() {
        XCTAssertTrue(PhotoGalleryRules.tourImage("https://tong.visitkorea.or.kr/cms/a.jpg"))
        for url in [
            "https://tong.visitkorea.or.kr.evil.example/a.jpg", "https://eviltong.visitkorea.or.kr/a.jpg",
            "https://example.com/tong.visitkorea.or.kr/a.jpg", "http://tong.visitkorea.or.kr/a.jpg",
            "https://name@tong.visitkorea.or.kr/a.jpg", "https://tong.visitkorea.or.kr:8443/a.jpg",
            "https://tong.visitkorea.or.kr/a.jpg?X-Amz-Signature=abc",
        ] {
            XCTAssertFalse(PhotoGalleryRules.tourImage(url), url)
        }
    }

    func testTourOfficialPhotosKeepTheWholeFrameEvenWithoutCredit() {
        let url = "https://tong.visitkorea.or.kr/cms/a.jpg"
        for credit in [nil, "사진: 한국관광공사 · 공공누리 제1유형", "사진: 한국관광공사 · 공공누리 제3유형"] {
            let photo = PhotoGalleryRules.photo(Photo(url: url, source: .official, credit: credit))
            XCTAssertTrue(PhotoGalleryRules.preservesFrame(photo))
            XCTAssertFalse(PhotoGalleryRules.signed(photo))
        }
        XCTAssertTrue(PhotoGalleryRules.preservesFrame(PhotoGalleryRules.photo(
            Photo(url: "https://img.example/a.jpg", source: .official, credit: "공공누리 제3유형")
        )))
    }

    func testVisitorAndOrdinaryPhotosKeepExistingFillAndSignedPolicy() {
        let visitor = PhotoGalleryRules.photo(review("p1"))
        let attached = PhotoGalleryRules.photo(ReviewPhoto(key: "reviews/a.jpg", url: visitor.url))
        let poster = PhotoGalleryRules.photo(official("poster"))
        for photo in [visitor, attached, poster] {
            XCTAssertFalse(PhotoGalleryRules.preservesFrame(photo))
        }
        XCTAssertTrue(PhotoGalleryRules.signed(visitor))
        XCTAssertTrue(PhotoGalleryRules.signed(attached))
        XCTAssertFalse(PhotoGalleryRules.signed(poster))
    }

    func testCaptionKeepsCreditVerbatimAndDropsOnlyBlankAndDuplicateValues() {
        let credit = "사진: 한국관광공사 · 공공누리 제3유형"
        let photos = [credit, credit, "", "  ", "다른 출처"].map {
            PhotoGalleryRules.photo(Photo(url: "https://img.example/\($0).jpg", source: .official, credit: $0))
        }
        XCTAssertEqual(PhotoGalleryRules.credits(photos), [credit, "다른 출처"])
        XCTAssertTrue(PhotoGalleryRules.credits([PhotoGalleryRules.photo(review("p1"))]).isEmpty)
    }

    func testExistingLoaderBoundsLargeTourPhotoDisplayPixels() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let source = UIGraphicsImageRenderer(size: CGSize(width: 5141, height: 3427), format: format).image { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 5141, height: 3427))
        }
        let data = try XCTUnwrap(source.jpegData(compressionQuality: 0.8))
        for size in [PhotoLoader.Size.tile, .page] {
            let image = try XCTUnwrap(PhotoLoader.decode(data, longest: size.rawValue))
            let width = image.size.width * image.scale
            let height = image.size.height * image.scale
            XCTAssertEqual(width, CGFloat(size.rawValue))
            XCTAssertEqual(width / height, 5141.0 / 3427.0, accuracy: 0.01)
        }
    }

    private func official(_ name: String) -> Photo {
        Photo(url: "https://img.example/\(name).jpg", source: .official)
    }

    /// 서명은 부를 때마다 다르다 — `sign` 으로 흉내 낸다.
    private func review(_ name: String, of reviewId: Int64 = 4, sign: String = "a") -> Photo {
        Photo(
            url: "http://store.example/media/reviews/\(name).jpg?X-Amz-Date=\(sign)&X-Amz-Signature=\(sign)\(sign)",
            source: .review, reviewId: reviewId
        )
    }

    // MARK: 이름

    /// 리뷰 사진의 주소는 서명돼 있어 받을 때마다 다르다 — 이름은 같아야 한다(티켓 「캐시 키로 쓰지 말고」).
    func testReviewPhotoKeepsItsKeyWhenTheSignedUrlChanges() {
        let first = PhotoGalleryRules.photo(review("p1", sign: "a"))
        let again = PhotoGalleryRules.photo(review("p1", sign: "b"))
        XCTAssertNotEqual(first.url, again.url)
        XCTAssertEqual(first.key, again.key)
        XCTAssertFalse(first.key.contains("X-Amz"), "서명이 이름에 섞이면 안 된다")
        XCTAssertNotEqual(first.key, PhotoGalleryRules.photo(review("p2")).key)
    }

    /// 우리 사진은 쿼리에 그림이 실린 주소가 있다 — 쿼리를 떼면 다른 사진이 한 이름이 된다.
    func testOfficialPhotoKeyKeepsTheQuery() {
        let one = PhotoGalleryRules.key(url: "https://img.example/common/?src=a.jpg", source: .official, reviewId: nil)
        let two = PhotoGalleryRules.key(url: "https://img.example/common/?src=b.jpg", source: .official, reviewId: nil)
        XCTAssertNotEqual(one, two)
    }

    func testPhotoCarriesSourceReviewAndCredit() {
        let visitor = PhotoGalleryRules.photo(review("p1", of: 7))
        XCTAssertTrue(visitor.isReview)
        XCTAssertEqual(visitor.reviewId, 7)
        XCTAssertTrue(PhotoGalleryRules.signed(visitor))

        let ours = PhotoGalleryRules.photo(
            Photo(url: "https://img.example/a.jpg", source: .official, credit: "© 한국관광공사")
        )
        XCTAssertFalse(ours.isReview)
        XCTAssertNil(ours.reviewId)
        XCTAssertEqual(ours.credit, "© 한국관광공사")
        XCTAssertFalse(PhotoGalleryRules.signed(ours))
    }

    /// 리뷰 한 건의 사진은 저장소 키가 이름이고, 그 리뷰 안에서 보므로 「방문자 사진」 표시가 없다.
    func testReviewRowPhotoUsesTheStorageKey() {
        let photo = PhotoGalleryRules.photo(ReviewPhoto(key: "reviews/a.jpg", url: "http://store.example/a.jpg?sig=1"))
        XCTAssertEqual(photo.key, "k:reviews/a.jpg")
        XCTAssertFalse(photo.isReview)
        XCTAssertTrue(PhotoGalleryRules.signed(photo), "서명된 주소다 — 디스크에 남기지 않는다")
    }

    // MARK: 상세에서

    /// `photos` 가 있으면 그것, 전체 수는 `photoCount`.
    func testBookUsesPhotosAndPhotoCount() {
        let book = PhotoGalleryRules.book(
            photos: [official("a"), official("b"), review("p1")], count: 19, legacy: ["https://old.example/x.jpg"]
        )
        XCTAssertEqual(book.photos.map(\.isReview), [false, false, true], "서버 순서 그대로 — 우리 사진 먼저")
        XCTAssertEqual(book.total, 19)
        XCTAssertEqual(book.fetched, 3)
        XCTAssertTrue(book.hasMore)
    }

    /// 옛 서버는 `photos` 를 안 준다 — 옛 칸으로 그린다(review-app.md §8-3). 겹친 주소는 한 번만.
    func testBookFallsBackToLegacyUrls() {
        let book = PhotoGalleryRules.book(
            photos: nil, count: nil, legacy: ["https://old.example/x.jpg", "https://old.example/x.jpg"]
        )
        XCTAssertEqual(book.photos.count, 1)
        XCTAssertEqual(book.total, 1)
        XCTAssertFalse(book.hasMore)
    }

    /// `photos` 가 빈 배열이면 사진이 없는 곳이다 — 옛 칸으로 물러서지 않는다.
    func testEmptyPhotosMeansNoPhotos() {
        let book = PhotoGalleryRules.book(photos: [], count: 0, legacy: ["https://old.example/x.jpg"])
        XCTAssertTrue(book.photos.isEmpty)
        XCTAssertEqual(book.total, 0)
        XCTAssertFalse(book.hasMore)
    }

    // MARK: 쪽을 이어 붙이기

    func testAppendAddsTheNextPage() {
        var book = PhotoGalleryRules.Book()
        XCTAssertTrue(book.append((0 ..< 20).map { official("a\($0)") }, total: 23, offset: 0))
        XCTAssertTrue(book.hasMore)
        XCTAssertTrue(book.append((20 ..< 23).map { review("p\($0)") }, total: 23, offset: 20))
        XCTAssertEqual(book.photos.count, 23)
        XCTAssertEqual(book.total, 23)
        XCTAssertFalse(book.hasMore)
    }

    /// 받는 사이 새 리뷰가 끼어들면 한 칸씩 밀려 같은 사진이 또 온다 — 서명이 달라도 한 번만 든다.
    /// 전체 수도 그만큼 줄여 적는다(안 그러면 마지막 쪽이 「22 / 23」 에서 끝난다).
    func testAppendDropsPhotosAlreadyLoadedEvenWithANewSignature() {
        var book = PhotoGalleryRules.Book()
        book.append([official("a"), review("p1", sign: "a"), review("p2", sign: "a")], total: 5, offset: 0)
        book.append([review("p2", sign: "b"), review("p3", sign: "b")], total: 5, offset: 3)
        XCTAssertEqual(book.photos.count, 4)
        XCTAssertEqual(book.fetched, 5)
        XCTAssertEqual(book.total, 4)
        XCTAssertFalse(book.hasMore)
        XCTAssertEqual(Set(book.photos.map(\.key)).count, 4)
    }

    /// 늦게 온 옛 응답(다른 자리의 쪽)은 붙이지 않는다.
    func testAppendIgnoresAPageForAnotherOffset() {
        var book = PhotoGalleryRules.Book()
        book.append([official("a"), official("b")], total: 6, offset: 0)
        XCTAssertFalse(book.append([official("z")], total: 6, offset: 4))
        XCTAssertEqual(book.photos.count, 2)
        XCTAssertEqual(book.fetched, 2)
    }

    /// 서버의 수가 남았다고 해도 빈 쪽이 오면 끝이다 — 끝에서 계속 묻지 않는다.
    func testAnEmptyPageEndsTheBook() {
        var book = PhotoGalleryRules.Book()
        book.append([official("a"), official("b")], total: 9, offset: 0)
        XCTAssertTrue(book.hasMore)
        book.append([], total: 9, offset: 2)
        XCTAssertFalse(book.hasMore)
        XCTAssertEqual(book.total, 2, "더 없으면 가진 만큼이 전체다")
    }

    /// 끝에서 다섯 장 안으로 들어오면 다음 쪽을 받는다. 더 없으면 받지 않는다.
    func testLoadsMoreBeforeReachingTheEnd() {
        XCTAssertFalse(PhotoGalleryRules.shouldLoadMore(index: 0, loaded: 20, hasMore: true))
        XCTAssertFalse(PhotoGalleryRules.shouldLoadMore(index: 14, loaded: 20, hasMore: true))
        XCTAssertTrue(PhotoGalleryRules.shouldLoadMore(index: 15, loaded: 20, hasMore: true))
        XCTAssertTrue(PhotoGalleryRules.shouldLoadMore(index: 19, loaded: 20, hasMore: true))
        XCTAssertFalse(PhotoGalleryRules.shouldLoadMore(index: 19, loaded: 20, hasMore: false))
        XCTAssertTrue(PhotoGalleryRules.shouldLoadMore(index: 0, loaded: 3, hasMore: true), "받은 것이 적으면 바로")
    }

    // MARK: 방문자 사진

    /// 우리 사진이 전부 먼저 온다 — 첫 방문자 사진 뒤는 전부 방문자 사진이다(아직 안 받은 것까지).
    func testReviewTotalCountsEverythingAfterTheFirstVisitorPhoto() {
        var book = PhotoGalleryRules.Book()
        book.append((0 ..< 9).map { official("a\($0)") } + (0 ..< 10).map { review("p\($0)") }, total: 19, offset: 0)
        XCTAssertEqual(book.reviewPhotos.count, 10)
        XCTAssertEqual(book.reviewTotal, 10)

        var partial = PhotoGalleryRules.Book()
        partial.append((0 ..< 18).map { official("a\($0)") } + [review("p0"), review("p1")], total: 50, offset: 0)
        XCTAssertEqual(partial.reviewTotal, 32)
    }

    /// 첫 쪽이 전부 우리 사진이고 더 남았으면 아직 모른다. 다 받았는데 없으면 0.
    func testReviewTotalIsUnknownUntilAVisitorPhotoIsSeen() {
        var book = PhotoGalleryRules.Book()
        book.append((0 ..< 20).map { official("a\($0)") }, total: 30, offset: 0)
        XCTAssertNil(book.reviewTotal)

        var done = PhotoGalleryRules.Book()
        done.append([official("a")], total: 1, offset: 0)
        XCTAssertEqual(done.reviewTotal, 0)
    }

    // MARK: 넘겨 보기

    /// 한 장뿐이면 넘김도 쪽 표시도 없다 — 전과 같은 모습.
    func testPagingNeedsMoreThanOnePhoto() {
        XCTAssertFalse(PhotoGalleryRules.pages(total: 0))
        XCTAssertFalse(PhotoGalleryRules.pages(total: 1))
        XCTAssertTrue(PhotoGalleryRules.pages(total: 2))
    }

    /// 보이는 쪽과 양옆만 받는다.
    func testOnlyTheCurrentPageAndItsNeighboursLoad() {
        XCTAssertTrue(PhotoGalleryRules.loads(page: 3, current: 3))
        XCTAssertTrue(PhotoGalleryRules.loads(page: 2, current: 3))
        XCTAssertTrue(PhotoGalleryRules.loads(page: 4, current: 3))
        XCTAssertFalse(PhotoGalleryRules.loads(page: 5, current: 3))
        XCTAssertFalse(PhotoGalleryRules.loads(page: 0, current: 3))
    }

    func testPositionText() {
        XCTAssertEqual(PhotoGalleryRules.position(index: 2, total: 19), "3 / 19")
        XCTAssertEqual(PhotoGalleryRules.position(index: 0, total: 1), "1 / 1")
        // 전체 수가 보는 자리보다 작게 왔어도 「5 / 3」 이라고 적지 않는다.
        XCTAssertEqual(PhotoGalleryRules.position(index: 4, total: 3), "5 / 5")
    }

    func testSpokenLabelSaysWhenItIsAVisitorPhoto() {
        let plain = PhotoGalleryRules.spoken(index: 2, total: 19, isReview: false)
        let visitor = PhotoGalleryRules.spoken(index: 9, total: 19, isReview: true)
        XCTAssertTrue(plain.contains("3") && plain.contains("19"))
        XCTAssertTrue(visitor.contains("10") && visitor.count > plain.count)
        XCTAssertTrue(visitor.hasPrefix(PhotoGalleryRules.spoken(index: 9, total: 19, isReview: false)))
    }

    /// 사진이 줄면(리뷰를 지움) 보던 자리를 안으로 당긴다.
    func testIndexIsClampedWhenPhotosShrink() {
        XCTAssertEqual(PhotoGalleryRules.clamped(18, count: 9), 8)
        XCTAssertEqual(PhotoGalleryRules.clamped(3, count: 9), 3)
        XCTAssertEqual(PhotoGalleryRules.clamped(3, count: 0), 0)
        XCTAssertEqual(PhotoGalleryRules.clamped(-1, count: 5), 0)
    }

    // MARK: 못 받았을 때

    /// 서명된 주소가 죽었을 수 있다 — 사진첩을 다시 받아 한 번 더 해 본다. 한 사진에 한 번만.
    func testAFailedSignedPhotoRenewsTheGalleryOnce() {
        XCTAssertTrue(PhotoGalleryRules.renewsAfterFailure(signed: true, alreadyRenewed: false))
        XCTAssertFalse(PhotoGalleryRules.renewsAfterFailure(signed: true, alreadyRenewed: true), "되풀이하지 않는다")
        // 우리 사진의 주소는 다시 받아도 같다 — 사람이 누르기를 기다린다.
        XCTAssertFalse(PhotoGalleryRules.renewsAfterFailure(signed: false, alreadyRenewed: false))
    }

    /// 여러 칸이 한꺼번에 실패해도 사진첩은 30초에 한 번만 다시 받는다. 사람이 누른 것은 언제나.
    func testGalleryRenewalIsThrottledUnlessForced() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertTrue(PhotoGalleryRules.shouldRenew(forced: false, last: nil, now: now))
        XCTAssertFalse(PhotoGalleryRules.shouldRenew(forced: false, last: now.addingTimeInterval(-5), now: now))
        XCTAssertTrue(PhotoGalleryRules.shouldRenew(forced: false, last: now.addingTimeInterval(-30), now: now))
        XCTAssertTrue(PhotoGalleryRules.shouldRenew(forced: true, last: now.addingTimeInterval(-1), now: now))
    }

    // MARK: 머리줄

    /// 한국어는 수만 붙는다. 영어의 단·복수는 번역 표의 `…|하나` 열쇠가 맡는다(`리뷰 %d|하나` 와 같은 관례).
    func testTitlesCarryTheCount() {
        XCTAssertTrue(PhotoGalleryRules.allTitle(1).contains("1"))
        XCTAssertTrue(PhotoGalleryRules.allTitle(19).contains("19"))
        XCTAssertTrue(PhotoGalleryRules.visitorTitle(1).contains("1"))
        XCTAssertTrue(PhotoGalleryRules.visitorTitle(10).contains("10"))
        XCTAssertFalse(PhotoGalleryRules.visitorTitle(nil).contains("%"))
    }

    /// 번역 표에 단수 열쇠가 있다 — 빠지면 영어 화면이 「1 photos」 가 된다.
    func testEnglishHasSingularForms() throws {
        let path = Bundle(for: PhotoGallery.self).path(forResource: "en", ofType: "lproj")
        let bundle = try XCTUnwrap(path.flatMap(Bundle.init(path:)))
        for (key, plural) in [("사진 %d|전체 하나", "사진 %d|전체"), ("방문자 사진 %d|하나", "방문자 사진 %d")] {
            let one = String(format: bundle.localizedString(forKey: key, value: key, table: nil), 1)
            let many = String(format: bundle.localizedString(forKey: plural, value: plural, table: nil), 2)
            XCTAssertFalse(one.contains("|"), "\(key) 가 번역 표에 없다")
            XCTAssertFalse(one.lowercased().contains("photos"), one)
            XCTAssertTrue(many.lowercased().contains("photos"), many)
        }
    }
}
