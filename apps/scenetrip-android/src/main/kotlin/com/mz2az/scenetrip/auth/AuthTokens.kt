package com.mz2az.scenetrip.auth

import android.content.Context
import com.mz2az.scenetrip.data.SecureStore
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiClient
import com.mz2az.scenetrip.sceneapi.client.model.AuthSession

/**
 * 로그인 토큰 두 개를 Keystore 로 잠근 저장소에 둔다 (MZ2AZ-336). iOS `Auth/AuthTokens.swift` 의 짝이다.
 *
 * **SharedPreferences 에 그대로 두지 않는다** — 백업으로 다른 기기에 따라가면 그대로 남의 손에
 * 들어간다([SecureStore] 머리말). 앱은 토큰 안을 읽지 않는다. 만료는 401 로 안다.
 *
 * 액세스 토큰은 생성 클라이언트의 `ApiClient.accessToken` 에도 넣는다 — 계약의 `security` 덕에
 * 생성 코드가 토큰을 받는 창구에 `Authorization: Bearer` 를 스스로 싣는다.
 */
object AuthTokens {
    private const val ACCESS = "accessToken"
    private const val REFRESH = "refreshToken"

    private lateinit var store: SecureStore

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    val hasSession: Boolean get() = refreshToken != null

    /** 앱이 뜰 때 한 번 — 저장된 세션을 읽어 생성 클라이언트에 싣는다. */
    fun load(context: Context) {
        store = SecureStore(context)
        accessToken = store.read(ACCESS)
        refreshToken = store.read(REFRESH)
        ApiClient.accessToken = accessToken
    }

    /** 로그인·갱신이 돌려준 묶음. **두 토큰을 모두 바꾼다** — 리프레시 토큰은 일회용이다. */
    @Synchronized
    fun store(session: AuthSession) {
        accessToken = session.accessToken
        refreshToken = session.refreshToken
        ApiClient.accessToken = session.accessToken
        store.write(ACCESS, session.accessToken)
        store.write(REFRESH, session.refreshToken)
    }

    @Synchronized
    fun clear() {
        accessToken = null
        refreshToken = null
        ApiClient.accessToken = null
        store.remove(ACCESS)
        store.remove(REFRESH)
    }
}
