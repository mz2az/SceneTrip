import SceneApiClient
@testable import SceneTrip
import XCTest

final class PhotoRenewalTests: XCTestCase {
    private struct Row { let id: Int64; let url: String }

    func testRefetchKeepsSecondPageAndItsNewPhotoURL() async throws {
        var offsets: [Int] = []
        let page = try await PhotoPages.refetch(wanted: 40, id: { $0.id }) { offset in
            offsets.append(offset)
            let rows = (offset ..< offset + 20).map { Row(id: Int64($0 + 1), url: "https://img.example/\($0 + 1)?sig=new") }
            return PhotoPages.Page(items: rows, total: 40)
        }
        XCTAssertEqual(offsets, [0, 20])
        XCTAssertEqual(page.items.count, 40)
        XCTAssertEqual(page.items.dropFirst(20).first?.id, 21)
        XCTAssertTrue(page.items.dropFirst(20).first?.url.hasSuffix("sig=new") ?? false)
    }

    func testPartialRefetchFailureDoesNotReturnATruncatedList() async {
        do {
            _ = try await PhotoPages.refetch(wanted: 40, id: { $0.id }) { offset in
                if offset > 0 {
                    throw URLError(.notConnectedToInternet)
                }
                return PhotoPages.Page(items: (1 ... 20).map { Row(id: Int64($0), url: "new") }, total: 40)
            }
            XCTFail("둘째 쪽 실패를 성공한 첫 쪽으로 처리하면 안 됩니다")
        } catch {}
    }

    @MainActor func testAutomaticRenewalIsLimitedButManualRetryIsAllowed() async {
        let renewal = PhotoRenewal(now: { Date(timeIntervalSince1970: 100) })
        var calls = 0
        let first = await renewal.run(forced: false) { calls += 1 }
        let automatic = await renewal.run(forced: false) { calls += 1 }
        let manual = await renewal.run(forced: true) { calls += 1 }
        XCTAssertTrue(first)
        XCTAssertFalse(automatic)
        XCTAssertTrue(manual)
        XCTAssertEqual(calls, 2)
    }

    @MainActor func testSimultaneousFailuresShareOneRenewal() async {
        let renewal = PhotoRenewal()
        let entered = expectation(description: "첫 주소 갱신 시작")
        let joining = expectation(description: "둘째 사진의 갱신 합류")
        var finish: CheckedContinuation<Void, Never>?
        var calls = 0
        let first = Task { @MainActor in
            await renewal.run(forced: false) {
                calls += 1
                await withCheckedContinuation { finish = $0; entered.fulfill() }
            }
        }
        await fulfillment(of: [entered], timeout: 1)
        let second = Task { @MainActor in
            joining.fulfill()
            return await renewal.run(forced: true) { calls += 1 }
        }
        await fulfillment(of: [joining], timeout: 1)
        finish?.resume()
        let firstResult = await first.value
        let secondResult = await second.value
        XCTAssertTrue(firstResult)
        XCTAssertTrue(secondResult)
        XCTAssertEqual(calls, 1)
    }

    @MainActor func testCommunityRefreshReceivesNewURLsAndMatchingBodyBlocks() async {
        let old = post(url: "https://img.example/a?sig=old")
        var fresh = post(url: "https://img.example/a?sig=new")
        fresh.bodyBlocks = [TripPostBlock(kind: .photo, photoIndex: 0, caption: "사진 설명", credit: "출처", aiGenerated: true)]
        var calls = 0
        let refresh = CommunityPhotoRefresh(post: old, accountEpoch: { 1 }, detail: { _ in calls += 1; return fresh })
        let renewed = await refresh.renew(forced: true)
        XCTAssertTrue(renewed)
        XCTAssertEqual(calls, 1)
        XCTAssertEqual(refresh.post?.remotePhotos, ["https://img.example/a?sig=new"])
        XCTAssertEqual(refresh.post?.bodyBlocks?.first?.credit, "출처")
        XCTAssertEqual(refresh.post?.bodyBlocks?.first?.aiGenerated, true)
    }

    @MainActor func testLocalLegacyPhotosDoNotAskServerForNewURLs() async {
        var local = post(url: "unused")
        local.serverId = nil
        local.remotePhotos = nil
        local.photos = ["original-photo.jpg"]
        let refresh = CommunityPhotoRefresh(post: local, detail: { _ in
            XCTFail("기기 옛 사진은 서버에서 다시 받지 않습니다")
            return local
        })
        let renewed = await refresh.renew(forced: true)
        XCTAssertFalse(renewed)
    }

    @MainActor func testLateCommunityPhotoResponseCannotCrossAccounts() async {
        let old = post(url: "https://img.example/a?sig=old")
        let entered = expectation(description: "주소 재발급 시작")
        var finish: CheckedContinuation<CommunityPost, Never>?
        var epoch = 1
        let refresh = CommunityPhotoRefresh(post: old, accountEpoch: { epoch }, detail: { _ in
            await withCheckedContinuation { finish = $0; entered.fulfill() }
        })
        let request = Task { @MainActor in await refresh.renew(forced: true) }
        await fulfillment(of: [entered], timeout: 1)
        epoch += 1
        finish?.resume(returning: post(url: "https://img.example/a?sig=new"))
        let renewed = await request.value
        XCTAssertFalse(renewed)
        XCTAssertNil(refresh.post)
    }

    @MainActor func testCancelledRenewalCannotReplaceNewlyOpenedPost() async {
        let old = post(url: "https://img.example/a?sig=old")
        let entered = expectation(description: "이전 글의 주소 갱신")
        var finish: CheckedContinuation<CommunityPost, Never>?
        let refresh = CommunityPhotoRefresh(post: old, accountEpoch: { 1 }, detail: { _ in
            await withCheckedContinuation { finish = $0; entered.fulfill() }
        })
        let request = Task { @MainActor in await refresh.renew(forced: true) }
        await fulfillment(of: [entered], timeout: 1)
        var next = post(url: "https://img.example/b?sig=current", id: UUID())
        next.serverId = 429
        refresh.show(next)
        finish?.resume(returning: post(url: "https://img.example/a?sig=new"))
        let renewed = await request.value
        XCTAssertFalse(renewed)
        XCTAssertEqual(refresh.post?.serverId, 429)
        XCTAssertEqual(refresh.post?.remotePhotos, next.remotePhotos)
    }

    @MainActor func testHeldPlaceARefreshCannotDiscardPlaceBPhotosOrEndItsLoading() async {
        let gallery = PhotoGallery()
        let bookA = PhotoGalleryRules.book(photos: nil, count: nil, legacy: ["https://img.example/a.jpg"])
        let bookB = PhotoGalleryRules.book(photos: nil, count: nil, legacy: ["https://img.example/b.jpg"])
        await gallery.loadDetail(subject: .place(1), fetch: { bookA }, apply: { $0 })
        let startedA = expectation(description: "A 상세 갱신 보류")
        let startedB = expectation(description: "B 선택 후 상세 조회 보류")
        var finishA: CheckedContinuation<PhotoGalleryRules.Book, Never>?
        var finishB: CheckedContinuation<PhotoGalleryRules.Book, Never>?
        let refreshA = Task { @MainActor in
            await gallery.loadDetail(subject: .place(1), fetch: {
                await withCheckedContinuation { finishA = $0; startedA.fulfill() }
            }, apply: { $0 })
        }
        await fulfillment(of: [startedA], timeout: 1)
        let generationA = gallery.generation
        let fetchB = Task { @MainActor in
            await gallery.loadDetail(subject: .place(2), fetch: {
                await withCheckedContinuation { finishB = $0; startedB.fulfill() }
            }, apply: { $0 })
        }
        await fulfillment(of: [startedB], timeout: 1)
        XCTAssertEqual(gallery.subject, .place(2))
        XCTAssertGreaterThan(gallery.generation, generationA)
        XCTAssertTrue(gallery.book.photos.isEmpty)
        XCTAssertTrue(gallery.loading)
        let generationB = gallery.generation
        finishA?.resume(returning: bookA)
        await refreshA.value
        XCTAssertEqual(gallery.generation, generationB)
        XCTAssertTrue(gallery.loading)
        finishB?.resume(returning: bookB)
        await fetchB.value
        XCTAssertEqual(gallery.book.photos, bookB.photos)
        XCTAssertEqual(gallery.subject, .place(2))
        XCTAssertFalse(gallery.loading)
    }

    @MainActor func testDiscardedCurrentDetailResponseClearsLoading() async {
        let gallery = PhotoGallery()
        await gallery.loadDetail(subject: .place(1), fetch: {
            gallery.forgetReviewPhotos()
            return PhotoGalleryRules.Book()
        }, apply: { _ in
            XCTFail("무효화한 상세 응답은 적용하지 않습니다")
            return nil
        })
        XCTAssertFalse(gallery.loading)
    }

    @MainActor func testFailedDetailRequestClearsLoading() async {
        let gallery = PhotoGallery()
        await gallery.loadDetail(subject: .place(1), fetch: { () throws -> PhotoGalleryRules.Book in
            throw URLError(.notConnectedToInternet)
        }, apply: { $0 })
        XCTAssertFalse(gallery.loading)
    }

    @MainActor func testCancelledDetailRequestClearsLoadingWithoutApplyingPhotos() async {
        let gallery = PhotoGallery()
        let started = expectation(description: "취소할 상세 조회 보류")
        var finish: CheckedContinuation<PhotoGalleryRules.Book, Never>?
        let request = Task { @MainActor in
            await gallery.loadDetail(subject: .place(1), fetch: {
                await withCheckedContinuation { finish = $0; started.fulfill() }
            }, apply: { _ in
                XCTFail("취소한 상세 응답은 적용하지 않습니다")
                return nil
            })
        }
        await fulfillment(of: [started], timeout: 1)
        request.cancel()
        finish?.resume(returning: PhotoGalleryRules.Book())
        await request.value
        XCTAssertFalse(gallery.loading)
    }

    private func post(url: String, id: UUID = UUID(uuidString: "00000000-0000-0000-0000-000000000428")!) -> CommunityPost {
        CommunityPost(id: id, board: .review,
                      title: "시험 글", body: "본문", createdAt: Date(timeIntervalSince1970: 1),
                      serverId: 428, serverIsMine: false, remotePhotos: [url], detailLoaded: true)
    }
}
