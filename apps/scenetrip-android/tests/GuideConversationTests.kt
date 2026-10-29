package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.DeviceNetwork
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.UsageQuota
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.routetab.GuideChat
import com.mz2az.scenetrip.routetab.GuideLimit
import com.mz2az.scenetrip.routetab.PendingTurn
import com.mz2az.scenetrip.routetab.RouteGuideFailure
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatReply
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/**
 * 가이드 챗봇의 오류·한도 문구와 대화 유지 (MZ2AZ-481). 실제 `/guide/chat` 은 부르지 않는다 — 전송은 가짜다.
 */
class GuideConversationTests {
    private fun client(
        status: Int,
        code: String?,
        headers: Map<String, List<String>> = emptyMap(),
    ): Throwable {
        val body = code?.let { """{"code":"$it","message":"server"}""" }
        return if (status >= 500) {
            ServerException("시험", status, ServerError<Unit>(body = body, statusCode = status, headers = headers))
        } else {
            ClientException("시험", status, ClientError<Unit>(body = body, statusCode = status, headers = headers))
        }
    }

    private fun failure(
        error: Throwable,
        offline: Boolean = false,
    ) = RouteGuideFailure.of(error) { offline }

    // 오류 갈래

    @Test
    fun contractResponsesMapToTheSameBranchesAsIos() {
        assertEquals(RouteGuideFailure.BadRequest, failure(client(400, "BAD_REQUEST")))
        assertEquals(RouteGuideFailure.SignInRequired, failure(client(401, "SIGN_IN_REQUIRED")))
        assertEquals(RouteGuideFailure.SessionExpired, failure(client(401, "ACCESS_TOKEN_EXPIRED")))
        assertEquals(RouteGuideFailure.SessionExpired, failure(client(401, "SESSION_REQUIRED")))
        assertEquals(RouteGuideFailure.TimedOut, failure(client(409, "IDEMPOTENCY_IN_PROGRESS")))
        assertEquals(RouteGuideFailure.Other(409), failure(client(409, "OTHER")))
        assertEquals(RouteGuideFailure.KeyReused, failure(client(422, "IDEMPOTENCY_KEY_REUSED")))
        assertEquals(RouteGuideFailure.RateLimited, failure(client(429, "RATE_LIMITED")))
        assertEquals(RouteGuideFailure.RateLimited, failure(client(429, null)))
        assertEquals(RouteGuideFailure.Other(429), failure(client(429, "SOMETHING_ELSE")))
        for (status in listOf(502, 503, 504)) assertEquals(RouteGuideFailure.Unavailable, failure(client(status, "GUIDE_UNAVAILABLE")))
        assertEquals(RouteGuideFailure.Other(500), failure(client(500, "INTERNAL")))
        assertEquals(RouteGuideFailure.Other(404), failure(client(404, "NOT_FOUND")))
    }

    @Test
    fun theGuideLimitCarriesRetryAfterFromTheHeaderAndNothingElse() {
        val limited = failure(client(429, "GUIDE_LIMIT_REACHED", mapOf("Retry-After" to listOf("1800"))))
        assertEquals(RouteGuideFailure.LimitReached(1800), limited)
        assertTrue(limited.isLimit)
        assertEquals(RouteGuideFailure.LimitReached(null), failure(client(429, "GUIDE_LIMIT_REACHED")))
        assertFalse(failure(client(429, "RATE_LIMITED")).isLimit)
    }

    @Test
    fun noResponseIsSplitIntoOfflineUnreachableAndTimedOut() {
        assertEquals(RouteGuideFailure.Offline, failure(UnknownHostException("x"), offline = true))
        assertEquals(RouteGuideFailure.Offline, failure(ConnectException("x"), offline = true))
        assertEquals(RouteGuideFailure.Unreachable, failure(ConnectException("x"), offline = false))
        assertEquals(RouteGuideFailure.Unreachable, failure(IOException("끊김")))
        assertEquals(RouteGuideFailure.TimedOut, failure(SocketTimeoutException("느림"), offline = true))
        assertEquals(RouteGuideFailure.Interrupted, failure(kotlinx.coroutines.CancellationException("멈춤")))
    }

    @Test
    fun consentBlocksAreOwnBranchesThatAreRetriedAfterAllowing() {
        assertEquals(
            RouteGuideFailure.ConsentRequired,
            failure(
                com.mz2az.scenetrip.data
                    .AIConsentRequired(),
            ),
        )
        assertEquals(
            RouteGuideFailure.LocationConsentRequired,
            failure(
                com.mz2az.scenetrip.data
                    .LocationConsentRequired(),
            ),
        )
        assertEquals(RouteGuideFailure.Retry.SAME_KEY, RouteGuideFailure.ConsentRequired.retry)
        assertEquals(RouteGuideFailure.Retry.SAME_KEY, RouteGuideFailure.LocationConsentRequired.retry)
    }

    @Test
    fun retryDependsOnWhetherAskingAgainCouldChangeTheOutcome() {
        val same =
            listOf(
                RouteGuideFailure.Unavailable,
                RouteGuideFailure.TimedOut,
                RouteGuideFailure.Offline,
                RouteGuideFailure.Unreachable,
                RouteGuideFailure.RateLimited,
                RouteGuideFailure.Interrupted,
                RouteGuideFailure.LimitReached(60),
                RouteGuideFailure.Other(500),
            )
        same.forEach { assertEquals(RouteGuideFailure.Retry.SAME_KEY, it.retry, it.toString()) }
        assertEquals(RouteGuideFailure.Retry.NEW_KEY, RouteGuideFailure.KeyReused.retry)
        listOf(
            RouteGuideFailure.SignInRequired,
            RouteGuideFailure.SessionExpired,
            RouteGuideFailure.BadRequest,
            RouteGuideFailure.Other(404),
        ).forEach { assertEquals(RouteGuideFailure.Retry.NONE, it.retry, it.toString()) }
    }

    @Test
    fun messagesAreTheIosSentencesAndAllHaveEnglish() {
        val expected =
            mapOf(
                RouteGuideFailure.SignInRequired to "여행 가이드는 가입한 분만 쓸 수 있어요",
                RouteGuideFailure.SessionExpired to "로그인이 풀렸어요. 다시 로그인해 주세요",
                RouteGuideFailure.BadRequest to "요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요",
                RouteGuideFailure.Unavailable to "가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요",
                RouteGuideFailure.TimedOut to "답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요",
                RouteGuideFailure.Offline to "인터넷 연결을 확인해 주세요.",
                RouteGuideFailure.Unreachable to "서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요",
                RouteGuideFailure.RateLimited to "요청이 많아요. 잠시 뒤 다시 시도해 주세요",
                RouteGuideFailure.Interrupted to "답을 기다리다 멈췄어요. 다시 시도해 주세요",
                RouteGuideFailure.KeyReused to "가이드 요청을 처리하지 못했어요 (422)",
                RouteGuideFailure.Other(418) to "가이드 요청을 처리하지 못했어요 (418)",
                RouteGuideFailure.LocationConsentRequired to "위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.",
                RouteGuideFailure.ConsentRequired to "AI 기능 사용을 허용한 뒤 다시 시도해 주세요.",
            )
        for ((failure, text) in expected) assertEquals(text, failure.message)
        // 영어 표에 이 문장들의 열쇠가 있어야 영어 화면에서 한국어가 안 새어 나온다 (%d 는 열쇠 그대로).
        for (text in expected.values.map { it.replace("422", "%d").replace("418", "%d") }) assertNotNull(englishOf(text), text)
    }

    // 공용 오류 문구 (429·5xx·오프라인)

    @Test
    fun sharedFailureTextSeparatesRateLimitServerTroubleAndOffline() {
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요.", ApiFailure(429).message)
        for (status in listOf(500, 502, 503, 504)) assertEquals("잠시 문제가 생겼습니다.", ApiFailure(status).message, "$status")
        assertEquals("인터넷 연결을 확인해 주세요.", ApiFailure(null, isOffline = true).message)
        assertEquals("서버에 연결하지 못했습니다.", ApiFailure(null).message)
        assertEquals("요청을 처리하지 못했습니다.", ApiFailure(404).message)
        assertEquals("요청을 처리하지 못했습니다.", ApiFailure(400).message)
        listOf("요청이 많아요. 잠시 뒤 다시 시도해 주세요.", "잠시 문제가 생겼습니다.", "인터넷 연결을 확인해 주세요.", "서버에 연결하지 못했습니다.")
            .forEach { assertNotNull(englishOf(it), it) }
    }

    @Test
    fun retryIsOfferedForServerTroubleAndRateLimitNotForABadRequest() {
        for (status in listOf(429, 500, 502, 503, 504)) assertTrue(ApiFailure(status).isRetryable, "$status")
        for (status in listOf(400, 401, 404, 409, 422)) assertFalse(ApiFailure(status).isRetryable, "$status")
        assertTrue(ApiFailure(null).isRetryable)
    }

    @Test
    fun offlineIsReadFromTheDeviceOnlyWhenThereWasNoResponse() {
        val before = DeviceNetwork.probe
        try {
            DeviceNetwork.probe = { true }
            assertTrue(ApiFailure.of(IOException("끊김")).isOffline)
            assertEquals("인터넷 연결을 확인해 주세요.", ApiFailure.of(UnknownHostException("x")).message)
            // 응답이 있었으면 기기 탓이 아니다.
            assertFalse(ApiFailure.of(client(503, null)).isOffline)
            assertEquals("잠시 문제가 생겼습니다.", ApiFailure.of(client(503, null)).message)
            assertEquals("인터넷 연결을 확인해 주세요.", NetworkFailure.of(IOException("끊김")).message)
            DeviceNetwork.probe = { false }
            assertEquals("서버에 연결하지 못했습니다.", NetworkFailure.of(IOException("끊김")).message)
        } finally {
            DeviceNetwork.probe = before
        }
    }

    @Test
    fun networkFailureFallsBackToTheSameSharedSentences() {
        assertEquals("잠시 문제가 생겼습니다.", NetworkFailure.of(client(503, null)).message)
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요", NetworkFailure.of(client(429, null)).message)
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요", NetworkFailure.of(client(429, "RATE_LIMITED")).message)
        assertEquals("요청을 처리하지 못했습니다.", NetworkFailure.of(client(404, "NOT_FOUND")).message)
        // 계약이 이름 붙인 코드는 그대로 구체적인 문구를 낸다.
        assertEquals("이미 쓰는 닉네임입니다", NetworkFailure.of(client(409, "NICKNAME_TAKEN")).message)
    }

    // 한도 안내

    @Test
    fun limitMessageSaysMinutesWithinAnHourAndTomorrowBeyondAndNothingWhenUnknown() {
        val now = 10_000L
        assertEquals("가이드에게 잠시 많이 물어봤어요. 30분 뒤에 다시 물어볼 수 있어요", GuideLimit.Block(1800, now).message(now))
        // 남은 분은 올림이고 적어도 1 — 「0분 뒤」 라고 말하지 않는다.
        assertEquals("가이드에게 잠시 많이 물어봤어요. 1분 뒤에 다시 물어볼 수 있어요", GuideLimit.Block(1, now).message(now))
        assertEquals("가이드에게 잠시 많이 물어봤어요. 1분 뒤에 다시 물어볼 수 있어요", GuideLimit.Block(0, now).message(now))
        assertEquals("가이드에게 잠시 많이 물어봤어요. 2분 뒤에 다시 물어볼 수 있어요", GuideLimit.Block(61, now).message(now))
        assertEquals("오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요", GuideLimit.Block(3601, now).message(now))
        assertEquals("지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요", GuideLimit.Block(null, now).message(now))
        // 시간이 가면 남은 분이 준다.
        assertEquals("가이드에게 잠시 많이 물어봤어요. 10분 뒤에 다시 물어볼 수 있어요", GuideLimit.Block(1800, now).message(now + 1200_000))
        listOf(
            "가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요",
            "오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요",
            "지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요",
            "이제 다시 물어볼 수 있어요",
        ).forEach { assertNotNull(englishOf(it), it) }
    }

    @Test
    fun theLockOpensOnlyWhenTheServerToldUsWhenAndStaysOffWhenItDidNot() {
        val block = GuideLimit.Block(120, 1_000L)
        assertTrue(block.stopsSending(1_000L))
        assertTrue(block.stopsSending(120_999L))
        assertTrue(block.isOver(121_000L))
        assertFalse(block.stopsSending(121_000L))
        val unknown = GuideLimit.Block(null, 1_000L)
        assertFalse(unknown.stopsSending(1_000L), "풀리는 때를 모르면 잠그지 않는다")
        assertFalse(unknown.isOver(999_999L))
    }

    @Test
    fun remainingLineShowsOnlyWhenFewAreLeftAndSaysWhenItRefills() {
        val now = 0L

        fun text(
            limit: Int,
            remaining: Int,
            refillSeconds: Int,
        ) = GuideLimit.remaining(UsageQuota(limit, remaining, now + refillSeconds * 1000L), now).text
        assertNull(text(30, 25, 600), "넉넉하면 보이지 않는다")
        assertEquals("6번 더 물어볼 수 있어요 · 10분 뒤 다시 채워져요", text(30, 6, 600))
        assertEquals("오늘 3번 더 물어볼 수 있어요", text(30, 3, 7200))
        assertEquals("지금은 더 물어볼 수 없어요 · 10분 뒤 다시 채워져요", text(30, 0, 600))
        assertEquals("오늘은 더 물어볼 수 없어요", text(30, 0, 7200))
        assertNull(GuideLimit.remaining(null, now).text)
        assertNull(GuideLimit.remaining(UsageQuota(30, 1, 5_000L), 5_000L).text, "그 창이 이미 다시 찼다")
        assertNull(GuideLimit.remaining(UsageQuota(0, 0, 9_000L), now).text)
    }

    @Test
    fun oneQuestionLeftUsesTheSingularEnglishRow() {
        assertEquals("%1\$d question left · refills in %2\$d min", englishOf("%1\$d번 더 물어볼 수 있어요 · %2\$d분 뒤 다시 채워져요|하나"))
        assertEquals("%d question left today", englishOf("오늘 %d번 더 물어볼 수 있어요|하나"))
        assertEquals("%d place", englishOf("%d곳|하나"))
        assertEquals("%d review", englishOf("리뷰 %d|하나"))
        // 한국어는 수에 따라 달라지지 않는다.
        assertEquals(
            "1곳",
            com.mz2az.scenetrip.data
                .trCount("%d곳", 1),
        )
        assertEquals(
            "2곳",
            com.mz2az.scenetrip.data
                .trCount("%d곳", 2),
        )
    }

    // 턴 전이

    private val request =
        com.mz2az.scenetrip.sceneapi.client.model.GuideChatRequest(
            sessionId = UUID.randomUUID(),
            latitude = 37.5,
            longitude = 127.0,
            messages = emptyList(),
        )

    private fun turn(
        keyed: Boolean = true,
        lang: Lang = Lang.ko,
    ) = PendingTurn(key = "k1", request = request, lang = lang, keyed = keyed)

    @Test
    fun afterAFailureTheTurnIsKeptOnlyWhenTheServerKnowsKeysAndAskingAgainCanHelp() {
        assertNotNull(turn().failing(RouteGuideFailure.Unavailable))
        assertNull(turn(keyed = false).failing(RouteGuideFailure.Unavailable), "서버가 키를 모르면 다시 시도 단추가 없다")
        assertNull(turn().failing(RouteGuideFailure.SignInRequired))
        assertNull(turn().failing(RouteGuideFailure.Other(404)))
    }

    @Test
    fun retryReusesTheKeyExceptForKeyReusedAndAChangedLanguage() {
        val failed = turn().failing(RouteGuideFailure.Unreachable)!!
        assertEquals("k1", failed.retried(Lang.ko) { "k2" }!!.key)
        assertEquals("k2", failed.retried(Lang.en) { "k2" }!!.key, "언어가 바뀌면 지문이 달라 같은 키로는 422 다")
        assertEquals("k2", turn().failing(RouteGuideFailure.KeyReused)!!.retried(Lang.ko) { "k2" }!!.key)
        assertNull(turn().retried(Lang.ko) { "k2" }, "실패하지 않은 턴은 다시 보내지 않는다")
        assertEquals(request, failed.retried(Lang.ko) { "k2" }!!.request, "요청은 처음 만든 그대로 다시 나간다")
    }

    @Test
    fun resendNoticesOnlyChangeBusyTurns() {
        val sending = turn()
        assertEquals(PendingTurn.Stage.Reconnecting, sending.resending(com.mz2az.scenetrip.data.ResendEvents.Reason.FAILED).stage)
        assertEquals(PendingTurn.Stage.AwaitingServer, sending.resending(com.mz2az.scenetrip.data.ResendEvents.Reason.IN_PROGRESS).stage)
        assertEquals(PendingTurn.Stage.WaitingForLimit, sending.resending(com.mz2az.scenetrip.data.ResendEvents.Reason.RATE_LIMITED).stage)
        val failed = sending.failing(RouteGuideFailure.Unavailable)!!
        assertEquals(failed, failed.resending(com.mz2az.scenetrip.data.ResendEvents.Reason.FAILED))
    }

    // 대화 (가짜 전송)

    private class Fake {
        var clock = 0L
        var keys = 0
        val sent = mutableListOf<PendingTurn>()
        var results = ArrayDeque<Result<GuideChatReply>>()
        var quota: UsageQuota? = null
        var keyed = true
        var signedIn = true

        fun environment() =
            GuideChat.Environment(
                send = { _, turn ->
                    sent += turn
                    results.removeFirst()
                },
                serverKeepsKeys = { keyed },
                lang = { Lang.ko },
                newKey = { "key-${++keys}" },
                asked = {},
                now = { clock },
                quota = { quota },
                signedIn = { signedIn },
                promptSignIn = {},
            )
    }

    private fun reply(
        text: String,
        places: List<GuidePlace> = emptyList(),
    ) = GuideChatReply(reply = text, toolsUsed = emptyList(), places = places, tookSeconds = 1.0, effects = emptyList(), ui = emptyList())

    private fun session(fake: Fake): GuideChat = GuideChat(UUID.randomUUID(), CoroutineScope(Dispatchers.Unconfined), fake.environment())

    @Test
    fun aReplyLandsAsAnAssistantTurnAndClearsThePending() =
        runBlocking {
            val fake = Fake().apply { results += Result.success(reply("안녕하세요")) }
            val guide = session(fake)
            guide.ask(" 주변 카페 알려줘 ", 37.5, 127.0)
            assertEquals(listOf("주변 카페 알려줘", "안녕하세요"), guide.turns.map { it.text })
            assertNull(guide.pending)
            assertNull(guide.failure)
            assertFalse(guide.asking)
        }

    @Test
    fun anEmptyReplyIsSaidSoInsteadOfLeavingABlankBubble() =
        runBlocking {
            val fake = Fake().apply { results += Result.success(reply("")) }
            val guide = session(fake)
            guide.ask("안녕", 37.5, 127.0)
            assertEquals("답을 받지 못했습니다.", guide.turns.last().text)
        }

    @Test
    fun unavailableKeepsTheTurnAndRetrySendsTheSameKeyWithoutRepeatingTheQuestion() =
        runBlocking {
            val fake = Fake().apply { results += Result.failure(client(503, "GUIDE_UNAVAILABLE")) }
            val guide = session(fake)
            guide.ask("카페", 37.5, 127.0)
            assertEquals(RouteGuideFailure.Unavailable, guide.failure)
            assertTrue(guide.canRetry)
            assertEquals(1, guide.turns.size)

            fake.results += Result.success(reply("여기요"))
            guide.retry()
            assertEquals(listOf("카페", "여기요"), guide.turns.map { it.text }, "질문은 대화에 한 번만 적힌다")
            assertEquals(listOf("key-1", "key-1"), fake.sent.map { it.key })
            assertEquals(fake.sent[0].request, fake.sent[1].request)
            assertNull(guide.failure)
            assertNull(guide.pending)
        }

    @Test
    fun withoutServerKeyKnowledgeThereIsNoRetryButTheFailureIsStillSaid() =
        runBlocking {
            val fake =
                Fake().apply {
                    keyed = false
                    results += Result.failure(client(503, null))
                }
            val guide = session(fake)
            guide.ask("카페", 37.5, 127.0)
            assertEquals(RouteGuideFailure.Unavailable, guide.failure)
            assertFalse(guide.canRetry)
        }

    @Test
    fun theLimitLocksSendingUntilItLiftsThenOffersTheSameKeyRetry() =
        runBlocking {
            val fake =
                Fake().apply {
                    clock = 1_000L
                    results += Result.failure(client(429, "GUIDE_LIMIT_REACHED", mapOf("Retry-After" to listOf("600"))))
                }
            val guide = session(fake)
            guide.ask("카페", 37.5, 127.0)
            assertEquals(RouteGuideFailure.LimitReached(600), guide.failure)
            assertTrue(guide.limit is GuideChat.Limit.Reached)
            assertTrue(guide.isLimited)
            assertFalse(guide.canRetry, "풀리기 전에는 다시 시도 단추가 없다")

            // 잠긴 동안의 질문은 서버로 나가지 않는다.
            guide.ask("또 물어봐", 37.5, 127.0)
            assertEquals(1, fake.sent.size)
            assertEquals(1, guide.turns.size)

            fake.clock = 601_000L
            guide.refreshLimit()
            assertEquals(GuideChat.Limit.Lifted, guide.limit)
            assertFalse(guide.isLimited)
            assertTrue(guide.canRetry)

            fake.results += Result.success(reply("이제 됩니다"))
            guide.retry()
            assertEquals(GuideChat.Limit.Clear, guide.limit)
            assertEquals(listOf("key-1", "key-1"), fake.sent.map { it.key })
            assertEquals("이제 됩니다", guide.turns.last().text)
        }

    @Test
    fun aLimitWithoutRetryAfterNeverLocksSending() =
        runBlocking {
            val fake = Fake().apply { results += Result.failure(client(429, "GUIDE_LIMIT_REACHED")) }
            val guide = session(fake)
            guide.ask("카페", 37.5, 127.0)
            assertTrue(guide.limit is GuideChat.Limit.Reached)
            assertFalse(guide.isLimited)
            assertTrue(guide.canRetry)
        }

    @Test
    fun theQuotaFromTheLastAnswerIsKeptForTheRemainingLine() =
        runBlocking {
            val fake =
                Fake().apply {
                    quota = UsageQuota(30, 3, 60_000L)
                    results += Result.success(reply("네"))
                }
            val guide = session(fake)
            guide.ask("카페", 37.5, 127.0)
            assertEquals(UsageQuota(30, 3, 60_000L), guide.quota)
            guide.forgetLimit()
            assertNull(guide.quota, "남은 양은 계정마다 따로다")
        }

    @Test
    fun resendNoticeMovesTheStageOnlyForTheMatchingKey() =
        runBlocking {
            val gate = CompletableGate()
            val fake = Fake().apply { results += Result.success(reply("네")) }
            val guide =
                GuideChat(
                    UUID.randomUUID(),
                    CoroutineScope(Dispatchers.Unconfined),
                    GuideChat.Environment(
                        send = { _, turn ->
                            guideProbe(gate, turn)
                            Result.success(reply("네"))
                        },
                        serverKeepsKeys = { true },
                        newKey = { "k-live" },
                        asked = {},
                        quota = { null },
                        signedIn = { true },
                        now = { 0L },
                    ),
                )
            // 창이 떠 있다 — 닫혀 있으면 다시 보내기 알림이 호출을 끊는다(MZ2AZ-481, `GuideCloseTests`).
            guide.setAttended(true, Any())
            // 전송이 끝나기 전에 알림을 흘려 단계가 바뀌는지 본다.
            gate.onInside = {
                guide.noteResend("other-key", com.mz2az.scenetrip.data.ResendEvents.Reason.FAILED)
                assertEquals(PendingTurn.Stage.Sending, guide.pending?.stage)
                guide.noteResend("k-live", com.mz2az.scenetrip.data.ResendEvents.Reason.RATE_LIMITED)
                assertEquals(PendingTurn.Stage.WaitingForLimit, guide.pending?.stage)
                assertTrue(guide.asking)
            }
            guide.ask("카페", 37.5, 127.0)
            assertTrue(gate.ran)
            assertNull(guide.pending)
            fake.results.clear()
        }

    private class CompletableGate {
        var onInside: () -> Unit = {}
        var ran = false
    }

    private fun guideProbe(
        gate: CompletableGate,
        @Suppress("UNUSED_PARAMETER") turn: PendingTurn,
    ) {
        gate.ran = true
        gate.onInside()
    }

    // 대화 유지 (코스별)

    @Test
    fun theConversationFollowsTheCourseAndSurvivesReopeningTheSameOne() =
        runBlocking {
            val fake = Fake().apply { results += Result.success(reply("네", listOf(place(1)))) }
            val guide = session(fake)
            guide.bind("course-7")
            guide.ask("카페", 37.5, 127.0)
            assertEquals(2, guide.turns.size)
            assertEquals(1, guide.places.size)

            // 같은 코스를 다시 열면 이어진다.
            guide.bind("course-7")
            assertEquals(2, guide.turns.size)
            assertEquals(1, guide.places.size)

            // 저장 전 코스가 서버 번호를 얻었다 — 같은 코스라 대화를 둔다.
            guide.bind("draft-x")
            fake.results += Result.success(reply("네"))
            guide.ask("카페", 37.5, 127.0)
            guide.rekey("course-9")
            guide.bind("course-9")
            assertEquals(2, guide.turns.size)

            // 다른 코스를 열면 앞 코스의 대화·AI 장소가 사라진다.
            guide.bind("course-8")
            assertTrue(guide.turns.isEmpty())
            assertTrue(guide.places.isEmpty())
        }

    @Test
    fun theSessionIdChangesWithTheCourseButNotWithinOne() =
        runBlocking {
            val fake =
                Fake().apply {
                    results += Result.success(reply("a"))
                    results += Result.success(reply("b"))
                    results +=
                        Result.success(reply("c"))
                }
            val guide = session(fake)
            guide.bind("course-1")
            guide.ask("하나", 37.5, 127.0)
            guide.ask("둘", 37.5, 127.0)
            guide.bind("course-2")
            guide.ask("셋", 37.5, 127.0)
            val ids = fake.sent.map { it.request.sessionId }
            assertEquals(ids[0], ids[1], "한 코스 안에서는 서버가 앞 턴의 장소를 기억하는 같은 방이다")
            assertNotEquals(ids[1], ids[2])
        }

    @Test
    fun theLimitSurvivesChangingTheCourseButNotChangingTheAccount() =
        runBlocking {
            val fake = Fake().apply { results += Result.failure(client(429, "GUIDE_LIMIT_REACHED", mapOf("Retry-After" to listOf("900")))) }
            val guide = session(fake)
            guide.bind("course-1")
            guide.ask("카페", 37.5, 127.0)
            guide.bind("course-2")
            assertTrue(guide.limit is GuideChat.Limit.Reached, "한도는 계정의 것이라 코스를 바꿔도 남는다")
            guide.forgetLimit()
            assertEquals(GuideChat.Limit.Clear, guide.limit)
            assertNull(guide.failure)
        }

    @Test
    fun aLateAnswerForAnotherCourseOrAccountIsDropped() =
        runBlocking {
            val fake = Fake()
            val lateAnswers = mutableListOf<() -> Unit>()
            val guide =
                GuideChat(
                    UUID.randomUUID(),
                    CoroutineScope(Dispatchers.Unconfined + Job()),
                    GuideChat.Environment(
                        send = { _, _ ->
                            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                                lateAnswers += { continuation.resumeWith(Result.success(Result.success(reply("늦은 답")))) }
                            }
                        },
                        serverKeepsKeys = { true },
                        asked = {},
                        quota = { null },
                        signedIn = { true },
                        now = { fake.clock },
                    ),
                )
            guide.bind("course-1")
            guide.ask("카페", 37.5, 127.0)
            assertTrue(guide.asking)
            guide.bind("course-2")
            assertFalse(guide.asking)
            lateAnswers.forEach { it() }
            assertTrue(guide.turns.isEmpty(), "다른 코스의 대화에 늦은 답을 적지 않는다")
        }

    private fun place(id: Long) =
        GuidePlace(
            id = id,
            name = "장소 $id",
            category = "카페",
            categoryGroup = com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup.food,
            latitude = 37.5,
            longitude = 127.0,
            source = com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource.poi,
        )
}
