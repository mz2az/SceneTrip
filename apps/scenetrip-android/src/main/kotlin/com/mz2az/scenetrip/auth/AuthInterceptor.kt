package com.mz2az.scenetrip.auth

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.sceneapi.client.api.AuthApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.model.RefreshTokenBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 생성 클라이언트의 요청에서 401 을 처리한다 (MZ2AZ-336). iOS `Auth/AuthRequestBuilder.swift` 의 짝이다.
 *
 * **생성기는 이것을 만들어 주지 않는다**(`social-login.md` §7). 화면 코드는 지금처럼
 * `CartApi(...).getCart(...)` 를 부르기만 하면 된다 — 만료·갱신·재시도는 여기서 끝난다.
 *
 * - `ACCESS_TOKEN_EXPIRED` → 갱신(동시에 하나) → 원래 요청을 **한 번** 다시 보낸다.
 * - 세션이 깨졌으면 토큰을 지우고 로그인 화면, 비회원이 가입 전용 기능을 불렀으면 로그인 화면.
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val api = API_BASE.toHttpUrl()
        if (original.url.scheme != api.scheme || original.url.host != api.host ||
            original.url.port != api.port
        ) {
            return chain.proceed(original)
        }
        val url = original.url.toString()
        val epoch = AuthStore.epoch
        if (!AuthRules.intercepts(url)) return chain.proceed(original)
        if (original.method == "GET" && original.url.encodedPath.contains("/reviews") &&
            AuthTokens.isStale
        ) {
            TokenRefresher.refresh(AuthTokens.accessToken)
        }
        if (epoch != AuthStore.epoch) throw IOException("account changed")
        val request = AuthTokens.accessToken?.let { original.newBuilder().header("Authorization", "Bearer $it").build() } ?: original

        val write = request.method != "GET"
        if (write) PendingWrites.begin()
        val response =
            try {
                chain.proceed(request)
            } finally {
                if (write) PendingWrites.end()
            }
        if (response.code != 401 || epoch != AuthStore.epoch) return response

        val code = AuthRules.apiCode(runCatching { response.peekBody(PEEK_BYTES).string() }.getOrNull())
        when (AuthRules.action(response.code, code)) {
            AuthAction.REFRESH_AND_RETRY -> {
                val stale = request.header("Authorization")?.removePrefix("Bearer ")
                val fresh = TokenRefresher.refresh(stale) ?: return response
                if (epoch != AuthStore.epoch) return response
                response.close()
                val retried = chain.proceed(request.newBuilder().header("Authorization", "Bearer $fresh").build())
                // 재시도는 한 번. 갱신 뒤에도 401 이면 로그인 화면이다.
                if (retried.code == 401 && epoch == AuthStore.epoch) AuthStore.sessionLost()
                return retried
            }

            AuthAction.SIGN_OUT -> {
                AuthStore.sessionLost()
            }

            AuthAction.PROMPT_SIGN_IN -> {
                AuthStore.promptSignIn()
            }

            AuthAction.NONE -> {
                Unit
            }
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 16_384L
    }
}

/**
 * 토큰 갱신. **동시에 하나만** 나간다.
 *
 * 여러 요청이 동시에 만료를 만나 저마다 갱신하면, 리프레시 토큰은 일회용이라 두 번째부터
 * 재사용으로 판정돼 서버가 그 계정의 토큰을 전부 폐기한다 — 사용자가 로그아웃된다.
 * 잠금 안에서 「내가 들고 온 토큰이 이미 바뀌었나」를 먼저 본다. 바뀌었으면 앞사람이 갱신한 것이다.
 */
object TokenRefresher {
    /** 새 액세스 토큰. 갱신하지 못했으면 `null`. */
    @Synchronized
    fun refresh(staleAccessToken: String?): String? {
        val current = AuthTokens.accessToken
        if (current != null && current != staleAccessToken) return current
        val token = AuthTokens.refreshToken ?: return null
        val epoch = AuthStore.epoch
        return try {
            val session = AuthApi(API_BASE).refreshSession(RefreshTokenBody(token))
            if (epoch != AuthStore.epoch || AuthTokens.refreshToken != token) return null
            AuthTokens.store(session)
            session.accessToken
        } catch (e: ClientException) {
            // 리프레시 토큰이 거절됐다 — 세션을 지우고 로그인 화면. 그 밖(서버에 못 닿음)은 토큰을 둔다.
            if (e.statusCode == 401 && epoch == AuthStore.epoch && AuthTokens.refreshToken == token) AuthStore.sessionLost()
            null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 나가 있는 쓰기 요청의 수.
 *
 * 로그인은 이것이 0 이 되기를 기다렸다 부른다 — 겹치면 그 쓰기가 합쳐지기 전 비회원 계정에
 * 떨어질 수 있다(계약 `/auth/google` 설명, `social-login.md` §5 「알려진 틈」).
 */
object PendingWrites {
    private val count = AtomicInteger(0)

    fun begin() {
        count.incrementAndGet()
    }

    fun end() {
        count.updateAndGet { maxOf(0, it - 1) }
    }

    /** 쓰기가 다 끝날 때까지, 길어야 3초 기다린다. */
    suspend fun settle() {
        repeat(SETTLE_TRIES) {
            if (count.get() == 0) return
            kotlinx.coroutines.delay(SETTLE_STEP_MS)
        }
    }

    private const val SETTLE_TRIES = 30
    private const val SETTLE_STEP_MS = 100L
}
