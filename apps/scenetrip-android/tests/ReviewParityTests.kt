package com.mz2az.scenetrip

import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.data.RelativeTime
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.reviews.MyReviewRules
import com.mz2az.scenetrip.reviews.PhotoUploadFailure
import com.mz2az.scenetrip.reviews.PhotoUploadRules
import com.mz2az.scenetrip.reviews.ReviewListPhase
import com.mz2az.scenetrip.reviews.ReviewListStore
import com.mz2az.scenetrip.reviews.ReviewPhotoRules
import com.mz2az.scenetrip.reviews.ReviewPhotoState
import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.routetab.PoiLabel
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewAuthor
import com.mz2az.scenetrip.sceneapi.client.model.ReviewList
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTarget
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime

/** 후기 시트·작성·내 후기 화면의 규칙 (MZ2AZ-476). iOS `ReviewRules`·`ReviewPhotoRules`·`MyReviewRules` 와 같은 값이다. */
class ReviewParityTests {
    private val at = OffsetDateTime.parse("2026-10-12T00:00:00Z")

    private fun review(
        id: Long,
        rating: Int = 4,
        body: String? = "좋았어요",
        photoKeys: List<String> = emptyList(),
        updated: OffsetDateTime = at,
    ) = Review(
        id,
        rating,
        photoKeys.map { ReviewPhoto(it, URI("https://x.example/$it")) },
        at,
        updated,
        false,
        body,
        ReviewAuthor("글쓴이$id"),
    )

    private fun summary(
        count: Int,
        average: Double?,
        distribution: List<Int>,
    ) = RatingSummary(count, distribution, average)

    // 별점 줄과 요약

    @Test
    fun ratingLineHidesWhenTheServerGaveNoRatingAndSaysSoWhenThereAreNoReviews() {
        assertEquals(ReviewRules.Line.Hidden, ReviewRules.line(null))
        assertEquals(ReviewRules.Line.Empty, ReviewRules.line(summary(0, null, listOf(0, 0, 0, 0, 0))))
        assertEquals(ReviewRules.Line.Rated("4.6", 128), ReviewRules.line(summary(128, 4.6, listOf(1, 2, 3, 40, 82))))
        assertEquals(ReviewRules.Line.Rated("-", 3), ReviewRules.line(summary(3, null, listOf(0, 0, 3, 0, 0))))
    }

    @Test
    fun countAndSpokenTextFollowIosWithSingularEnglishRow() {
        assertEquals("리뷰 128", ReviewRules.countText(128))
        assertEquals("리뷰 1", ReviewRules.countText(1))
        assertEquals("%d review", englishOf("리뷰 %d|하나"))
        assertEquals("별점 4.6점, 리뷰 128", ReviewRules.spoken(summary(128, 4.6, listOf(1, 2, 3, 40, 82))))
        assertEquals("아직 리뷰가 없어요", ReviewRules.spoken(summary(0, null, listOf(0, 0, 0, 0, 0))))
        assertEquals("4", ReviewRules.starValue(4.0))
        assertEquals("4.6", ReviewRules.starValue(4.6))
    }

    @Test
    fun starsRoundToTheNearestHalf() {
        val full = ReviewRules.Star.FULL
        val half = ReviewRules.Star.HALF
        val empty = ReviewRules.Star.EMPTY
        assertEquals(listOf(full, full, full, full, half), ReviewRules.stars(4.6))
        assertEquals(listOf(full, full, full, full, full), ReviewRules.stars(4.8))
        assertEquals(listOf(full, full, full, full, empty), ReviewRules.stars(4.2))
        assertEquals(List(5) { empty }, ReviewRules.stars(0.0))
        assertEquals(listOf(full, full, full, full, full), ReviewRules.stars(5.0))
    }

    @Test
    fun barsRunFromFiveToOneAndNeverDivideByZero() {
        val bars = ReviewRules.bars(listOf(1, 0, 0, 3, 6))
        assertEquals(listOf(5, 4, 3, 2, 1), bars.map { it.score })
        assertEquals(listOf(6, 3, 0, 0, 1), bars.map { it.count })
        assertEquals(0.6f, bars[0].share, 0.0001f)
        assertEquals(List(5) { 0f }, ReviewRules.bars(listOf(1, 2)).map { it.share }, "길이가 다르면 빈 막대")
        assertEquals(List(5) { 0f }, ReviewRules.bars(listOf(0, 0, 0, 0, 0)).map { it.share })
    }

    @Test
    fun editedNeedsAFullSecondAndWithdrawnAuthorsHaveAName() {
        assertFalse(ReviewRules.edited(at, at.plusNanos(900_000_000)))
        assertTrue(ReviewRules.edited(at, at.plusSeconds(1)))
        assertEquals("탈퇴한 사용자", ReviewRules.authorName(null))
        assertEquals("닉네임", ReviewRules.authorName(ReviewAuthor("닉네임")))
    }

    @Test
    fun visitorAndAllPhotoTitles() {
        assertEquals("방문자 사진", ReviewRules.visitorTitle(null))
        assertEquals("방문자 사진 10", ReviewRules.visitorTitle(10))
        assertEquals("사진 19", ReviewRules.allTitle(19))
        assertEquals("%d photo", englishOf("사진 %d|전체 하나"))
        assertEquals("%d photos", englishOf("사진 %d|전체"))
        assertNotNull(englishOf("방문자 사진 %d|하나"))
        assertNotNull(englishOf("방문자 사진|모음"))
    }

    // 저장

    @Test
    fun canSaveNeedsStarsAndABodyWithinTheServersUtf16Count() {
        assertFalse(ReviewRules.canSave(0, ""))
        assertTrue(ReviewRules.canSave(5, "   "))
        assertTrue(ReviewRules.canSave(3, "a".repeat(2000)))
        assertFalse(ReviewRules.canSave(3, "a".repeat(2001)))
        // 이모지는 UTF-16 으로 둘 — 1,001개면 서버는 2,002 로 보고 거절한다.
        assertEquals(2002, ReviewRules.bodyLength("😀".repeat(1001)))
        assertFalse(ReviewRules.canSave(3, "😀".repeat(1001)))
    }

    @Test
    fun changedComparesRatingAndNormalizedBody() {
        val saved = review(1, rating = 4, body = "좋았어요")
        assertTrue(ReviewRules.changed(null, 0, ""))
        assertFalse(ReviewRules.changed(saved, 4, "  좋았어요  "))
        assertTrue(ReviewRules.changed(saved, 5, "좋았어요"))
        assertTrue(ReviewRules.changed(saved, 4, ""))
    }

    @Test
    fun landedComparesPhotoFilesByNameInOrder() {
        val saved = review(1, rating = 4, body = "좋았어요", photoKeys = listOf("reviews/a.jpg", "reviews/b.jpg"))
        assertTrue(ReviewRules.landed(saved, 4, "좋았어요", listOf("uploads/tmp/a.jpg", "uploads/tmp/b.jpg")))
        // 순서가 다르거나 장수만 같고 파일이 다르면 저장된 것이 아니다.
        assertFalse(ReviewRules.landed(saved, 4, "좋았어요", listOf("uploads/tmp/b.jpg", "uploads/tmp/a.jpg")))
        assertFalse(ReviewRules.landed(saved, 4, "좋았어요", listOf("uploads/tmp/a.jpg", "uploads/tmp/c.jpg")))
        assertFalse(ReviewRules.landed(saved, 5, "좋았어요", listOf("uploads/tmp/a.jpg", "uploads/tmp/b.jpg")))
        assertFalse(ReviewRules.landed(null, 4, null, emptyList()))
    }

    @Test
    fun failureTextIsPickedByCodeAndFallsBackToTheGenericSentence() {
        val generic = "저장하지 못했어요. 잠시 뒤 다시 해 주세요"
        assertEquals("로그인이 풀렸어요. 다시 로그인해 주세요", ReviewRules.failureText(401, "ACCESS_TOKEN_INVALID"))
        assertEquals("로그인한 뒤에 쓸 수 있어요", ReviewRules.failureText(401, "SIGN_IN_REQUIRED"))
        assertEquals("사진을 다시 올려 주세요", ReviewRules.failureText(400, "REVIEW_PHOTO_INVALID"))
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요", ReviewRules.failureText(429, "RATE_LIMITED"))
        assertEquals(generic, ReviewRules.failureText(500, null))
        assertEquals(generic, ReviewRules.failureText(null, null))
        assertNotNull(englishOf(generic))
        assertNotNull(englishOf("로그인한 뒤에 쓸 수 있어요"))
        assertNotNull(englishOf("사진을 다시 올려 주세요"))
    }

    // 사진 줄

    private val uploading = ReviewPhotoState.Uploading
    private val waiting = ReviewPhotoState.Waiting

    @Test
    fun photoKeysKeepScreenOrderAndOnlyAttachedOrUploaded() {
        val states =
            listOf(
                ReviewPhotoState.Attached("a"),
                waiting,
                ReviewPhotoState.Uploaded("b"),
                ReviewPhotoState.Failed(PhotoUploadFailure.NETWORK),
                uploading,
            )
        assertEquals(listOf("a", "b"), ReviewPhotoRules.keys(states))
        assertTrue(ReviewPhotoRules.busy(states))
        assertEquals(1, ReviewPhotoRules.failedCount(states))
        assertFalse(ReviewPhotoRules.canSave(states))
        assertTrue(ReviewPhotoRules.canSave(listOf(ReviewPhotoState.Attached("a"), ReviewPhotoState.Uploaded("b"))))
        assertFalse(ReviewPhotoRules.canSave(List(11) { ReviewPhotoState.Uploaded("k$it") }))
    }

    @Test
    fun changedIsFalseOnlyWhenNothingWasTouched() {
        val existing = listOf("a", "b")
        assertFalse(ReviewPhotoRules.changed(existing, listOf(ReviewPhotoState.Attached("a"), ReviewPhotoState.Attached("b"))))
        assertTrue(ReviewPhotoRules.changed(existing, listOf(ReviewPhotoState.Attached("b"), ReviewPhotoState.Attached("a"))))
        assertTrue(ReviewPhotoRules.changed(existing, listOf(ReviewPhotoState.Attached("a"))))
        assertTrue(ReviewPhotoRules.changed(existing, listOf(ReviewPhotoState.Attached("a"), ReviewPhotoState.Attached("b"), waiting)))
    }

    @Test
    fun theQueueResumesOnlyWhenNothingThatStopsItRemains() {
        val stopped = ReviewPhotoState.Failed(PhotoUploadFailure.NETWORK)
        val ownProblem = ReviewPhotoState.Failed(PhotoUploadFailure.TOO_LARGE)
        assertFalse(ReviewPhotoRules.shouldResume(listOf(stopped, waiting)), "네트워크 실패가 남아 있으면 잇지 않는다")
        assertTrue(ReviewPhotoRules.shouldResume(listOf(ownProblem, waiting)), "사진 자체의 문제는 그 칸만의 일이다")
        assertFalse(ReviewPhotoRules.shouldResume(listOf(ReviewPhotoState.Uploaded("a"))), "기다리는 사진이 없다")
        assertTrue(ReviewPhotoRules.shouldResume(listOf(waiting)))
    }

    @Test
    fun progressCountsFailuresAsFinishedAndNeverExceedsTheBatch() {
        assertEquals(1 to 3, ReviewPhotoRules.progress(listOf(uploading, waiting, waiting)))
        assertEquals(2 to 3, ReviewPhotoRules.progress(listOf(ReviewPhotoState.Uploaded("a"), uploading, waiting)))
        assertEquals(
            3 to 3,
            ReviewPhotoRules.progress(
                listOf(ReviewPhotoState.Uploaded("a"), ReviewPhotoState.Failed(PhotoUploadFailure.TOO_LARGE), uploading),
            ),
        )
        assertEquals(2 to 2, ReviewPhotoRules.progress(listOf(ReviewPhotoState.Uploaded("a"), ReviewPhotoState.Uploaded("b"))))
    }

    @Test
    fun remainingAndLimit() {
        assertEquals(10, ReviewPhotoRules.remaining(0))
        assertEquals(0, ReviewPhotoRules.remaining(12))
        assertEquals(3, ReviewPhotoRules.remaining(5, limit = 8))
    }

    @Test
    fun uploadFailuresAreSplitByCodeThenStatusAndHaveIosSentences() {
        assertEquals(PhotoUploadFailure.TOO_LARGE, PhotoUploadRules.failure(413, "UPLOAD_TOO_LARGE"))
        assertEquals(PhotoUploadFailure.UNSUPPORTED_TYPE, PhotoUploadRules.failure(415, "UPLOAD_TYPE_UNSUPPORTED"))
        assertEquals(PhotoUploadFailure.UNAVAILABLE, PhotoUploadRules.failure(503, "UPLOAD_UNAVAILABLE"))
        assertEquals(PhotoUploadFailure.RATE_LIMITED, PhotoUploadRules.failure(429, "RATE_LIMITED"))
        assertEquals(PhotoUploadFailure.RATE_LIMITED, PhotoUploadRules.failure(429, null))
        assertEquals(PhotoUploadFailure.SIGNED_OUT, PhotoUploadRules.failure(401, null))
        assertEquals(PhotoUploadFailure.NETWORK, PhotoUploadRules.failure(500, null))
        assertEquals(PhotoUploadFailure.NETWORK, PhotoUploadRules.failure(null, null))
        val retryless = setOf(PhotoUploadFailure.UNREADABLE, PhotoUploadFailure.TOO_LARGE, PhotoUploadFailure.UNSUPPORTED_TYPE)
        for (failure in PhotoUploadFailure.entries) assertEquals(failure !in retryless, PhotoUploadRules.retryable(failure), "$failure")
        val stops =
            setOf(
                PhotoUploadFailure.RATE_LIMITED,
                PhotoUploadFailure.UNAVAILABLE,
                PhotoUploadFailure.SIGNED_OUT,
                PhotoUploadFailure.STORAGE,
                PhotoUploadFailure.NETWORK,
            )
        for (failure in PhotoUploadFailure.entries) assertEquals(failure in stops, PhotoUploadRules.stopsQueue(failure), "$failure")
        val texts =
            mapOf(
                PhotoUploadFailure.UNREADABLE to "사진을 읽지 못했어요. 다른 사진을 골라 주세요",
                PhotoUploadFailure.TOO_LARGE to "사진이 너무 커요(10MB 까지). 다른 사진을 골라 주세요",
                PhotoUploadFailure.UNSUPPORTED_TYPE to "올릴 수 없는 사진 형식이에요",
                PhotoUploadFailure.UNAVAILABLE to "지금은 사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요",
                PhotoUploadFailure.SIGNED_OUT to "로그인이 풀렸어요. 다시 로그인해 주세요",
                PhotoUploadFailure.EXPIRED to "사진을 다시 올려 주세요",
                PhotoUploadFailure.NETWORK to "사진을 올리지 못했어요. 다시 시도해 주세요",
            )
        for ((failure, text) in texts) {
            assertEquals(text, PhotoUploadRules.text(failure))
            assertNotNull(englishOf(text), text)
        }
    }

    // 목록 상태

    private fun page(
        items: List<Review>,
        total: Int,
    ) = ReviewList(items, total, RatingSummary(items.size, listOf(0, 0, 0, items.size, 0), 4.0))

    @Test
    fun theListStartsInTheLoadingPhaseSoThereIsNoEmptyFlash() =
        runBlocking {
            val store = ReviewListStore({ _, _ -> page(listOf(review(1)), 1) }, { 0 })
            assertEquals(ReviewListPhase.LOADING, store.ui.value.phase)
            store.load(ReviewSort.recent, reset = true)
            assertEquals(ReviewListPhase.LOADED, store.ui.value.phase)
        }

    @Test
    fun aFailedFirstPageIsAPhaseButAFailedNextPageOnlyChangesTheFooter() =
        runBlocking {
            var calls = 0
            val store =
                ReviewListStore(
                    { _, offset ->
                        calls++
                        when {
                            calls == 1 -> throw IOException("끊김")
                            offset == 0 -> page(listOf(review(1)), 3)
                            else -> throw IOException("끊김")
                        }
                    },
                    { 0 },
                )
            store.load(ReviewSort.recent, reset = true)
            assertEquals(ReviewListPhase.FAILED, store.ui.value.phase)
            store.prepareRetry()
            assertEquals(ReviewListPhase.LOADING, store.ui.value.phase)
            store.load(ReviewSort.recent, reset = true)
            assertEquals(ReviewListPhase.LOADED, store.ui.value.phase)
            store.load(ReviewSort.recent)
            assertEquals(ReviewListPhase.LOADED, store.ui.value.phase, "다음 쪽 실패로 목록을 지우지 않는다")
            assertTrue(store.ui.value.moreFailed)
            assertEquals(
                listOf(1L),
                store.ui.value.reviews
                    .map { it.id },
            )
            store.retryMore()
            assertFalse(store.ui.value.moreFailed)
        }

    @Test
    fun changingTheSortDropsInFlightRequestsAndShowsTheSpinner() =
        runBlocking {
            val store = ReviewListStore({ _, _ -> page(listOf(review(1), review(2)), 5) }, { 0 })
            store.load(ReviewSort.recent, reset = true)
            store.prepareSortChange()
            assertEquals(ReviewListPhase.LOADING, store.ui.value.phase)
            assertEquals(2, store.ui.value.total, "옛 목록 끝에 새 정렬의 쪽이 붙지 않게 받은 만큼으로 접는다")
        }

    // 내 후기

    private fun target(
        type: ReviewTargetType,
        name: String = "스타벅스",
    ) = ReviewTarget(type, 7, name)

    @Test
    fun poiNamesAreLookedUpOnlyOnEnglishScreens() {
        val poi = target(ReviewTargetType.poi)
        val place = target(ReviewTargetType.place)
        assertTrue(MyReviewRules.needsLookup(poi, korean = false))
        assertFalse(MyReviewRules.needsLookup(poi, korean = true))
        assertFalse(MyReviewRules.needsLookup(place, korean = false))
        val label = PoiLabel("Starbucks", "스타벅스", null, null)
        assertEquals("Starbucks" to "스타벅스", MyReviewRules.name(poi, label, korean = false))
        assertEquals("스타벅스" to null, MyReviewRules.name(poi, label, korean = true), "한국어로 바꾸면 원본으로 돌아간다")
        assertEquals("스타벅스" to null, MyReviewRules.name(poi, null, korean = false))
        assertEquals("스타벅스" to null, MyReviewRules.name(place, label, korean = false))
        assertTrue(MyReviewRules.subject(poi).isPoi)
        assertFalse(MyReviewRules.subject(place).isPoi)
        assertEquals("편의시설", MyReviewRules.kindName(poi))
        assertEquals("촬영지", MyReviewRules.kindName(place))
        assertNotNull(englishOf("편의시설"))
        assertEquals("My reviews", englishOf("내 리뷰|목록"))
    }

    @Test
    fun poiHeadingsSplitBackIntoTitleAndReading() {
        assertEquals("Starbucks" to "스타벅스 수원조원DT", PoiLabel.lines("Starbucks · 스타벅스 수원조원DT"))
        assertEquals("스타벅스" to null, PoiLabel.lines("스타벅스"))
    }

    // 상대 시각과 분석 이벤트

    @Test
    fun relativeTimePicksTheLargestUnitThatFits() {
        val now = OffsetDateTime.parse("2026-10-24T12:00:00+09:00")

        fun phrase(minus: Long) = RelativeTime.phrase(now.minusSeconds(minus), now)
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.NOW, 0), phrase(30))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.MINUTE, 5), phrase(5 * 60 + 10))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.HOUR, 3), phrase(3 * 3600 + 5))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.DAY, 1), RelativeTime.phrase(now.minusDays(1), now))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.DAY, 3), RelativeTime.phrase(now.minusDays(3), now))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.WEEK, 2), RelativeTime.phrase(now.minusDays(15), now))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.MONTH, 3), RelativeTime.phrase(now.minusDays(100), now))
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.YEAR, 2), RelativeTime.phrase(now.minusDays(800), now))
        // 서버 시계가 조금 앞서도 「지금」 으로 접는다.
        assertEquals(RelativeTime.Phrase(RelativeTime.Unit.NOW, 0), RelativeTime.phrase(now.plusSeconds(20), now))
    }

    @Test
    fun reviewAnalyticsEventsMatchTheDocumentedNamesAndParameters() {
        assertEquals("view_reviews", AppEvent.ViewReviews("poi").name)
        assertEquals(mapOf("target_type" to "poi"), AppEvent.ViewReviews("poi").parameters)
        val written = AppEvent.WriteReview("place", rating = 4, photoCount = 2, hasBody = true, edited = false)
        assertEquals("write_review", written.name)
        assertEquals(mapOf("target_type" to "place", "rating" to 4, "photo_count" to 2, "has_body" to 1, "edited" to 0), written.parameters)
        assertEquals("delete_review", AppEvent.DeleteReview("place").name)
        assertNull(AppEvent.DeleteReview("place").parameters["body"], "글은 보내지 않는다")
        val photos = AppEvent.ViewPhotos("place", "detail")
        assertEquals("view_photos", photos.name)
        assertEquals(mapOf("target_type" to "place", "entry" to "detail"), photos.parameters)
        assertEquals("reviews", AppEvent.ViewPhotos("poi", "reviews").parameters["entry"])
        assertEquals("card", AppEvent.ViewPhotos("poi", "card").parameters["entry"])
    }
}
