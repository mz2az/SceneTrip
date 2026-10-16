package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.sceneapi.client.model.CourseCreate
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CoursePace
import com.mz2az.scenetrip.sceneapi.client.model.CourseReplace

/** 코스 한 건을 읽고 쓰는 서버 창구. 화면 없이도 저장 흐름을 시험하려고 인터페이스로 둔다. */
interface CourseServer {
    fun get(courseId: Long): CourseDetail

    fun create(body: CourseCreate): CourseDetail

    fun replace(
        courseId: Long,
        body: CourseReplace,
    ): CourseDetail

    fun delete(courseId: Long)
}

/** 조회 실패와 저장 실패. 둘은 서로 채우지 않는다. */
data class RouteFailureState(
    /** 마지막 조회·쓰기 호출이 실패했나. 화면의 「불러오지 못했습니다」용이다. */
    val failure: ApiFailure? = null,
    /** 편집 화면의 저장 실패. 조회 실패로는 채워지지 않는다. */
    val saveFailure: ApiFailure? = null,
)

/**
 * 코스 상세 조회와 저장 — [RouteStore]에서 Android 컨텍스트 없이 시험할 수 있는 부분을 떼어 낸 것이다.
 * iOS `RouteStore.detail`·`RouteStore.save`·`failure`·`saveFailure`를 옮겼다.
 *
 * ## 데이터 손실을 막는 두 가지
 *
 * 1. **상세를 못 받은 서버 코스는 저장하지 않는다.** 목록 요약은 일차 속이 비어 있어, 그것을
 *    `PUT /courses/{id}`로 보내면 서버 코스의 장소가 전부 지워진다. [RouteCourse.canSave]가 거짓이면
 *    요청을 보내지 않고 [ApiFailure.courseDetailNotLoaded]만 남긴다.
 * 2. **저장 오류([saveFailure])와 조회 오류([failure])를 나눈다.** 목록·재료 조회가 실패했다고 편집
 *    화면이 들어서자마자 「저장하지 못했습니다」를 띄우지 않고, 저장이 성공한 뒤 목록 재조회가 실패해도
 *    저장 실패로 보이지 않는다.
 */
class RouteCourseSession(
    private val server: CourseServer,
    private val changed: (RouteFailureState) -> Unit = {},
) {
    var state = RouteFailureState()
        private set

    val failure: ApiFailure? get() = state.failure
    val saveFailure: ApiFailure? get() = state.saveFailure

    private fun publish(value: RouteFailureState) {
        state = value
        changed(value)
    }

    private fun setFailure(value: ApiFailure?) = publish(state.copy(failure = value))

    private fun setSaveFailure(value: ApiFailure?) = publish(state.copy(saveFailure = value))

    fun recordFailure(error: Throwable) = setFailure(ApiFailure.of(error))

    fun clearLoadFailure() = setFailure(null)

    fun clearFailure() = publish(RouteFailureState())

    /**
     * 코스 하나의 속을 받아온다. 목록 카드에는 일차 속이 없으므로 편집 화면을 열 때 부른다. 서버 id가 없는
     * (아직 저장 안 한) 코스는 그대로 돌려준다. 실패하면 null — **호출부는 이때 편집 화면을 열면 안 된다.**
     */
    suspend fun detail(course: RouteCourse): RouteCourse? {
        val serverId = course.serverId ?: return course
        return apiResult { server.get(serverId) }.fold(
            onSuccess = {
                setFailure(null)
                RouteBridge.course(it)
            },
            onFailure = {
                setFailure(ApiFailure.of(it))
                null
            },
        )
    }

    /**
     * 코스를 넣거나 덮어쓴다. 편집 화면이 작업 사본을 들고 있다가 저장할 때 부른다. 서버 id가 없으면
     * 만들고(POST), 있으면 통째로 덮어쓴다(PUT) — **덮어쓰기는 보낸 것이 전부다**, 빠진 아이템은
     * 지운 것이 된다. 그래서 [RouteCourse.canSave]가 거짓이면 아무것도 보내지 않는다.
     */
    suspend fun save(
        course: RouteCourse,
        origin: String? = null,
    ): CourseDetail? {
        setSaveFailure(null)
        if (!course.canSave) {
            setSaveFailure(ApiFailure.courseDetailNotLoaded)
            return null
        }
        return apiResult {
            val serverId = course.serverId
            if (serverId != null) {
                server.replace(serverId, RouteBridge.replace(course))
            } else {
                // 만들기와 내용 채우기가 두 번에 나뉜다 — 계약이 만들 때는 기간과 출처만 받고,
                // 장소는 편집 완료로 넣게 돼 있다.
                val created =
                    server.create(
                        CourseCreate(
                            dayCount = course.days.size,
                            title = course.title,
                            origin = if (course.madeByAI) CourseOrigin.ai else CourseOrigin.self,
                            pace = if (course.pace == RoutePace.LOOSE) CoursePace.loose else CoursePace.tight,
                        ),
                    )
                val replaced =
                    try {
                        server.replace(created.id, RouteBridge.replace(course))
                    } catch (error: Exception) {
                        // 채우기가 실패하면 만든 코스를 되돌린다 — 안 그러면 장소 없는 껍데기가 목록에 남고
                        // 저장을 다시 누를 때마다 하나씩 더 쌓인다.
                        runCatching { server.delete(created.id) }
                        throw error
                    }
                // 핵심 지표 — 코스가 **새로** 만들어졌다. 고쳐 저장한 것은 세지 않는다 (MZ2AZ-353).
                AppAnalytics.log(
                    AppEvent.CreateCourse(
                        origin = origin ?: if (course.madeByAI) "ai" else "self",
                        dayCount = course.days.size,
                        placeCount = course.placeCount,
                    ),
                )
                replaced
            }
        }.fold(
            onSuccess = {
                setFailure(null)
                it
            },
            onFailure = {
                val reason = ApiFailure.of(it)
                publish(RouteFailureState(failure = reason, saveFailure = reason))
                null
            },
        )
    }
}
