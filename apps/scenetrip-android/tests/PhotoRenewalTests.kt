package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityPhotoRefresh
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.PhotoPages
import com.mz2az.scenetrip.data.PhotoRenewal
import com.mz2az.scenetrip.data.PhotoRenewalRules
import com.mz2az.scenetrip.reviews.ReviewListStore
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewAuthor
import com.mz2az.scenetrip.sceneapi.client.model.ReviewList
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime

/** MZ2AZ-477: 만료된 서명 사진 주소 다시 받기. iOS `PhotoRenewalTests` 와 같은 규칙을 같은 순서로 검사한다. */
class PhotoRenewalTests {
    private data class Row(
        val id: Long,
        val url: String,
    )

    // MARK: 쪽으로 나뉜 목록 다시 받기

    @Test
    fun refetchKeepsSecondPageAndItsNewPhotoUrl() =
        runBlocking {
            val offsets = mutableListOf<Int>()
            val page =
                PhotoPages.refetch(40, { it.id }) { offset ->
                    offsets += offset
                    PhotoPages.Page((offset until offset + 20).map { Row(it + 1L, "https://img.example/${it + 1}?sig=new") }, 40)
                }
            assertEquals(listOf(0, 20), offsets)
            assertEquals(40, page.items.size)
            assertEquals(21L, page.items[20].id)
            assertTrue(page.items[20].url.endsWith("sig=new"))
        }

    @Test
    fun partialRefetchFailureDoesNotReturnATruncatedList() {
        assertThrows(IOException::class.java) {
            runBlocking {
                PhotoPages.refetch(40, { it: Row -> it.id }) { offset ->
                    if (offset > 0) throw IOException("offline")
                    PhotoPages.Page((1L..20L).map { Row(it, "new") }, 40)
                }
            }
        }
    }

    @Test
    fun refetchStopsOnAnEmptyPageAndDropsRepeatedRows() =
        runBlocking {
            var calls = 0
            val page =
                PhotoPages.refetch(60, { it: Row -> it.id }) { offset ->
                    calls++
                    if (offset == 0) {
                        PhotoPages.Page(listOf(Row(1, "a"), Row(2, "b"), Row(2, "b")), 100)
                    } else {
                        PhotoPages.Page(emptyList(), 100)
                    }
                }
            assertEquals(2, calls)
            assertEquals(listOf(1L, 2L), page.items.map { it.id })
        }

    // MARK: 갱신 간격과 합류

    @Test
    fun automaticRenewalIsLimitedButManualRetryIsAllowed() =
        runBlocking {
            val renewal = PhotoRenewal(this, now = { 100_000L })
            var calls = 0
            val first = renewal.run(false) { calls++ }
            val automatic = renewal.run(false) { calls++ }
            val manual = renewal.run(true) { calls++ }
            assertTrue(first)
            assertFalse(automatic)
            assertTrue(manual)
            assertEquals(2, calls)
        }

    @Test
    fun automaticRenewalComesBackAfterThirtySeconds() =
        runBlocking {
            var clock = 0L
            val renewal = PhotoRenewal(this, now = { clock })
            var calls = 0
            assertTrue(renewal.run(false) { calls++ })
            clock = 29_999
            assertFalse(renewal.run(false) { calls++ })
            clock = 30_000
            assertTrue(renewal.run(false) { calls++ })
            assertEquals(2, calls)
        }

    @Test
    fun aFailedRenewalReportsFalseAndStillCountsForTheGap() =
        runBlocking {
            val renewal = PhotoRenewal(this, now = { 5L })
            assertFalse(renewal.run(false) { throw IOException("offline") })
            assertFalse(renewal.run(false) { error("간격 안이라 다시 나가지 않는다") })
        }

    @Test
    fun simultaneousFailuresShareOneRenewal() =
        runBlocking {
            val renewal = PhotoRenewal(this)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val first =
                async {
                    renewal.run(false) {
                        calls++
                        entered.complete(Unit)
                        finish.await()
                    }
                }
            entered.await()
            val second = async { renewal.run(true) { calls++ } }
            yield()
            finish.complete(Unit)
            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(1, calls)
        }

    @Test
    fun rulesMatchIos() {
        assertTrue(PhotoRenewalRules.shouldRenew(forced = true, last = 100, now = 101))
        assertTrue(PhotoRenewalRules.shouldRenew(forced = false, last = null, now = 0))
        assertFalse(PhotoRenewalRules.shouldRenew(forced = false, last = 100, now = 100 + 29_999))
        assertTrue(PhotoRenewalRules.shouldRenew(forced = false, last = 100, now = 100 + 30_000))
        // 서명된 주소일 때만, 이 사진으로는 한 번만.
        assertTrue(PhotoRenewalRules.renewsAfterFailure(signed = true, alreadyRenewed = false))
        assertFalse(PhotoRenewalRules.renewsAfterFailure(signed = true, alreadyRenewed = true))
        assertFalse(PhotoRenewalRules.renewsAfterFailure(signed = false, alreadyRenewed = false))
        assertEquals(
            "https://img.example/a/b.jpg",
            PhotoRenewalRules.unsigned("https://img.example/a/b.jpg?X-Amz-Signature=old&X-Amz-Expires=3600#frag"),
        )
    }

    // MARK: 여행후기 사진

    @Test
    fun communityRefreshReceivesNewUrlsAndMatchingBodyBlocks() =
        runBlocking {
            val old = post("https://img.example/a?sig=old")
            val fresh =
                post("https://img.example/a?sig=new")
                    .copy(
                        bodyBlocks =
                            listOf(
                                TripPostBlock(
                                    TripPostBlockKind.photo,
                                    photoIndex = 0,
                                    caption = "사진 설명",
                                    credit = "출처",
                                    aiGenerated = true,
                                ),
                            ),
                    )
            var calls = 0
            val refresh =
                CommunityPhotoRefresh(old, { 1 }, {
                    calls++
                    fresh
                }, PhotoRenewal(this))
            assertTrue(refresh.renew(true))
            assertEquals(1, calls)
            assertEquals(listOf("https://img.example/a?sig=new"), refresh.post?.remotePhotos)
            assertEquals(
                "출처",
                refresh.post
                    ?.bodyBlocks
                    ?.first()
                    ?.credit,
            )
            assertEquals(
                true,
                refresh.post
                    ?.bodyBlocks
                    ?.first()
                    ?.aiGenerated,
            )
        }

    @Test
    fun localLegacyPhotosDoNotAskTheServerForNewUrls() =
        runBlocking {
            val local = post("unused").copy(serverId = null, remotePhotos = emptyList(), photos = listOf("original-photo.jpg"))
            val refresh = CommunityPhotoRefresh(local, { 1 }, { error("기기 옛 사진은 서버에서 다시 받지 않습니다") }, PhotoRenewal(this))
            assertFalse(refresh.renew(true))
        }

    @Test
    fun lateCommunityPhotoResponseCannotCrossAccounts() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<CommunityPost>()
            var epoch = 1
            val refresh =
                CommunityPhotoRefresh(post("https://img.example/a?sig=old"), { epoch }, {
                    entered.complete(Unit)
                    finish.await()
                }, PhotoRenewal(this))
            val request = async { refresh.renew(true) }
            entered.await()
            epoch += 1
            finish.complete(post("https://img.example/a?sig=new"))
            assertFalse(request.await())
            assertNull(refresh.post)
        }

    @Test
    fun cancelledRenewalCannotReplaceANewlyOpenedPost() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<CommunityPost>()
            val refresh =
                CommunityPhotoRefresh(post("https://img.example/a?sig=old"), { 1 }, {
                    entered.complete(Unit)
                    finish.await()
                }, PhotoRenewal(this))
            val request = async { refresh.renew(true) }
            entered.await()
            val next = post("https://img.example/b?sig=current", id = "server:429").copy(serverId = 429)
            refresh.show(next)
            finish.complete(post("https://img.example/a?sig=new"))
            assertFalse(request.await())
            assertEquals(429L, refresh.post?.serverId)
            assertEquals(next.remotePhotos, refresh.post?.remotePhotos)
        }

    @Test
    fun aDifferentPostInTheAnswerIsRejected() =
        runBlocking {
            val refresh =
                CommunityPhotoRefresh(
                    post("https://img.example/a?sig=old"),
                    { 1 },
                    { post("x").copy(serverId = 7, id = "server:7") },
                    PhotoRenewal(this),
                )
            assertFalse(refresh.renew(true))
            assertNull(refresh.post)
        }

    // MARK: 리뷰 목록

    @Test
    fun reviewListSwapsInNewPhotoUrlsKeepingWhatWasRead() =
        runBlocking {
            var round = 0
            val asked = mutableListOf<Int>()
            val listing =
                ReviewListStore({ _, offset ->
                    asked += offset
                    val rows = (offset until minOf(offset + 20, 30)).map { review(it + 1L, "https://img.example/${it + 1}?sig=r$round") }
                    reviewList(rows, 30)
                }, { 0 }, this)
            listing.load(ReviewSort.recent, reset = true)
            listing.load(ReviewSort.recent)
            assertEquals(30, listing.ui.value.reviews.size)
            round = 1
            asked.clear()
            listing.renewPhotos(forced = true)
            assertEquals(listOf(0, 20), asked)
            val after = listing.ui.value
            assertEquals(30, after.reviews.size)
            assertEquals(30, after.offset)
            assertTrue(
                after.reviews.all {
                    it.photos
                        .single()
                        .url
                        .toString()
                        .endsWith("sig=r1")
                },
            )
            assertTrue(after.revision > 0)
        }

    @Test
    fun reviewListKeepsItsRowsWhenARenewalPageFails() =
        runBlocking {
            var broken = false
            val listing =
                ReviewListStore({ _, offset ->
                    if (broken && offset > 0) throw IOException("offline")
                    val rows =
                        (offset until minOf(offset + 20, 30)).map {
                            review(it + 1L, "https://img.example/${it + 1}?sig=${if (broken) "new" else "old"}")
                        }
                    reviewList(rows, 30)
                }, { 0 }, this)
            listing.load(ReviewSort.recent, reset = true)
            listing.load(ReviewSort.recent)
            broken = true
            listing.renewPhotos(forced = true)
            assertEquals(30, listing.ui.value.reviews.size)
            assertTrue(
                listing.ui.value.reviews
                    .all {
                        it.photos
                            .single()
                            .url
                            .toString()
                            .endsWith("sig=old")
                    },
            )
        }

    @Test
    fun reviewListRenewalIsDroppedWhenTheAccountChangesMeanwhile() =
        runBlocking {
            var epoch = 0
            var round = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val listing =
                ReviewListStore({ _, offset ->
                    if (round == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    reviewList(listOf(review(1, "https://img.example/1?sig=r$round")), 1)
                }, { epoch }, this)
            listing.load(ReviewSort.recent, reset = true)
            round = 1
            val renewing = async { listing.renewPhotos(forced = true) }
            entered.await()
            epoch += 1
            release.complete(Unit)
            renewing.await()
            assertTrue(
                listing.ui.value.reviews
                    .single()
                    .photos
                    .single()
                    .url
                    .toString()
                    .endsWith("sig=r0"),
            )
        }

    private fun post(
        url: String,
        id: String = "server:428",
    ) = CommunityPost(
        id = id,
        title = "시험 글",
        body = "본문",
        createdAt = 1,
        serverId = 428,
        serverIsMine = false,
        remotePhotos = listOf(url),
        detailLoaded = true,
    )

    private fun review(
        id: Long,
        url: String,
    ) = Review(id, 4, listOf(ReviewPhoto("k$id", URI(url))), AT, AT, false, "시험 리뷰", ReviewAuthor("글쓴이$id"))

    private fun reviewList(
        items: List<Review>,
        total: Int,
    ) = ReviewList(items, total, RatingSummary(items.size, listOf(0, 0, 0, items.size, 0), 4.0))

    private companion object {
        val AT: OffsetDateTime = OffsetDateTime.parse("2026-10-12T00:00:00Z")
    }
}
