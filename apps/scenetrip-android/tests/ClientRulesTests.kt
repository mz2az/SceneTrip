package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.RetryRules
import com.mz2az.scenetrip.data.UsageBlock
import com.mz2az.scenetrip.data.UsageQuota
import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.routetab.ExternalDirections
import com.mz2az.scenetrip.routetab.NaverMapLink
import com.mz2az.scenetrip.routetab.PoiLabel
import com.mz2az.scenetrip.routetab.TripArrival
import com.mz2az.scenetrip.routetab.courseStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientRulesTests {
    @Test
    fun ratingBarsUseDistributionAndHandleEmptyOrInvalidBuckets() {
        assertEquals(0f, ReviewRules.ratingFraction(listOf(0, 0, 0, 0, 0), 5))
        assertEquals(0.75f, ReviewRules.ratingFraction(listOf(0, -2, 1, 0, 3), 5))
        assertEquals(0f, ReviewRules.ratingFraction(listOf(0, -2, 1, 0, 3), 2))
        assertEquals(0f, ReviewRules.ratingFraction(emptyList(), 1))
    }

    @Test
    fun discardDetectionComparesOnlySavedFields() {
        val place =
            com.mz2az.scenetrip.sceneapi.client.model
                .PlaceSummary(id = 8, name = "장소", latitude = 37.5, longitude = 127.0)
        val stop =
            com.mz2az.scenetrip.routetab
                .RouteStop(place = place, serverItemId = 9)
        val initial =
            com.mz2az.scenetrip.routetab.RouteCourse(
                title = "여행",
                days =
                    listOf(
                        com.mz2az.scenetrip.routetab
                            .RouteDay(stops = listOf(stop)),
                    ),
            )
        val opened =
            com.mz2az.scenetrip.routetab.RouteBridge
                .outgoing(initial)
        val visited =
            initial.copy(
                title = " 여행 ",
                isRunning = true,
                days = listOf(initial.days.single().copy(stops = listOf(stop.copy(visited = true)))),
            )
        assertFalse(
            com.mz2az.scenetrip.routetab.RouteBridge
                .changed(opened, visited),
        )
        assertTrue(
            com.mz2az.scenetrip.routetab.RouteBridge
                .changed(opened, initial.copy(title = "새 여행")),
        )
        val longerStay = initial.copy(days = listOf(initial.days.single().copy(stops = listOf(stop.copy(stayMinutes = 60)))))
        assertTrue(
            com.mz2az.scenetrip.routetab.RouteBridge
                .changed(opened, longerStay),
        )
    }

    @Test
    fun poiStopRetainsSourceAndDwellWhileSaving() {
        val stop =
            com.mz2az.scenetrip.routetab.RouteStop(
                place =
                    com.mz2az.scenetrip.sceneapi.client.model
                        .PlaceSummary(id = 0, name = "식당", latitude = 37.5, longitude = 127.0),
                poiId = 42,
                serverItemId = 77,
                stayMinutes = 60,
                visited = true,
            )
        val body =
            com.mz2az.scenetrip.routetab.RouteBridge.replace(
                com.mz2az.scenetrip.routetab.RouteCourse(
                    title = "여행",
                    days =
                        listOf(
                            com.mz2az.scenetrip.routetab
                                .RouteDay(stops = listOf(stop)),
                        ),
                ),
            )
        val item =
            body.days
                .single()
                .items
                .single()
        assertEquals(42L, item.poiId)
        assertEquals(77L, item.id)
        assertEquals(60, item.dwellMinutes)
        assertNull(item.placeId)
        assertNull(item.customPin)
    }

    @Test
    fun savedPinCategoryAndLinkedPlaceStayDistinct() {
        val pin =
            com.mz2az.scenetrip.routetab.RouteStop(
                place =
                    com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary(
                        id = -1,
                        name = "숙소",
                        type = "lodging",
                        latitude = 37.5,
                        longitude = 127.0,
                    ),
                isPinned = true,
            )
        val body =
            com.mz2az.scenetrip.routetab.RouteBridge.replace(
                com.mz2az.scenetrip.routetab.RouteCourse(
                    title = "여행",
                    days =
                        listOf(
                            com.mz2az.scenetrip.routetab
                                .RouteDay(stops = listOf(pin)),
                        ),
                ),
            )
        assertEquals(
            com.mz2az.scenetrip.sceneapi.client.model.PinCategory.lodging,
            body.days
                .single()
                .items
                .single()
                .customPin!!
                .category,
        )
        val linked =
            com.mz2az.scenetrip.sceneapi.client.model.GuidePlace(
                id = 42,
                name = "촬영지 식당",
                category = "음식점",
                categoryGroup = com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup.food,
                latitude = 37.5,
                longitude = 127.0,
                source = com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource.poi,
                placeId = 8,
            )
        val courseStop = linked.courseStop()
        assertEquals(8L, courseStop.savablePlaceId)
        assertNull(courseStop.poiId)
    }

    @Test
    fun arrivalRequiresContinuousFiveMinuteStay() {
        val arrival = TripArrival()
        assertFalse(arrival.update(99.0, 1000))
        assertFalse(arrival.update(99.0, 300_999))
        assertTrue(arrival.update(100.0, 301_000))
        arrival.reset()
        assertFalse(arrival.update(50.0, 0))
        assertFalse(arrival.update(101.0, 200_000))
        assertFalse(arrival.update(50.0, 300_000))
        assertTrue(arrival.update(50.0, 600_000))
        arrival.reset()
        assertFalse(arrival.update(Double.NaN, 900_000))
    }

    @Test
    fun externalDirectionsEncodeNamesAndRejectInvalidCoordinates() {
        val end = ExternalDirections.Spot("서울/역, 동쪽", 37.556, 126.972)
        val start = ExternalDirections.Spot("여기", 37.5663, 126.9779)
        assertTrue(ExternalDirections.kakaoWeb(start, end)!!.contains("37.566300,126.977900"))
        assertFalse(ExternalDirections.kakaoWeb(null, end)!!.contains("서울/역"))
        assertTrue(ExternalDirections.naver(start, end)!!.startsWith("nmap://route/public?"))
        assertNull(ExternalDirections.naver(null, end.copy(latitude = 91.0)))
        assertNull(ExternalDirections.kakaoApp(start.copy(longitude = Double.NaN), end))
    }

    @Test
    fun reviewSaveRequiresValidRatingAndAllUploads() {
        assertFalse(ReviewRules.canSave(0, "", 0, 0))
        assertTrue(ReviewRules.canSave(5, "   ", 0, 0))
        assertTrue(ReviewRules.canSave(1, "a".repeat(2000), 10, 10))
        assertFalse(ReviewRules.canSave(6, "", 0, 0))
        assertFalse(ReviewRules.canSave(4, "a".repeat(2001), 0, 0))
        assertFalse(ReviewRules.canSave(4, "", 9, 10))
        assertFalse(ReviewRules.canSave(4, "", 11, 11))
        assertNull(ReviewRules.normalizedBody(" \n "))
        assertEquals("본문", ReviewRules.normalizedBody(" 본문 \n"))
    }

    @Test
    fun nicknameNormalizesAndRejectsReservedOrInvisibleNames() {
        assertEquals("가나", ReviewRules.nickname(" 가나 "))
        listOf("가나", "A_b2", "ㄱㄴ").forEach { assertNull(ReviewRules.nicknameProblem(it)) }
        listOf(
            "a",
            "a".repeat(17),
            "여행자123",
            "가 나",
            "가\u3164",
            "hello😀",
        ).forEach { assertTrue(ReviewRules.nicknameProblem(it) != null, it) }
    }

    @Test
    fun signedPhotoIdentitySurvivesUrlRotationAndPromotion() {
        assertEquals(
            ReviewRules.photoKey("https://store.example/reviews/x.jpg?sig=a", 12),
            ReviewRules.photoKey("https://store.example/reviews/x.jpg?sig=b", 12),
        )
        assertFalse(ReviewRules.photoKey("https://store.example/x.jpg", null) == ReviewRules.photoKey("https://store.example/y.jpg", null))
        assertTrue(ReviewRules.samePhotoFiles(listOf("tmp/a.jpg", "tmp/b.jpg"), listOf("reviews/a.jpg", "reviews/b.jpg")))
        assertFalse(ReviewRules.samePhotoFiles(listOf("a.jpg", "b.jpg"), listOf("b.jpg", "a.jpg")))
    }

    @Test
    fun translatedPoiKeepsOriginalNameAndUsesFallbacks() {
        val translated = PoiLabel.make("서울역", "Seoul Station", "Seoul-yeok", "역", "Station", "서울", "Seoul", false)
        assertEquals("Seoul Station · 서울역", translated.heading)
        assertEquals("Station", translated.category)
        val original = PoiLabel.make("서울역", "Seoul Station", null, "역", null, "서울", null, true)
        assertEquals("서울역", original.heading)
        assertEquals("서울", original.address)
    }

    @Test
    fun paidCallsAndOneTimeCredentialsNeverReplayWithoutServerSupport() {
        listOf("/guide/chat", "/guide/plan", "/navigation/next-leg", "/auth/refresh", "/auth/sign-out").forEach { path ->
            val policy = RetryRules.policy("POST", path)
            assertEquals(RetryRules.Lane.NEVER, policy.lane)
            assertNull(RetryRules.next(policy, RetryRules.History(), 503, null, null, false, 0))
        }
        assertEquals(RetryRules.Lane.NEVER, RetryRules.policy("DELETE", "/me").lane)
        assertEquals(RetryRules.Lane.WRITE, RetryRules.policy("DELETE", "/places/2/reviews/me").lane)
    }

    @Test
    fun unsafeWritesOnlyReplayBeforeConnectionOrAfterThrottleRejection() {
        val policy = RetryRules.policy("POST", "/courses")
        assertNull(RetryRules.next(policy, RetryRules.History(), null, null, null, false, 0))
        assertNull(RetryRules.next(policy, RetryRules.History(), 502, null, null, false, 0))
        assertTrue(RetryRules.next(policy, RetryRules.History(), null, null, null, true, 0) != null)
        assertTrue(RetryRules.next(policy, RetryRules.History(), 429, "RATE_LIMITED", 1, false, 0) != null)
        assertEquals(RetryRules.Lane.UNSAFE, RetryRules.policy("PUT", "/me/nickname").lane)
    }

    @Test
    fun readsBoundRetriesAndWaitOnlyOnce() {
        val policy = RetryRules.policy("GET", "/places/2")
        var history = RetryRules.History()
        repeat(3) { index ->
            val step = RetryRules.next(policy, history, 503, null, null, false, 0)!!
            assertEquals((1L shl index) * 1000, step.waitMillis)
            history = RetryRules.record(history, step, 503, false)
        }
        assertNull(RetryRules.next(policy, history, 503, null, null, false, 0))
        assertNull(RetryRules.next(policy, RetryRules.History(), 503, null, null, false, 25_000))
        val wait = RetryRules.next(policy, RetryRules.History(), 429, "RATE_LIMITED", 60, false, 0)!!
        assertNull(RetryRules.next(policy, RetryRules.record(RetryRules.History(), wait, 429, false), 429, "RATE_LIMITED", 1, false, 0))
        assertNull(RetryRules.next(policy, RetryRules.History(), 429, "GUIDE_LIMIT_REACHED", 1, false, 0))
        assertNull(RetryRules.next(policy, RetryRules.History(), 429, null, 61, false, 0))
        assertNull(RetryRules.next(policy, RetryRules.History(retried500 = true), 500, null, null, false, 0))
    }

    @Test
    fun keyedChatPollsWithinKeyLifetimeAndRequestBudget() {
        val policy = RetryRules.policy("POST", "/guide/chat", true)
        assertEquals(RetryRules.Lane.CHAT, policy.lane)
        val poll = RetryRules.next(policy, RetryRules.History(), 409, "IDEMPOTENCY_IN_PROGRESS", null, false, 0)!!
        assertTrue(poll.poll)
        assertEquals(0, RetryRules.record(RetryRules.History(), poll, 409, false).retries)
        assertNull(RetryRules.next(policy, RetryRules.History(), 409, "IDEMPOTENCY_IN_PROGRESS", null, false, 0, 52_000))
        assertNull(RetryRules.next(policy, RetryRules.History(polls = 20), 409, "IDEMPOTENCY_IN_PROGRESS", null, false, 0))
    }

    @Test
    fun quotasUseServerDeadlineWithoutInventingRemainingCounts() {
        val block = UsageBlock(61, 1000)
        assertTrue(block.blocked(61_999))
        assertEquals(2, block.minutes(1000))
        assertTrue(block.lifted(62_000))
        assertFalse(block.blocked(62_000))
        assertFalse(UsageBlock(null, 0).blocked(0))
        assertTrue(UsageQuota(10, 2, 1000).visible(999))
        assertFalse(UsageQuota(10, 3, 1000).visible(0))
        assertFalse(UsageQuota(10, 2, 1000).visible(1000))
        assertFalse(UsageQuota(10, -1, 1000).visible(0))
    }

    @Test
    fun naverOnlyAllowsOfficialHttpsPlaceLinks() {
        assertEquals("https://naver.me/example", NaverMapLink.place("https://naver.me/example"))
        assertEquals("https://map.naver.com/p/entry/place/123", NaverMapLink.place("https://map.naver.com/p/entry/place/123"))
        listOf(
            "http://naver.me/x",
            "https://naver.com.evil.example/x",
            "https://naver.com@evil.example/x",
            "https://naver.me:443/x",
            "javascript:alert(1)",
            "https://evil.example/x",
            "",
        ).forEach {
            assertNull(NaverMapLink.place(it), it)
        }
    }
}
