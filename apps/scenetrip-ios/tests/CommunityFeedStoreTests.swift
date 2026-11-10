import SceneApiClient
@testable import SceneTrip
import XCTest

// 모두의 목록 — 게시판·검색어·커서 (MZ2AZ-547 K2·K3·K4·K5, 웹 `community/store.ts` refresh·setFilter).

func sampleFeedSummary(_ id: Int64, board: TripPostBoard? = nil, likeCount: Int? = nil, commentCount: Int? = nil,
                       liked: Bool? = nil) -> TripPostSummary
{
    TripPostSummary(id: id, title: "글\(id)", excerpt: "본문", createdAt: Date(timeIntervalSince1970: TimeInterval(id)),
                    photoCount: 0, isMine: false, board: board, likeCount: likeCount, commentCount: commentCount, liked: liked)
}

@MainActor
final class FeedFakeAPI: CommunityClient {
    struct FeedCall: Equatable {
        let limit: Int
        let offset: Int
        let board: TripPostBoard?
        let query: String?
        let cursor: String?
    }

    var feedCalls: [FeedCall] = []
    var listCalls = 0
    /// 요청마다 돌려줄 쪽.
    var respond: @MainActor (FeedCall) -> TripPostList = { _ in TripPostList(items: [], total: 0) }
    /// 켜 두면 받은 요청을 붙잡아 둔다 — 늦게 오는 응답을 흉내 낸다.
    var hold = false
    var held: [CheckedContinuation<Void, Never>] = []

    func list(mine _: Bool, limit _: Int, offset _: Int) async throws -> TripPostList {
        listCalls += 1
        return TripPostList(items: [], total: 0)
    }

    func feed(limit: Int, offset: Int, board: TripPostBoard?, query: String?, cursor: String?) async throws -> TripPostList {
        let call = FeedCall(limit: limit, offset: offset, board: board, query: query, cursor: cursor)
        feedCalls.append(call)
        if hold {
            await withCheckedContinuation { held.append($0) }
        }
        return respond(call)
    }

    func releaseAll() {
        let waiting = held
        held = []
        waiting.forEach { $0.resume() }
    }

    func detail(_ id: Int64) async throws -> TripPostDetail {
        TripPostDetail(id: id, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: false)
    }

    func create(_: TripPostCreate) async throws -> TripPostDetail {
        throw URLError(.unsupportedURL)
    }

    func delete(_: Int64) async throws {}

    func saveCourse(_: Int64) async throws -> CourseDetail {
        throw URLError(.unsupportedURL)
    }
}

@MainActor
final class CommunityFeedStoreTests: XCTestCase {
    private func page(_ ids: [Int64], total: Int, cursor: String? = nil, board: TripPostBoard? = nil) -> TripPostList {
        TripPostList(items: ids.map { sampleFeedSummary($0, board: board) }, total: total, nextCursor: cursor)
    }

    private func store(_ api: FeedFakeAPI) -> CommunityStore {
        CommunityStore(client: api, accountEpoch: { 0 })
    }

    func testRefreshAsksFeedWithDefaultBoardAndNoQuery() async {
        let api = FeedFakeAPI()
        api.respond = { _ in self.page([1, 2], total: 2) }
        let store = store(api)
        await store.refresh()
        XCTAssertEqual(store.board, .review)
        XCTAssertNil(store.query)
        XCTAssertEqual(api.feedCalls, [.init(limit: CommunityRules.pageSize, offset: 0, board: .review, query: nil, cursor: nil)])
        XCTAssertEqual(api.listCalls, 0, "모두의 목록은 feed 로 받는다")
        XCTAssertEqual(store.posts.compactMap(\.serverId), [1, 2])
    }

    func testSetFilterAsksFirstPageWithBoardAndQuery() async {
        let api = FeedFakeAPI()
        api.respond = { call in
            call.board == .free ? self.page([100], total: 1, board: .free) : self.page([1, 2, 3], total: 30, cursor: "old")
        }
        let store = store(api)
        await store.refresh()
        await store.setFilter(board: .free, query: "서울")
        XCTAssertEqual(store.board, .free)
        XCTAssertEqual(store.query, "서울")
        XCTAssertEqual(api.feedCalls.last, .init(limit: CommunityRules.pageSize, offset: 0, board: .free, query: "서울", cursor: nil))
        XCTAssertEqual(store.posts.compactMap(\.serverId), [100])
        XCTAssertEqual(store.total, 1)
        XCTAssertNil(store.nextCursor)
    }

    func testMoreUsesServerCursorWithZeroOffset() async {
        let api = FeedFakeAPI()
        api.respond = { call in
            call.cursor == "c1" ? self.page([4, 5, 6], total: 10, cursor: "c2") : self.page([1, 2, 3], total: 10, cursor: "c1")
        }
        let store = store(api)
        await store.refresh()
        XCTAssertEqual(store.nextCursor, "c1")
        await store.refresh(more: true)
        XCTAssertEqual(api.feedCalls.count, 2)
        XCTAssertEqual(api.feedCalls.last, .init(limit: CommunityRules.pageSize, offset: 0, board: .review, query: nil, cursor: "c1"))
        XCTAssertEqual(store.posts.compactMap(\.serverId), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(store.nextCursor, "c2")
    }

    func testMoreWithoutCursorSkipsLoadedRows() async {
        let api = FeedFakeAPI()
        api.respond = { call in call.offset == 0 ? self.page([1, 2, 3], total: 10) : self.page([4, 5], total: 10) }
        let store = store(api)
        await store.refresh()
        XCTAssertNil(store.nextCursor)
        await store.refresh(more: true)
        XCTAssertEqual(api.feedCalls.last, .init(limit: CommunityRules.pageSize, offset: 3, board: .review, query: nil, cursor: nil))
        XCTAssertEqual(store.posts.compactMap(\.serverId), [1, 2, 3, 4, 5])
    }

    func testMoreKeepsCurrentFilter() async {
        let api = FeedFakeAPI()
        api.respond = { call in call.cursor == nil ? self.page([1], total: 5, cursor: "c1") : self.page([2], total: 5) }
        let store = store(api)
        await store.setFilter(board: .free, query: "바다")
        await store.refresh(more: true)
        XCTAssertEqual(api.feedCalls.last, .init(limit: CommunityRules.pageSize, offset: 0, board: .free, query: "바다", cursor: "c1"))
    }

    func testSetFilterClearsOldPostsBeforeNewPageArrives() async {
        let api = FeedFakeAPI()
        api.respond = { call in call.board == .free ? self.page([100], total: 1) : self.page([1, 2, 3], total: 3) }
        let store = store(api)
        await store.refresh()
        XCTAssertEqual(store.posts.count, 3)
        api.hold = true
        let task = Task { await store.setFilter(board: .free, query: nil) }
        let asked = await communityWaitUntil { !api.held.isEmpty }
        XCTAssertTrue(asked, "새 조건으로 받지 않았다")
        XCTAssertTrue(store.posts.isEmpty, "옛 조건의 글이 남아 있다")
        api.hold = false
        api.releaseAll()
        await task.value
        XCTAssertEqual(store.posts.compactMap(\.serverId), [100])
    }

    func testLateResponseOfOldFilterDoesNotOverwriteNewList() async {
        let api = FeedFakeAPI()
        api.respond = { call in call.board == .free ? self.page([100, 101], total: 2) : self.page([1, 2, 3], total: 3, cursor: "late") }
        let store = store(api)
        api.hold = true
        let old = Task { await store.refresh() }
        let asked = await communityWaitUntil { api.held.count == 1 }
        XCTAssertTrue(asked, "첫 받기가 feed 로 가지 않았다")
        api.hold = false
        await store.setFilter(board: .free, query: nil)
        XCTAssertEqual(store.posts.compactMap(\.serverId), [100, 101])
        api.releaseAll()
        await old.value
        XCTAssertEqual(store.posts.compactMap(\.serverId), [100, 101], "늦게 온 옛 응답이 새 목록을 덮었다")
        XCTAssertEqual(store.total, 2)
        XCTAssertNil(store.nextCursor)
        XCTAssertFalse(store.loading)
    }

    func testUpdateSocialChangesMatchingPostOnly() async {
        let api = FeedFakeAPI()
        api.respond = { _ in TripPostList(items: [sampleFeedSummary(1, likeCount: 0, commentCount: 0, liked: false),
                                                  sampleFeedSummary(2, likeCount: 9, commentCount: 1, liked: false)], total: 2) }
        let store = store(api)
        await store.refresh()
        store.updateSocial(postId: 1, liked: true, likeCount: 5, commentCount: 2)
        let first = store.posts.first { $0.serverId == 1 }
        XCTAssertEqual(first?.liked, true)
        XCTAssertEqual(first?.likeCount, 5)
        XCTAssertEqual(first?.commentCount, 2)
        let second = store.posts.first { $0.serverId == 2 }
        XCTAssertEqual(second?.liked, false)
        XCTAssertEqual(second?.likeCount, 9)
        XCTAssertEqual(second?.commentCount, 1)
    }

    func testUpdateSocialAlsoChangesMyPosts() {
        let store = store(FeedFakeAPI())
        store.accept(TripPostDetail(id: 7, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: true,
                                    likeCount: 1, commentCount: 0, liked: false))
        store.updateSocial(postId: 7, liked: true, likeCount: 2, commentCount: 3)
        XCTAssertEqual(store.posts.first?.likeCount, 2)
        XCTAssertEqual(store.myPosts.first?.liked, true)
        XCTAssertEqual(store.myPosts.first?.likeCount, 2)
        XCTAssertEqual(store.myPosts.first?.commentCount, 3)
    }

    // MARK: 서버 값 → 글

    func testSummaryCarriesBoardAndSocialCounts() {
        let post = CommunityPost(summary: sampleFeedSummary(1, board: .free, likeCount: 3, commentCount: 4, liked: true))
        XCTAssertEqual(post.serverBoard, .free)
        XCTAssertEqual(post.likeCount, 3)
        XCTAssertEqual(post.commentCount, 4)
        XCTAssertEqual(post.liked, true)
    }

    func testSummaryWithoutSocialFieldsLeavesThemNil() {
        let post = CommunityPost(summary: sampleFeedSummary(1))
        XCTAssertNil(post.serverBoard)
        XCTAssertNil(post.likeCount)
        XCTAssertNil(post.commentCount)
        XCTAssertNil(post.liked)
    }

    func testDetailCarriesBoardAndSocialCounts() {
        let detail = TripPostDetail(id: 3, title: "t", body: "b", createdAt: Date(), photoUrls: [], isMine: false,
                                    board: .review, likeCount: 8, commentCount: 2, liked: false)
        let post = CommunityPost(detail: detail)
        XCTAssertEqual(post.serverBoard, .review)
        XCTAssertEqual(post.likeCount, 8)
        XCTAssertEqual(post.commentCount, 2)
        XCTAssertEqual(post.liked, false)
    }
}
