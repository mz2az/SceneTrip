import SceneApiClient
@testable import SceneTrip
import XCTest

// 글 하나의 좋아요·댓글 (MZ2AZ-547 K8·K9, 웹 `proto/protoPostSocial.tsx`).

/// 조건이 설 때까지 다른 작업에 차례를 넘긴다. 끝내 서지 않으면 false(무한히 기다리지 않는다).
@MainActor
func communityWaitUntil(_ condition: () -> Bool) async -> Bool {
    for _ in 0 ..< 2000 {
        if condition() {
            return true
        }
        await Task.yield()
    }
    return condition()
}

func sampleComment(_ id: Int64, _ body: String = "댓글", mine: Bool = false) -> TripPostComment {
    TripPostComment(id: id, body: body, author: TripPostAuthor(nickname: "사람\(id)"),
                    createdAt: Date(timeIntervalSince1970: TimeInterval(id)), isMine: mine)
}

@MainActor
final class FakeSocialClient: CommunitySocialClient {
    struct LikeCall: Equatable {
        let postId: Int64
        let on: Bool
    }

    struct AddCall: Equatable {
        let postId: Int64
        let body: String
    }

    struct DeleteCall: Equatable {
        let postId: Int64
        let commentId: Int64
    }

    var likeCalls: [LikeCall] = []
    var likeError: Error?
    /// 좋아요 응답을 붙잡아 둔다 — 보내는 중의 상태를 본다.
    var holdLike = false
    var likeContinuation: CheckedContinuation<Void, Never>?

    var commentsCalls: [Int64] = []
    var commentsResult = TripPostCommentList(items: [], total: 0)
    var commentsError: Error?

    var addCalls: [AddCall] = []
    var addError: Error?
    var nextCommentId: Int64 = 500

    var deleteCalls: [DeleteCall] = []
    var deleteError: Error?

    func like(_ postId: Int64, on: Bool) async throws {
        likeCalls.append(LikeCall(postId: postId, on: on))
        if holdLike {
            await withCheckedContinuation { likeContinuation = $0 }
        }
        if let likeError {
            throw likeError
        }
    }

    func releaseLike() {
        holdLike = false
        likeContinuation?.resume()
        likeContinuation = nil
    }

    func comments(_ postId: Int64) async throws -> TripPostCommentList {
        commentsCalls.append(postId)
        if let commentsError {
            throw commentsError
        }
        return commentsResult
    }

    func addComment(_ postId: Int64, body: String) async throws -> TripPostComment {
        addCalls.append(AddCall(postId: postId, body: body))
        if let addError {
            throw addError
        }
        defer { nextCommentId += 1 }
        return sampleComment(nextCommentId, body, mine: true)
    }

    func deleteComment(_ postId: Int64, commentId: Int64) async throws {
        deleteCalls.append(DeleteCall(postId: postId, commentId: commentId))
        if let deleteError {
            throw deleteError
        }
    }
}

@MainActor
final class PostSocialTests: XCTestCase {
    private struct Change: Equatable {
        let liked: Bool
        let likes: Int
        let comments: Int
    }

    private func model(liked: Bool? = false, likeCount: Int? = 3, commentCount: Int? = 0,
                       client: FakeSocialClient, signedIn: Bool = true) -> PostSocialModel
    {
        PostSocialModel(postId: 42, liked: liked, likeCount: likeCount, commentCount: commentCount,
                        client: client, signedIn: { signedIn })
    }

    // MARK: 처음 값·맞추기

    func testInitialValuesComeFromRow() {
        let client = FakeSocialClient()
        let social = model(liked: true, likeCount: 5, commentCount: 2, client: client)
        XCTAssertTrue(social.liked)
        XCTAssertEqual(social.likeCount, 5)
        XCTAssertEqual(social.commentTotal, 2)
        XCTAssertNil(social.comments)
    }

    func testSyncTakesServerValuesAndNilMeansZero() {
        let client = FakeSocialClient()
        let social = model(liked: false, likeCount: 0, client: client)
        social.sync(liked: true, likeCount: 7)
        XCTAssertTrue(social.liked)
        XCTAssertEqual(social.likeCount, 7)
        social.sync(liked: nil, likeCount: nil)
        XCTAssertFalse(social.liked)
        XCTAssertEqual(social.likeCount, 0)
    }

    // MARK: 좋아요

    func testLikeIsOptimisticAndSent() async {
        let client = FakeSocialClient()
        client.holdLike = true
        let social = model(liked: false, likeCount: 3, client: client)
        let task = Task { await social.toggleLike() }
        let sent = await communityWaitUntil { client.likeContinuation != nil }
        XCTAssertTrue(sent, "좋아요를 보내지 않았다")
        XCTAssertTrue(social.liked)
        XCTAssertEqual(social.likeCount, 4)
        XCTAssertTrue(social.likeBusy)
        client.releaseLike()
        let outcome = await task.value
        XCTAssertEqual(outcome, .done)
        XCTAssertTrue(social.liked)
        XCTAssertEqual(social.likeCount, 4)
        XCTAssertFalse(social.likeBusy)
        XCTAssertEqual(client.likeCalls, [.init(postId: 42, on: true)])
    }

    func testUnlikeSendsOff() async {
        let client = FakeSocialClient()
        let social = model(liked: true, likeCount: 3, client: client)
        let outcome = await social.toggleLike()
        XCTAssertEqual(outcome, .done)
        XCTAssertFalse(social.liked)
        XCTAssertEqual(social.likeCount, 2)
        XCTAssertEqual(client.likeCalls, [.init(postId: 42, on: false)])
    }

    func testLikeFailureRollsBack() async {
        let client = FakeSocialClient()
        client.likeError = URLError(.notConnectedToInternet)
        let social = model(liked: false, likeCount: 3, client: client)
        let outcome = await social.toggleLike()
        XCTAssertEqual(outcome, .failed)
        XCTAssertFalse(social.liked)
        XCTAssertEqual(social.likeCount, 3)
        XCTAssertFalse(social.likeBusy)
        XCTAssertEqual(client.likeCalls.count, 1)
    }

    func testLikeNeedsSignInAndDoesNotCall() async {
        let client = FakeSocialClient()
        let social = model(liked: false, likeCount: 3, client: client, signedIn: false)
        let outcome = await social.toggleLike()
        XCTAssertEqual(outcome, .needsSignIn)
        XCTAssertFalse(social.liked)
        XCTAssertEqual(social.likeCount, 3)
        XCTAssertTrue(client.likeCalls.isEmpty)
    }

    func testSecondTapWhileSendingIsIgnored() async {
        let client = FakeSocialClient()
        client.holdLike = true
        let social = model(liked: false, likeCount: 3, client: client)
        let first = Task { await social.toggleLike() }
        let sent = await communityWaitUntil { client.likeContinuation != nil }
        XCTAssertTrue(sent, "좋아요를 보내지 않았다")
        let second = await social.toggleLike()
        XCTAssertEqual(second, .ignored)
        XCTAssertTrue(social.liked)
        XCTAssertEqual(social.likeCount, 4)
        client.releaseLike()
        _ = await first.value
        XCTAssertEqual(client.likeCalls.count, 1)
    }

    // MARK: 댓글 받기

    func testLoadCommentsKeepsServerOrderAndTotal() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1), sampleComment(2)], total: 2)
        let social = model(client: client)
        await social.loadComments()
        XCTAssertEqual(social.comments?.map(\.id), [1, 2])
        XCTAssertEqual(social.commentTotal, 2)
        XCTAssertFalse(social.commentsFailed)
        XCTAssertEqual(client.commentsCalls, [42])
    }

    func testLoadCommentsFailureKeepsListAndMarksFailed() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1)], total: 1)
        let social = model(client: client)
        await social.loadComments()
        client.commentsError = URLError(.timedOut)
        await social.loadComments()
        XCTAssertTrue(social.commentsFailed)
        XCTAssertEqual(social.comments?.map(\.id), [1])
        XCTAssertEqual(social.commentTotal, 1)
        client.commentsError = nil
        await social.loadComments()
        XCTAssertFalse(social.commentsFailed)
    }

    func testFirstLoadFailureLeavesCommentsUnloaded() async {
        let client = FakeSocialClient()
        client.commentsError = URLError(.timedOut)
        let social = model(client: client)
        await social.loadComments()
        XCTAssertTrue(social.commentsFailed)
        XCTAssertNil(social.comments)
    }

    // MARK: 댓글 쓰기

    func testSendTrimsAppendsAndCounts() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1)], total: 1)
        let social = model(client: client)
        await social.loadComments()
        let outcome = await social.send("  안녕하세요  ")
        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(client.addCalls, [.init(postId: 42, body: "안녕하세요")])
        XCTAssertEqual(social.comments?.map(\.id), [1, 500])
        XCTAssertEqual(social.comments?.last?.body, "안녕하세요")
        XCTAssertEqual(social.commentTotal, 2)
        XCTAssertFalse(social.sending)
    }

    func testSendCutsLongBodyToFiveHundredCharacters() async {
        let client = FakeSocialClient()
        let social = model(client: client)
        await social.loadComments()
        let outcome = await social.send(String(repeating: "가", count: 600))
        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(client.addCalls.first?.body, String(repeating: "가", count: 500))
    }

    func testSendEmptyIsIgnored() async {
        let client = FakeSocialClient()
        let social = model(client: client)
        let blank = await social.send("   \n ")
        XCTAssertEqual(blank, .ignored)
        let empty = await social.send("")
        XCTAssertEqual(empty, .ignored)
        XCTAssertTrue(client.addCalls.isEmpty)
    }

    func testSendNeedsSignInAndDoesNotCall() async {
        let client = FakeSocialClient()
        let social = model(client: client, signedIn: false)
        let outcome = await social.send("좋아요")
        XCTAssertEqual(outcome, .needsSignIn)
        XCTAssertTrue(client.addCalls.isEmpty)
    }

    func testSendFailureLeavesListAlone() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1)], total: 1)
        let social = model(client: client)
        await social.loadComments()
        client.addError = URLError(.networkConnectionLost)
        let outcome = await social.send("좋아요")
        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(social.comments?.map(\.id), [1])
        XCTAssertEqual(social.commentTotal, 1)
        XCTAssertFalse(social.sending)
        XCTAssertEqual(client.addCalls.count, 1)
    }

    // MARK: 댓글 지우기·차단

    func testRemoveDropsCommentAndCounts() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1, mine: true), sampleComment(2)], total: 2)
        let social = model(client: client)
        await social.loadComments()
        await social.remove(sampleComment(1, mine: true))
        XCTAssertEqual(social.comments?.map(\.id), [2])
        XCTAssertEqual(social.commentTotal, 1)
        XCTAssertEqual(client.deleteCalls, [.init(postId: 42, commentId: 1)])
        XCTAssertEqual(client.commentsCalls.count, 1)
    }

    func testRemoveFailureReloads() async {
        let client = FakeSocialClient()
        let mine = sampleComment(1, mine: true)
        client.commentsResult = TripPostCommentList(items: [mine, sampleComment(2)], total: 2)
        let social = model(client: client)
        await social.loadComments()
        client.deleteError = URLError(.networkConnectionLost)
        await social.remove(mine)
        _ = await communityWaitUntil { client.commentsCalls.count >= 2 && social.comments?.count == 2 }
        XCTAssertEqual(client.deleteCalls.count, 1)
        XCTAssertEqual(client.commentsCalls.count, 2, "실패한 뒤 다시 받지 않았다")
        XCTAssertEqual(social.comments?.map(\.id), [1, 2])
        XCTAssertEqual(social.commentTotal, 2)
    }

    func testBlockedReloadsComments() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1), sampleComment(2)], total: 2)
        let social = model(client: client)
        await social.loadComments()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1)], total: 1)
        await social.blocked()
        XCTAssertEqual(client.commentsCalls.count, 2)
        XCTAssertEqual(social.comments?.map(\.id), [1])
        XCTAssertEqual(social.commentTotal, 1)
    }

    // MARK: 목록 줄에 알리기

    func testOnChangeReportsLikeAndCommentChanges() async {
        let client = FakeSocialClient()
        client.commentsResult = TripPostCommentList(items: [sampleComment(1)], total: 1)
        let social = model(liked: false, likeCount: 3, commentCount: 1, client: client)
        var changes: [Change] = []
        social.onChange = { changes.append(Change(liked: $0, likes: $1, comments: $2)) }
        _ = await social.toggleLike()
        XCTAssertEqual(changes.last, Change(liked: true, likes: 4, comments: 1))
        await social.loadComments()
        _ = await social.send("좋아요")
        XCTAssertEqual(changes.last, Change(liked: true, likes: 4, comments: 2))
        await social.remove(sampleComment(500, "좋아요", mine: true))
        XCTAssertEqual(changes.last, Change(liked: true, likes: 4, comments: 1))
    }

    func testOnChangeReportsRollback() async {
        let client = FakeSocialClient()
        client.likeError = URLError(.timedOut)
        let social = model(liked: false, likeCount: 3, commentCount: 0, client: client)
        var changes: [Change] = []
        social.onChange = { changes.append(Change(liked: $0, likes: $1, comments: $2)) }
        _ = await social.toggleLike()
        XCTAssertFalse(changes.isEmpty)
        XCTAssertEqual(changes.last, Change(liked: false, likes: 3, comments: 0))
    }
}
