package com.mz2az.scenetrip.auth

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.sceneapi.client.api.AuthApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.Success
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 비회원 기록 삭제. 회원 탈퇴와 달리 토큰을 보내거나 세션을 갱신하지 않는다(MZ2AZ-471, iOS `Auth/GuestDataDeletion.swift`).
 */
object GuestDataDeletion {
    enum class Outcome {
        DELETED,
        SESSION_REQUIRED,
        FAILED,
    }

    /** 가입자의 설치본은 토큰 없이 부르면 `401 SESSION_REQUIRED` 다. 회원은 기존 「회원 탈퇴」 를 쓴다. */
    fun isVisible(signedIn: Boolean): Boolean = !signedIn

    suspend fun delete(
        installId: UUID,
        request: suspend (UUID) -> Unit = { GuestDeleteRequest.send(it) },
    ): Outcome =
        try {
            request(installId)
            Outcome.DELETED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = ApiFailure.of(error)
            if (failure.statusCode == 401 && failure.apiCode == SESSION_REQUIRED) Outcome.SESSION_REQUIRED else Outcome.FAILED
        }

    /**
     * 삭제 한 번의 흐름 — [AuthStore] 상태에서 떼어 내 시험이 선다(iOS `AuthStore.deleteDeviceData`).
     *
     * 비회원이 아니거나 이미 하나가 돌고 있으면 보내지 않는다. 나가 있는 쓰기가 끝난 뒤에 부르고, 그사이 로그인·계정 전환이
     * 있었으면 응답을 버린다. **204 성공에서만** [onDeleted] 가 화면 사본을 비운다 — 설치 UUID·토큰·로그인 상태는 건드리지 않는다.
     */
    @Suppress("LongParameterList")
    suspend fun run(
        signedIn: () -> Boolean,
        busy: () -> Boolean,
        setBusy: (Boolean) -> Unit,
        epoch: () -> Int,
        settle: suspend () -> Unit,
        delete: suspend () -> Outcome,
        onDeleted: () -> Unit,
    ): Outcome {
        if (!isVisible(signedIn()) || busy()) return Outcome.FAILED
        setBusy(true)
        try {
            val requestedEpoch = epoch()
            try {
                settle()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Outcome.FAILED
            }
            if (!currentCoroutineContext().isActive || signedIn() || epoch() != requestedEpoch) return Outcome.FAILED
            val outcome = delete()
            if (signedIn() || epoch() != requestedEpoch) return Outcome.FAILED
            if (outcome == Outcome.DELETED) onDeleted()
            return outcome
        } finally {
            setBusy(false)
        }
    }

    private const val SESSION_REQUIRED = "SESSION_REQUIRED"
}

/**
 * 생성기의 메서드·경로·설치 헤더를 재사용하되 인증 가로채기와 자동 재전송은 거치지 않는다.
 * 삭제 응답을 잃은 뒤 새 비회원 계정이 생길 수 있으므로 삭제를 자동으로 반복하지 않는다(iOS `GuestDeleteRequest`).
 */
object GuestDeleteRequest {
    /** 204 만 성공이다. 401·5xx 는 생성 클라이언트의 예외로, 그 밖의 응답은 [IOException] 으로 던진다. */
    suspend fun send(
        installId: UUID,
        factory: Call.Factory = GuestDeleteRequestFactory(),
    ) {
        withContext(Dispatchers.IO) {
            when (val response = AuthApi(API_BASE, factory).deleteMeWithHttpInfo(installId)) {
                is Success<*> -> {
                    if (response.statusCode != NO_CONTENT) throw IOException("unexpected status ${response.statusCode}")
                }

                is ClientError<*> -> {
                    throw ClientException("Client error : ${response.statusCode}", response.statusCode, response)
                }

                is ServerError<*> -> {
                    throw ServerException("Server error : ${response.statusCode}", response.statusCode, response)
                }

                else -> {
                    throw IOException("unexpected response ${response.statusCode}")
                }
            }
        }
    }

    private const val NO_CONTENT = 204
}

/** 인증·재시도·언어 가로채기가 붙은 공용 클라이언트를 거치지 않는 전용 전송기. 삭제는 자동으로 반복하지 않는다. */
object GuestDeleteTransport {
    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cache(null)
            .build()
}

/** 생성 클라이언트가 어떤 경로로든 토큰을 넣더라도 지우고 보낸다 — 설치 UUID 만 신원이다. */
class GuestDeleteRequestFactory(
    private val transport: Call.Factory = GuestDeleteTransport.client,
) : Call.Factory {
    override fun newCall(request: Request): Call = transport.newCall(request.newBuilder().removeHeader("Authorization").build())
}
