package com.mz2az.scenetrip.auth

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.sceneapi.client.api.AuthApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiClient
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.GoogleSignIn
import com.mz2az.scenetrip.sceneapi.client.model.Me
import com.mz2az.scenetrip.sceneapi.client.model.RefreshTokenBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 로그인 상태 (MZ2AZ-336). 앱에 하나뿐이다. iOS `Auth/AuthStore.swift` 를 옮긴 것이다.
 *
 * 계정은 **있어도 되고 없어도 된다** — 검색·장바구니·찜·코스는 비회원으로 다 된다
 * (`docs/api/auth.md`). 로그인은 그 데이터를 계정에 붙여 다른 기기에서도 보게 하고,
 * 길찾기·챗봇·마켓 쓰기를 연다.
 */
object AuthStore {
    /** 로그인한 계정. 토큰은 있는데 아직 `/me` 를 못 읽었으면 `null` 일 수 있다. */
    var me by mutableStateOf<Me?>(null)
        private set
    var signedIn by mutableStateOf(false)
        private set

    /** 로그인 화면을 띄울 것인가. 화면 어디서든 이것을 켜면 맨 위에 시트가 올라온다. */
    var showingSignIn by mutableStateOf(false)

    /**
     * 계정이 바뀔 때마다 오른다(로그인·로그아웃·탈퇴·세션 소실). 서버 데이터를 든 화면이
     * 이것을 보고 다시 읽는다 — 설치본이 가리키는 계정이 달라졌기 때문이다.
     */
    var epoch by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set

    /** 로그인 화면에 보일 한 줄. */
    var message by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context
    private lateinit var installId: UUID
    private val api by lazy { AuthApi(API_BASE) }

    /**
     * 앱이 뜰 때 한 번, **생성 클라이언트를 처음 쓰기 전에.** 401 처리를 끼우고 저장된 세션을
     * 되살린다. `ApiClient.builder` 는 첫 호출에 굳으므로 그 뒤에 끼우면 늦다.
     */
    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        ApiClient.builder.addInterceptor(AuthInterceptor())
        AuthTokens.load(appContext)
        installId = InstallIdentity.of(appContext)
        signedIn = AuthTokens.hasSession
        if (signedIn) scope.launch { me = io { api.getMe() }.getOrNull() }
    }

    /** 다른 스레드(요청 가로채기)에서도 부른다. */
    fun promptSignIn() {
        scope.launch {
            if (signedIn) return@launch
            message = null
            showingSignIn = true
        }
    }

    /** 구글 로그인. 계정 고르는 창을 띄워야 해서 [activity] 가 필요하다. */
    fun signInWithGoogle(activity: Activity) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val google = GoogleIdSignIn.signIn(activity)
                // 나가 있는 쓰기가 끝난 뒤에 부른다 — 겹치면 합치기 전 계정에 떨어진다(계약).
                PendingWrites.settle()
                val session = withContext(Dispatchers.IO) { api.signInWithGoogle(installId, GoogleSignIn(google.idToken, google.nonce)) }
                AuthTokens.store(session)
                me = session.user
                signedIn = true
                showingSignIn = false
                AppAnalytics.setMember(true)
                AppAnalytics.log(if (session.isNewUser) AppEvent.SignUp("google") else AppEvent.Login("google"))
                // `merged` 가 아니어도 다시 읽는다 — 값이 싸고, 화면이 든 것이 서버와 같다는 보장이 된다.
                accountChanged()
            } catch (_: GetCredentialCancellationException) {
                // 사람이 창을 닫았다. 알릴 것이 없다.
            } catch (e: GoogleIdSignIn.Unavailable) {
                message = "이 기기에 로그인된 구글 계정이 없어요. 기기 설정에서 계정을 추가해 주세요"
            } catch (e: ClientException) {
                message = "로그인하지 못했어요. 다시 해 주세요"
            } catch (e: ServerException) {
                message = "구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요"
            } catch (e: Exception) {
                message = "연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요"
            } finally {
                busy = false
            }
        }
    }

    /** 로그아웃. 서버 응답과 상관없이 토큰을 지운다 — 이 설치본은 서버에서 **새 비회원**이 된다. */
    fun signOut() {
        if (busy) return
        busy = true
        scope.launch {
            AuthTokens.refreshToken?.let { token -> io { api.signOut(installId, RefreshTokenBody(token)) } }
            forget()
            AppAnalytics.log(AppEvent.Logout)
            busy = false
        }
    }

    /** 탈퇴. **되돌릴 수 없다** — 부르는 쪽이 확인 창을 띄운 뒤에 부른다. */
    fun deleteAccount(onDone: (Boolean) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val deleted = io { api.deleteMe() }.isSuccess
            if (deleted) {
                forget()
                AppAnalytics.log(AppEvent.DeleteAccount)
            }
            busy = false
            onDone(deleted)
        }
    }

    /** 세션이 깨졌다(토큰 위조·폐기·리프레시 만료). 토큰을 지우고 로그인 화면을 띄운다. 다른 스레드에서도 부른다. */
    fun sessionLost() {
        scope.launch {
            if (!signedIn) return@launch
            forget()
            message = "로그인이 풀렸어요. 다시 로그인해 주세요"
            showingSignIn = true
        }
    }

    private fun forget() {
        AuthTokens.clear()
        me = null
        signedIn = false
        AppAnalytics.setMember(false)
        accountChanged()
    }

    private fun accountChanged() {
        epoch += 1
        scope.launch { LikeStore.getInstance(appContext).refresh() }
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }
}
