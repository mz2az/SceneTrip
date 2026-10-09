package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiResponse
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 오류의 본문·헤더를 계약 코드로 해석한다. 서버 오류 메시지를 사용자에게 그대로 내보내지 않는다. */
data class NetworkFailure(
    val status: Int?,
    val code: String?,
    val retryAfter: Int?,
) {
    val message: String get() =
        tr(
            when (code) {
                "NICKNAME_TAKEN" -> "이미 쓰는 닉네임입니다"
                "NICKNAME_INVALID" -> "닉네임 규칙을 확인해 주세요"
                "REVIEW_PHOTO_INVALID" -> "사진을 다시 올려 주세요"
                "UPLOAD_TOO_LARGE" -> "사진이 너무 커요"
                "UPLOAD_TYPE_UNSUPPORTED" -> "이 사진 형식은 올릴 수 없어요"
                "UPLOAD_UNAVAILABLE" -> "사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요"
                "SIGN_IN_REQUIRED", "ACCESS_TOKEN_EXPIRED", "SESSION_REQUIRED" -> "로그인이 필요해요"
                "RATE_LIMITED" -> "요청이 많아요. 잠시 뒤 다시 시도해 주세요"
                "REVIEW_NOT_FOUND" -> "리뷰를 찾지 못했어요"
                else -> if (status == null) "서버에 연결하지 못했습니다." else "요청을 처리하지 못했습니다."
            },
        )

    companion object {
        fun of(error: Throwable): NetworkFailure {
            val response =
                when (error) {
                    is ClientException -> error.response
                    is ServerException -> error.response
                    else -> null
                }
            val body =
                when (response) {
                    is ClientError<*> -> response.body?.toString()
                    is ServerError<*> -> response.body?.toString()
                    else -> error.message
                }
            val api = response as? ApiResponse<*>
            val retry =
                api
                    ?.headers
                    ?.entries
                    ?.firstOrNull { it.key.equals("Retry-After", true) }
                    ?.value
                    ?.firstOrNull()
                    ?.toIntOrNull()
            return NetworkFailure(api?.statusCode, AuthRules.apiCode(body), retry)
        }
    }
}

/** 취소는 실패 화면으로 바꾸지 않고 코루틴에 돌려준다. */
suspend fun <T> apiResult(block: () -> T): Result<T> =
    withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
