package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteGuidePlan
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemSource
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlan
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanDay
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanStop
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 챗봇 일정 왕복(MZ2AZ-425·434) — 촬영지·편의시설·개인 핀 번호를 종류에 맞춰 주고받는다. 모델은 부르지 않는다. */
class GuidePlanIdentityTests {
    private fun item(
        id: Long,
        source: CourseItemSource,
        name: String,
        placeId: Long? = null,
        poiId: Long? = null,
        customPinId: Long? = null,
    ) = CourseItem(
        id = id,
        source = source,
        name = name,
        latitude = 37.0 + id / 1000.0,
        longitude = 127.0,
        dwellMinutes = 45,
        placeId = placeId,
        poiId = poiId,
        customPinId = customPinId,
    )

    private fun course(vararg items: CourseItem) =
        RouteCourse(
            serverId = 9,
            title = "코스",
            detailLoaded = true,
            days = listOf(RouteDay(stops = items.map { RouteBridge.stop(it) })),
        )

    private fun planStop(
        order: Int,
        name: String,
        placeId: Long? = null,
        poiId: Long? = null,
        customPinId: Long? = null,
        latitude: Double? = 37.5,
    ) = GuidePlanStop(
        order = order,
        name = name,
        placeId = placeId,
        poiId = poiId,
        customPinId = customPinId,
        latitude = latitude,
        longitude = 127.0,
        arriveMinute = 540,
        dwellMinutes = 30,
    )

    private fun plan(vararg stops: GuidePlanStop) =
        GuidePlan(pace = GuidePlan.Pace.packed, days = listOf(GuidePlanDay(day = 1, stops = stops.toList())))

    @Test
    fun pinNumbersComeFromTheServerAndGoBackWithTheKindMatchingPlanStop() {
        val original =
            course(
                item(1, CourseItemSource.place, "촬영지", placeId = 7),
                item(2, CourseItemSource.poi, "카페", poiId = 8),
                item(3, CourseItemSource.customPin, "내 핀", customPinId = 9),
            )
        val stops = original.stops
        assertEquals(9L, stops[2].customPinId)
        assertNull(stops[0].customPinId)

        val sent = RouteGuidePlan.plan(original).days[0].stops
        assertEquals(listOf<Long?>(7, null, null), sent.map { it.placeId })
        assertEquals(listOf<Long?>(null, 8, null), sent.map { it.poiId })
        assertEquals(listOf<Long?>(null, null, 9), sent.map { it.customPinId })
    }

    @Test
    fun roundTripKeepsKindsIdsAndOrderInsteadOfTurningPoisAndPinsIntoMissingRows() {
        val original =
            course(
                item(1, CourseItemSource.place, "촬영지", placeId = 7),
                item(2, CourseItemSource.poi, "카페", poiId = 8),
                item(3, CourseItemSource.customPin, "내 핀", customPinId = 9),
            )
        val sent = RouteGuidePlan.plan(original)
        val reversed =
            sent.copy(
                days =
                    sent.days.map { day ->
                        day.copy(
                            stops =
                                day.stops.reversed().mapIndexed { i, s ->
                                    s.copy(
                                        order =
                                            i + 1,
                                    )
                                },
                        )
                    },
            )

        val revised = RouteGuidePlan.revising(original, reversed)

        val rows = revised.stops
        assertEquals(listOf("내 핀", "카페", "촬영지"), rows.map { it.place.name })
        assertTrue(rows.none { it.placeMissing })
        assertTrue(rows[0].isPinned)
        assertEquals(9L, rows[0].customPinId)
        assertEquals(8L, rows[1].poiId)
        assertFalse(rows[1].isPinned)
        assertEquals(7L, rows[2].savablePlaceId)
        // 같은 대상이 한 줄씩이므로 서버 항목 번호를 이어받는다 — 저장 때 같은 줄로 인식된다.
        assertEquals(listOf<Long?>(3, 2, 1), rows.map { it.serverItemId })
        // 저장 몸통도 종류별로 하나씩만 싣는다.
        val saved = RouteBridge.replace(revised).days[0].items
        assertNotNull(saved[0].customPin)
        assertNull(saved[0].placeId)
        assertEquals(8L, saved[1].poiId)
        assertEquals(7L, saved[2].placeId)
    }

    @Test
    fun aRowWithNoIdOrMoreThanOneIdIsKeptAsUnsavableAndNeverRecoveredFromTheName() {
        val drafted =
            RouteGuidePlan.course(
                plan(
                    planStop(1, "이름만", placeId = null),
                    planStop(2, "둘 다", placeId = 7, poiId = 8),
                    planStop(3, "번호 0", poiId = 0),
                    planStop(4, "정상", poiId = 5),
                ),
                "초안",
                null,
                RoutePace.TIGHT,
            )
        assertEquals(listOf(true, true, true, false), drafted.stops.map { it.placeMissing })
        assertEquals(listOf<Long?>(null, null, null, 5L), drafted.stops.map { it.poiId })
        // 저장 몸통에서는 저장할 수 없는 줄이 빠진다.
        assertEquals(
            1,
            RouteBridge
                .replace(drafted)
                .days[0]
                .items.size,
        )
    }

    @Test
    fun aTargetListedTwiceDoesNotInheritServerRowOrVisitedMark() {
        val original =
            course(
                item(1, CourseItemSource.place, "한 곳", placeId = 7),
                item(2, CourseItemSource.place, "다른 곳", placeId = 5),
            )
        val marked = original.copy(days = original.days.map { it.copy(stops = it.stops.map { stop -> stop.copy(visited = true) }) })
        val twice = plan(planStop(1, "한 곳", placeId = 7), planStop(2, "한 곳", placeId = 7), planStop(3, "다른 곳", placeId = 5))

        val revised = RouteGuidePlan.revising(marked, twice)

        // 같은 대상이 새 일정에 두 줄이면 어느 줄이 옛 줄인지 알 수 없다 — 임의로 짝짓지 않는다.
        assertEquals(listOf<Long?>(null, null, 2), revised.stops.map { it.serverItemId })
        assertEquals(listOf(false, false, true), revised.stops.map { it.visited })
    }

    @Test
    fun poiTextAndTypeSurviveARevisionOfTheSameSingleRow() {
        val poi = item(2, CourseItemSource.poi, "카페", poiId = 8)
        val original =
            RouteCourse(
                serverId = 9,
                title = "코스",
                detailLoaded = true,
                days =
                    listOf(
                        RouteDay(
                            stops =
                                listOf(
                                    RouteBridge.stop(poi).copy(
                                        place = PlaceSummary(id = 0, name = "카페", type = "음식점", latitude = 37.0, longitude = 127.0),
                                    ),
                                ),
                        ),
                    ),
            )
        val revised = RouteGuidePlan.revising(original, RouteGuidePlan.plan(original))
        assertEquals("음식점", revised.stops[0].place.type)
        assertEquals(2L, revised.stops[0].serverItemId)
    }
}
