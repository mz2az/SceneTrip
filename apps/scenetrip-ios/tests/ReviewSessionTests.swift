import SceneApiClient
@testable import SceneTrip
import XCTest

final class ReviewSessionTests: XCTestCase {
    @MainActor func testOldDraftCannotSaveOrDeleteNewAccountsReview() async {
        let auth = AuthStore(refreshLikes: {})
        await auth.signInWithApple { _ in sampleSession("first") }
        defer { AuthTokens.clear() }
        var writes = 0
        var deletes = 0
        let editor = ReviewEditSession(subject: .place(1), auth: auth, client: ReviewEditClient(
            load: { nil }, save: { _, _, _ in writes += 1; return sampleReview(1) },
            remove: { deletes += 1 }
        ))
        auth.sessionLost()
        await auth.signInWithApple { _ in sampleSession("second") }
        do { _ = try await editor.save(rating: 4, body: "옛 초안", photoKeys: []); XCTFail("계정이 바뀐 초안 저장") }
        catch {}
        do { try await editor.remove(); XCTFail("새 계정의 리뷰 삭제") }
        catch {}
        XCTAssertEqual(writes, 0)
        XCTAssertEqual(deletes, 0)
    }

    @MainActor func testReviewLoadingAfterAccountChangeIsRejected() async {
        let auth = AuthStore(refreshLikes: {})
        await auth.signInWithApple { _ in sampleSession("first") }
        defer { AuthTokens.clear() }
        let entered = expectation(description: "내 리뷰 조회 시작")
        var finish: CheckedContinuation<Review?, Never>?
        let editor = ReviewEditSession(subject: .poi(1), auth: auth, client: ReviewEditClient(
            load: { await withCheckedContinuation { finish = $0; entered.fulfill() } },
            save: { _, _, _ in sampleReview(1) }, remove: {}
        ))
        let loading = Task { @MainActor in try await editor.load() }
        await fulfillment(of: [entered], timeout: 1)
        auth.sessionLost()
        await auth.signInWithApple { _ in sampleSession("second") }
        finish?.resume(returning: sampleReview(1))
        do { _ = try await loading.value; XCTFail("떠난 계정의 리뷰 응답") }
        catch {}
    }

    @MainActor func testReviewSaveAfterAccountChangeCannotCompleteOldEditor() async {
        let auth = AuthStore(refreshLikes: {})
        await auth.signInWithApple { _ in sampleSession("first") }
        defer { AuthTokens.clear() }
        let entered = expectation(description: "옛 계정 저장 시작")
        var finish: CheckedContinuation<Review, Never>?
        let editor = ReviewEditSession(subject: .place(1), auth: auth, client: ReviewEditClient(
            load: { nil },
            save: { _, _, _ in await withCheckedContinuation { finish = $0; entered.fulfill() } },
            remove: {}
        ))
        let saving = Task { @MainActor in try await editor.save(rating: 4, body: nil, photoKeys: []) }
        await fulfillment(of: [entered], timeout: 1)
        auth.sessionLost()
        await auth.signInWithApple { _ in sampleSession("second") }
        finish?.resume(returning: sampleReview(1))
        do { _ = try await saving.value; XCTFail("옛 저장 응답의 완료 표시") }
        catch {}
    }

    @MainActor func testNewAccountListWinsOverDelayedOldAccountList() async {
        let auth = AuthStore(refreshLikes: {})
        await auth.signInWithApple { _ in sampleSession("first") }
        defer { AuthTokens.clear() }
        let entered = expectation(description: "옛 계정 목록 조회 시작")
        var finish: CheckedContinuation<ReviewList, Never>?
        var calls = 0
        let listing = ReviewListStore(subject: .place(1), auth: auth, load: { _, _ in
            calls += 1
            if calls == 1 {
                return await withCheckedContinuation { finish = $0; entered.fulfill() }
            }
            return samplePage(2, mine: false)
        })
        let first = Task { @MainActor in await listing.reload(sort: .recent) }
        await fulfillment(of: [entered], timeout: 1)
        auth.sessionLost()
        await auth.signInWithApple { _ in sampleSession("second") }
        listing.resetForAccount()
        await listing.reload(sort: .recent)
        finish?.resume(returning: samplePage(1, mine: true))
        await first.value
        XCTAssertEqual(listing.reviews.map(\.id), [2])
        XCTAssertEqual(listing.reviews.first?.isMine, false)
    }

    @MainActor func testNewSortWinsOverDelayedOldSort() async {
        let entered = expectation(description: "최신순 지연")
        var finish: CheckedContinuation<ReviewList, Never>?
        let listing = ReviewListStore(subject: .place(1), load: { sort, _ in
            if sort == .recent {
                return await withCheckedContinuation { finish = $0; entered.fulfill() }
            }
            return samplePage(2, mine: false)
        })
        let first = Task { @MainActor in await listing.reload(sort: .recent) }
        await fulfillment(of: [entered], timeout: 1)
        listing.prepareSortChange()
        await listing.reload(sort: .ratingHigh)
        finish?.resume(returning: samplePage(1, mine: false))
        await first.value
        XCTAssertEqual(listing.reviews.map(\.id), [2])
    }

    @MainActor func testPhotoRenewalReplacesURLsBeyondFirstReviewPage() async {
        var signature = "old"
        var offsets: [Int] = []
        let listing = ReviewListStore(subject: .place(1), load: { _, offset in
            offsets.append(offset)
            return photoPage(offset: offset, signature: signature, total: 40)
        })
        await listing.reload(sort: .recent)
        await listing.loadMore(sort: .recent)
        signature = "new"
        await listing.renewPhotos(forced: true)
        XCTAssertEqual(offsets, [0, 20, 0, 20])
        XCTAssertEqual(listing.reviews.count, 40)
        XCTAssertTrue(listing.reviews[20].photos[0].url.hasSuffix("sig=new"))
    }

    @MainActor func testPhotoRenewalFinishesBeforeLoadingMoreReviews() async {
        let entered = expectation(description: "사진 주소 갱신 지연")
        var finish: CheckedContinuation<ReviewList, Never>?
        var renewing = false
        let listing = ReviewListStore(subject: .place(1), load: { _, offset in
            if renewing, offset == 0 {
                return await withCheckedContinuation { finish = $0; entered.fulfill() }
            }
            return photoPage(offset: offset, signature: "current", total: 60)
        })
        await listing.reload(sort: .recent)
        await listing.loadMore(sort: .recent)
        renewing = true
        let request = Task { @MainActor in await listing.renewPhotos(forced: true) }
        await fulfillment(of: [entered], timeout: 1)
        await listing.loadMore(sort: .recent)
        XCTAssertEqual(listing.reviews.count, 40)
        finish?.resume(returning: photoPage(offset: 0, signature: "new", total: 60))
        await request.value
        renewing = false
        await listing.loadMore(sort: .recent)
        XCTAssertEqual(listing.reviews.count, 60)
        XCTAssertEqual(listing.reviews.last?.id, 60)
        XCTAssertTrue(listing.reviews[0].photos[0].url.hasSuffix("sig=new"))
    }

    @MainActor func testOldNextPageCannotMixWithRenewedReviewOrder() async {
        let entered = expectation(description: "이전 다음 쪽 요청 지연")
        var finish: CheckedContinuation<ReviewList, Never>?
        var renewed = false
        let listing = ReviewListStore(subject: .place(1), load: { _, offset in
            if offset == 20, !renewed {
                return await withCheckedContinuation { finish = $0; entered.fulfill() }
            }
            if renewed {
                let ids = Array(([100] + Array(1 ... 40)).dropFirst(offset).prefix(20))
                return ReviewList(items: ids.map { sampleReview(Int64($0), mine: false) }, total: 41,
                                  summary: RatingSummary(average: 4, count: 41, distribution: [0, 0, 0, 41, 0]))
            }
            return photoPage(offset: offset, signature: "old", total: 40)
        })
        await listing.reload(sort: .recent)
        let request = Task { @MainActor in await listing.loadMore(sort: .recent) }
        await fulfillment(of: [entered], timeout: 1)
        renewed = true
        await listing.renewPhotos(forced: true)
        finish?.resume(returning: photoPage(offset: 20, signature: "old", total: 40))
        await request.value
        XCTAssertEqual(listing.reviews.map(\.id), [100] + Array(1 ... 19).map(Int64.init))
        await listing.loadMore(sort: .recent)
        XCTAssertEqual(listing.reviews.map(\.id), [100] + Array(1 ... 39).map(Int64.init))
    }
}

private func photoPage(offset: Int, signature: String, total: Int) -> ReviewList {
    let rows = (offset ..< min(offset + 20, total)).map { index in
        Review(id: Int64(index + 1), rating: 4, body: "시험 리뷰",
               photos: [ReviewPhoto(key: "reviews/\(index).jpg", url: "https://img.example/\(index)?sig=\(signature)")],
               author: nil, createdAt: Date(timeIntervalSince1970: 1),
               updatedAt: Date(timeIntervalSince1970: 1), isMine: false)
    }
    return ReviewList(items: rows, total: total,
                      summary: RatingSummary(average: 4, count: total, distribution: [0, 0, 0, total, 0]))
}

private func sampleSession(_ name: String) -> AuthSession {
    let id = name == "first" ? "00000000-0000-0000-0000-000000000148" : "00000000-0000-0000-0000-000000000363"
    return AuthSession(accessToken: "unit-\(name)-access", accessTokenExpiresIn: 1800,
                       refreshToken: "unit-\(name)-refresh", refreshTokenExpiresIn: 2_592_000,
                       user: Me(nickname: "시험회원", nicknameConfirmed: true, id: UUID(uuidString: id)!,
                                identities: [], registeredAt: Date(timeIntervalSince1970: 1)),
                       isNewUser: false, merged: false)
}

private func sampleReview(_ id: Int64, mine: Bool = true) -> Review {
    Review(id: id, rating: 4, body: "시험 리뷰", photos: [], author: nil,
           createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 1), isMine: mine)
}

private func samplePage(_ id: Int64, mine: Bool) -> ReviewList {
    ReviewList(items: [sampleReview(id, mine: mine)], total: 1,
               summary: RatingSummary(average: 4, count: 1, distribution: [0, 0, 0, 1, 0]))
}
