import SceneApiClient
@testable import SceneTrip
import XCTest

/// 신고·차단·차단 목록·금칙어 안내·문의 연락처 (MZ2AZ-461). 서버 없이 규칙과 흐름만 본다.
@MainActor
final class ModerationTests: XCTestCase {
    // MARK: 규칙

    func testReasonsFollowContractOrderAndValues() {
        XCTAssertEqual(ModerationRules.reasons, ReportReason.allCases)
        XCTAssertEqual(
            ModerationRules.reasons.map(\.rawValue),
            ["spam", "abuse", "sexual", "personal_info", "inappropriate_nickname", "other"]
        )
        let labels = ModerationRules.reasons.map(ModerationRules.label)
        XCTAssertEqual(Set(labels).count, labels.count)
        XCTAssertFalse(labels.contains(""))
    }

    func testTargetMapsToContractType() {
        XCTAssertEqual(ModerationTarget.review(7).type, .review)
        XCTAssertEqual(ModerationTarget.post(9).type, .post)
        XCTAssertEqual(ModerationTarget.post(9).targetId, 9)
        XCTAssertNotEqual(ModerationTarget.review(1).id, ModerationTarget.post(1).id)
    }

    func testDetailIsTrimmedOmittedWhenBlankAndCapped() {
        XCTAssertNil(ModerationRules.detail("  \n "))
        XCTAssertEqual(ModerationRules.detail("  광고 글  "), "광고 글")
        XCTAssertEqual(ModerationRules.detail(String(repeating: "가", count: 600))?.count, 500)
    }

    func testMenuIsHiddenOnOwnContentAndBlockNeedsAnAuthor() {
        XCTAssertFalse(ModerationRules.showsMenu(isMine: true))
        XCTAssertFalse(ModerationRules.showsMenu(isMine: false, onServer: false))
        XCTAssertTrue(ModerationRules.showsMenu(isMine: false))
        XCTAssertFalse(ModerationRules.canBlock(isMine: true, hasAuthor: true))
        XCTAssertFalse(ModerationRules.canBlock(isMine: false, hasAuthor: false))
        XCTAssertTrue(ModerationRules.canBlock(isMine: false, hasAuthor: true))
    }

    func testReportOutcomesComeFromCodes() {
        XCTAssertEqual(ModerationRules.reportOutcome(nil), .received)
        XCTAssertEqual(ModerationRules.reportOutcome(moderationError(400, "CANNOT_REPORT_OWN")), .own)
        XCTAssertEqual(ModerationRules.reportOutcome(moderationError(404, "REPORT_TARGET_NOT_FOUND")), .gone)
        XCTAssertEqual(ModerationRules.reportOutcome(moderationError(429, "RATE_LIMITED")), .rateLimited)
        XCTAssertEqual(ModerationRules.reportOutcome(moderationError(500, "INTERNAL_ERROR")), .failed)
        XCTAssertEqual(ModerationRules.reportOutcome(URLError(.notConnectedToInternet)), .failed)
        XCTAssertTrue(ModerationRules.ReportOutcome.gone.finishes)
        XCTAssertFalse(ModerationRules.ReportOutcome.failed.finishes)
        XCTAssertEqual(ModerationRules.message(.received), tr("신고가 접수됐어요. 24시간 안에 확인할게요."))
    }

    func testBlockOutcomesComeFromCodes() {
        XCTAssertEqual(ModerationRules.blockOutcome(nil), .blocked)
        XCTAssertEqual(ModerationRules.blockOutcome(moderationError(401, "SIGN_IN_REQUIRED")), .signInRequired)
        XCTAssertEqual(ModerationRules.blockOutcome(moderationError(400, "CANNOT_BLOCK_SELF")), .own)
        XCTAssertEqual(ModerationRules.blockOutcome(moderationError(400, "BLOCK_TARGET_HAS_NO_AUTHOR")), .noAuthor)
        XCTAssertEqual(ModerationRules.blockOutcome(moderationError(404, "BLOCK_TARGET_NOT_FOUND")), .gone)
        XCTAssertEqual(ModerationRules.blockOutcome(URLError(.timedOut)), .failed)
        XCTAssertNil(ModerationRules.message(ModerationRules.BlockOutcome.blocked))
        XCTAssertNotNil(ModerationRules.message(ModerationRules.BlockOutcome.failed))
    }

    // MARK: 금칙어

    func testTextNotAllowedIsRecognisedOnEverySavePath() {
        let error = moderationError(400, "TEXT_NOT_ALLOWED")
        let wording = ModerationRules.textNotAllowedMessage
        XCTAssertTrue(ModerationRules.isTextNotAllowed(error))
        XCTAssertFalse(ModerationRules.isTextNotAllowed(moderationError(400, "INVALID_PARAMETER")))
        // 코스 저장(편집 화면의 알림).
        XCTAssertEqual(ApiFailure(error).message, wording)
        XCTAssertNotEqual(ApiFailure(moderationError(400, "INVALID_PARAMETER")).message, wording)
        // 여행후기 저장.
        XCTAssertEqual(CommunityRules.failureText(error), wording)
    }

    // MARK: 신고·차단 흐름

    func testGuestBlockAsksForSignInInsteadOfConfirming() async {
        let client = FakeModerationClient()
        var prompted = 0
        let controller = ModerationController(client: client, signedIn: { false }, promptSignIn: { prompted += 1 })
        controller.askBlock(.review(3))
        XCTAssertNil(controller.confirmingBlock)
        XCTAssertEqual(prompted, 1)
        let blocked = await controller.block(.review(3))
        XCTAssertFalse(blocked)
        XCTAssertTrue(client.blocked.isEmpty)
    }

    func testBlockSendsTheTargetAndReportsSuccess() async {
        let client = FakeModerationClient()
        let controller = ModerationController(client: client, signedIn: { true }, promptSignIn: {})
        controller.askBlock(.post(5))
        XCTAssertEqual(controller.confirmingBlock, .post(5))
        let blocked = await controller.block(.post(5))
        XCTAssertTrue(blocked)
        XCTAssertNil(controller.confirmingBlock)
        XCTAssertNil(controller.notice)
        XCTAssertEqual(client.blocked, [.post(5)])
    }

    func testFailedBlockExplainsWhy() async {
        let client = FakeModerationClient()
        client.blockError = moderationError(400, "BLOCK_TARGET_HAS_NO_AUTHOR")
        let controller = ModerationController(client: client, signedIn: { true }, promptSignIn: {})
        let blocked = await controller.block(.review(8))
        XCTAssertFalse(blocked)
        XCTAssertEqual(controller.notice, tr("탈퇴한 사용자는 차단할 수 없어요"))
    }

    func testReportWorksForGuestsAndSendsTrimmedDetail() async {
        let client = FakeModerationClient()
        let controller = ModerationController(
            client: client, signedIn: { false }, promptSignIn: { XCTFail("신고는 로그인이 필요 없다") }
        )
        let outcome = await controller.report(.review(4), reason: .other, detail: "  이상한 링크  ")
        XCTAssertEqual(outcome, .received)
        XCTAssertEqual(client.reports.first?.target, .review(4))
        XCTAssertEqual(client.reports.first?.reason, .other)
        XCTAssertEqual(client.reports.first?.detail, "이상한 링크")

        client.reportError = moderationError(400, "CANNOT_REPORT_OWN")
        let own = await controller.report(.review(4), reason: .spam, detail: "")
        XCTAssertEqual(own, .own)
    }

    // MARK: 차단 목록

    func testBlockedListLoadsAndUnblocks() async {
        let client = FakeModerationClient()
        client.list = [sampleBlocked(1, "가"), sampleBlocked(2, "나")]
        let store = BlockedUsersStore(client: client)
        await store.reload()
        XCTAssertEqual(store.phase, .loaded)
        XCTAssertEqual(store.items.map(\.id), [1, 2])
        let done = await store.unblock(store.items[0])
        XCTAssertTrue(done)
        XCTAssertEqual(store.items.map(\.id), [2])
        XCTAssertEqual(client.unblocked, [1])
    }

    func testAlreadyRemovedBlockLeavesTheListButOtherFailuresKeepIt() async {
        let client = FakeModerationClient()
        client.list = [sampleBlocked(1, "가"), sampleBlocked(2, "나")]
        let store = BlockedUsersStore(client: client)
        await store.reload()
        client.unblockError = moderationError(404, "BLOCK_NOT_FOUND")
        _ = await store.unblock(store.items[0])
        XCTAssertEqual(store.items.map(\.id), [2])

        client.unblockError = URLError(.notConnectedToInternet)
        let done = await store.unblock(store.items[0])
        XCTAssertFalse(done)
        XCTAssertEqual(store.items.map(\.id), [2])
        XCTAssertNotNil(store.message)
    }

    func testBlockedListFailureShowsRetryState() async {
        let client = FakeModerationClient()
        client.listError = URLError(.timedOut)
        let store = BlockedUsersStore(client: client)
        await store.reload()
        XCTAssertEqual(store.phase, .failed)
    }

    // MARK: 차단한 뒤 바로 빼기

    func testBlockedReviewLeavesTheListAtOnce() async {
        let listing = ReviewListStore(subject: .place(1), load: { _, _ in
            ReviewList(items: [sampleModeratedReview(1), sampleModeratedReview(2)], total: 2,
                       summary: RatingSummary(average: 4, count: 2, distribution: [0, 0, 0, 2, 0]))
        })
        await listing.reload(sort: .recent)
        listing.drop(reviewId: 1)
        XCTAssertEqual(listing.reviews.map(\.id), [2])
        XCTAssertEqual(listing.total, 1)
        listing.drop(reviewId: 99)
        XCTAssertEqual(listing.total, 1)
    }

    func testBlockedPostLeavesTheFeedAtOnce() async {
        let store = CommunityStore(client: FeedFake(), accountEpoch: { 0 })
        await store.refresh()
        XCTAssertEqual(store.posts.compactMap(\.serverId), [42, 43])
        store.drop(postId: 42)
        XCTAssertEqual(store.posts.compactMap(\.serverId), [43])
        XCTAssertEqual(store.total, 1)
    }

    // MARK: 늦게 온 응답·앱 전체 신호 (검증 지적 2·3·4)

    /// 차단 전에 떠난 새로고침이 늦게 와도 차단한 글을 되살리지 않고, 차단 뒤 첫 쪽을 새로 받는다.
    func testLateFeedResponseDoesNotRestoreABlockedPost() async {
        let feed = DelayedFeed()
        let store = CommunityStore(client: feed, accountEpoch: { 0 })
        let entered = expectation(description: "첫 받기가 나감")
        feed.onFirst = { entered.fulfill() }
        let first = Task { @MainActor in await store.refresh() }
        await fulfillment(of: [entered], timeout: 1)
        await store.blocked(postId: 42)
        XCTAssertEqual(store.posts.compactMap(\.serverId), [43])
        XCTAssertEqual(feed.calls, 2)
        // 차단 전에 나간 응답(42 를 담은)이 이제 온다.
        feed.release()
        await first.value
        XCTAssertEqual(store.posts.compactMap(\.serverId), [43])
        XCTAssertFalse(store.loading)
    }

    /// 목록을 받는 사이 푼 줄은 늦게 온 목록 응답에 들어 있어도 되살아나지 않는다.
    func testLateBlockListDoesNotRestoreAnUnblockedRow() async {
        let client = FakeModerationClient()
        client.list = [sampleBlocked(1, "가"), sampleBlocked(2, "나")]
        let store = BlockedUsersStore(client: client, events: ModerationEvents())
        await store.reload()
        let entered = expectation(description: "목록 받기가 나감")
        var resume: CheckedContinuation<Void, Never>?
        client.blocksGate = {
            await withCheckedContinuation { resume = $0; entered.fulfill() }
        }
        let late = Task { @MainActor in await store.reload() }
        await fulfillment(of: [entered], timeout: 1)
        _ = await store.unblock(store.items[0])
        XCTAssertEqual(store.items.map(\.id), [2])
        resume?.resume()
        await late.value
        XCTAssertEqual(store.items.map(\.id), [2])
    }

    func testBlockAndUnblockAnnounceTheChange() async {
        let events = ModerationEvents()
        let client = FakeModerationClient()
        client.list = [sampleBlocked(1, "가")]
        let controller = ModerationController(client: client, signedIn: { true }, promptSignIn: {}, events: events)
        await controller.block(.review(3))
        XCTAssertEqual(events.version, 1)
        client.blockError = URLError(.timedOut)
        await controller.block(.review(4))
        XCTAssertEqual(events.version, 1, "실패한 차단은 알리지 않는다")
        let store = BlockedUsersStore(client: client, events: events)
        await store.reload()
        _ = await store.unblock(store.items[0])
        XCTAssertEqual(events.version, 2)
    }

    /// 차단 신호를 받으면 방문자 사진을 먼저 비운다 — 다시 받기가 실패해도 그 사람의 사진이 남지 않게.
    func testForgettingReviewPhotosKeepsOnlyOfficialOnes() {
        let gallery = PhotoGallery(subject: .place(1))
        var book = PhotoGalleryRules.Book()
        book.append([
            Photo(url: "https://img.example/official.jpg", source: .official),
            Photo(url: "https://img.example/visitor.jpg?sig=1", source: .review, reviewId: 7),
        ], total: 30, offset: 0)
        gallery.show(book, subject: .place(1), generation: gallery.generation)
        XCTAssertEqual(gallery.book.reviewPhotos.count, 1)
        gallery.forgetReviewPhotos()
        XCTAssertTrue(gallery.book.reviewPhotos.isEmpty)
        XCTAssertEqual(gallery.book.photos.count, 1)
        XCTAssertFalse(gallery.book.hasMore)
    }

    // MARK: 문의 연락처·번역

    func testSupportAddressIsOneConstantOnOurDomain() {
        XCTAssertEqual(LegalLinks.supportEmail, "support@scenetrip.io")
        XCTAssertEqual(LegalLinks.supportMail.scheme, "mailto")
        XCTAssertEqual(LegalLinks.supportMail.absoluteString, "mailto:" + LegalLinks.supportEmail)
    }

    /// 새 문구의 번역이 빠지면 영어 화면에 한국어가 섞인다.
    func testNewWordingIsTranslated() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: "en"
        ))
        let table = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: String]
        )
        let keys = [
            "신고", "이 사용자 차단", "신고·차단 메뉴", "신고 이유", "스팸·광고", "욕설·혐오·괴롭힘", "성적인 내용",
            "개인정보 노출", "닉네임이 부적절함", "기타", "설명", "자세한 내용 (선택)", "보내기",
            "운영자가 확인하고 규칙을 어긴 글은 내려요.",
            "신고가 접수됐어요. 24시간 안에 확인할게요.", "내 글은 신고할 수 없어요", "이미 내려갔거나 없는 글이에요",
            "신고를 보내지 못했어요. 잠시 뒤 다시 해 주세요", "이 사용자를 차단할까요?", "차단",
            "이 사람의 리뷰·후기가 더 이상 보이지 않아요. 마이페이지의 「차단한 사용자」에서 풀 수 있어요.",
            "내 글의 작성자는 차단할 수 없어요", "탈퇴한 사용자는 차단할 수 없어요", "차단하지 못했어요. 잠시 뒤 다시 해 주세요",
            "차단한 사용자", "차단 목록을 불러오지 못했어요", "차단한 사용자가 없어요",
            "리뷰나 여행후기의 「⋯」 에서 작성자를 차단할 수 있어요",
            "차단한 사람의 리뷰와 여행후기는 보이지 않아요. 차단한 사람에게는 알리지 않아요.",
            "%@ 차단", "차단 풀기", "%@ 차단 풀기", "차단을 풀지 못했어요. 잠시 뒤 다시 해 주세요", "문의하기", "메일 쓰기",
            "쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.",
        ]
        for key in keys {
            let english = try XCTUnwrap(table[key], key)
            XCTAssertNotEqual(english, key)
            XCTAssertNil(english.range(of: "\\p{Hangul}", options: .regularExpression), english)
        }
    }
}

extension ModerationTests {
    func testLatePhotoResponseDoesNotRestoreForgottenReviews() async {
        let gallery = PhotoGallery(subject: .place(1))
        let generation = gallery.generation
        let entered = expectation(description: "사진 요청이 나감")
        var resume: CheckedContinuation<Void, Never>?
        let old = Task { @MainActor in
            await withCheckedContinuation { resume = $0; entered.fulfill() }
            var book = PhotoGalleryRules.Book()
            book.append([Photo(url: "https://img.example/old.jpg", source: .review, reviewId: 7)],
                        total: 1, offset: 0)
            gallery.show(book, subject: .place(1), generation: generation)
        }
        await fulfillment(of: [entered], timeout: 1)
        gallery.forgetReviewPhotos()
        gallery.show(PhotoGalleryRules.Book(), subject: .place(1), generation: gallery.generation)
        resume?.resume()
        await old.value
        XCTAssertTrue(gallery.book.reviewPhotos.isEmpty)
        XCTAssertEqual(gallery.book.total, 0)
    }

    func testInvalidatingFeedWithoutPostIdRefetchesWhileLoading() async {
        let feed = DelayedFeed()
        let store = CommunityStore(client: feed, accountEpoch: { 0 })
        let entered = expectation(description: "피드 요청이 나감")
        feed.onFirst = { entered.fulfill() }
        let old = Task { @MainActor in await store.refresh(more: true) }
        await fulfillment(of: [entered], timeout: 1)
        await store.invalidateAndRefresh()
        XCTAssertEqual(feed.calls, 2)
        XCTAssertEqual(feed.offsets.last, 0)
        feed.release()
        await old.value
        XCTAssertEqual(store.posts.compactMap(\.serverId), [43])
        XCTAssertFalse(store.loading)
    }

    func testFeedInvalidationPreservesInFlightMyPosts() async {
        let feed = DelayedFeed()
        let store = CommunityStore(client: feed, accountEpoch: { 0 })
        let entered = expectation(description: "내 글 요청이 나감")
        feed.onFirst = { entered.fulfill() }
        let mine = Task { @MainActor in await store.refresh(mine: true) }
        await fulfillment(of: [entered], timeout: 1)
        await store.blocked(postId: 42)
        XCTAssertTrue(store.mineLoading)
        await store.refresh(mine: true)
        XCTAssertEqual(feed.calls, 2)
        feed.release()
        await mine.value
        XCTAssertEqual(store.myPosts.compactMap(\.serverId), [42, 43])
        XCTAssertEqual(store.mineTotal, 2)
        XCTAssertFalse(store.mineLoading)
    }

    func testReportSignInHandoffBeforeAndDuringAppearanceAndAfterCancellation() {
        let presence = ReportSheetPresence()
        var dismissed = 0
        presence.appeared(signInRequested: true) { dismissed += 1 }
        XCTAssertEqual(dismissed, 1)
        XCTAssertTrue(presence.open)
        presence.disappeared()
        XCTAssertFalse(presence.open)
        presence.appeared(signInRequested: false) { dismissed += 1 }
        XCTAssertEqual(dismissed, 1)
        presence.signInChanged(true) { dismissed += 1 }
        XCTAssertEqual(dismissed, 2)
        presence.disappeared()
        presence.disappeared()
        XCTAssertFalse(presence.open)
        presence.signInChanged(false) { dismissed += 1 }
        presence.appeared(signInRequested: true) { dismissed += 1 }
        XCTAssertEqual(dismissed, 3)
        presence.disappeared()
        XCTAssertFalse(presence.open)
    }
}
