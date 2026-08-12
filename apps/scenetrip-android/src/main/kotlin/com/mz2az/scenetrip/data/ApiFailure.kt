package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException

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
) {
    /**
     * 재시도가 의미 있는 경우.
     *
     * 계약이 "재시도해도 된다 — 클라이언트가 고칠 것은 없다" 고 적은 것은 `500`
     * 뿐이다. 서버에 닿지도 못한 경우도 같이 본다 — 그쪽은 앱이 고칠 것이 없다는
     * 점에서 성격이 같다.
     */
    val isRetryable: Boolean get() = statusCode == null || statusCode == 500

    /** 화면에 띄우는 문구. iOS `ErrorView` 와 **같은 세 갈래**다. */
    val message: String get() =
        when (statusCode) {
            null -> "서버에 연결하지 못했습니다."
            500 -> "잠시 문제가 생겼습니다."
            else -> "요청을 처리하지 못했습니다."
        }

    companion object {
        fun of(error: Throwable): ApiFailure =
            when (error) {
                // 생성 클라이언트는 상태 코드를 모를 때 -1 을 넣는다. 그것은 HTTP 코드가
                // 아니므로 "닿지 못했다" 와 같이 다룬다.
                is ClientException -> ApiFailure(error.statusCode.takeIf { it > 0 })

                is ServerException -> ApiFailure(error.statusCode.takeIf { it > 0 })

                // 연결 실패·타임아웃은 OkHttp 의 IOException 으로 온다.
                else -> ApiFailure(statusCode = null)
            }
    }
}
