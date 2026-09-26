package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 방문 스탬프 하나 — 여행 중 성지 반경 100m 에 들어(또는 "여기 도착함"을 눌러) 서버에
 * 남은 `visitedAt` 이 근거다. iOS `ProfileTab/ProfileStamps.swift`의 `VisitStamp`를
 * 옮긴 것이다. **지어낸 기록이 아니다.**
 *
 * 마이페이지와 홈이 같이 쓴다 — 마이페이지 이식 때 이 파일을 옮기지 말고 그대로
 * 참조한다.
 */
data class VisitStamp(
    val id: Long,
    val name: String,
    val workTitle: String?,
    val courseTitle: String,
    val visitedAt: OffsetDateTime,
)

/**
 * 코스마다 상세를 받아 `visitedAt`이 찍힌 것만 모은다. 최근 것부터. 코스 수만큼
 * 요청이 나가지만 **나란히** 보낸다.
 */
suspend fun collectVisitStamps(
    courses: List<CourseSummary>,
    deviceId: UUID,
): List<VisitStamp> =
    coroutineScope {
        val coursesApi = CoursesApi(API_BASE)
        courses
            .map { course ->
                async(Dispatchers.IO) {
                    val detail = runCatching { coursesApi.getCourse(deviceId, course.id) }.getOrNull()
                    detail?.days.orEmpty().flatMap { it.items }.mapNotNull { item ->
                        item.visitedAt?.let { visitedAt ->
                            VisitStamp(
                                id = item.id,
                                name = item.name,
                                workTitle = item.sourceContentTitle,
                                courseTitle = course.title,
                                visitedAt = visitedAt,
                            )
                        }
                    }
                }
            }.awaitAll()
            .flatten()
            .sortedByDescending { it.visitedAt }
    }

/** 코스 목록부터 받아서 모은다 — 목록을 아직 안 든 화면(홈)용. */
suspend fun collectVisitStamps(deviceId: UUID): List<VisitStamp> {
    val coursesApi = CoursesApi(API_BASE)
    val list =
        withContext(Dispatchers.IO) {
            runCatching { coursesApi.listCourses(deviceId) }.getOrNull()
        } ?: return emptyList()
    return collectVisitStamps(list.items ?: emptyList(), deviceId)
}
