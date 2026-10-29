package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ResendEvents
import com.mz2az.scenetrip.data.RetryInterceptor
import com.mz2az.scenetrip.data.RetryLedger
import com.mz2az.scenetrip.routetab.GuideChat
import com.mz2az.scenetrip.routetab.PendingTurn
import com.mz2az.scenetrip.routetab.RouteGuideFailure
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatReply
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.util.UUID

/**
 * 가이드 창을 닫으면 기다리던 자동 다시 보내기·되묻기를 끊는다 (MZ2AZ-481 남은 몫, iOS `RouteGuideTurnTests`의
 * `testClosingThePanelCutsAWaitingResend` 등). 전송은 모두 가짜다 — 실제 `/guide/chat` 은 부르지 않는다.
 */
class GuideCloseTests {
    private fun reply(text: String) =
        GuideChatReply(
            reply = text,
            toolsUsed = emptyList(),
            places = emptyList(),
            tookSeconds = 1.0,
            effects = emptyList(),
            ui = emptyList(),
        )

    /** 보낸 턴을 모으고, 끊으라는 요청이 오면 걸려 있던 전송을 중단 오류로 끝내는 가짜 전송. */
    private class Wire {
        val sent = mutableListOf<PendingTurn>()
        val cancelled = mutableListOf<String>()
        var hanging: CompletableDeferred<Result<GuideChatReply>>? = null
        var next: Result<GuideChatReply>? = null
        var keys = 0

        fun environment() =
            GuideChat.Environment(
                send = { _, turn ->
                    sent += turn
                    next?.also { next = null } ?: CompletableDeferred<Result<GuideChatReply>>().also { hanging = it }.await()
                },
                serverKeepsKeys = { true },
                lang = { Lang.ko },
                newKey = { "key-${++keys}" },
                asked = {},
                now = { 0L },
                quota = { null },
                signedIn = { true },
                promptSignIn = {},
                cancelSend = { turn ->
                    cancelled += turn.key
                    hanging?.complete(Result.failure(InterruptedIOException("cancelled")))
                },
            )
    }

    private fun chat(wire: Wire) = GuideChat(UUID.randomUUID(), CoroutineScope(Dispatchers.Unconfined), wire.environment())

    @Test
    fun closingThePanelCutsAWaitingResendAndNothingIsSentAfterwards() =
        runBlocking {
            val wire = Wire()
            val guide = chat(wire)
            val panel = Any()
            guide.setAttended(true, panel)
            guide.ask("질문", 37.5, 127.0)
            assertEquals(1, wire.sent.size)
            val key = wire.sent[0].key
            guide.noteResend(key, ResendEvents.Reason.RATE_LIMITED)
            assertEquals(PendingTurn.Stage.WaitingForLimit, guide.pending?.stage)
            assertTrue(wire.cancelled.isEmpty(), "창이 떠 있는 동안은 기다린다")

            guide.setAttended(false, panel)

            assertEquals(listOf(key), wire.cancelled, "닫는 순간 나가 있는 호출을 끊는다")
            assertEquals(RouteGuideFailure.Interrupted, guide.failure)
            assertTrue(guide.canRetry)
            assertEquals(1, wire.sent.size, "닫은 뒤에는 아무것도 더 보내지 않는다")
            // 대화는 그대로다 — 질문이 남아 있다.
            assertEquals(listOf("질문"), guide.turns.map { it.text })
        }

    @Test
    fun reopeningResumesTheSameTurnWithTheSameKey() =
        runBlocking {
            val wire = Wire()
            val guide = chat(wire)
            val panel = Any()
            guide.bind("course-7")
            guide.setAttended(true, panel)
            guide.ask("질문", 37.5, 127.0)
            val key = wire.sent[0].key
            guide.noteResend(key, ResendEvents.Reason.IN_PROGRESS)
            guide.setAttended(false, panel)
            assertTrue(guide.canRetry)

            // 같은 코스의 창을 다시 연다 — 대화가 이어지고 「다시 시도」 가 같은 키로 나간다.
            guide.bind("course-7")
            guide.setAttended(true, panel)
            assertEquals(1, guide.turns.size)
            wire.next = Result.success(reply("저장돼 있던 답"))
            guide.retry()

            assertEquals(listOf(key, key), wire.sent.map { it.key })
            assertEquals(wire.sent[0].request, wire.sent[1].request)
            assertEquals(listOf("질문", "저장돼 있던 답"), guide.turns.map { it.text })
            assertNull(guide.pending)
            assertNull(guide.failure)
        }

    @Test
    fun aResendNoticeWhileTheWindowIsAlreadyClosedIsCutAtOnce() =
        runBlocking {
            val wire = Wire()
            val guide = chat(wire)
            val panel = Any()
            guide.setAttended(true, panel)
            guide.ask("질문", 37.5, 127.0)
            // 처음 보낸 요청은 창을 닫아도 끊지 않는다 — 묻고 지도를 보려고 창을 내린다.
            guide.setAttended(false, panel)
            assertTrue(guide.asking)
            assertTrue(wire.cancelled.isEmpty())
            assertNull(guide.failure)

            // 창이 닫힌 채로 다시 보내기가 시작되면 그때 끊는다.
            guide.noteResend(wire.sent[0].key, ResendEvents.Reason.FAILED)

            assertEquals(listOf(wire.sent[0].key), wire.cancelled)
            assertEquals(RouteGuideFailure.Interrupted, guide.failure)
            assertTrue(guide.canRetry)
            assertEquals(1, wire.sent.size)
        }

    @Test
    fun onePanelLeavingDoesNotCloseTheOther() =
        runBlocking {
            val wire = Wire()
            val guide = chat(wire)
            val editor = Any()
            val navigation = Any()
            guide.setAttended(true, editor)
            guide.setAttended(true, navigation)
            guide.ask("질문", 37.5, 127.0)
            guide.noteResend(wire.sent[0].key, ResendEvents.Reason.IN_PROGRESS)

            guide.setAttended(false, editor)
            guide.setAttended(false, editor) // 두 번 알려도 같다
            assertTrue(wire.cancelled.isEmpty(), "다른 창이 떠 있으면 열린 것이다")
            assertTrue(guide.asking)

            guide.setAttended(false, navigation) // 마지막 창도 사라졌다
            assertEquals(1, wire.cancelled.size)
            assertEquals(RouteGuideFailure.Interrupted, guide.failure)
        }

    @Test
    fun aClosedWindowWithoutAnyResendLeavesTheConversationAlone() =
        runBlocking {
            val wire = Wire().apply { next = Result.success(reply("답")) }
            val guide = chat(wire)
            val panel = Any()
            guide.setAttended(true, panel)
            guide.ask("질문", 37.5, 127.0)
            guide.setAttended(false, panel)
            assertTrue(wire.cancelled.isEmpty())
            assertEquals(listOf("질문", "답"), guide.turns.map { it.text })
            assertFalse(guide.asking)
        }

    // 실제 공통 계층(RetryInterceptor)과 OkHttp 호출을 가짜 서버 위에서 돌려, 닫은 뒤 정말 요청이 더 나가지 않는지 본다.

    /** 멱등 키를 아는 서버처럼 구는 장부 — 챗봇 호출이 자동 다시 보내기 차선(CHAT)을 탄다. */
    private class KnowingLedger : RetryLedger {
        override val generation = 0
        override val knowsLimits = true

        override fun keyStarted(
            key: String,
            now: Long,
        ) = now

        override fun observe(
            response: Response,
            requestedGeneration: Int,
            now: Long,
        ) = Unit
    }

    /** 모든 시도가 503 인 가짜 서버. 시도 횟수를 센다. */
    private class FlakyServer {
        var attempts = 0
        var pauses = 0
        var now = 0L

        val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    RetryInterceptor(clock = { now }, pause = {
                        now += it
                        pauses += 1
                    }, ledger = KnowingLedger()),
                ).addInterceptor { chain ->
                    attempts += 1
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("unavailable")
                        .body("""{"code":"GUIDE_UNAVAILABLE"}""".toResponseBody())
                        .build()
                }.build()

        /** 실제 전송과 같은 모양 — 멱등 키를 실은 `POST /guide/chat`. 2xx 가 아니면 서버 오류로 던진다. */
        fun send(turn: PendingTurn): Result<GuideChatReply> =
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url("$API_BASE/guide/chat")
                        .header("Idempotency-Key", turn.key)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(request).execute().use { response ->
                    throw ServerException("서버 오류", response.code, ServerError<Unit>(body = "", statusCode = response.code))
                }
            }
    }

    @Test
    fun closedPanelStopsTheRealResendLoopAfterTheFirstAttempt() =
        runBlocking {
            val server = FlakyServer()
            val guide =
                GuideChat(
                    UUID.randomUUID(),
                    CoroutineScope(Dispatchers.Unconfined),
                    GuideChat.Environment(
                        send = { _, turn -> server.send(turn) },
                        serverKeepsKeys = { true },
                        lang = { Lang.ko },
                        newKey = { "key-live" },
                        asked = {},
                        now = { 0L },
                        quota = { null },
                        signedIn = { true },
                        promptSignIn = {},
                        // 기본값과 같다 — 호출 자체를 끊는다. 시험 클라이언트의 호출을 찾도록 디스패처만 넘긴다.
                        cancelSend = { turn -> ResendEvents.cancelCalls(turn.key, server.client.dispatcher) },
                    ),
                )
            // 창이 한 번도 열린 적 없다 = 닫혀 있다. 첫 시도가 실패하고 다시 보내려는 순간 알림이 호출을 끊는다.
            guide.ask("질문", 37.5, 127.0)

            assertEquals(1, server.attempts, "닫힌 뒤에는 두 번째 요청이 나가지 않는다")
            assertEquals(RouteGuideFailure.Interrupted, guide.failure)
            assertTrue(guide.canRetry, "턴은 남아 다시 열었을 때 같은 키로 이어진다")
            assertEquals("key-live", guide.pending?.key)
        }

    @Test
    fun openPanelLetsTheRealResendGoThrough() =
        runBlocking {
            val server = FlakyServer()
            val guide =
                GuideChat(
                    UUID.randomUUID(),
                    CoroutineScope(Dispatchers.Unconfined),
                    GuideChat.Environment(
                        send = { _, turn -> server.send(turn) },
                        serverKeepsKeys = { true },
                        lang = { Lang.ko },
                        newKey = { "key-live" },
                        asked = {},
                        now = { 0L },
                        quota = { null },
                        signedIn = { true },
                        promptSignIn = {},
                        cancelSend = { turn -> ResendEvents.cancelCalls(turn.key, server.client.dispatcher) },
                    ),
                )
            guide.setAttended(true, Any())
            guide.ask("질문", 37.5, 127.0)

            assertEquals(2, server.attempts, "창이 떠 있으면 자동으로 한 번 다시 보낸다")
            assertTrue(guide.failure is RouteGuideFailure.Unavailable)
        }
}
