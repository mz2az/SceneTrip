package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

object CommunityRules {
    const val PHOTO_LIMIT = 8
    const val PAGE_SIZE = 20
    const val TITLE_LIMIT = 100
    const val BODY_LIMIT = 5000

    fun canPost(
        title: String,
        body: String,
        photoCount: Int,
    ): Boolean = title.trim().length in 1..TITLE_LIMIT && body.trim().length in 1..BODY_LIMIT && photoCount in 0..PHOTO_LIMIT

    fun failureText(error: Throwable): String {
        val failure = NetworkFailure.of(error)
        val status = failure.status ?: ApiFailure.of(error).statusCode
        return tr(
            when {
                status == 401 -> "로그인한 뒤에 쓸 수 있어요"

                failure.code == "POST_PHOTO_INVALID" -> "사진을 다시 올려 주세요"

                failure.code == "POST_COURSE_INVALID" -> "붙인 코스를 다시 골라 주세요"

                failure.code == "RATE_LIMITED" -> "요청이 많아요. 잠시 뒤 다시 시도해 주세요"

                failure.code == "POST_NOT_FOUND" -> "이 후기는 더 이상 볼 수 없어요"

                // 금칙어(MZ2AZ-461) — 제목·본문·문단·사진 설명·출처 중 어디인지는 화면에 말하지 않는다.
                ModerationRules.isTextNotAllowed(failure.code) -> ModerationRules.TEXT_NOT_ALLOWED_MESSAGE

                else -> "연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요"
            },
        )
    }

    fun uncertain(error: Throwable): Boolean {
        val status = ApiFailure.of(error).statusCode
        if (status != null) return status !in 400..499
        return error !is ConnectException && error !is UnknownHostException && error !is NoRouteToHostException
    }

    /** 업로드 UUID가 없으면 다른 기기의 같은 글과 구분할 수 없어 성공으로 확정하지 않는다. */
    fun matches(
        detail: TripPostDetail,
        input: TripPostCreate,
        expectedCourse: PostCourse?,
    ): Boolean {
        if (input.photoKeys.isEmpty() || detail.title != input.title || detail.body != input.body ||
            (detail.course != null) != (input.courseId != null)
        ) {
            return false
        }
        if (input.bodyBlocks != null && detail.bodyBlocks != input.bodyBlocks) return false
        val names = detail.photoUrls.map { it.path.substringAfterLast('/') }
        if (names != input.photoKeys.map { it.substringAfterLast('/') }) return false
        if (input.courseId == null) return true
        val course = detail.course ?: return false
        if (expectedCourse == null || expectedCourse.title != course.title) return false
        val expected =
            expectedCourse.days.map { day ->
                day.mapNotNull { stop ->
                    stop.placeId?.let { "place:$it" } ?: stop.poiId?.let { "poi:$it" }
                }
            }
        val received =
            course.days.sortedBy { it.dayNumber }.map { day ->
                day.stops.map { "${it.source.value}:${it.placeId ?: it.poiId ?: -1}" }
            }
        return expected == received
    }
}
