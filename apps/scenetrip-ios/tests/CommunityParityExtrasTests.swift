import SceneApiClient
@testable import SceneTrip
import XCTest

/// 커뮤니티 웹 시안 반영의 작은 조각들 (MZ2AZ-547 K7·K9·K11) — 댓글 신고 대상, 읽는 화면 블록, 사진 칸 순서.
final class CommunityParityExtrasTests: XCTestCase {
    // MARK: 댓글 신고·차단 대상 (K9)

    func testCommentModerationTarget() {
        let target = ModerationTarget.comment(5)
        XCTAssertEqual(target.type, .comment)
        XCTAssertEqual(target.targetId, 5)
        XCTAssertEqual(target.id, "comment-5")
        XCTAssertNotEqual(target.id, ModerationTarget.post(5).id)
    }

    // MARK: 읽는 화면 블록 (K7)

    func testReadingBlocksWithoutServerBlocksIsLegacy() {
        let body = "첫 문단\n\n둘째 문단\n\n셋째 문단"
        XCTAssertEqual(CommunityArticle.readingBlocks(bodyBlocks: nil, body: body, photoCount: 2),
                       CommunityArticle.legacy(body: body, photoCount: 2))
    }

    func testReadingBlocksKeepsCompleteServerBlocks() {
        let blocks = [TripPostBlock(kind: .text, text: "가"), TripPostBlock(kind: .photo, photoIndex: 0, caption: "바다"),
                      TripPostBlock(kind: .text, text: "나"), TripPostBlock(kind: .photo, photoIndex: 1)]
        XCTAssertEqual(CommunityArticle.readingBlocks(bodyBlocks: blocks, body: "가\n\n나", photoCount: 2), blocks)
    }

    func testReadingBlocksShowsEachPhotoOnce() {
        let blocks = [TripPostBlock(kind: .text, text: "가"),
                      TripPostBlock(kind: .photo, photoIndex: 0, caption: "첫"),
                      TripPostBlock(kind: .text, text: "나"),
                      TripPostBlock(kind: .photo, photoIndex: 0, caption: "중복"),
                      TripPostBlock(kind: .photo, photoIndex: 5)]
        let result = CommunityArticle.readingBlocks(bodyBlocks: blocks, body: "가\n\n나", photoCount: 3)
        // 중복·범위 밖은 빠지고, 어디에도 없던 1·2 번 사진은 끝에 붙는다.
        XCTAssertEqual(result.map(\.kind), [.text, .photo, .text, .photo, .photo])
        XCTAssertEqual(result.compactMap(\.text), ["가", "나"])
        XCTAssertEqual(result.filter { $0.kind == .photo }.compactMap(\.photoIndex), [0, 1, 2])
        XCTAssertEqual(result.count > 1 ? result[1].caption : nil, "첫")
    }

    func testReadingBlocksAppendsUnreferencedPhotos() {
        let blocks = [TripPostBlock(kind: .text, text: "가")]
        let result = CommunityArticle.readingBlocks(bodyBlocks: blocks, body: "가", photoCount: 2)
        XCTAssertEqual(result.map(\.kind), [.text, .photo, .photo])
        XCTAssertEqual(result.compactMap(\.photoIndex), [0, 1])
    }

    // MARK: 사진 칸 순서 (K11)

    @MainActor func testReorderFollowsGivenIdsAndKeepsOthersAfter() {
        let draft = ReviewPhotoDraft(purpose: .post, limit: 8)
        draft.start(with: (0 ..< 3).map { ReviewPhoto(key: "posts/\($0).jpg", url: "https://example.com/\($0).jpg") })
        let ids = draft.slots.map(\.id)
        XCTAssertEqual(ids.count, 3)
        draft.reorder([ids[2], ids[0]])
        XCTAssertEqual(draft.slots.map(\.id), [ids[2], ids[0], ids[1]])
    }

    @MainActor func testReorderIgnoresUnknownIds() {
        let draft = ReviewPhotoDraft(purpose: .post, limit: 8)
        draft.start(with: (0 ..< 3).map { ReviewPhoto(key: "posts/\($0).jpg", url: "https://example.com/\($0).jpg") })
        let ids = draft.slots.map(\.id)
        draft.reorder([UUID(), ids[1]])
        XCTAssertEqual(draft.slots.map(\.id), [ids[1], ids[0], ids[2]])
        XCTAssertEqual(draft.slots.count, 3)
    }
}
