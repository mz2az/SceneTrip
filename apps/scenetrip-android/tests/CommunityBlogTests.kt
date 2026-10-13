package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityClient
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunitySubmission
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

class CommunityBlogTests {
    @Test fun invalidBlocksNeverReachTheClientAndValidBlocksArePreserved() =
        runBlocking {
            var reads = 0
            var saved: TripPostCreate? = null
            val blocks = listOf(TripPostBlock(TripPostBlockKind.text, text = "본문"))
            val client =
                object : CommunityClient {
                    override suspend fun list(
                        mine: Boolean,
                        limit: Int,
                        offset: Int,
                    ): TripPostList {
                        reads++
                        return TripPostList(emptyList(), 0)
                    }

                    override suspend fun create(input: TripPostCreate): TripPostDetail {
                        saved = input
                        return TripPostDetail(
                            id = 1,
                            title = input.title,
                            body = input.body,
                            createdAt = OffsetDateTime.now(),
                            isMine = true,
                            photoUrls = emptyList(),
                            bodyBlocks = input.bodyBlocks,
                        )
                    }

                    override suspend fun detail(id: Long): TripPostDetail = error("상세 요청 없음")

                    override suspend fun delete(id: Long) {
                        error("삭제 요청 없음")
                    }

                    override suspend fun saveCourse(id: Long): CourseDetail = error("코스 요청 없음")
                }
            val submission = CommunitySubmission(client)
            assertEquals(
                null,
                submission.submit(
                    TripPostCreate(
                        "제목",
                        "본문",
                        emptyList(),
                        bodyBlocks =
                            blocks.reversed() + TripPostBlock(TripPostBlockKind.photo, photoIndex = 0),
                    ),
                ),
            )
            assertEquals(0, reads)
            val detail = submission.submit(TripPostCreate("제목", "본문", emptyList(), bodyBlocks = blocks))
            assertEquals(blocks, saved?.bodyBlocks)
            assertEquals(blocks, detail?.let { CommunityPost.from(it).bodyBlocks })
            assertTrue(submission.state.completed)
        }

    @Test fun paragraphsNormalizeNfcAndLineEndingsButKeepSingleNewlinesAndPlainText() {
        val body = "  \u1100\u1161\r\n한 줄\r\n\r\n\r\n\r\n <b>원문</b>  "
        assertEquals(listOf("가\n한 줄", "<b>원문</b>"), CommunityArticle.paragraphs(body))
        assertEquals("가\n한 줄\n\n<b>원문</b>", CommunityArticle.plainBody(body))
        assertFalse(CommunityArticle.canCompose(" \n\n "))
        assertTrue(CommunityArticle.canCompose(List(64) { "문단" }.joinToString("\n\n")))
        assertFalse(CommunityArticle.canCompose(List(65) { "문단" }.joinToString("\n\n")))
        assertFalse(CommunityArticle.canCompose("x".repeat(5001)))
    }

    @Test fun placementUsesCurrentIndexAndClampsAfterDeletingPhotosOrParagraphs() {
        val removed = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val placements =
            mapOf(
                removed to CommunityPhotoPlacement(0, credit = "x".repeat(1001)),
                first to CommunityPhotoPlacement(2, caption = "바다", aiGenerated = true),
                second to CommunityPhotoPlacement(1, credit = "출처"),
            )
        val retained = CommunityArticle.retained(placements, listOf(first, second))
        assertEquals(setOf(first, second), retained.keys)
        val blocks = CommunityArticle.blocks("앞\n\n뒤", listOf(first, second), retained)
        assertEquals(listOf(1, 0), blocks.mapNotNull { it.photoIndex })
        assertEquals("바다", blocks.last().caption)
        assertEquals(true, blocks.last().aiGenerated)
        val shorter = CommunityArticle.blocks("앞", listOf(first), retained)
        assertEquals(0, shorter.last().photoIndex)
        assertEquals("바다", shorter.last().caption)
        assertTrue(CommunityArticle.validBlocks("앞", 1, shorter))
    }

    @Test fun placementBeforeTheFirstParagraphAndDefaultsAreDeterministic() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val blocks = CommunityArticle.blocks("앞\n\n뒤", listOf(first, second), mapOf(first to CommunityPhotoPlacement(-4, caption = "   ")))
        assertEquals(0, blocks.first().photoIndex)
        assertEquals(null, blocks.first().caption)
        assertEquals(1, blocks.last().photoIndex)
        assertEquals(null, blocks.last().aiGenerated)
        assertTrue(CommunityArticle.validBlocks("앞\n\n뒤", 2, blocks))
    }

    @Test fun metadataUsesNormalizedUtf16Limits() {
        val valid = CommunityPhotoPlacement(1, "😀".repeat(250), "가".repeat(1000))
        assertEquals(500, valid.captionLength)
        assertTrue(valid.metadataValid)
        assertFalse(valid.copy(caption = valid.caption + "x").metadataValid)
        assertFalse(valid.copy(credit = valid.credit + "x").metadataValid)
        assertTrue(CommunityPhotoPlacement(0, " \u1100\u1161 ").metadataValid)
        assertEquals(1, CommunityPhotoPlacement(0, " \u1100\u1161 ").captionLength)
    }

    @Test fun oldPostsArrangeEveryPhotoOnceWithoutModifyingTheOriginal() {
        val post = CommunityPost(title = "옛 글", body = " 앞\n\n뒤 ", photos = listOf("a.jpg", "b.jpg", "c.jpg"))
        val blocks = CommunityArticle.legacy(post.body, 3)
        assertEquals(listOf(0, 1, 2), blocks.mapNotNull { it.photoIndex })
        assertEquals(listOf("앞", "뒤"), blocks.mapNotNull { it.text })
        assertEquals(" 앞\n\n뒤 ", post.body)
        assertEquals(null, post.bodyBlocks)
        assertEquals(listOf(0, 1), CommunityArticle.legacy("", 2).mapNotNull { it.photoIndex })
        assertEquals(emptyList<TripPostBlock>(), CommunityArticle.legacy("", 0))
    }

    @Test fun blockBoundaryRejectsMissingDuplicateOutOfRangeAndCrossKindFields() {
        val text = TripPostBlock(TripPostBlockKind.text, text = "본문")
        val photo = TripPostBlock(TripPostBlockKind.photo, photoIndex = 0)
        assertTrue(CommunityArticle.validBlocks("본문", 1, listOf(text, photo)))
        assertFalse(CommunityArticle.validBlocks("다른 본문", 1, listOf(text, photo)))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text)))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text, photo, photo)))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text, photo.copy(photoIndex = 1))))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text.copy(credit = "출처"), photo)))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text, photo.copy(text = "본문"))))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text, photo.copy(caption = "x".repeat(501)))))
        assertFalse(CommunityArticle.validBlocks("본문", 1, listOf(text, photo.copy(credit = "x".repeat(1001)))))
        assertFalse(CommunityArticle.validBlocks("본문", 0, emptyList()))
        assertTrue(CommunityArticle.validBlocks("본문", 0, null))
    }

    @Test fun uncertainPostMustMatchPhotoPlacementAndMetadata() {
        val blocks =
            listOf(
                TripPostBlock(TripPostBlockKind.text, text = "첫 문단"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "바다", credit = "출처", aiGenerated = true),
            )
        val input = TripPostCreate("제목", "첫 문단", listOf("uploads/tmp/photo.jpg"), bodyBlocks = blocks)
        val detail =
            TripPostDetail(
                id = 1,
                title = "제목",
                body = "첫 문단",
                createdAt = OffsetDateTime.now(),
                isMine = true,
                photoUrls = listOf(URI("https://photos.example/posts/photo.jpg")),
                bodyBlocks = blocks,
            )
        assertTrue(CommunityRules.matches(detail, input, null))
        assertFalse(CommunityRules.matches(detail.copy(bodyBlocks = blocks.reversed()), input, null))
        assertFalse(
            CommunityRules.matches(
                detail.copy(
                    bodyBlocks =
                        blocks.map {
                            if (it.kind ==
                                TripPostBlockKind.photo
                            ) {
                                it.copy(credit = "다른 출처")
                            } else {
                                it
                            }
                        },
                ),
                input,
                null,
            ),
        )
        assertFalse(CommunityRules.matches(detail.copy(bodyBlocks = null), input, null))
        assertTrue(CommunityRules.matches(detail.copy(bodyBlocks = null), input.copy(bodyBlocks = null), null))
    }
}
