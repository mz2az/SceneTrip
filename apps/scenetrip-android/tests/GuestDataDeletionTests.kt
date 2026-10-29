package com.mz2az.scenetrip

import com.mz2az.scenetrip.auth.GuestDataDeletion
import com.mz2az.scenetrip.auth.GuestDeleteRequest
import com.mz2az.scenetrip.auth.GuestDeleteRequestFactory
import com.mz2az.scenetrip.auth.GuestDeleteTransport
import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartLoadState
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.profiletab.DeviceDataDeletionCopy
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.util.UUID

/** 비회원 「이 기기의 기록 지우기」 (MZ2AZ-471, iOS `GuestDataDeletionTests`). 가짜 전송만 쓴다. */
class GuestDataDeletionTests {
    private fun clientError(
        status: Int,
        code: String? = "OTHER",
    ) = ClientException(
        "시험",
        status,
        ClientError<Unit>(body = code?.let { """{"code":"$it"}""" }, statusCode = status, headers = emptyMap()),
    )

    private fun serverError(status: Int) =
        ServerException("시험", status, ServerError<Unit>(body = """{"code":"OTHER"}""", statusCode = status, headers = emptyMap()))

    /** 보낸 요청을 모으고 정해 둔 응답(또는 예외)을 차례로 돌려주는 가짜 전송. */
    private class FakeTransport(
        private vararg val replies: Any,
    ) {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    requests += chain.request()
                    when (val reply = replies[minOf(requests.size - 1, replies.lastIndex)]) {
                        is IOException -> {
                            throw reply
                        }

                        else -> {
                            val status = reply as Int
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(status)
                                .message("시험")
                                .body(if (status == 401) """{"code":"SESSION_REQUIRED"}""".toResponseBody() else "".toResponseBody())
                                .build()
                        }
                    }
                }.build()
    }

    // MARK: 결과 분류

    @Test
    fun successPassesSameInstallId() =
        runBlocking {
            val id = UUID.randomUUID()
            val sent = mutableListOf<UUID>()
            val outcome = GuestDataDeletion.delete(id) { sent += it }
            assertEquals(GuestDataDeletion.Outcome.DELETED, outcome)
            assertEquals(listOf(id), sent)
        }

    @Test
    fun sessionRequiredIsDistinctFromOtherFailures() =
        runBlocking {
            val outcome = GuestDataDeletion.delete(UUID.randomUUID()) { throw clientError(401, "SESSION_REQUIRED") }
            assertEquals(GuestDataDeletion.Outcome.SESSION_REQUIRED, outcome)
        }

    @Test
    fun networkServerAndOtherAuthFailuresStayFailures() =
        runBlocking {
            val errors: List<Exception> =
                listOf(
                    UnknownHostException("offline"),
                    SocketTimeoutException("timeout"),
                    serverError(500),
                    serverError(503),
                    clientError(401),
                    clientError(401, null),
                    clientError(403, "SESSION_REQUIRED"),
                    ServerException("시험", 500, ServerError<Unit>(body = """{"code":"SESSION_REQUIRED"}""", statusCode = 500)),
                )
            for (error in errors) {
                assertEquals(
                    GuestDataDeletion.Outcome.FAILED,
                    GuestDataDeletion.delete(UUID.randomUUID()) { throw error },
                    error.toString(),
                )
            }
        }

    @Test
    fun cancellationIsNotAFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking { GuestDataDeletion.delete(UUID.randomUUID()) { throw CancellationException("화면이 닫혔다") } }
        }
    }

    @Test
    fun rowIsOnlyVisibleToGuests() {
        assertTrue(GuestDataDeletion.isVisible(signedIn = false))
        assertFalse(GuestDataDeletion.isVisible(signedIn = true))
    }

    // MARK: 요청 — 토큰 없이 설치 UUID 만, 자동 반복 없음

    @Test
    fun generatedRequestSendsOnlyInstallIdentityAndAccepts204() =
        runBlocking {
            val id = UUID.randomUUID()
            val transport = FakeTransport(204)
            val outcome = GuestDataDeletion.delete(id) { GuestDeleteRequest.send(it, GuestDeleteRequestFactory(transport.client)) }
            assertEquals(GuestDataDeletion.Outcome.DELETED, outcome)
            assertEquals(1, transport.requests.size)
            val request = transport.requests.single()
            assertEquals("DELETE", request.method)
            assertTrue(request.url.encodedPath.endsWith("/me"), request.url.encodedPath)
            assertEquals(id.toString(), request.header("X-Install-Id"))
            assertNull(request.header("Authorization"))
        }

    @Test
    fun factoryDropsAnyAuthorizationHeaderBeforeSending() {
        val transport = FakeTransport(204)
        val request =
            Request
                .Builder()
                .url("http://localhost/v1/me")
                .delete()
                .header("Authorization", "Bearer unit-global")
                .header("authorization", "Bearer unit-lowercase")
                .header("X-Install-Id", UUID.randomUUID().toString())
                .build()
        GuestDeleteRequestFactory(transport.client).newCall(request).execute().close()
        assertNull(transport.requests.single().header("Authorization"))
        assertNotNull(transport.requests.single().header("X-Install-Id"))
    }

    @Test
    fun generatedFailuresNeverRetry() =
        runBlocking {
            val replies: List<Pair<Any, GuestDataDeletion.Outcome>> =
                listOf(
                    401 to GuestDataDeletion.Outcome.SESSION_REQUIRED,
                    503 to GuestDataDeletion.Outcome.FAILED,
                    500 to GuestDataDeletion.Outcome.FAILED,
                    SocketTimeoutException("timeout") to GuestDataDeletion.Outcome.FAILED,
                    UnknownHostException("offline") to GuestDataDeletion.Outcome.FAILED,
                    200 to GuestDataDeletion.Outcome.FAILED,
                )
            for ((reply, expected) in replies) {
                val transport = FakeTransport(reply)
                val outcome =
                    GuestDataDeletion.delete(UUID.randomUUID()) { GuestDeleteRequest.send(it, GuestDeleteRequestFactory(transport.client)) }
                assertEquals(expected, outcome, reply.toString())
                assertEquals(1, transport.requests.size, "삭제는 자동으로 반복하지 않는다: $reply")
            }
        }

    @Test
    fun dedicatedTransportHasNoAuthRetryOrLanguageLayers() {
        // 공용 클라이언트의 인증·재시도·동의 가로채기를 거치지 않는다 — 응답을 잃은 삭제를 되풀이하면 새 비회원 계정을 지울 수 있다.
        assertTrue(GuestDeleteTransport.client.interceptors.isEmpty())
        assertTrue(GuestDeleteTransport.client.networkInterceptors.isEmpty())
        assertFalse(GuestDeleteTransport.client.retryOnConnectionFailure)
    }

    // MARK: 한 번의 흐름 (iOS `AuthStore.deleteDeviceData`)

    /** [GuestDataDeletion.run] 에 끼우는 가짜 계정 상태. */
    private class Account(
        var signedIn: Boolean = false,
        var epoch: Int = 0,
    ) {
        var busy = false
        var settleError: Exception? = null
        var deletes = 0
        var clears = 0

        suspend fun run(delete: suspend () -> GuestDataDeletion.Outcome): GuestDataDeletion.Outcome =
            GuestDataDeletion.run(
                signedIn = { signedIn },
                busy = { busy },
                setBusy = { busy = it },
                epoch = { epoch },
                settle = { settleError?.let { throw it } },
                delete = {
                    deletes += 1
                    delete()
                },
                onDeleted = {
                    clears += 1
                    epoch += 1 // `AuthStore.accountChanged`
                },
            )
    }

    @Test
    fun signedInUserCannotDeleteGuestData() =
        runBlocking {
            val account = Account(signedIn = true)
            val result = account.run { GuestDataDeletion.Outcome.DELETED }
            assertEquals(GuestDataDeletion.Outcome.FAILED, result)
            assertEquals(0, account.deletes)
            assertEquals(0, account.clears)
            assertTrue(account.signedIn)
            assertFalse(account.busy)
        }

    @Test
    fun onlySuccessClearsLocalDataWithoutChangingSignInState() =
        runBlocking {
            val account = Account()
            var clears = 0
            for (outcome in GuestDataDeletion.Outcome.entries.sortedBy { it == GuestDataDeletion.Outcome.DELETED }) {
                val result = account.run { outcome }
                assertEquals(outcome, result)
                clears = if (outcome == GuestDataDeletion.Outcome.DELETED) 1 else 0
                assertEquals(clears, account.clears)
                assertEquals(clears, account.epoch)
                assertFalse(account.signedIn)
                assertFalse(account.busy)
            }
        }

    @Test
    fun duplicateDeleteDoesNotSendOrClearTwice() =
        runBlocking {
            val account = Account()
            val result =
                account.run {
                    assertTrue(account.busy)
                    val duplicate = account.run { GuestDataDeletion.Outcome.DELETED }
                    assertEquals(GuestDataDeletion.Outcome.FAILED, duplicate)
                    GuestDataDeletion.Outcome.DELETED
                }
            assertEquals(GuestDataDeletion.Outcome.DELETED, result)
            assertEquals(1, account.deletes)
            assertEquals(1, account.clears)
            assertFalse(account.busy)
        }

    @Test
    fun pendingWritesThatDoNotSettleStopBeforeSending() =
        runBlocking {
            val account = Account().apply { settleError = IllegalStateException("still running") }
            assertEquals(GuestDataDeletion.Outcome.FAILED, account.run { GuestDataDeletion.Outcome.DELETED })
            assertEquals(0, account.deletes)
            assertEquals(0, account.clears)
            assertFalse(account.busy)
        }

    @Test
    fun accountChangeWhileWaitingDiscardsTheAnswer() =
        runBlocking {
            val account = Account()
            val result =
                account.run {
                    account.epoch += 1 // 요청이 가 있는 동안 계정이 바뀌었다.
                    GuestDataDeletion.Outcome.DELETED
                }
            assertEquals(GuestDataDeletion.Outcome.FAILED, result)
            assertEquals(0, account.clears)
            val signedInMeanwhile = Account()
            val second =
                signedInMeanwhile.run {
                    signedInMeanwhile.signedIn = true
                    GuestDataDeletion.Outcome.DELETED
                }
            assertEquals(GuestDataDeletion.Outcome.FAILED, second)
            assertEquals(0, signedInMeanwhile.clears)
        }

    // MARK: 장바구니 사본 (iOS `CartStoreTests` 의 초기화 시험)

    private class FakeCart : CartClient {
        var fetchError: Exception? = null
        var beforeFetchReturns: (() -> Unit)? = null

        override fun fetch(): List<CartItem> {
            beforeFetchReturns?.invoke()
            fetchError?.let { throw it }
            return listOf(CartItem(placeId = 7, name = "장소 7", addedAt = OffsetDateTime.parse("2026-10-16T00:00:00+09:00")))
        }

        override fun add(
            placeId: Long,
            sourceContentId: Long?,
        ) = Unit

        override fun remove(placeId: Long) = Unit
    }

    @Test
    fun accountResetClearsRowsAndIconsEvenIfNextReloadFails() =
        runBlocking {
            val client = FakeCart()
            val cart = CartSession(client)
            assertEquals(CartLoadState.NOT_LOADED, cart.state.loadState)
            cart.refresh()
            assertEquals(CartLoadState.LOADED, cart.state.loadState)
            assertTrue(cart.contains(7))
            cart.reset()
            assertEquals(CartLoadState.NOT_LOADED, cart.state.loadState)
            client.fetchError = IOException("offline")
            cart.refresh()
            assertEquals(CartLoadState.FAILED, cart.state.loadState)
            assertTrue(cart.items.isEmpty())
            assertFalse(cart.contains(7))
            assertNull(cart.toast)
        }

    @Test
    fun responseStartedBeforeDeletionCannotRestoreDeletedCart() =
        runBlocking {
            var epoch = 0
            val client = FakeCart()
            val cart = CartSession(client, epoch = { epoch })
            client.beforeFetchReturns = {
                epoch += 1 // 삭제가 끝나 계정 변경 신호가 올랐다.
            }
            cart.refresh()
            assertTrue(cart.items.isEmpty())
            assertFalse(cart.contains(7))
        }

    // MARK: 문구

    @Test
    fun copyMatchesIosAndHasEnglish() {
        assertEquals("이 기기의 기록 지우기", DeviceDataDeletionCopy.ROW_KO)
        assertEquals("이 기기의 기록을 지울까요?", DeviceDataDeletionCopy.CONFIRM_TITLE_KO)
        assertEquals("이 기기의 장바구니·코스·찜이 모두 지워지고 되돌릴 수 없어요.", DeviceDataDeletionCopy.CONFIRM_MESSAGE_KO)
        assertEquals("이 기기의 기록을 지웠어요.", DeviceDataDeletionCopy.DELETED_KO)
        assertEquals("가입한 계정의 기록이에요. 로그인한 뒤 마이페이지에서 회원 탈퇴를 이용해 주세요.", DeviceDataDeletionCopy.SESSION_REQUIRED_KO)
        assertEquals("기록을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.", DeviceDataDeletionCopy.FAILED_KO)
        assertEquals("이 기기의 발자취도 함께 지워져요.", DeviceDataDeletionCopy.FOOTER_KO)
        for (key in DeviceDataDeletionCopy.copyKeys) assertNotNull(englishOf(key), key)
        assertEquals("Delete this device's data", englishOf(DeviceDataDeletionCopy.ROW_KO))
        assertEquals(DeviceDataDeletionCopy.DELETED_KO, DeviceDataDeletionCopy.message(GuestDataDeletion.Outcome.DELETED))
        assertEquals(DeviceDataDeletionCopy.SESSION_REQUIRED_KO, DeviceDataDeletionCopy.message(GuestDataDeletion.Outcome.SESSION_REQUIRED))
        assertEquals(DeviceDataDeletionCopy.FAILED_KO, DeviceDataDeletionCopy.message(GuestDataDeletion.Outcome.FAILED))
    }
}
