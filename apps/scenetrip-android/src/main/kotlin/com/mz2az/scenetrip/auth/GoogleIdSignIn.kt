package com.mz2az.scenetrip.auth

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 구글 로그인 — Credential Manager 로 ID 토큰을 받는다 (MZ2AZ-336).
 *
 * iOS 는 시스템 로그인 창 + PKCE 로 같은 것을 받는다(`Auth/GoogleOAuth.swift`). 여기서는
 * 기기에 로그인된 구글 계정을 고르는 창이 뜬다.
 *
 * **`serverClientId` 는 웹 클라이언트 ID 다.** Android 클라이언트 ID 가 아니다 — 서버가 받는
 * ID 토큰의 `aud` 가 이것이다. Android 클라이언트는 「이 패키지·서명 지문의 앱만 우리 앱」을
 * 구글에 등록하는 용도뿐이다(`docs/project/plans/social-login.md` §9).
 */
object GoogleIdSignIn {
    /** 비밀이 아니다. 서버의 `aud` 허용 목록에 있다. */
    private const val WEB_CLIENT_ID = "700188854872-3dl36cm33ndb2m1e8j04svnrnjpjleep.apps.googleusercontent.com"
    private const val NONCE_BYTES = 32

    class Result(
        val idToken: String,
        /** 서버에 **원문 그대로** 보낸다. 구글이 ID 토큰 안에 넣어 준 값과 같은지 서버가 본다. */
        val nonce: String,
    )

    /** 고를 구글 계정이 기기에 없거나 구글 서비스가 응답하지 않는다. */
    class Unavailable(
        cause: Throwable,
    ) : Exception(cause)

    /** 로그인 시도마다 새 난수(32 바이트)의 base64url. */
    fun randomNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** 계정 고르는 창이 끝내 뜨지 않았다 — 기기의 시스템 창 문제다(2026-10-31 SM-S928N: 계정 선택 창 앱이 너무 큰 데이터를 받다 죽었다). */
    class WindowFailed : Exception("the account chooser never appeared")

    /**
     * 창을 요청하는 방식. **선언 순서가 시도 순서다.** 모든 계정을 먼저 보여 준다 — 이 앱 계정만 먼저 띄우면 계정이 하나만 보여 다른 계정을
     * 고를 수 없다. 창이 안 뜨는 기기에서만 뒤의 방식으로 내려간다.
     */
    private enum class Variant {
        /** 기기의 모든 구글 계정 — 로그인이 가입을 겸한다. 계정이 많은 기기에서는 창이 안 뜰 수 있다. */
        ALL,

        /** 이 앱에 로그인한 적 있는 계정만 — 시스템 창에 보내는 데이터가 가장 작다. */
        AUTHORIZED,

        /** 구글 로그인 버튼 방식 (`GetSignInWithGoogleOption`). */
        BUTTON,
    }

    /** 요청한 뒤 이 시간 안에 우리 화면이 가려지지 않으면(=시스템 창이 안 뜸) 다음 방식으로 넘어간다. */
    private const val WINDOW_MS = 10_000L

    /**
     * 계정 고르는 창을 띄우고 ID 토큰을 받아 온다. 사람이 닫으면 [GetCredentialCancellationException].
     *
     * 모든 계정 → 이 앱 계정만 → 버튼 방식 순서로 시도한다. 앞의 방식이 계정이 없다고 하거나 시스템 창이 안 뜨면 다음으로 간다.
     * 시스템 창이 뜨지 않는 기기(계정 선택 창 앱이 죽음)에서 앱이 영원히 기다리던 것을 막는다.
     */
    suspend fun signIn(activity: Activity): Result {
        var windowFailed = false
        var last: GetCredentialException? = null
        for (variant in Variant.values()) {
            try {
                return withWindow(activity) { request(activity, variant) }
            } catch (e: GetCredentialCancellationException) {
                throw e
            } catch (_: WindowFailed) {
                windowFailed = true
            } catch (e: GetCredentialException) {
                last = e
            }
        }
        if (windowFailed) throw WindowFailed()
        throw Unavailable(last ?: IllegalStateException("no credential"))
    }

    private suspend fun request(
        activity: Activity,
        variant: Variant,
    ): Result {
        val nonce = randomNonce()
        val option =
            when (variant) {
                // 전에 이 앱에 로그인한 계정만.
                Variant.AUTHORIZED -> {
                    GetGoogleIdOption
                        .Builder()
                        .setServerClientId(WEB_CLIENT_ID)
                        .setFilterByAuthorizedAccounts(true)
                        .setNonce(nonce)
                        .build()
                }

                // 이 앱에 로그인한 적 없는 계정도 — 로그인이 가입을 겸한다.
                Variant.ALL -> {
                    GetGoogleIdOption
                        .Builder()
                        .setServerClientId(
                            WEB_CLIENT_ID,
                        ).setFilterByAuthorizedAccounts(false)
                        .setNonce(nonce)
                        .build()
                }

                Variant.BUTTON -> {
                    GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).setNonce(nonce).build()
                }
            }
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw Unavailable(IllegalStateException("unexpected credential type ${credential.type}"))
        }
        return Result(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce)
    }

    /**
     * [block] 을 돌리되, [WINDOW_MS] 안에 우리 화면이 멈추지(ON_PAUSE) 않으면 — 시스템 창이 위에 안 떴다 — 중단하고 [WindowFailed].
     * 창이 떴으면 그 뒤로는 사람이 고르는 만큼 기다린다. 계정이 없어 창 없이 바로 끝나는 경우는 그대로 돌려준다.
     */
    private suspend fun <T> withWindow(
        activity: Activity,
        block: suspend () -> T,
    ): T {
        val owner = activity as? LifecycleOwner ?: return block()
        return coroutineScope {
            val shown = CompletableDeferred<Unit>()
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) shown.complete(Unit) }
            owner.lifecycle.addObserver(observer)
            var gaveUp = false
            val call = async { block() }
            val watchdog =
                launch {
                    delay(WINDOW_MS)
                    if (!shown.isCompleted && call.isActive) {
                        gaveUp = true
                        call.cancel()
                    }
                }
            try {
                call.await()
            } catch (e: CancellationException) {
                if (gaveUp) throw WindowFailed()
                throw e
            } finally {
                watchdog.cancel()
                owner.lifecycle.removeObserver(observer)
            }
        }
    }
}
