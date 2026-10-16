package com.mz2az.scenetrip

import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.BlockedUsersStore
import com.mz2az.scenetrip.data.BlockedUsersUi
import com.mz2az.scenetrip.data.CommunityClient
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunitySession
import com.mz2az.scenetrip.data.ModerationClient
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationEvents
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.reviews.ReviewListStore
import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.BlockedUser
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.ReportReason
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewAuthor
import com.mz2az.scenetrip.sceneapi.client.model.ReviewList
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostList
import com.mz2az.scenetrip.sceneapi.client.model.TripPostSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 신고·차단·차단 목록·금칙어 안내·문의 연락처 (MZ2AZ-461). 서버 없이 규칙과 흐름만 본다. iOS `ModerationTests` 와 같은 순서다. */
class ModerationTests {
    // --- 규칙 ----------------------------------------------------------------------------------------

    @Test
    fun reasonsFollowContractOrderAndValues() {
        assertEquals(ReportReason.entries, ModerationRules.reasons)
        assertEquals(
            listOf("spam", "abuse", "sexual", "personal_info", "inappropriate_nickname", "other"),
            ModerationRules.reasons.map { it.value },
        )
        val labels = ModerationRules.reasons.map(ModerationRules::label)
        assertEquals(labels.size, labels.toSet().size)
        assertFalse(labels.contains(""))
        assertEquals(
            listOf("스팸·광고", "욕설·혐오·괴롭힘", "성적인 내용", "개인정보 노출", "닉네임이 부적절함", "기타"),
            labels,
        )
    }

    @Test
    fun targetMapsToContractType() {
        assertEquals(ReportTargetType.review, ModerationTarget.review(7).type)
        assertEquals(ReportTargetType.post, ModerationTarget.post(9).type)
        assertEquals(9L, ModerationTarget.post(9).targetId)
        assertNotEquals(ModerationTarget.review(1).key, ModerationTarget.post(1).key)
    }

    @Test
    fun detailIsTrimmedOmittedWhenBlankAndCapped() {
        assertNull(ModerationRules.detail("  \n "))
        assertEquals("광고 글", ModerationRules.detail("  광고 글  "))
        assertEquals(500, ModerationRules.detail("가".repeat(600))?.length)
        // 이모지(서로게이트 쌍)를 반으로 자르지 않는다.
        val capped = ModerationRules.capped("가".repeat(499) + "😀😀")
        assertEquals(499, capped.length)
        assertFalse(capped.last().isHighSurrogate())
    }

    @Test
    fun menuIsHiddenOnOwnContentAndBlockNeedsAnAuthor() {
        assertFalse(ModerationRules.showsMenu(isMine = true))
        assertFalse(ModerationRules.showsMenu(isMine = false, onServer = false))
        assertTrue(ModerationRules.showsMenu(isMine = false))
        assertFalse(ModerationRules.canBlock(isMine = true, hasAuthor = true))
        assertFalse(ModerationRules.canBlock(isMine = false, hasAuthor = false))
        assertTrue(ModerationRules.canBlock(isMine = false, hasAuthor = true))
    }

    @Test
    fun reportOutcomesComeFromCodes() {
        assertEquals(ModerationRules.ReportOutcome.RECEIVED, ModerationRules.reportOutcome(null))
        assertEquals(ModerationRules.ReportOutcome.OWN, ModerationRules.reportOutcome(apiError(400, "CANNOT_REPORT_OWN")))
        assertEquals(ModerationRules.ReportOutcome.GONE, ModerationRules.reportOutcome(apiError(404, "REPORT_TARGET_NOT_FOUND")))
        assertEquals(ModerationRules.ReportOutcome.RATE_LIMITED, ModerationRules.reportOutcome(apiError(429, "RATE_LIMITED")))
        // 본문이 우리 모양이 아니어도 429 는 한도다.
        assertEquals(ModerationRules.ReportOutcome.RATE_LIMITED, ModerationRules.reportOutcome(apiError(429, null)))
        assertEquals(ModerationRules.ReportOutcome.FAILED, ModerationRules.reportOutcome(apiError(500, "INTERNAL_ERROR", server = true)))
        assertEquals(ModerationRules.ReportOutcome.FAILED, ModerationRules.reportOutcome(IOException("offline")))
        assertTrue(ModerationRules.ReportOutcome.GONE.finishes)
        assertTrue(ModerationRules.ReportOutcome.OWN.finishes)
        assertFalse(ModerationRules.ReportOutcome.FAILED.finishes)
        assertFalse(ModerationRules.ReportOutcome.RATE_LIMITED.finishes)
        assertEquals("신고가 접수됐어요. 24시간 안에 확인할게요.", ModerationRules.message(ModerationRules.ReportOutcome.RECEIVED))
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요", ModerationRules.message(ModerationRules.ReportOutcome.RATE_LIMITED))
    }

    @Test
    fun blockOutcomesComeFromCodes() {
        assertEquals(ModerationRules.BlockOutcome.BLOCKED, ModerationRules.blockOutcome(null))
        assertEquals(ModerationRules.BlockOutcome.SIGN_IN_REQUIRED, ModerationRules.blockOutcome(apiError(401, "SIGN_IN_REQUIRED")))
        assertEquals(ModerationRules.BlockOutcome.OWN, ModerationRules.blockOutcome(apiError(400, "CANNOT_BLOCK_SELF")))
        assertEquals(ModerationRules.BlockOutcome.NO_AUTHOR, ModerationRules.blockOutcome(apiError(400, "BLOCK_TARGET_HAS_NO_AUTHOR")))
        assertEquals(ModerationRules.BlockOutcome.GONE, ModerationRules.blockOutcome(apiError(404, "BLOCK_TARGET_NOT_FOUND")))
        assertEquals(ModerationRules.BlockOutcome.FAILED, ModerationRules.blockOutcome(IOException("timeout")))
        assertNull(ModerationRules.message(ModerationRules.BlockOutcome.BLOCKED))
        assertNull(ModerationRules.message(ModerationRules.BlockOutcome.SIGN_IN_REQUIRED))
        assertNotNull(ModerationRules.message(ModerationRules.BlockOutcome.FAILED))
    }

    // --- 금칙어 --------------------------------------------------------------------------------------

    @Test
    fun textNotAllowedIsRecognisedOnEverySavePath() {
        val error = apiError(400, "TEXT_NOT_ALLOWED")
        val wording = "쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요."
        assertEquals(wording, ModerationRules.textNotAllowedMessage)
        assertTrue(ModerationRules.isTextNotAllowed(error))
        assertFalse(ModerationRules.isTextNotAllowed(apiError(400, "INVALID_PARAMETER")))
        assertFalse(ModerationRules.isTextNotAllowed(IOException("offline")))
        // 리뷰 저장·닉네임 저장(`NetworkFailure.message`).
        assertEquals(wording, NetworkFailure.of(error).message)
        assertNotEquals(wording, NetworkFailure.of(apiError(400, "INVALID_PARAMETER")).message)
        // 코스 저장(편집 화면의 알림).
        assertEquals(wording, ApiFailure.of(error).message)
        assertNotEquals(wording, ApiFailure.of(apiError(400, "INVALID_PARAMETER")).message)
        // 여행후기 저장.
        assertEquals(wording, CommunityRules.failureText(error))
        assertNotEquals(wording, CommunityRules.failureText(apiError(400, "INVALID_PARAMETER")))
    }

    // --- 신고·차단 흐름 ------------------------------------------------------------------------------

    @Test
    fun guestBlockAsksForSignInInsteadOfConfirming() =
        runBlocking {
            val client = FakeModerationClient()
            var prompted = 0
            val controller = ModerationController(client, signedIn = { false }, promptSignIn = { prompted++ }, events = ModerationEvents())
            controller.askBlock(ModerationTarget.review(3))
            assertNull(controller.ui.value.confirmingBlock)
            assertEquals(1, prompted)
            assertFalse(controller.block(ModerationTarget.review(3)))
            assertTrue(client.blocked.isEmpty())
        }

    @Test
    fun blockSendsTheTargetAndReportsSuccess() =
        runBlocking {
            val client = FakeModerationClient()
            val controller = ModerationController(client, signedIn = { true }, promptSignIn = {}, events = ModerationEvents())
            controller.askBlock(ModerationTarget.post(5))
            assertEquals(ModerationTarget.post(5), controller.ui.value.confirmingBlock)
            assertTrue(controller.block(ModerationTarget.post(5)))
            assertNull(controller.ui.value.confirmingBlock)
            assertNull(controller.ui.value.notice)
            assertEquals(listOf(ModerationTarget.post(5)), client.blocked)
        }

    @Test
    fun failedBlockExplainsWhy() =
        runBlocking {
            val client = FakeModerationClient()
            client.blockError = apiError(400, "BLOCK_TARGET_HAS_NO_AUTHOR")
            val controller = ModerationController(client, signedIn = { true }, promptSignIn = {}, events = ModerationEvents())
            assertFalse(controller.block(ModerationTarget.review(8)))
            assertEquals("탈퇴한 사용자는 차단할 수 없어요", controller.ui.value.notice)
            controller.clearNotice()
            assertNull(controller.ui.value.notice)
        }

    @Test
    fun serverSaysGuestInTheMiddleOfBlockingSendsToSignIn() =
        runBlocking {
            val client = FakeModerationClient()
            client.blockError = apiError(401, "SIGN_IN_REQUIRED")
            var prompted = 0
            val controller = ModerationController(client, signedIn = { true }, promptSignIn = { prompted++ }, events = ModerationEvents())
            assertFalse(controller.block(ModerationTarget.review(8)))
            assertEquals(1, prompted)
            assertNull(controller.ui.value.notice)
        }

    @Test
    fun reportWorksForGuestsAndSendsTrimmedDetail() =
        runBlocking {
            val client = FakeModerationClient()
            val controller =
                ModerationController(
                    client,
                    signedIn = { false },
                    promptSignIn = { throw AssertionError("신고는 로그인이 필요 없다") },
                    events = ModerationEvents(),
                )
            val outcome = controller.report(ModerationTarget.review(4), ReportReason.other, "  이상한 링크  ")
            assertEquals(ModerationRules.ReportOutcome.RECEIVED, outcome)
            assertEquals(ModerationTarget.review(4), client.reports.first().target)
            assertEquals(ReportReason.other, client.reports.first().reason)
            assertEquals("이상한 링크", client.reports.first().detail)
            assertFalse(controller.ui.value.sending)

            client.reportError = apiError(400, "CANNOT_REPORT_OWN")
            assertEquals(ModerationRules.ReportOutcome.OWN, controller.report(ModerationTarget.review(4), ReportReason.spam, ""))
            assertEquals(1, client.reports.size)

            // 설명을 비워 두면 보내지 않는다.
            client.reportError = null
            controller.report(ModerationTarget.review(4), ReportReason.spam, "   ")
            assertNull(client.reports.last().detail)
        }

    // --- 차단 목록 -----------------------------------------------------------------------------------

    @Test
    fun blockedListLoadsAndUnblocks() =
        runBlocking {
            val client = FakeModerationClient()
            client.list = mutableListOf(blocked(1, "가"), blocked(2, "나"))
            val store = BlockedUsersStore(client, ModerationEvents())
            store.reload()
            assertEquals(BlockedUsersUi.Phase.LOADED, store.ui.value.phase)
            assertEquals(
                listOf(1L, 2L),
                store.ui.value.items
                    .map { it.id },
            )
            assertTrue(store.unblock(store.ui.value.items[0]))
            assertEquals(
                listOf(2L),
                store.ui.value.items
                    .map { it.id },
            )
            assertEquals(listOf(1L), client.unblocked)
            assertTrue(
                store.ui.value.unblocking
                    .isEmpty(),
            )
        }

    @Test
    fun alreadyRemovedBlockLeavesTheListButOtherFailuresKeepIt() =
        runBlocking {
            val client = FakeModerationClient()
            client.list = mutableListOf(blocked(1, "가"), blocked(2, "나"))
            val store = BlockedUsersStore(client, ModerationEvents())
            store.reload()
            client.unblockError = apiError(404, "BLOCK_NOT_FOUND")
            store.unblock(store.ui.value.items[0])
            assertEquals(
                listOf(2L),
                store.ui.value.items
                    .map { it.id },
            )

            client.unblockError = IOException("offline")
            assertFalse(store.unblock(store.ui.value.items[0]))
            assertEquals(
                listOf(2L),
                store.ui.value.items
                    .map { it.id },
            )
            assertEquals("차단을 풀지 못했어요. 잠시 뒤 다시 해 주세요", store.ui.value.message)
            assertTrue(
                store.ui.value.unblocking
                    .isEmpty(),
            )
        }

    @Test
    fun blockedListFailureShowsRetryState() =
        runBlocking {
            val client = FakeModerationClient()
            client.listError = IOException("timeout")
            val store = BlockedUsersStore(client, ModerationEvents())
            store.reload()
            assertEquals(BlockedUsersUi.Phase.FAILED, store.ui.value.phase)
            // 한 번 받은 목록은 다음 실패에도 남는다.
            client.listError = null
            client.list = mutableListOf(blocked(1, "가"))
            store.reload()
            client.listError = IOException("timeout")
            store.reload()
            assertEquals(BlockedUsersUi.Phase.LOADED, store.ui.value.phase)
            assertEquals(1, store.ui.value.items.size)
        }

    /** 목록을 받는 사이 푼 줄은 늦게 온 목록 응답에 들어 있어도 되살아나지 않는다. */
    @Test
    fun lateBlockListDoesNotRestoreAnUnblockedRow() =
        runBlocking {
            val client = FakeModerationClient()
            client.list = mutableListOf(blocked(1, "가"), blocked(2, "나"))
            val store = BlockedUsersStore(client, ModerationEvents())
            store.reload()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            client.blocksGate = {
                entered.complete(Unit)
                release.await()
            }
            val late = launch { store.reload() }
            entered.await()
            store.unblock(store.ui.value.items[0])
            assertEquals(
                listOf(2L),
                store.ui.value.items
                    .map { it.id },
            )
            release.complete(Unit)
            late.join()
            assertEquals(
                listOf(2L),
                store.ui.value.items
                    .map { it.id },
            )
        }

    @Test
    fun blockAndUnblockAnnounceTheChange() =
        runBlocking {
            val events = ModerationEvents()
            val client = FakeModerationClient()
            client.list = mutableListOf(blocked(1, "가"))
            val controller = ModerationController(client, signedIn = { true }, promptSignIn = {}, events = events)
            controller.block(ModerationTarget.review(3))
            assertEquals(1, events.version.value)
            client.blockError = IOException("timeout")
            controller.block(ModerationTarget.review(4))
            assertEquals(1, events.version.value)
            val store = BlockedUsersStore(client, events)
            store.reload()
            store.unblock(store.ui.value.items[0])
            assertEquals(2, events.version.value)
        }

    // --- 차단한 뒤 바로 빼기 -------------------------------------------------------------------------

    @Test
    fun blockedReviewLeavesTheListAtOnce() =
        runBlocking {
            val listing = ReviewListStore({ _, _ -> reviewList(listOf(review(1), review(2)), 2) }, { 0 })
            listing.load(ReviewSort.recent, reset = true)
            listing.drop(1)
            assertEquals(
                listOf(2L),
                listing.ui.value.reviews
                    .map { it.id },
            )
            assertEquals(1, listing.ui.value.total)
            listing.drop(99)
            assertEquals(1, listing.ui.value.total)
        }

    /** 차단 전에 떠난 목록 받기가 늦게 와도 지운 리뷰를 되살리지 않는다. */
    @Test
    fun lateReviewResponseDoesNotRestoreADroppedReview() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val listing =
                ReviewListStore(
                    { _, _ ->
                        calls++
                        if (calls == 1) {
                            entered.complete(Unit)
                            release.await()
                            reviewList(listOf(review(1), review(2)), 2)
                        } else {
                            reviewList(listOf(review(2)), 1)
                        }
                    },
                    { 0 },
                )
            val first = launch { listing.load(ReviewSort.recent, reset = true) }
            entered.await()
            // 차단 → 바로 빼고 첫 쪽을 새로 받는다(나가 있던 받기가 있어도 막히지 않는다).
            listing.drop(1)
            listing.load(ReviewSort.recent, reset = true)
            assertEquals(
                listOf(2L),
                listing.ui.value.reviews
                    .map { it.id },
            )
            // 차단 전에 나간 응답(1 을 담은)이 이제 온다.
            release.complete(Unit)
            first.join()
            assertEquals(
                listOf(2L),
                listing.ui.value.reviews
                    .map { it.id },
            )
            assertEquals(1, listing.ui.value.total)
            assertFalse(listing.ui.value.loading)
        }

    @Test
    fun accountChangeDropsTheReviewResponse() =
        runBlocking {
            var epoch = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val listing =
                ReviewListStore(
                    { _, _ ->
                        entered.complete(Unit)
                        release.await()
                        reviewList(listOf(review(1)), 1)
                    },
                    { epoch },
                )
            val pending = launch { listing.load(ReviewSort.recent, reset = true) }
            entered.await()
            epoch++
            release.complete(Unit)
            pending.join()
            assertTrue(
                listing.ui.value.reviews
                    .isEmpty(),
            )
            assertFalse(listing.ui.value.loading)
        }

    @Test
    fun blockedPostLeavesTheFeedAtOnce() =
        runBlocking {
            val feed = FeedFake()
            val session = CommunitySession(feed)
            session.refresh()
            assertEquals(listOf(42L, 43L), session.state.posts.map { it.serverId })
            session.drop(42)
            assertEquals(listOf(43L), session.state.posts.map { it.serverId })
            assertEquals(1, session.state.total)
        }

    /** 차단 전에 떠난 새로고침이 늦게 와도 차단한 글을 되살리지 않고, 차단 뒤 첫 쪽을 새로 받는다. */
    @Test
    fun lateFeedResponseDoesNotRestoreABlockedPost() =
        runBlocking {
            val feed = FeedFake()
            val session = CommunitySession(feed)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            feed.gateFirst = {
                entered.complete(Unit)
                release.await()
            }
            val first = launch { session.refresh() }
            entered.await()
            feed.rows = listOf(summary(43))
            session.blocked(42)
            assertEquals(listOf(43L), session.state.posts.map { it.serverId })
            assertEquals(2, feed.calls)
            // 차단 전에 나간 응답(42 를 담은)이 이제 온다.
            release.complete(Unit)
            first.join()
            assertEquals(listOf(43L), session.state.posts.map { it.serverId })
            assertFalse(session.state.loading)
        }

    @Test
    fun invalidatingFeedWhileLoadingMoreRefetchesTheFirstPage() =
        runBlocking {
            val feed = FeedFake()
            val session = CommunitySession(feed)
            session.refresh()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            feed.gateFirst = {
                entered.complete(Unit)
                release.await()
            }
            val old = launch { session.refresh(more = true) }
            entered.await()
            feed.rows = listOf(summary(43))
            session.invalidateAndRefresh()
            assertEquals(2, feed.offsets.size - 1)
            assertEquals(0, feed.offsets.last())
            release.complete(Unit)
            old.join()
            assertEquals(listOf(43L), session.state.posts.map { it.serverId })
            assertFalse(session.state.loading)
        }

    @Test
    fun feedInvalidationPreservesInFlightMyPosts() =
        runBlocking {
            val feed = FeedFake()
            val session = CommunitySession(feed)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            feed.gateFirst = {
                entered.complete(Unit)
                release.await()
            }
            val mine = launch { session.refresh(mine = true) }
            entered.await()
            session.blocked(42)
            assertTrue(session.state.mineLoading)
            release.complete(Unit)
            mine.join()
            assertEquals(listOf(42L, 43L), session.state.mine.map { it.serverId })
            assertEquals(2, session.state.mineTotal)
            assertFalse(session.state.mineLoading)
        }

    /** 차단하면 방문자 사진을 먼저 비운다 — 다시 받기가 실패해도 그 사람의 사진이 남지 않게. 우리 사진은 둔다. */
    @Test
    fun forgettingReviewPhotosKeepsOnlyOfficialOnes() {
        val photos =
            listOf(
                Photo(URI("https://img.example/official.jpg"), PhotoSource.official),
                Photo(URI("https://img.example/visitor.jpg?sig=1"), PhotoSource.review, reviewId = 7),
            )
        assertEquals(listOf(photos[0]), ReviewRules.withoutReviewPhotos(photos))
        assertTrue(ReviewRules.withoutReviewPhotos(photos.drop(1)).isEmpty())
    }

    // --- 만료된 토큰·문의 연락처 ---------------------------------------------------------------------

    /** 누구나 읽는 창구는 만료된 토큰에 401 을 주지 않고 비회원으로 답한다 — 부르기 전에 토큰을 살필 곳이다. */
    @Test
    fun anonymousReadsRefreshTheTokenBeforeTheCall() {
        for (
        path in
        listOf(
            "/v1/places/7",
            "/v1/places/7/photos",
            "/v1/places/7/reviews",
            "/v1/pois/9",
            "/v1/pois/9/photos",
            "/v1/pois/9/reviews/me",
            "/v1/posts",
            "/v1/posts/12",
            "/v1/me/posts",
            "/v1/me/reviews",
        )
        ) {
            assertTrue(AuthRules.refreshesBeforeRead("GET", path), path)
        }
        // 쓰기와 그 밖의 창구는 건드리지 않는다.
        assertFalse(AuthRules.refreshesBeforeRead("POST", "/v1/posts"))
        assertFalse(AuthRules.refreshesBeforeRead("PUT", "/v1/places/7/reviews/me"))
        for (path in listOf("/v1/places", "/v1/places/search", "/v1/contents/3", "/v1/me/blocks", "/v1/reports", "/v1/cart")) {
            assertFalse(AuthRules.refreshesBeforeRead("GET", path), path)
        }
    }

    @Test
    fun supportAddressIsOneConstantOnOurDomain() {
        assertEquals("support@scenetrip.io", ModerationRules.SUPPORT_EMAIL)
        assertEquals("mailto:support@scenetrip.io", ModerationRules.SUPPORT_MAIL_URI)
    }

    @Test
    fun blockedDateShowsInTheViewersLanguage() {
        val user = BlockedUser(1, "가", OffsetDateTime.of(2026, 10, 15, 23, 30, 0, 0, ZoneOffset.UTC))
        // 시험 기기는 한국어 — 날짜만 언어별로 본다(시간대는 한국 표준시).
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        assertEquals("2026년 10월 16일 차단", ModerationRules.blockedOn(user, Lang.ko, seoul))
        assertTrue(ModerationRules.blockedOn(user, Lang.en, seoul).contains("Oct 16, 2026"))
    }

    // --- 가짜와 표본 ---------------------------------------------------------------------------------

    private class FakeModerationClient : ModerationClient {
        data class Sent(
            val target: ModerationTarget,
            val reason: ReportReason,
            val detail: String?,
        )

        val reports = mutableListOf<Sent>()
        val blocked = mutableListOf<ModerationTarget>()
        val unblocked = mutableListOf<Long>()
        var list = mutableListOf<BlockedUser>()
        var reportError: Exception? = null
        var blockError: Exception? = null
        var listError: Exception? = null
        var unblockError: Exception? = null

        /** 목록 응답을 붙잡아 둔다 — 늦게 오는 응답을 흉내 낸다. */
        var blocksGate: (suspend () -> Unit)? = null

        override suspend fun report(
            target: ModerationTarget,
            reason: ReportReason,
            detail: String?,
        ) {
            reportError?.let { throw it }
            reports += Sent(target, reason, detail)
        }

        override suspend fun block(target: ModerationTarget): BlockedUser {
            blockError?.let { throw it }
            blocked += target
            return blocked(blocked.size.toLong(), "차단됨")
        }

        override suspend fun blocks(): List<BlockedUser> {
            val snapshot = list.toList()
            blocksGate?.let {
                blocksGate = null
                it()
            }
            listError?.let { throw it }
            return snapshot
        }

        override suspend fun unblock(blockId: Long) {
            unblockError?.let { throw it }
            unblocked += blockId
            list.removeAll { it.id == blockId }
        }
    }

    private class FeedFake : CommunityClient {
        var rows = listOf(summary(42), summary(43))
        var calls = 0
        val offsets = mutableListOf<Int>()

        /** 첫 호출만 붙잡아 둔다. */
        var gateFirst: (suspend () -> Unit)? = null

        override suspend fun list(
            mine: Boolean,
            limit: Int,
            offset: Int,
        ): TripPostList {
            calls++
            offsets += offset
            val snapshot = rows
            gateFirst?.let {
                gateFirst = null
                it()
            }
            return TripPostList(snapshot, snapshot.size)
        }

        override suspend fun detail(id: Long): TripPostDetail = error("이 시험은 상세를 요청하지 않는다")

        override suspend fun create(input: TripPostCreate): TripPostDetail = error("이 시험은 게시를 요청하지 않는다")

        override suspend fun delete(id: Long) = error("이 시험은 삭제를 요청하지 않는다")

        override suspend fun saveCourse(id: Long): CourseDetail = error("이 시험은 코스 복사를 요청하지 않는다")
    }

    companion object {
        private val AT = OffsetDateTime.parse("2026-10-12T00:00:00Z")

        private fun apiError(
            status: Int,
            code: String?,
            server: Boolean = false,
        ): Exception {
            val body = code?.let { """{"code":"$it","message":"시험"}""" } ?: "busy"
            return if (server) {
                ServerException("시험", status, ServerError<Unit>(body = body, statusCode = status, headers = emptyMap()))
            } else {
                ClientException("시험", status, ClientError<Unit>(body = body, statusCode = status, headers = emptyMap()))
            }
        }

        private fun blocked(
            id: Long,
            nickname: String,
        ) = BlockedUser(id, nickname, AT)

        private fun summary(id: Long) = TripPostSummary(id, "Title $id", "Body", AT, 0, false, null)

        private fun review(id: Long) = Review(id, 4, emptyList(), AT, AT, false, "시험 리뷰", ReviewAuthor("글쓴이$id"))

        private fun reviewList(
            items: List<Review>,
            total: Int,
        ) = ReviewList(items, total, RatingSummary(items.size, listOf(0, 0, 0, items.size, 0), 4.0))
    }
}
