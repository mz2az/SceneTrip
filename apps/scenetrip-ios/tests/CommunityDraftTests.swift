@testable import SceneTrip
import XCTest

/// 블로그형 글쓰기 초안 (MZ2AZ-547 K11) — 웹 `community/articleDraft.ts`·`ArticleDraft.test.ts`·`ArticleEditor.prepareSpot` 와 같은 뜻.
final class CommunityDraftTests: XCTestCase {
    private func uid(_ number: Int) -> UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", number))!
    }

    private func text(_ number: Int, _ value: String) -> CommunityDraftItem {
        .text(id: uid(number), text: value)
    }

    private func photo(_ number: Int) -> CommunityDraftItem {
        .photo(id: uid(number))
    }

    /// 부를 때마다 900, 901, … 번 id 를 낸다.
    private func counter() -> () -> UUID {
        var next = 900
        return { [self] in
            defer { next += 1 }
            return uid(next)
        }
    }

    // MARK: story

    func testStoryJoinsTextInOrderAndSkipsPhotos() {
        XCTAssertEqual(CommunityDraft.story([text(1, "가"), photo(10), text(2, "나")]), "가\n\n나")
    }

    func testStoryTrimsAndSkipsBlankPieces() {
        XCTAssertEqual(CommunityDraft.story([text(1, "  가 "), text(2, ""), text(3, "   \n  "), photo(10), text(4, "나\n")]), "가\n\n나")
    }

    func testStoryKeepsBlankLinesInsidePieceSoParagraphsSurvive() {
        let items = [text(1, "가\n\n나"), photo(10), text(2, ""), text(3, "다")]
        XCTAssertEqual(CommunityArticle.paragraphs(CommunityDraft.story(items)), ["가", "나", "다"])
    }

    func testStoryIsEmptyWithoutText() {
        XCTAssertEqual(CommunityDraft.story([]), "")
        XCTAssertEqual(CommunityDraft.story([photo(10), text(1, "  ")]), "")
    }

    // MARK: placements

    func testPlacementCountsParagraphsBeforeEachPhoto() {
        let items = [text(1, "가\n\n나"), photo(10), text(2, ""), photo(11), text(3, "다"), photo(12)]
        let result = CommunityDraft.placements(items, meta: [:])
        XCTAssertEqual(result[uid(10)]?.afterParagraph, 2)
        XCTAssertEqual(result[uid(11)]?.afterParagraph, 2)
        XCTAssertEqual(result[uid(12)]?.afterParagraph, 3)
    }

    func testLeadingPhotoIsAfterParagraphZero() {
        XCTAssertEqual(CommunityDraft.placements([photo(10), text(1, "가")], meta: [:])[uid(10)]?.afterParagraph, 0)
    }

    func testPlacementCopiesMetaButIgnoresItsPosition() {
        let items = [text(1, "가"), photo(10), photo(11)]
        let meta = [uid(10): CommunityPhotoPlacement(afterParagraph: 99, caption: "바다", credit: "내 사진", aiGenerated: true)]
        let result = CommunityDraft.placements(items, meta: meta)
        XCTAssertEqual(result, [
            uid(10): CommunityPhotoPlacement(afterParagraph: 1, caption: "바다", credit: "내 사진", aiGenerated: true),
            uid(11): CommunityPhotoPlacement(afterParagraph: 1),
        ])
    }

    func testPlacementsHaveOnlyPhotos() {
        XCTAssertEqual(Array(CommunityDraft.placements([text(1, "가"), photo(10)], meta: [:]).keys), [uid(10)])
    }

    func testDraftBecomesBlocksInWrittenOrder() {
        let items = [text(1, "가\n\n나"), photo(10), text(2, "다"), photo(11)]
        let blocks = CommunityArticle.blocks(body: CommunityDraft.story(items), photoIds: CommunityDraft.photoOrder(items),
                                             placements: CommunityDraft.placements(items, meta: [:]))
        XCTAssertEqual(blocks.map(\.kind), [.text, .text, .photo, .text, .photo])
        XCTAssertEqual(blocks.compactMap(\.text), ["가", "나", "다"])
        XCTAssertEqual(blocks.compactMap(\.photoIndex), [0, 1])
    }

    // MARK: photoOrder

    func testPhotoOrderFollowsList() {
        XCTAssertEqual(CommunityDraft.photoOrder([photo(11), text(1, "가"), photo(10), text(2, ""), photo(12)]), [uid(11), uid(10), uid(12)])
        XCTAssertEqual(CommunityDraft.photoOrder([text(1, "가")]), [])
    }

    // MARK: insertPhotos

    func testInsertAtMinusOneGoesFirst() {
        let base = [text(1, "가"), text(2, "나")]
        XCTAssertEqual(CommunityDraft.insertPhotos(base, after: -1, ids: [uid(10), uid(11)]),
                       [photo(10), photo(11), text(1, "가"), text(2, "나")])
    }

    func testInsertAfterGivenItem() {
        let base = [text(1, "가"), text(2, "나")]
        XCTAssertEqual(CommunityDraft.insertPhotos(base, after: 0, ids: [uid(10)]), [text(1, "가"), photo(10), text(2, "나")])
        XCTAssertEqual(CommunityDraft.insertPhotos(base, after: 1, ids: [uid(10)]), [text(1, "가"), text(2, "나"), photo(10)])
    }

    func testInsertPositionIsClamped() {
        let base = [text(1, "가"), text(2, "나")]
        XCTAssertEqual(CommunityDraft.insertPhotos(base, after: 10, ids: [uid(10)]), [text(1, "가"), text(2, "나"), photo(10)])
        XCTAssertEqual(CommunityDraft.insertPhotos(base, after: -5, ids: [uid(10)]), [photo(10), text(1, "가"), text(2, "나")])
    }

    func testInsertSkipsPresentAndRepeatedIdsKeepingOrder() {
        let items = [text(1, "가"), photo(10), text(2, "나")]
        XCTAssertEqual(CommunityDraft.insertPhotos(items, after: 2, ids: [uid(12), uid(10), uid(11), uid(12)]),
                       [text(1, "가"), photo(10), text(2, "나"), photo(12), photo(11)])
    }

    func testInsertNothingNewKeepsList() {
        let items = [text(1, "가"), photo(10)]
        XCTAssertEqual(CommunityDraft.insertPhotos(items, after: 0, ids: [uid(10)]), items)
        XCTAssertEqual(CommunityDraft.insertPhotos(items, after: 0, ids: []), items)
    }

    // MARK: dropMissingPhotos

    func testDropMissingPhotosKeepsText() {
        let items = [text(1, "가"), photo(10), text(2, "나"), photo(11), photo(12)]
        XCTAssertEqual(CommunityDraft.dropMissingPhotos(items, slotIds: [uid(12), uid(10)]),
                       [text(1, "가"), photo(10), text(2, "나"), photo(12)])
        XCTAssertEqual(CommunityDraft.dropMissingPhotos([photo(10), text(1, "가")], slotIds: []), [text(1, "가")])
    }

    // MARK: normalize

    func testNormalizeEmptyListGetsOneBlankPiece() {
        XCTAssertEqual(CommunityDraft.normalize([], newId: counter()), [text(900, "")])
    }

    func testNormalizeOnlyPhotosGetsOneBlankPieceAtEnd() {
        XCTAssertEqual(CommunityDraft.normalize([photo(10)], newId: counter()), [photo(10), text(900, "")])
    }

    func testNormalizeAddsBlankPieceAfterTrailingPhoto() {
        XCTAssertEqual(CommunityDraft.normalize([text(1, "가"), photo(10)], newId: counter()), [text(1, "가"), photo(10), text(900, "")])
    }

    func testNormalizeMergesTwoBlankPiecesKeepingFirst() {
        let items = [text(1, "가"), photo(10), text(2, " "), text(3, ""), photo(11), text(4, "나")]
        XCTAssertEqual(CommunityDraft.normalize(items, newId: counter()), [text(1, "가"), photo(10), text(2, " "), photo(11), text(4, "나")])
    }

    func testNormalizeKeepsWellFormedList() {
        let items = [text(1, "가\n\n나"), photo(10), text(2, "다")]
        XCTAssertEqual(CommunityDraft.normalize(items, newId: counter()), items)
    }

    // MARK: moveItem

    func testMoveItem() {
        let items = [text(1, "가"), photo(10), text(2, "나")]
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 0, to: 2), [photo(10), text(2, "나"), text(1, "가")])
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 2, to: 0), [text(2, "나"), text(1, "가"), photo(10)])
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 1, to: 1), items)
    }

    func testMoveItemOutOfRangeKeepsList() {
        let items = [text(1, "가"), photo(10), text(2, "나")]
        XCTAssertEqual(CommunityDraft.moveItem(items, from: -1, to: 0), items)
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 3, to: 0), items)
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 0, to: 3), items)
        XCTAssertEqual(CommunityDraft.moveItem(items, from: 0, to: -1), items)
    }

    // MARK: split

    func testSplitWithoutCaretInsertsAfterLastItem() {
        let items = [text(1, "가"), photo(10), text(2, "나")]
        let result = CommunityDraft.split(items, caret: nil, newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 2)
    }

    func testSplitWithUnknownCaretIdInsertsAfterLastItem() {
        let items = [text(1, "가나")]
        let result = CommunityDraft.split(items, caret: (id: uid(77), at: 1), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 0)
    }

    func testSplitOnPhotoInsertsAfterThatPhoto() {
        let items = [text(1, "가"), photo(10), text(2, "나")]
        let result = CommunityDraft.split(items, caret: (id: uid(10), at: 0), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 1)
    }

    func testSplitWithCaretAtEndDoesNotSplit() {
        let items = [text(1, "앞"), photo(10), text(2, "가나다")]
        let result = CommunityDraft.split(items, caret: (id: uid(2), at: "가나다".utf16.count), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 2)
    }

    func testSplitWithOnlySpaceAfterCaretDoesNotSplit() {
        let items = [text(1, "가나다 \n ")]
        let result = CommunityDraft.split(items, caret: (id: uid(1), at: 3), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 0)
    }

    func testSplitWithCaretAtStartInsertsBeforePiece() {
        let items = [text(1, "앞"), photo(10), text(2, "가나다")]
        let result = CommunityDraft.split(items, caret: (id: uid(2), at: 0), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, 1)
    }

    func testSplitWithCaretAtStartOfFirstPieceInsertsFirst() {
        let items = [text(1, "  가나다")]
        let result = CommunityDraft.split(items, caret: (id: uid(1), at: 2), newId: counter())
        XCTAssertEqual(result.items, items)
        XCTAssertEqual(result.insertAfter, -1)
    }

    func testSplitInsideCutsPieceAndTrimsAroundCaret() {
        let items = [text(1, "앞"), photo(10), text(2, "가\n\n나")]
        let result = CommunityDraft.split(items, caret: (id: uid(2), at: 1), newId: counter())
        XCTAssertEqual(result.items, [text(1, "앞"), photo(10), text(2, "가"), text(900, "나")])
        XCTAssertEqual(result.insertAfter, 2)
    }

    func testSplitCaretIsUTF16Offset() {
        // 😀 는 UTF-16 두 칸이다 — "😀가 나다" 에서 4 는 「나」 앞이다(문자 단위면 「다」 앞).
        let items = [text(1, "😀가 나다"), text(2, "뒤")]
        let result = CommunityDraft.split(items, caret: (id: uid(1), at: 4), newId: counter())
        XCTAssertEqual(result.items, [text(1, "😀가"), text(900, "나다"), text(2, "뒤")])
        XCTAssertEqual(result.insertAfter, 0)
    }
}
