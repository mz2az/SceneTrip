import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

final class CommunityArticleTests: XCTestCase {
    func testPhotoMetadataUsesNormalizedServerLengthLimits() {
        var placement = CommunityPhotoPlacement(afterParagraph: 1, caption: String(repeating: "😀", count: 250),
                                                credit: String(repeating: "a", count: 1000))
        XCTAssertTrue(placement.metadataValid)
        placement.caption += "😀"
        XCTAssertFalse(placement.metadataValid)
        placement.caption = "  " + String(repeating: "가", count: 500) + "  "
        XCTAssertEqual(placement.captionLength, 500)
        XCTAssertTrue(placement.metadataValid)
        placement.credit += "a"
        XCTAssertFalse(placement.metadataValid)
    }

    @MainActor func testUnreadablePhotoPreviewFailsRatherThanLoadingForever() {
        let failed = ReviewPhotoDraft.Slot(state: .failed(.unreadable))
        let source = CommunityPhotoSource(slot: failed)
        guard case .failed = source else { return XCTFail("읽기 실패를 계속 로딩으로 표시했다") }
        XCTAssertFalse(source.retryable)
        let waiting = CommunityPhotoSource(slot: ReviewPhotoDraft.Slot(state: .waiting))
        guard case .pending = waiting else { return XCTFail("대기 중 사진을 실패로 표시했다") }
    }

    func testPhotoOrderIsPlacedAfterChosenParagraph() {
        let first = UUID(), second = UUID()
        let placements = [first: CommunityPhotoPlacement(afterParagraph: 2, caption: "바다", aiGenerated: true),
                          second: CommunityPhotoPlacement(afterParagraph: 1, caption: "골목")]
        let blocks = CommunityArticle.blocks(body: "첫 문단\n\n둘째 문단", photoIds: [first, second], placements: placements)
        XCTAssertEqual(blocks.map(\.kind), [.text, .photo, .text, .photo])
        XCTAssertEqual(blocks.filter { $0.kind == .photo }.compactMap(\.photoIndex), [1, 0])
        XCTAssertEqual(blocks.last?.caption, "바다")
        XCTAssertEqual(blocks.last?.aiGenerated, true)
    }

    func testDeletingPhotoKeepsRemainingPlacementAndRebuildsIndices() {
        let removed = UUID(), kept = UUID()
        let placements = [removed: CommunityPhotoPlacement(afterParagraph: 1),
                          kept: CommunityPhotoPlacement(afterParagraph: 9, caption: "남은 사진")]
        let blocks = CommunityArticle.blocks(body: "짧은 문단", photoIds: [kept], placements: placements)
        XCTAssertEqual(blocks.count, 2)
        XCTAssertEqual(blocks.last?.photoIndex, 0)
        XCTAssertEqual(blocks.last?.caption, "남은 사진")
    }

    func testBlankLinesAndWindowsLineEndingsAreCanonicalizedWithoutLosingText() {
        XCTAssertEqual(CommunityArticle.paragraphs(" 첫째\r\n문장\r\n\r\n\r\n둘째 "), ["첫째\n문장", "둘째"])
        XCTAssertEqual(CommunityArticle.plainBody(" 첫째\n\n\n둘째 "), "첫째\n\n둘째")
        XCTAssertFalse(CommunityArticle.canCompose(String(repeating: "문단\n\n", count: 65)))
    }

    func testLegacyLayoutUsesAllPhotosOnceAndPreservesFullBody() {
        let blocks = CommunityArticle.legacy(body: "첫째\n\n둘째\n\n셋째", photoCount: 4)
        XCTAssertEqual(blocks.filter { $0.kind == .photo }.compactMap(\.photoIndex), [0, 1, 2, 3])
        XCTAssertEqual(blocks.filter { $0.kind == .text }.compactMap(\.text).joined(separator: "\n\n"), "첫째\n\n둘째\n\n셋째")
    }

    func testResponseConfirmationRequiresSameLayout() {
        let blocks = [TripPostBlock(kind: .text, text: "본문"), TripPostBlock(kind: .photo, photoIndex: 0)]
        let input = TripPostCreate(title: "제목", body: "본문", photoKeys: ["uploads/tmp/a.jpg"], bodyBlocks: blocks)
        var detail = TripPostDetail(id: 1, title: "제목", body: "본문", createdAt: Date(), photoUrls: ["https://example.com/posts/a.jpg"], isMine: true, bodyBlocks: blocks)
        XCTAssertTrue(CommunityRules.matches(detail, input: input))
        detail.bodyBlocks = blocks.reversed()
        XCTAssertFalse(CommunityRules.matches(detail, input: input))
        detail.bodyBlocks = nil
        XCTAssertFalse(CommunityRules.matches(detail, input: input))
    }
}
