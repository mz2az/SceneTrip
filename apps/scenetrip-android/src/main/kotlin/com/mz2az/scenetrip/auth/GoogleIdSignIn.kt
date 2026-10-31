package com.mz2az.scenetrip.auth

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
     * 창을 요청하는 방식과 기다리는 시간. **선언 순서가 시도 순서다.**
     *
     * 모든 계정을 먼저 보여 준다 — 이 앱 계정만 먼저 띄우면 계정이 하나만 보여 다른 계정으로 바꿀 수 없다. 시스템 창이 죽어도 우리 화면은
     * 「가려짐」 상태로 남아(죽은 창 기록이 맨 위에 남는다 — 2026-10-31 실측) 창이 떴는지 알 수 없으므로, 방식마다 시간을 정해 넘어간다.
     * 사람이 고르는 동안 시간이 다 되면 창이 닫히고 다음 방식이 뜨므로 넉넉히 둔다.
     */
    private enum class Variant(
        val waitMs: Long,
    ) {
        /** 기기의 모든 구글 계정 — 로그인이 가입을 겸한다. 계정이 많은 기기에서는 창이 안 뜰 수 있다. */
        ALL(20_000),

        /** 이 앱에 로그인한 적 있는 계정만 — 시스템 창에 보내는 데이터가 작아 계정이 많은 기기에서도 뜬다. 없으면 바로 넘어간다. */
        AUTHORIZED(60_000),

        /** 구글 로그인 버튼 방식 (`GetSignInWithGoogleOption`). */
        BUTTON(60_000),
    }

    /**
     * 계정 고르는 창을 띄우고 ID 토큰을 받아 온다. 사람이 닫으면 [GetCredentialCancellationException].
     *
     * 모든 계정 → 이 앱 계정만 → 버튼 방식 순서로 시도한다. 앞의 방식이 계정이 없다고 하거나 창이 안 뜨면 다음으로 간다. 시스템 창이 뜨지
     * 않는 기기에서 앱이 영원히 기다리던 것을 막는다.
     */
    suspend fun signIn(activity: Activity): Result {
        var timedOut = false
        var last: GetCredentialException? = null
        for (variant in Variant.values()) {
            try {
                return withTimeout(variant.waitMs) { request(activity, variant) }
            } catch (e: GetCredentialCancellationException) {
                throw e
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            } catch (e: GetCredentialException) {
                last = e
            }
        }
        if (timedOut) throw WindowFailed()
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
}
