package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityDraft
import com.mz2az.scenetrip.data.CommunityDraftCaret
import com.mz2az.scenetrip.data.CommunityDraftItem
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** 블로그형 글쓰기 초안 (MZ2AZ-547 P8 K11, iOS `CommunityDraftTests` 를 옮긴 것). */
class CommunityDraftParityTests {
    private fun uid(number: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(number))

    private fun text(
        number: Int,
        value: String,
    ): CommunityDraftItem = CommunityDraftItem.Text(uid(number), value)

    private fun photo(number: Int): CommunityDraftItem = CommunityDraftItem.Photo(uid(number))

    /** 부를 때마다 900, 901, … 번 id 를 낸다. */
    private fun counter(): () -> UUID {
        var next = 900
        return { uid(next++) }
    }

    // ── story ──

    @Test
    fun storyJoinsTextInOrderAndSkipsPhotos() {
        assertEquals("가\n\n나", CommunityDraft.story(listOf(text(1, "가"), photo(10), text(2, "나"))))
    }

    @Test
    fun storyTrimsAndSkipsBlankPieces() {
        assertEquals(
            "가\n\n나",
            CommunityDraft.story(listOf(text(1, "  가 "), text(2, ""), text(3, "   \n  "), photo(10), text(4, "나\n"))),
        )
    }

    @Test
    fun storyKeepsBlankLinesInsidePieceSoParagraphsSurvive() {
        val items = listOf(text(1, "가\n\n나"), photo(10), text(2, ""), text(3, "다"))
        assertEquals(listOf("가", "나", "다"), CommunityArticle.paragraphs(CommunityDraft.story(items)))
    }

    @Test
    fun storyIsEmptyWithoutText() {
        assertEquals("", CommunityDraft.story(emptyList()))
        assertEquals("", CommunityDraft.story(listOf(photo(10), text(1, "  "))))
    }

    // ── placements ──

    @Test
    fun placementCountsParagraphsBeforeEachPhoto() {
        val items = listOf(text(1, "가\n\n나"), photo(10), text(2, ""), photo(11), text(3, "다"), photo(12))
        val result = CommunityDraft.placements(items, emptyMap())
        assertEquals(2, result[uid(10)]?.afterParagraph)
        assertEquals(2, result[uid(11)]?.afterParagraph)
        assertEquals(3, result[uid(12)]?.afterParagraph)
    }

    @Test
    fun leadingPhotoIsAfterParagraphZero() {
        assertEquals(0, CommunityDraft.placements(listOf(photo(10), text(1, "가")), emptyMap())[uid(10)]?.afterParagraph)
    }

    @Test
    fun placementCopiesMetaButIgnoresItsPosition() {
        val items = listOf(text(1, "가"), photo(10), photo(11))
        val meta = mapOf(uid(10) to CommunityPhotoPlacement(99, caption = "바다", credit = "내 사진", aiGenerated = true))
        assertEquals(
            mapOf(
                uid(10) to CommunityPhotoPlacement(1, caption = "바다", credit = "내 사진", aiGenerated = true),
                uid(11) to CommunityPhotoPlacement(1),
            ),
            CommunityDraft.placements(items, meta),
        )
    }

    @Test
    fun placementsHaveOnlyPhotos() {
        assertEquals(setOf(uid(10)), CommunityDraft.placements(listOf(text(1, "가"), photo(10)), emptyMap()).keys)
    }

    @Test
    fun draftBecomesBlocksInWrittenOrder() {
        val items = listOf(text(1, "가\n\n나"), photo(10), text(2, "다"), photo(11))
        val blocks =
            CommunityArticle.blocks(
                CommunityDraft.story(items),
                CommunityDraft.photoOrder(items),
                CommunityDraft.placements(items, emptyMap()),
            )
        assertEquals(
            listOf(
                TripPostBlockKind.text,
                TripPostBlockKind.text,
                TripPostBlockKind.photo,
                TripPostBlockKind.text,
                TripPostBlockKind.photo,
            ),
            blocks.map { it.kind },
        )
        assertEquals(listOf("가", "나", "다"), blocks.mapNotNull { it.text })
        assertEquals(listOf(0, 1), blocks.mapNotNull { it.photoIndex })
    }

    // ── photoOrder ──

    @Test
    fun photoOrderFollowsList() {
        assertEquals(
            listOf(uid(11), uid(10), uid(12)),
            CommunityDraft.photoOrder(listOf(photo(11), text(1, "가"), photo(10), text(2, ""), photo(12))),
        )
        assertEquals(emptyList<UUID>(), CommunityDraft.photoOrder(listOf(text(1, "가"))))
    }

    // ── insertPhotos ──

    @Test
    fun insertAtMinusOneGoesFirst() {
        val base = listOf(text(1, "가"), text(2, "나"))
        assertEquals(
            listOf(photo(10), photo(11), text(1, "가"), text(2, "나")),
            CommunityDraft.insertPhotos(base, -1, listOf(uid(10), uid(11))),
        )
    }

    @Test
    fun insertAfterGivenItem() {
        val base = listOf(text(1, "가"), text(2, "나"))
        assertEquals(listOf(text(1, "가"), photo(10), text(2, "나")), CommunityDraft.insertPhotos(base, 0, listOf(uid(10))))
        assertEquals(listOf(text(1, "가"), text(2, "나"), photo(10)), CommunityDraft.insertPhotos(base, 1, listOf(uid(10))))
    }

    @Test
    fun insertPositionIsClamped() {
        val base = listOf(text(1, "가"), text(2, "나"))
        assertEquals(listOf(text(1, "가"), text(2, "나"), photo(10)), CommunityDraft.insertPhotos(base, 10, listOf(uid(10))))
        assertEquals(listOf(photo(10), text(1, "가"), text(2, "나")), CommunityDraft.insertPhotos(base, -5, listOf(uid(10))))
    }

    @Test
    fun insertSkipsPresentAndRepeatedIdsKeepingOrder() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"))
        assertEquals(
            listOf(text(1, "가"), photo(10), text(2, "나"), photo(12), photo(11)),
            CommunityDraft.insertPhotos(items, 2, listOf(uid(12), uid(10), uid(11), uid(12))),
        )
    }

    @Test
    fun insertNothingNewKeepsList() {
        val items = listOf(text(1, "가"), photo(10))
        assertEquals(items, CommunityDraft.insertPhotos(items, 0, listOf(uid(10))))
        assertEquals(items, CommunityDraft.insertPhotos(items, 0, emptyList()))
    }

    // ── dropMissingPhotos ──

    @Test
    fun dropMissingPhotosKeepsText() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"), photo(11), photo(12))
        assertEquals(
            listOf(text(1, "가"), photo(10), text(2, "나"), photo(12)),
            CommunityDraft.dropMissingPhotos(items, listOf(uid(12), uid(10))),
        )
        assertEquals(listOf(text(1, "가")), CommunityDraft.dropMissingPhotos(listOf(photo(10), text(1, "가")), emptyList()))
    }

    // ── normalize ──

    @Test
    fun normalizeEmptyListGetsOneBlankPiece() {
        assertEquals(listOf(text(900, "")), CommunityDraft.normalize(emptyList(), counter()))
    }

    @Test
    fun normalizeOnlyPhotosGetsOneBlankPieceAtEnd() {
        assertEquals(listOf(photo(10), text(900, "")), CommunityDraft.normalize(listOf(photo(10)), counter()))
    }

    @Test
    fun normalizeAddsBlankPieceAfterTrailingPhoto() {
        assertEquals(
            listOf(text(1, "가"), photo(10), text(900, "")),
            CommunityDraft.normalize(listOf(text(1, "가"), photo(10)), counter()),
        )
    }

    @Test
    fun normalizeMergesTwoBlankPiecesKeepingFirst() {
        val items = listOf(text(1, "가"), photo(10), text(2, " "), text(3, ""), photo(11), text(4, "나"))
        assertEquals(
            listOf(text(1, "가"), photo(10), text(2, " "), photo(11), text(4, "나")),
            CommunityDraft.normalize(items, counter()),
        )
    }

    @Test
    fun normalizeKeepsWellFormedList() {
        val items = listOf(text(1, "가\n\n나"), photo(10), text(2, "다"))
        assertEquals(items, CommunityDraft.normalize(items, counter()))
    }

    // ── moveItem ──

    @Test
    fun moveItem() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"))
        assertEquals(listOf(photo(10), text(2, "나"), text(1, "가")), CommunityDraft.moveItem(items, 0, 2))
        assertEquals(listOf(text(2, "나"), text(1, "가"), photo(10)), CommunityDraft.moveItem(items, 2, 0))
        assertEquals(items, CommunityDraft.moveItem(items, 1, 1))
    }

    @Test
    fun moveItemOutOfRangeKeepsList() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"))
        assertEquals(items, CommunityDraft.moveItem(items, -1, 0))
        assertEquals(items, CommunityDraft.moveItem(items, 3, 0))
        assertEquals(items, CommunityDraft.moveItem(items, 0, 3))
        assertEquals(items, CommunityDraft.moveItem(items, 0, -1))
    }

    // ── split ──

    @Test
    fun splitWithoutCaretInsertsAfterLastItem() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"))
        val result = CommunityDraft.split(items, null, counter())
        assertEquals(items, result.items)
        assertEquals(2, result.insertAfter)
    }

    @Test
    fun splitWithUnknownCaretIdInsertsAfterLastItem() {
        val items = listOf(text(1, "가나"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(77), 1), counter())
        assertEquals(items, result.items)
        assertEquals(0, result.insertAfter)
    }

    @Test
    fun splitOnPhotoInsertsAfterThatPhoto() {
        val items = listOf(text(1, "가"), photo(10), text(2, "나"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(10), 0), counter())
        assertEquals(items, result.items)
        assertEquals(1, result.insertAfter)
    }

    @Test
    fun splitWithCaretAtEndDoesNotSplit() {
        val items = listOf(text(1, "앞"), photo(10), text(2, "가나다"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(2), "가나다".length), counter())
        assertEquals(items, result.items)
        assertEquals(2, result.insertAfter)
    }

    @Test
    fun splitWithOnlySpaceAfterCaretDoesNotSplit() {
        val items = listOf(text(1, "가나다 \n "))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(1), 3), counter())
        assertEquals(items, result.items)
        assertEquals(0, result.insertAfter)
    }

    @Test
    fun splitWithCaretAtStartInsertsBeforePiece() {
        val items = listOf(text(1, "앞"), photo(10), text(2, "가나다"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(2), 0), counter())
        assertEquals(items, result.items)
        assertEquals(1, result.insertAfter)
    }

    @Test
    fun splitWithCaretAtStartOfFirstPieceInsertsFirst() {
        val items = listOf(text(1, "  가나다"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(1), 2), counter())
        assertEquals(items, result.items)
        assertEquals(-1, result.insertAfter)
    }

    @Test
    fun splitInsideCutsPieceAndTrimsAroundCaret() {
        val items = listOf(text(1, "앞"), photo(10), text(2, "가\n\n나"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(2), 1), counter())
        assertEquals(listOf(text(1, "앞"), photo(10), text(2, "가"), text(900, "나")), result.items)
        assertEquals(2, result.insertAfter)
    }

    @Test
    fun splitCaretIsUtf16Offset() {
        // 😀 는 UTF-16 두 칸이다 — "😀가 나다" 에서 4 는 「나」 앞이다(코드 포인트 단위면 「다」 앞).
        val items = listOf(text(1, "😀가 나다"), text(2, "뒤"))
        val result = CommunityDraft.split(items, CommunityDraftCaret(uid(1), 4), counter())
        assertEquals(listOf(text(1, "😀가"), text(900, "나다"), text(2, "뒤")), result.items)
        assertEquals(0, result.insertAfter)
    }

    // ── 사진 칸 순서 (iOS `ReviewPhotoDraft.reorder`) ──

    private data class Slot(
        val id: UUID,
        val key: String,
    )

    private val slots = (0 until 3).map { Slot(uid(100 + it), "posts/$it.jpg") }

    @Test
    fun reorderFollowsGivenIdsAndKeepsOthersAfter() {
        val ids = slots.map { it.id }
        assertEquals(listOf(ids[2], ids[0], ids[1]), CommunityDraft.reorder(slots, listOf(ids[2], ids[0])) { it.id }.map { it.id })
    }

    @Test
    fun reorderIgnoresUnknownIds() {
        val ids = slots.map { it.id }
        val result = CommunityDraft.reorder(slots, listOf(uid(999), ids[1])) { it.id }
        assertEquals(listOf(ids[1], ids[0], ids[2]), result.map { it.id })
        assertEquals(3, result.size)
    }
}
