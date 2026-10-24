package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.auth.AuthAction
import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.data.LocationConsentRequired
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.tr

/**
 * 길찾기 한도 안내의 규칙 (MZ2AZ-366 D). iOS `RouteTab/RouteNavLimit.swift` 의 `NavLimit`.
 *
 * 서버는 길찾기를 1 분 창과 하루 창으로 센다. 넘으면 `429 NAVIGATION_LIMIT_REACHED` — **두 창의 `code` 가 같다.**
 * 가르는 것은 `Retry-After`(그 창이 끝날 때까지의 초)뿐이다.
 */
object NavLimit {
    const val CODE = "NAVIGATION_LIMIT_REACHED"

    /** `Retry-After` 가 이 초 이하면 「곧 다시」, 넘으면 「오늘은 다 썼어요」. 1 분 창은 길어야 60초다. */
    const val SHORT_WINDOW_SECONDS = 60

    enum class Window { SHORT, DAY, UNKNOWN }

    fun window(seconds: Int?): Window =
        when {
            seconds == null -> Window.UNKNOWN
            seconds <= SHORT_WINDOW_SECONDS -> Window.SHORT
            else -> Window.DAY
        }

    /** 안내 한 줄. **iOS 와 같은 문구여야 한다.** */
    fun message(window: Window): String =
        when (window) {
            Window.SHORT -> tr("길찾기를 잠시 많이 썼어요. 1분 안에 다시 찾을 수 있어요. 지도 앱에서 이어서 볼 수도 있어요")
            Window.DAY -> tr("오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요")
            Window.UNKNOWN -> tr("지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요")
        }
}

/**
 * 길찾기가 안 된 이유 — **계약이 정한 응답별로 갈린다** (`POST /navigation/next-leg`). iOS
 * `RouteTab/RouteNavFailure.swift` 와 같은 갈래·같은 문구다 (MZ2AZ-478 E4).
 *
 * 앞서 Android 는 모든 오류를 일반 문구 한 줄 + 늘 「다시 시도」 로 뭉뚱그렸다. 401 도 503 도 같은 말이면 사용자는 가입을 해야 하는지
 * 잠시 뒤 다시 눌러야 하는지 알 수 없다.
 */
sealed interface RouteNavFailure {
    /** 가입해야 부를 수 있다 (401). */
    data object SignInRequired : RouteNavFailure

    /** 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). */
    data object SessionExpired : RouteNavFailure

    /** 코스나 항목이 서버에 없다 (404). 저장 전 코스, 지운 항목. */
    data object NotFound : RouteNavFailure

    /** 코스를 시작하지 않았다 (409). 「시작」을 누르면 통한다. */
    data object CourseNotActive : RouteNavFailure

    /** 경로가 없다 (422). **다시 불러도 같다** — 섬·산속, 정류장 없는 자리. */
    data class NoRoute(
        val code: String,
    ) : RouteNavFailure

    /** 제공자(카카오)가 응답하지 않거나 한도를 넘었다 (503). 잠시 뒤 다시. */
    data object ProviderDown : RouteNavFailure

    /** 서버에 닿지 못했다. 백엔드가 꺼져 있거나 네트워크가 없다. */
    data object Unreachable : RouteNavFailure

    /** 길찾기 한도(429 `NAVIGATION_LIMIT_REACHED`). **자동으로 다시 부르지 않는다** — 화면이 지도 앱으로 넘기는 단추를 보인다. */
    data class LimitReached(
        val retryAfter: Int?,
    ) : RouteNavFailure

    /** 분당 요청 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). 사람이 「다시 시도」 를 누른다. */
    data object RateLimited : RouteNavFailure

    /** 그 밖의 응답. 코드를 그대로 보여 준다. */
    data class Other(
        val status: Int,
    ) : RouteNavFailure

    /** 위치기반서비스 이용 동의 전에는 기기 좌표를 싣는 요청이 나가지 않는다 (MZ2AZ-472). */
    data object LocationConsentNeeded : RouteNavFailure

    /** 저장 전 코스라 서버 항목 id 가 없다. */
    data object UnsavedCourse : RouteNavFailure

    /** 화면에 보이는 한 줄. **iOS 와 같은 문구여야 한다.** */
    val message: String
        get() =
            when (this) {
                SignInRequired -> {
                    tr("길찾기는 가입한 분만 쓸 수 있어요")
                }

                SessionExpired -> {
                    tr("로그인이 풀렸어요. 다시 로그인해 주세요")
                }

                NotFound, UnsavedCourse -> {
                    tr("저장된 코스의 장소에서만 길찾기를 부를 수 있어요")
                }

                CourseNotActive -> {
                    tr("코스를 시작한 뒤에 길찾기를 쓸 수 있어요")
                }

                is NoRoute -> {
                    if (code == "NO_TRANSIT_NEARBY") {
                        tr("근처에 정류장이 없어 대중교통 경로를 찾지 못했어요")
                    } else {
                        tr("여기서는 경로를 찾지 못했어요")
                    }
                }

                ProviderDown -> {
                    tr("길찾기 서비스가 잠시 응답하지 않아요. 잠시 뒤 다시 시도해 주세요")
                }

                Unreachable -> {
                    tr("연결하지 못했어요. 인터넷 연결을 확인하고 잠시 뒤 다시 시도해 주세요")
                }

                is LimitReached -> {
                    NavLimit.message(NavLimit.window(retryAfter))
                }

                RateLimited -> {
                    tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
                }

                is Other -> {
                    tr("길찾기를 처리하지 못했어요 (%d)").format(status)
                }

                LocationConsentNeeded -> {
                    tr("위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.")
                }
            }

    /**
     * 「다시 시도」 단추를 보일 것인가. 다시 불러도 같은 답이 오는 것(경로 없음·가입 필요·코스 밖)에는 단추를 두지 않는다 — 눌러도
     * 달라지지 않는 단추는 사용자를 두 번 실망시킨다. 한도는 풀린 뒤에만 다시 부를 수 있어 한도 안내가 따로 정한다.
     */
    val canRetry: Boolean
        get() =
            when (this) {
                ProviderDown, Unreachable, RateLimited, is Other, LocationConsentNeeded -> true
                else -> false
            }

    /** 길찾기 한도인가 — 안내 띠는 이것을 실패 한 줄이 아니라 한도 안내(지도 앱으로 넘기기)로 그린다. */
    val isLimit: Boolean get() = this is LimitReached

    companion object {
        /** 오류를 계약 응답으로 분류한다. */
        fun of(error: Throwable): RouteNavFailure {
            val failure = NetworkFailure.of(error)
            if (failure.code == LocationConsentRequired.CODE) return LocationConsentNeeded
            val status = failure.status ?: return Unreachable
            // 음수·0 은 HTTP 코드가 아니라 「연결 실패」 신호다.
            if (status <= 0) return Unreachable
            val code = failure.code
            return when (status) {
                // 401 이 전부 「가입하세요」는 아니다 — 토큰 만료·폐기도 401 로 온다. `code` 로 가른다.
                401 -> {
                    when (AuthRules.action(401, code)) {
                        AuthAction.REFRESH_AND_RETRY, AuthAction.SIGN_OUT -> SessionExpired
                        AuthAction.PROMPT_SIGN_IN, AuthAction.NONE -> SignInRequired
                    }
                }

                404 -> {
                    NotFound
                }

                409 -> {
                    CourseNotActive
                }

                422 -> {
                    NoRoute(code ?: "ROUTE_NOT_FOUND")
                }

                429 -> {
                    when (code) {
                        NavLimit.CODE -> LimitReached(failure.retryAfter)
                        null, "RATE_LIMITED" -> RateLimited
                        else -> Other(429)
                    }
                }

                503 -> {
                    ProviderDown
                }

                else -> {
                    Other(status)
                }
            }
        }
    }
}
