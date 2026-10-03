package com.mz2az.scenetrip.auth

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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

    /** 계정 고르는 창을 띄우고 ID 토큰을 받아 온다. 사람이 닫으면 [GetCredentialCancellationException]. */
    suspend fun signIn(activity: Activity): Result {
        val nonce = randomNonce()
        val option =
            GetGoogleIdOption
                .Builder()
                .setServerClientId(WEB_CLIENT_ID)
                // 전에 이 앱에 로그인한 계정만이 아니라 기기의 모든 구글 계정을 보여 준다 — 로그인이 가입을 겸한다.
                .setFilterByAuthorizedAccounts(false)
                .setNonce(nonce)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential =
            try {
                CredentialManager.create(activity).getCredential(activity, request).credential
            } catch (e: GetCredentialCancellationException) {
                throw e
            } catch (e: GetCredentialException) {
                throw Unavailable(e)
            }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw Unavailable(IllegalStateException("unexpected credential type ${credential.type}"))
        }
        return Result(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce)
    }
}
