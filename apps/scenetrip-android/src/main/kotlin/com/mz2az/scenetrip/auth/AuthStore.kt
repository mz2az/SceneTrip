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
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.sceneapi.client.api.AuthApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiClient
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.GoogleSignIn
import com.mz2az.scenetrip.sceneapi.client.model.Me
import com.mz2az.scenetrip.sceneapi.client.model.NicknameInput
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
    var showingNickname by mutableStateOf(false)
        private set

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
        ApiClient.builder.retryOnConnectionFailure(false)
        ApiClient.builder.addInterceptor(
            com.mz2az.scenetrip.data
                .RetryInterceptor(),
        )
        // 재시도 계층의 안쪽 — 인증 갱신 뒤 재전송과 자동 재시도도 매번 동의를 확인한다(MZ2AZ-468).
        ApiClient.builder.addInterceptor(
            com.mz2az.scenetrip.data
                .AIConsentInterceptor(
                    com.mz2az.scenetrip.data.AIConsentStore
                        .getInstance(appContext),
                ),
        )
        // 기기 좌표를 싣는 요청(지도 범위 검색·주변 편의시설·가이드·길 안내)은 위치기반서비스 동의가 있어야 나간다(MZ2AZ-472).
        // 같은 자리(재시도 계층의 안쪽)라 인증 갱신 뒤 재전송과 자동 재시도도 매번 검사받는다.
        ApiClient.builder.addInterceptor(
            com.mz2az.scenetrip.data
                .LocationConsentInterceptor(
                    com.mz2az.scenetrip.data.LocationConsentStore
                        .getInstance(appContext),
                ),
        )
        AuthTokens.load(appContext)
        installId = InstallIdentity.of(appContext)
        signedIn = AuthTokens.hasSession
        if (signedIn) {
            scope.launch {
                val before = epoch
                val restored = io { api.getMe() }.getOrNull() ?: return@launch
                if (!AccountReply.restoration(before, epoch, signedIn)) return@launch
                me = restored
                offerNickname(restored)
            }
        }
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
                offerNickname(session.user)
            } catch (_: GetCredentialCancellationException) {
                // 사람이 창을 닫았다. 알릴 것이 없다.
            } catch (e: GoogleIdSignIn.Unavailable) {
                message = "이 기기에 로그인된 구글 계정이 없어요. 기기 설정에서 계정을 추가해 주세요"
            } catch (e: PendingWrites.StillRunning) {
                message = "저장 중인 내용이 있어요. 잠시 뒤 다시 로그인해 주세요"
            } catch (e: ClientException) {
                message = signInFailure(e)
            } catch (e: ServerException) {
                message = signInFailure(e)
            } catch (e: Exception) {
                message = AuthRules.signInFailureText(null, null)
            } finally {
                busy = false
            }
        }
    }

    private fun signInFailure(error: Throwable): String = NetworkFailure.of(error).let { AuthRules.signInFailureText(it.status, it.code) }

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

    /**
     * 비회원 기록만 지운다(MZ2AZ-471). 성공해도 토큰·설치 UUID·로그인 상태를 바꾸지 않는다.
     * 화면이 떠나도 요청은 끝까지 가도록 앱 범위에서 돌린다 — 부르는 쪽은 [onDone] 으로 결과를 받는다.
     */
    fun deleteDeviceData(onDone: (GuestDataDeletion.Outcome) -> Unit) {
        scope.launch {
            val outcome =
                GuestDataDeletion.run(
                    signedIn = { signedIn },
                    busy = { busy },
                    setBusy = { busy = it },
                    epoch = { epoch },
                    settle = { PendingWrites.settle() },
                    delete = { GuestDataDeletion.delete(installId) },
                    onDeleted = {
                        clearLocalAccountData()
                        accountChanged()
                    },
                )
            onDone(outcome)
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
        clearLocalAccountData()
        AuthTokens.clear()
        me = null
        signedIn = false
        showingNickname = false
        AppAnalytics.setMember(false)
        accountChanged()
    }

    /**
     * 발자취는 기기에만 있지만 그 사람의 것이다 — 계정이 떠나면 함께 지운다(MZ2AZ-348). 화면이 든 앞 계정의 사본(찜·코스·스탬프)도
     * 먼저 비운다 — 다시 읽기가 실패해도 남아 보이지 않게 (MZ2AZ-480). 로그아웃·탈퇴·비회원 기록 삭제가 함께 쓴다.
     */
    private fun clearLocalAccountData() {
        com.mz2az.scenetrip.data.FootprintStore
            .getInstance(appContext)
            .forgetOwner()
        com.mz2az.scenetrip.data.AccountDataReset
            .clear()
    }

    private fun accountChanged() {
        com.mz2az.scenetrip.data.LimitLedger
            .reset()
        // 챗봇 한도와 남은 양은 계정마다 따로다 — 앞 계정의 잠금을 다음 계정에 남기지 않는다(MZ2AZ-366).
        com.mz2az.scenetrip.routetab.RouteGuideSession
            .sharedOrNull()
            ?.accountChanged()
        epoch += 1
        com.mz2az.scenetrip.data.CommunityStore
            .accountChanged()
        scope.launch { LikeStore.getInstance(appContext).refresh() }
    }

    private fun offerNickname(user: Me) {
        showingNickname = user.nicknameConfirmed == false &&
            !appContext.getSharedPreferences("scenetrip", Context.MODE_PRIVATE).getBoolean("nickname.asked.${user.id}", false)
    }

    fun finishNicknamePrompt() {
        // 아직 확정하지 않은 계정에서 건너뛴 것만 「건너뜀」 으로 센다 — 이미 정했거나 저장하고 닫은 것은 아니다(iOS `skipNickname`).
        if (me?.nicknameConfirmed == false) AppAnalytics.log(AppEvent.SetNickname(skipped = true))
        me?.id?.let {
            appContext
                .getSharedPreferences(
                    "scenetrip",
                    Context.MODE_PRIVATE,
                ).edit()
                .putBoolean("nickname.asked.$it", true)
                .apply()
        }
        showingNickname = false
    }

    suspend fun setNickname(name: String): String? {
        val before = epoch
        val expectedUser = me?.id?.toString()
        // 서버 문장이 아니라 code 로 가른다 — 못 보내는 경우(로그인 풀림·늦은 응답)도 같은 한 줄이다(iOS `NicknameOutcome.failed`).
        val failed = ReviewRules.nicknameFailureText(null)
        if (!signedIn || expectedUser == null) return failed
        val result = apiResult { api.setMyNickname(NicknameInput(name)) }
        if (before != epoch) return failed
        return result.fold(onSuccess = {
            if (!AccountReply.nickname(before, epoch, expectedUser, me?.id?.toString(), it.id.toString())) return@fold failed
            me = it
            AppAnalytics.log(AppEvent.SetNickname(skipped = false))
            null
        }, onFailure = { ReviewRules.nicknameFailureText(NetworkFailure.of(it).code) })
    }

    /** 세션 복원·새로고침도 미확정 닉네임을 확인한다. 늦은 응답은 떠난 계정을 되살리지 않는다 (iOS `loadMe`). */
    suspend fun loadMe() {
        val before = epoch
        if (!signedIn) return
        val fresh = io { api.getMe() }.getOrNull() ?: return
        if (!AccountReply.restoration(before, epoch, signedIn)) return
        if (me != null && me?.id != fresh.id) return
        me = fresh
        if (fresh.nicknameConfirmed == true) showingNickname = false
        offerNickname(fresh)
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }
}
