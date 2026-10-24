package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.auth.AuthAction
import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.data.AIConsentRequired
import com.mz2az.scenetrip.data.LocationConsentRequired
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.tr
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 가이드가 답하지 못한 이유 — **계약이 정한 응답별로 갈린다** (`POST /guide/chat` · `POST /guide/plan`). iOS `RouteGuideFailure` 의 문구와
 * 갈래를 옮겼다 (MZ2AZ-482 E11). 마법사의 「일정을 짜지 못했습니다」 알림이 이 문구를 쓴다.
 *
 * 「준비 중」은 서버가 없던 때의 말이다. 창구가 섰으니 그 문장이 남아 있으면 거짓말이다 — 401 도 503 도 같은 말이면 사용자는 가입을 해야
 * 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다. 응답이 없을 때는 **왜 없는지**를 가른다 — 기기가 오프라인인지, 서버에 닿지 못했는지, 기다리다
 * 그만둔 것인지.
 */
sealed interface RouteGuideFailure {
    data object LocationConsentNeeded : RouteGuideFailure

    data object ConsentNeeded : RouteGuideFailure

    /** 가입해야 부를 수 있다 (401). */
    data object SignInRequired : RouteGuideFailure

    /** 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). */
    data object SessionExpired : RouteGuideFailure

    /** 요청이 계약을 어겼다 (400). 서버 사정이라 `message` 는 화면에 그대로 띄우지 않는다. */
    data object BadRequest : RouteGuideFailure

    /** 에이전트가 응답하지 않는다 (503 `GUIDE_UNAVAILABLE`, 게이트웨이의 502·504). */
    data object Unavailable : RouteGuideFailure

    /** 기다리다 그만뒀다 — 시간 초과, 또는 서버가 끝내 「처리 중」(409)이다. */
    data object TimedOut : RouteGuideFailure

    /** 기기가 오프라인이다. */
    data object Offline : RouteGuideFailure

    /** 서버에 닿지 못했다(연결 거부·끊김) — 기기 탓이 아니다. */
    data object Unreachable : RouteGuideFailure

    /** 분당 요청 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). */
    data object RateLimited : RouteGuideFailure

    /** 답을 기다리던 중에 화면이 그만 기다리게 했다. */
    data object Interrupted : RouteGuideFailure

    /** `422 IDEMPOTENCY_KEY_REUSED` — 같은 멱등 키에 다른 내용을 보냈다. **앱 버그다.** */
    data object KeyReused : RouteGuideFailure

    /** 챗봇 한도(429 `GUIDE_LIMIT_REACHED`) — 1 시간 창이든 하루 창이든 이 하나다. */
    data class LimitReached(
        val retryAfter: Int?,
    ) : RouteGuideFailure

    /** 그 밖의 응답. 코드를 그대로 보여 준다. */
    data class Other(
        val status: Int,
    ) : RouteGuideFailure

    /** 화면에 보이는 한 줄. **iOS 와 같은 문구여야 한다.** */
    val message: String
        get() =
            when (this) {
                LocationConsentNeeded -> tr("위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.")
                ConsentNeeded -> tr("AI 기능 사용을 허용한 뒤 다시 시도해 주세요.")
                SignInRequired -> tr("여행 가이드는 가입한 분만 쓸 수 있어요")
                SessionExpired -> tr("로그인이 풀렸어요. 다시 로그인해 주세요")
                BadRequest -> tr("요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요")
                Unavailable -> tr("가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요")
                TimedOut -> tr("답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요")
                Offline -> tr("인터넷 연결을 확인해 주세요.")
                Unreachable -> tr("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요")
                RateLimited -> tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
                Interrupted -> tr("답을 기다리다 멈췄어요. 다시 시도해 주세요")
                is LimitReached -> limitMessage(retryAfter)
                KeyReused -> tr("가이드 요청을 처리하지 못했어요 (%d)").format(422)
                is Other -> tr("가이드 요청을 처리하지 못했어요 (%d)").format(status)
            }

    companion object {
        const val LIMIT_CODE = "GUIDE_LIMIT_REACHED"

        /** 챗봇 한도의 짧은 창(1시간). 이보다 길게 기다려야 하면 하루 한도다. */
        const val SHORT_WINDOW_SECONDS = 3_600

        /** 한도 안내 한 줄 (iOS `GuideLimit.Block.message`). 풀리는 때를 모르면 「오늘」 도 「내일」 도 말하지 않는다. */
        fun limitMessage(retryAfter: Int?): String =
            when {
                retryAfter == null -> {
                    tr("지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요")
                }

                retryAfter <= SHORT_WINDOW_SECONDS -> {
                    tr("가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요").format(maxOf(1, (retryAfter + 59) / 60))
                }

                else -> {
                    tr("오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요")
                }
            }

        /** 오류를 계약 응답으로 분류한다. */
        fun of(error: Throwable): RouteGuideFailure {
            if (error is CancellationException) return Interrupted
            if (AIConsentRequired.isIn(error)) return ConsentNeeded
            if (LocationConsentRequired.isIn(error)) return LocationConsentNeeded
            val failure = NetworkFailure.of(error)
            val status = failure.status
            if (status == null || status <= 0) return unanswered(error)
            val code = failure.code
            return when {
                status == 400 -> {
                    BadRequest
                }

                // 401 이 전부 「가입하세요」는 아니다 — 토큰 만료·폐기도 401 로 온다. `code` 로 가른다.
                status == 401 -> {
                    when (AuthRules.action(status, code)) {
                        AuthAction.REFRESH_AND_RETRY, AuthAction.SIGN_OUT -> SessionExpired
                        AuthAction.PROMPT_SIGN_IN, AuthAction.NONE -> SignInRequired
                    }
                }

                status == 409 && code == "IDEMPOTENCY_IN_PROGRESS" -> {
                    TimedOut
                }

                status == 422 && code == "IDEMPOTENCY_KEY_REUSED" -> {
                    KeyReused
                }

                status == 429 && code == LIMIT_CODE -> {
                    LimitReached(failure.retryAfter)
                }

                status == 429 && (code == null || code == "RATE_LIMITED") -> {
                    RateLimited
                }

                status == 502 || status == 503 || status == 504 -> {
                    Unavailable
                }

                else -> {
                    Other(status)
                }
            }
        }

        /** 응답이 없었다 — 원래 오류로 왜인지 가른다. */
        private fun unanswered(error: Throwable): RouteGuideFailure =
            when (error) {
                is SocketTimeoutException -> TimedOut
                is UnknownHostException -> Offline
                else -> Unreachable
            }
    }
}
