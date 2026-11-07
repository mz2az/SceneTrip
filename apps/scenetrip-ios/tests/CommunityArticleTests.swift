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

    @MainActor func testUnreadablePhotoPreviewFailsRatherThanLoadingForever() throws {
        let draft = ReviewPhotoDraft(purpose: .post)
        draft.start(with: [ReviewPhoto(key: "posts/preview.jpg", url: "https://example.com/preview.jpg")])
        var slot = try XCTUnwrap(draft.slots.first)
        slot.remoteUrl = nil
        slot.state = .failed(.unreadable)
        let source = CommunityPhotoSource(slot: slot)
        guard case .failed = source else { return XCTFail("읽기 실패를 계속 로딩으로 표시했다") }
        XCTAssertFalse(source.retryable)
        slot.state = .waiting
        let waiting = CommunityPhotoSource(slot: slot)
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

    // MARK: 글 끝의 References·Photo credits 접기 (MZ2AZ-528 — Android `splitReferences`·웹 `article.ts` 와 같은 표)

    private func text(_ value: String) -> TripPostBlock {
        TripPostBlock(kind: .text, text: value)
    }

    private let photo = TripPostBlock(kind: .photo, photoIndex: 0)

    func testTrailingReferenceBlocksSplitFromTheBodyButMiddleOnesStay() {
        let all = [text("첫 문단"), text("References\n본문 중간에 우연히 같은 말"), photo, text("마지막 문단"),
                   text("References\nhttps://example.com"), text("Photo credits\n1. 사진 · 출처")]
        let (body, references) = CommunityArticle.splitReferences(all)
        XCTAssertEqual(body, Array(all.prefix(4)))
        XCTAssertEqual(references, Array(all.suffix(2)))
        XCTAssertEqual(references.map(CommunityArticle.referenceHeading), ["References", "Photo credits"])
    }

    func testWithoutTrailingReferencesNothingFolds() {
        XCTAssertEqual(CommunityArticle.splitReferences([text("본문"), photo]).references.count, 0)
        XCTAssertEqual(CommunityArticle.splitReferences([text("본문"), photo]).body.count, 2)
        XCTAssertEqual(CommunityArticle.splitReferences([]).references.count, 0)
        // 덧붙임 뒤에 본문이 오면 그 덧붙임은 끝이 아니다.
        XCTAssertEqual(CommunityArticle.splitReferences([text("References\nx"), text("맺음말")]).references.count, 0)
        // 한국어 제목도 알아보고, 글 전체가 덧붙임이어도 접는다.
        let only = CommunityArticle.splitReferences([text("참고 자료\n링크")])
        XCTAssertEqual(only.body.count, 0)
        XCTAssertEqual(only.references.count, 1)
    }

    func testReferenceHeadingIgnoresCaseButKeepsTheWrittenLine() {
        XCTAssertEqual(CommunityArticle.referenceHeading(text("PHOTO CREDITS\n내용")), "PHOTO CREDITS")
        XCTAssertEqual(CommunityArticle.referenceHeading(text("  References  \r\nhttps://a.example")), "References")
        let headings = ["references", "Reference", "photo credits", "Photo credit", "Image Credits",
                        "참고 자료", "참고자료", "사진 출처", "이미지 출처", "출처"]
        for heading in headings {
            XCTAssertEqual(CommunityArticle.referenceHeading(text("\(heading)\n본문")), heading, heading)
        }
        XCTAssertNil(CommunityArticle.referenceHeading(text("References and notes\n본문")))
        XCTAssertNil(CommunityArticle.referenceHeading(text("첫 줄\nReferences")))
        XCTAssertNil(CommunityArticle.referenceHeading(photo))
        XCTAssertNil(CommunityArticle.referenceHeading(TripPostBlock(kind: .text)))
    }

    func testFoldLabelJoinsTheHeadingsInOrder() {
        let references = [text("References\nhttps://example.com"), text("Photo credits\n1. 사진")]
        XCTAssertEqual(CommunityArticle.referencesLabel(references), "References · Photo credits")
        XCTAssertEqual(CommunityArticle.referencesLabel([text("사진 출처\n관광공사")]), "사진 출처")
    }

    // MARK: 본문 사진 출처 「더 보기」 (MZ2AZ-528 — AI 변환 표시는 고지라 늘 보인다)

    func testPhotoCreditFoldsButAINoticeAlwaysShows() {
        let notice = CommunityPhotoNotice(block: TripPostBlock(kind: .photo, photoIndex: 0,
                                                               credit: "한국관광공사 · 공공누리 제1유형", aiGenerated: true))
        XCTAssertFalse(notice.isEmpty)
        XCTAssertTrue(notice.aiGenerated)
        XCTAssertTrue(notice.foldsCredit)
        XCTAssertNil(notice.shownCredit(open: false))
        XCTAssertEqual(notice.shownCredit(open: true), "한국관광공사 · 공공누리 제1유형")
    }

    func testPhotoWithoutCreditHasNoFoldAndBlankCreditCountsAsNone() {
        let aiOnly = CommunityPhotoNotice(block: TripPostBlock(kind: .photo, photoIndex: 0,
                                                               credit: "  ", aiGenerated: true))
        XCTAssertFalse(aiOnly.isEmpty)
        XCTAssertTrue(aiOnly.aiGenerated)
        XCTAssertFalse(aiOnly.foldsCredit)
        XCTAssertNil(aiOnly.shownCredit(open: true))

        let creditOnly = CommunityPhotoNotice(block: TripPostBlock(kind: .photo, photoIndex: 0, credit: "출처"))
        XCTAssertFalse(creditOnly.aiGenerated)
        XCTAssertTrue(creditOnly.foldsCredit)
        XCTAssertNil(creditOnly.shownCredit(open: false))

        XCTAssertTrue(CommunityPhotoNotice(block: photo).isEmpty)
        XCTAssertFalse(CommunityPhotoNotice(block: photo).foldsCredit)
    }

    /// 접기 단추(References 줄·「더 보기」)는 VoiceOver 에 펼침/접힘 상태를 알린다 — 두 언어 문구가 번들에 있어야 한다.
    func testFoldStateIsAnnouncedInKoreanAndEnglish() throws {
        XCTAssertEqual(CommunityArticle.foldStateKey(open: true), "펼침")
        XCTAssertEqual(CommunityArticle.foldStateKey(open: false), "접힘")
        for language in ["ko", "en"] {
            let url = try XCTUnwrap(Bundle(for: Self.self).url(
                forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: language
            ))
            let values = try XCTUnwrap(PropertyListSerialization.propertyList(
                from: Data(contentsOf: url), format: nil
            ) as? [String: String])
            for key in [CommunityArticle.foldStateKey(open: true), CommunityArticle.foldStateKey(open: false)] {
                let value = try XCTUnwrap(values[key], "\(language) 에 「\(key)」 가 없다")
                XCTAssertFalse(value.isEmpty)
                if language == "en" {
                    XCTAssertNotEqual(key, value)
                }
            }
        }
    }
}
