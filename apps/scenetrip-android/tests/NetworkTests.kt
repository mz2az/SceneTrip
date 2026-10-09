package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.RetryInterceptor
import com.mz2az.scenetrip.data.UsageLedger
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

/** 실제 소켓·잠·Android 시계 없이 공통 HTTP 계층을 실행한다. */
class NetworkTests {
    @Test
    fun cancelledRequestsStayCancelledInsteadOfBecomingScreenErrors() {
        org.junit.jupiter.api.Assertions.assertThrows(CancellationException::class.java) {
            runBlocking { apiResult<Unit> { throw CancellationException("newer search") } }
        }
    }

    @Test
    fun deleteAfterLostResponseIsAlreadyDone() {
        var now = 0L
        var attempts = 0
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(RetryInterceptor(clock = { now }, pause = { now += it }, ledger = UsageLedger()))
                .addInterceptor { chain ->
                    attempts += 1
                    if (attempts == 1) throw SocketTimeoutException("lost response")
                    Response
                        .Builder()
                        .request(
                            chain.request(),
                        ).protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("gone")
                        .body("{}".toResponseBody())
                        .build()
                }.build()
        client
            .newCall(
                Request
                    .Builder()
                    .url("$API_BASE/places/8/reviews/me")
                    .delete()
                    .build(),
            ).execute()
            .use { assertEquals(204, it.code) }
        assertEquals(2, attempts)
    }

    @Test
    fun generatedClientErrorPreservesCodeAndCaseInsensitiveHeaders() {
        val response =
            ClientError<Unit>(
                body = """{"code":"REVIEW_PHOTO_INVALID"}""",
                statusCode = 400,
                headers =
                    mapOf("retry-after" to listOf("60")),
            )
        val failure = NetworkFailure.of(ClientException("error", 400, response))
        assertEquals(400, failure.status)
        assertEquals("REVIEW_PHOTO_INVALID", failure.code)
        assertEquals(60, failure.retryAfter)
    }

    @Test
    fun transientReadsAndAwaitedWritesUseBoundedRetries() {
        var now = 0L
        var attempts = 0
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(RetryInterceptor(clock = { now }, pause = { now += it }, ledger = UsageLedger()))
                .addInterceptor { chain ->
                    attempts += 1
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(
                            if (attempts <
                                3
                            ) {
                                503
                            } else {
                                200
                            },
                        ).message("test")
                        .body("{}".toResponseBody())
                        .build()
                }.build()
        client.newCall(Request.Builder().url("$API_BASE/places/8").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(3, attempts)
        attempts = 0
        client
            .newCall(
                Request
                    .Builder()
                    .url("$API_BASE/places/8/reviews/me")
                    .put("{}".toRequestBody())
                    .build(),
            ).execute()
            .use {
                assertEquals(200, it.code)
            }
        assertEquals(3, attempts)
    }

    @Test
    fun unsafePostDoesNotRepeatAfterLostResponse() {
        var now = 0L
        var attempts = 0
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(RetryInterceptor(clock = { now }, pause = { now += it }, ledger = UsageLedger()))
                .addInterceptor {
                    attempts += 1
                    throw SocketTimeoutException("lost response")
                }.build()
        org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException::class.java) {
            client
                .newCall(
                    Request
                        .Builder()
                        .url("$API_BASE/courses")
                        .post("{}".toRequestBody())
                        .build(),
                ).execute()
        }
        assertEquals(1, attempts)
    }

    @Test
    fun lateAccountResponsesCannotRestoreLimits() {
        val ledger = UsageLedger()
        val oldGeneration = ledger.generation
        ledger.reset()
        val request = Request.Builder().url("$API_BASE/guide/chat").build()
        val response =
            Response
                .Builder()
                .request(
                    request,
                ).protocol(
                    Protocol.HTTP_1_1,
                ).code(429)
                .message("test")
                .header("Retry-After", "60")
                .body("""{"code":"GUIDE_LIMIT_REACHED"}""".toResponseBody())
                .build()
        response.use { ledger.observe(it, oldGeneration, 0) }
        assertEquals(null, ledger.snapshot.guideBlock)
    }
}
