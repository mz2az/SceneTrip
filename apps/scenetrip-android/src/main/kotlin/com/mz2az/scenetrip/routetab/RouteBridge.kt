package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.CourseDayInput
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemInput
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemSource
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CoursePace
import com.mz2az.scenetrip.sceneapi.client.model.CourseReplace
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.CustomPinInput
import com.mz2az.scenetrip.sceneapi.client.model.PinCategory
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/**
 * 계약 타입 ↔ 화면 타입 사이의 번역. iOS `RouteTab/RouteBridge.swift`를 옮긴 것이다.
 *
 * 화면([RouteCourse]·[RouteStop])과 계약(`CourseDetail`·`CourseItem`)을 **한곳에서만**
 * 잇는다. 화면 코드가 계약 타입을 직접 만지면 서버가 필드를 하나 바꿀 때마다 화면
 * 여러 곳이 함께 깨진다.
 *
 * **아이템 id를 잃으면 방문 체크가 날아간다** — 편집 완료(`PUT`)에서 그 값을 그대로
 * 돌려보내야 한다. 그래서 [RouteStop.serverItemId]가 편집 중에도 따라다닌다.
 */
object RouteBridge {
    fun savedTitle(raw: String): String =
        raw.trim().ifEmpty {
            com.mz2az.scenetrip.data
                .tr("내 코스", "코스 제목")
        }

    fun outgoing(course: RouteCourse): CourseReplace = replace(course).copy(title = savedTitle(course.title))

    fun changed(
        opened: CourseReplace,
        course: RouteCourse,
    ): Boolean = outgoing(course) != opened

    fun isBlank(course: RouteCourse): Boolean =
        outgoing(course).let { body ->
            body.days.all { it.items.isEmpty() } &&
                body.title == savedTitle("")
        }

    // 서버 → 화면

    fun course(detail: CourseDetail): RouteCourse =
        RouteCourse(
            serverId = detail.id,
            title = detail.title,
            startDate = detail.startDate,
            pace = pace(detail.pace),
            days = detail.days.map { day -> RouteDay(stops = day.items.map { stop(it) }) },
            madeByAI = detail.origin == CourseOrigin.ai,
            isRunning = detail.status == CourseStatus.active,
        )

    /**
     * 목록 카드용. 일차 속까지는 안 받으므로 `days`가 빈 자리표시자 배열이고, 대신
     * 서버가 세어 준 장소 수를 들고 온다 — 카드에 "7곳"을 그리는 데 상세를 부를
     * 이유가 없다.
     */
    fun course(summary: CourseSummary): RouteCourse =
        RouteCourse(
            serverId = summary.id,
            title = summary.title,
            startDate = summary.startDate,
            pace = pace(summary.pace),
            days = List(maxOf(summary.dayCount, 1)) { RouteDay() },
            madeByAI = summary.origin == CourseOrigin.ai,
            isRunning = summary.status == CourseStatus.active,
            placeCountFromServer = summary.placeCount,
        )

    private fun stop(item: CourseItem): RouteStop =
        RouteStop(
            place =
                PlaceSummary(
                    // 직접 찍은 핀은 촬영지 id가 없다.
                    id = if (item.source == CourseItemSource.poi) 0 else item.placeId ?: -item.id,
                    name = item.name,
                    type = item.category,
                    address = item.address,
                    latitude = item.latitude,
                    longitude = item.longitude,
                    imageUrl = item.imageUrl,
                ),
            serverItemId = item.id,
            stayMinutes = item.dwellMinutes,
            isPinned = item.source == CourseItemSource.customPin || (item.source == CourseItemSource.poi && item.poiId == null),
            poiId = item.poiId.takeIf { item.source == CourseItemSource.poi },
            visited = item.visitedAt != null,
        )

    // 화면 → 서버

    /**
     * 편집 완료로 보낼 몸통. **보낸 것이 코스의 전부다** — 빠진 아이템은 지운 것으로
     * 처리되고, `days` 배열 길이가 곧 기간이 된다.
     */
    fun replace(course: RouteCourse): CourseReplace =
        CourseReplace(
            title = course.title,
            startDate = course.startDate,
            days =
                course.days.map { day ->
                    // 초안에 placeId가 없던 줄은 저장할 수 없다 — 이름으로 대체하지 않는다.
                    CourseDayInput(items = day.stops.filterNot { it.placeMissing }.map { item(it) })
                },
        )

    private fun item(stop: RouteStop): CourseItemInput =
        CourseItemInput(
            id = stop.serverItemId,
            placeId = stop.savablePlaceId,
            poiId = stop.poiId.takeUnless { stop.isPinned },
            customPin =
                if (stop.isPinned) {
                    CustomPinInput(
                        name = stop.place.name,
                        category = pinCategory(stop.place.type),
                        latitude = stop.place.latitude,
                        longitude = stop.place.longitude,
                    )
                } else {
                    null
                },
            // 옮기기만 할 때도 현재 값을 실어야 한다 — 비우면 서버가 기본값으로 덮어쓴다.
            dwellMinutes = stop.stayMinutes,
        )

    /** 직접 찍은 핀의 분류. 계약은 닫힌 다섯 갈래이고 화면은 한국어 이름을 쓴다. */
    private fun pinCategory(text: String?): PinCategory =
        when (text) {
            "숙소", "lodging" -> PinCategory.lodging
            "음식점·카페", "food" -> PinCategory.food
            "명소·자연", "attraction" -> PinCategory.attraction
            "거리·다리", "street" -> PinCategory.street
            else -> PinCategory.building
        }

    private fun pace(value: CoursePace?): RoutePace = if (value == CoursePace.loose) RoutePace.LOOSE else RoutePace.TIGHT
}
