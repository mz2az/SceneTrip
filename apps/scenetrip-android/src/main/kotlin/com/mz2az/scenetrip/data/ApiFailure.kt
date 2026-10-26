package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import java.net.UnknownHostException

/**
 * 실패를 화면이 쓸 수 있는 형태로 줄인 것. iOS `Models/SceneData.swift` 의
 * `ApiFailure` 와 같다.
 *
 * ## 왜 예외를 그대로 쓰지 않는가
 *
 * 생성 클라이언트마다 실패를 다르게 표현한다. **Swift 는 연결 실패를 `-1`, HTTP 가
 * 아닌 응답을 `-2` 로 돌려주고, 코틀린은 `ClientException`(4xx)·`ServerException`
 * (5xx)을 던지며 연결 실패는 OkHttp 의 `IOException` 으로 새어 나온다.**
 * MZ2AZ-193 이 "붙이기 전에 확인하라" 고 경고한 자리다.
 *
 * 화면이 알아야 하는 것은 그 표현이 아니라 **"재시도가 의미 있는가"** 뿐이므로,
 * 두 플랫폼이 각자 자기 클라이언트를 여기서 같은 모양으로 줄인다.
 */
data class ApiFailure(
    /** HTTP 상태 코드. **서버에 닿지도 못했으면 null 이다.** */
    val statusCode: Int?,
    /** 오류 본문의 `code`. 금칙어·숨김·폐업처럼 구체적인 안내가 필요한 경우에 쓴다(MZ2AZ-461·458). */
    val apiCode: String? = null,
    /** 오류 본문의 `traceId` — **500 응답에만** 실린다. 사용자가 이 문자열을 알려 주면 서버 쪽에서 그 요청을 찾는다. */
    val traceId: String? = null,
    /** 기기가 오프라인이라 실패했다 — 서버 탓이 아니라 「인터넷 연결」 을 말해야 한다(iOS MZ2AZ-366). */
    val isOffline: Boolean = false,
) {
    /**
     * 재시도가 의미 있는 경우 — **다시 누르면 달라질 수 있는 것.** 서버에 닿지 못했거나(상태 코드 없음),
     * 서버 사정(500·502·503·504)이거나, 분당 한도(429)다. 400·404 처럼 요청이 틀린 것은 눌러도 같다.
     *
     * 여기까지 올라온 실패는 공통 계층이 이미 몇 번 다시 보내 본 것이다 — 그래도 안 된 것이라 사람에게
     * 단추를 준다. iOS `ApiFailure.isRetryable` 과 같다.
     */
    val isRetryable: Boolean get() = statusCode == null || statusCode in RETRYABLE_STATUS

    /** 화면에 띄우는 문구. iOS `ErrorView` 와 **같은 세 갈래**다. */
    val message: String get() {
        // 숨김·폐업으로 거절된 새 담기(409)는 종류별 안내다. 그 밖의 409 는 아래 일반 문구다(MZ2AZ-458).
        UnavailableItem.rejection(statusCode, apiCode)?.let { return it }
        return when {
            // 코스 이름이 금칙어에 걸렸다(`POST /courses`·`PUT /courses/{id}`) — 고쳐 달라고 한다.
            ModerationRules.isTextNotAllowed(apiCode) -> ModerationRules.textNotAllowedMessage

            // 동의 전에는 AI 요청이 나가지 않는다(MZ2AZ-468) — 연결 실패가 아니라 허용을 안내한다.
            apiCode == AIConsentRequired.CODE -> tr(AIConsentRequired.MESSAGE_KO)

            // 위치기반서비스 동의 전에는 기기 좌표가 나가지 않는다(MZ2AZ-472).
            apiCode == LocationConsentRequired.CODE -> tr(LocationConsentRequired.MESSAGE_KO)

            statusCode == null && isOffline -> tr("인터넷 연결을 확인해 주세요.")

            statusCode == null -> tr("서버에 연결하지 못했습니다.")

            statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504 -> tr("잠시 문제가 생겼습니다.")

            statusCode == 429 -> tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요.")

            else -> tr("요청을 처리하지 못했습니다.")
        }
    }

    /**
     * 코스 저장 실패 알림에 띄우는 문구. 상세를 받지 못한 코스를 저장하려던 경우(서버에 닿은 적이 없다)는
     * 「코스를 불러오지 못했어요」다. iOS `ApiFailure.courseSaveMessage`.
     */
    val courseSaveMessage: String get() {
        if (this == courseDetailNotLoaded) return tr("코스를 불러오지 못했어요")
        // 숨김·폐업 거절은 첫 실패의 종류만 알 수 있다 — 특정 행을 지목하지 않고 편집이 남아 있음을 알린다(MZ2AZ-458).
        val rejection = UnavailableItem.rejection(statusCode, apiCode) ?: return message
        return rejection + " " + tr("편집 내용은 그대로예요. 해당 항목을 빼고 다시 저장해 주세요.")
    }

    companion object {
        private val RETRYABLE_STATUS = setOf(429, 500, 502, 503, 504)

        /** 상세를 받지 못한 서버 코스를 저장하려 했다 — 요청을 보내지 않고 막는다. iOS `ApiFailure.courseDetailNotLoaded`. */
        val courseDetailNotLoaded = ApiFailure(statusCode = null, apiCode = "COURSE_DETAIL_NOT_LOADED")

        fun of(error: Throwable): ApiFailure =
            when {
                AIConsentRequired.isIn(error) -> ApiFailure(statusCode = null, apiCode = AIConsentRequired.CODE)
                LocationConsentRequired.isIn(error) -> ApiFailure(statusCode = null, apiCode = LocationConsentRequired.CODE)
                else -> ofResponse(error)
            }

        private fun ofResponse(error: Throwable): ApiFailure =
            when (error) {
                // 생성 클라이언트는 상태 코드를 모를 때 -1 을 넣는다. 그것은 HTTP 코드가
                // 아니므로 "닿지 못했다" 와 같이 다룬다.
                is ClientException -> reached(error)

                is ServerException -> reached(error)

                // 연결 실패·타임아웃은 OkHttp 의 IOException 으로 온다. 주소를 풀지 못했거나 시스템이 네트워크가 없다고 하면 오프라인이다.
                else -> ApiFailure(statusCode = null, isOffline = isOffline(error) || DeviceNetwork.isOffline())
            }

        private fun reached(error: Throwable): ApiFailure {
            val failure = NetworkFailure.of(error)
            val status =
                when (error) {
                    is ClientException -> error.statusCode
                    is ServerException -> error.statusCode
                    else -> -1
                }.takeIf { it > 0 }
            return ApiFailure(status, failure.code, traceId = status?.let { failure.traceId })
        }

        /**
         * 기기가 오프라인이라 실패했나. iOS 는 `notConnectedToInternet`·`dataNotAllowed`·`internationalRoamingOff` 를 본다.
         * Android 는 네트워크가 없으면 주소를 풀지 못해 [UnknownHostException] 이 난다 — 서버가 꺼진 것(연결 거부)과 구별된다.
         */
        fun isOffline(error: Throwable): Boolean {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 8) {
                if (cause is UnknownHostException) return true
                cause = cause.cause
                depth++
            }
            return false
        }
    }
}
