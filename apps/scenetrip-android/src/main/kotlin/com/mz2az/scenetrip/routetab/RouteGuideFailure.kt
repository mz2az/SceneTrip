package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.auth.AuthAction
import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.data.AIConsentRequired
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.DeviceNetwork
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.tr
import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 가이드가 답하지 못한 이유 — **계약이 정한 응답별로 갈린다** (`POST /guide/chat`·`POST /guide/plan`).
 * iOS `RouteGuideFailure` 와 같은 갈래·같은 문구다 — 두 앱이 같은 상황에서 다른 말을 하면 같은 앱으로 보이지 않는다.
 *
 * 401 도 503 도 「준비 중」이면 사용자는 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다. 응답이 없을 때는
 * **왜 없는지**를 가른다(MZ2AZ-366) — 기기가 오프라인인지, 서버에 닿지 못했는지, 기다리다 그만둔 것인지.
 */
sealed class RouteGuideFailure {
    object LocationConsentRequired : RouteGuideFailure()

    object ConsentRequired : RouteGuideFailure()

    /** 가입해야 부를 수 있다 (401). */
    object SignInRequired : RouteGuideFailure()

    /** 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). */
    object SessionExpired : RouteGuideFailure()

    /** 요청이 계약을 어겼다 (400). 서버 문장은 화면에 그대로 띄우지 않는다. */
    object BadRequest : RouteGuideFailure()

    /** 에이전트가 응답하지 않는다 (502·503·504) — 규칙 기반으로 조용히 떨어지지 않고 이유를 말한다. */
    object Unavailable : RouteGuideFailure()

    /** 기다리다 그만뒀다 — 시간을 넘겼거나 서버가 끝내 「처리 중」(409)이다. 같은 멱등 키로 다시 물으면 그 답을 받는다. */
    object TimedOut : RouteGuideFailure()

    /** 기기가 오프라인이다. */
    object Offline : RouteGuideFailure()

    /** 서버에 닿지 못했다(연결 거부·끊김) — 기기 탓이 아니다. */
    object Unreachable : RouteGuideFailure()

    /** 분당 요청 한도 (429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). */
    object RateLimited : RouteGuideFailure()

    /** 답을 기다리던 중에 그만 기다리게 했다 — 재시도·되묻기를 끊은 것이다. */
    object Interrupted : RouteGuideFailure()

    /** `422 IDEMPOTENCY_KEY_REUSED` — 같은 멱등 키에 다른 내용을 보냈다. **앱 버그다.** */
    object KeyReused : RouteGuideFailure()

    /**
     * 챗봇 한도 (429 `GUIDE_LIMIT_REACHED`) — 1 시간 창이든 하루 창이든 이 하나다. [retryAfter] 는 풀릴 때까지의 초
     * (서버의 `Retry-After`), 없으면 모른다. **자동으로 다시 보내지 않는다.**
     */
    data class LimitReached(
        val retryAfter: Int?,
    ) : RouteGuideFailure()

    /** 그 밖의 응답. 코드를 그대로 보여 준다. */
    data class Other(
        val status: Int,
    ) : RouteGuideFailure()

    /** 「다시 시도」 가 무엇을 보내야 하나 (MZ2AZ-366). */
    enum class Retry {
        /** **같은 멱등 키**로 — 서버가 이미 처리했으면 저장한 답을 주고, 아니면 한 번만 처리한다. */
        SAME_KEY,

        /** 새 키로 — 그 키로는 영영 422 다. */
        NEW_KEY,

        /** 다시 보내도 같다. 단추를 내지 않는다. */
        NONE,
    }

    /** 다시 해 볼 만한가. 서버 사정·끊김·시간 초과는 그렇다 — 요청이 틀렸거나(400) 로그인이 필요하면(401) 아니다. */
    val retry: Retry
        get() =
            when (this) {
                Unavailable, TimedOut, Offline, Unreachable, RateLimited, Interrupted -> Retry.SAME_KEY

                // 한도에 걸린 턴은 서버가 처리하지 않았다 — 풀린 뒤 같은 키로 다시 보낸다. 단추는 풀린 뒤에만 뜬다.
                is LimitReached, ConsentRequired, LocationConsentRequired -> Retry.SAME_KEY

                KeyReused -> Retry.NEW_KEY

                // 500 은 공통 계층이 한 번 다시 보내 본 뒤다 — 그래도 사람이 한 번 더 해 볼 수 있다.
                is Other -> if (status == 500) Retry.SAME_KEY else Retry.NONE

                SignInRequired, SessionExpired, BadRequest -> Retry.NONE
            }

    /** 챗봇 한도인가 — 대화창은 이것을 실패 줄이 아니라 한도 안내로 그린다. */
    val isLimit: Boolean get() = this is LimitReached

    /** 화면에 보이는 한 줄. **iOS 와 같은 문구여야 한다.** */
    val message: String
        get() =
            when (this) {
                LocationConsentRequired -> tr("위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.")

                ConsentRequired -> tr("AI 기능 사용을 허용한 뒤 다시 시도해 주세요.")

                SignInRequired -> tr("여행 가이드는 가입한 분만 쓸 수 있어요")

                SessionExpired -> tr("로그인이 풀렸어요. 다시 로그인해 주세요")

                BadRequest -> tr("요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요")

                Unavailable -> tr("가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요")

                TimedOut -> tr("답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요")

                Offline -> tr("인터넷 연결을 확인해 주세요.")

                Unreachable -> tr("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요")

                RateLimited -> tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")

                Interrupted -> tr("답을 기다리다 멈췄어요. 다시 시도해 주세요")

                // 대화창은 풀리는 때까지 남은 시간을 다시 세어 보인다([GuideLimit.Block.message]) — 이것은 받은 순간의 말이다.
                is LimitReached -> GuideLimit.Block(retryAfter, 0L).message(0L)

                KeyReused -> tr("가이드 요청을 처리하지 못했어요 (%d)").format(422)

                is Other -> tr("가이드 요청을 처리하지 못했어요 (%d)").format(status)
            }

    companion object {
        const val KEY_REUSED_CODE = "IDEMPOTENCY_KEY_REUSED"
        const val IN_PROGRESS_CODE = "IDEMPOTENCY_IN_PROGRESS"
        const val LIMIT_CODE = "GUIDE_LIMIT_REACHED"

        /** 한도 안내 한 줄 — 받은 순간의 말이다(풀리는 때까지 남은 시간을 다시 세는 쪽은 [GuideLimit.Block.message]). */
        fun limitMessage(retryAfter: Int?): String = GuideLimit.Block(retryAfter, 0L).message(0L)

        /** 오류를 계약 응답으로 분류한다. [offline] 은 기기가 오프라인인지 — 응답이 없을 때만 본다. */
        fun of(
            error: Throwable,
            offline: () -> Boolean = DeviceNetwork::isOffline,
        ): RouteGuideFailure {
            if (error is CancellationException) return Interrupted
            if (AIConsentRequired.isIn(error)) return ConsentRequired
            if (com.mz2az.scenetrip.data.LocationConsentRequired
                    .isIn(error)
            ) {
                return LocationConsentRequired
            }
            val failure = NetworkFailure.of(error)
            val status = failure.status
            // 응답이 없다 — 왜 없는지를 가른다.
            if (status == null || status <= 0) return unanswered(error, offline)
            val code = failure.code
            return when (status) {
                400 -> {
                    BadRequest
                }

                // 401 이 전부 「가입하세요」는 아니다 — 토큰 만료·폐기도 401 로 온다. `code` 로 가른다.
                401 -> {
                    when (AuthRules.action(status, code)) {
                        AuthAction.REFRESH_AND_RETRY, AuthAction.SIGN_OUT -> SessionExpired
                        AuthAction.PROMPT_SIGN_IN, AuthAction.NONE -> SignInRequired
                    }
                }

                409 -> {
                    if (code == IN_PROGRESS_CODE) TimedOut else Other(status)
                }

                422 -> {
                    if (code == KEY_REUSED_CODE) KeyReused else Other(status)
                }

                429 -> {
                    when (code) {
                        LIMIT_CODE -> LimitReached(failure.retryAfter)
                        null, "RATE_LIMITED" -> RateLimited
                        else -> Other(status)
                    }
                }

                502, 503, 504 -> {
                    Unavailable
                }

                else -> {
                    Other(status)
                }
            }
        }

        private fun unanswered(
            error: Throwable,
            offline: () -> Boolean,
        ): RouteGuideFailure =
            when {
                error is SocketTimeoutException -> TimedOut
                error is InterruptedIOException -> Interrupted
                ApiFailure.isOffline(error) || offline() -> Offline
                else -> Unreachable
            }
    }
}
