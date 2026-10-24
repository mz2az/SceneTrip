package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlan
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanStop
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TravelBasis
import java.time.LocalDate

/**
 * 계약의 일정 초안(`GuidePlan`) ↔ 화면의 코스(`RouteCourse`). iOS
 * `RouteTab/RouteGuidePlan.swift`·`RouteGuideIdentity.swift`를 옮긴 것이다. 두 방향이 다 필요하다 —
 * 마법사와 챗봇의 `plan.*` 이 준 초안을 화면에 그리고([course]), 챗봇으로 고치려면 편집 중인 사본을
 * 같은 모양으로 되돌려 보낸다([plan]). 이때 촬영지(`placeId`)·편의시설(`poiId`)·개인 핀(`customPinId`)을
 * 종류에 맞춰 주고받아 기존 줄이 「저장 안 됨」으로 바뀌지 않게 한다(MZ2AZ-425·434).
 *
 * 저장된 코스와 다른 모양이고 일부러 합치지 않는다 — 초안에는 도착 시각과 "넣지 못한 곳"이
 * 있고 DB id 가 없다.
 */
object RouteGuidePlan {
    /** 손으로 고친 일정을 같은 계약으로 되돌려 보내야 챗봇이 낡은 초안을 고치지 않는다. */
    fun plan(course: RouteCourse): GuidePlan =
        GuidePlan(
            pace = if (course.pace == RoutePace.LOOSE) GuidePlan.Pace.relaxed else GuidePlan.Pace.packed,
            days =
                course.days.mapIndexed { dayIndex, day ->
                    com.mz2az.scenetrip.sceneapi.client.model.GuidePlanDay(
                        day = dayIndex + 1,
                        stops =
                            day.stops.mapIndexed { index, stop ->
                                com.mz2az.scenetrip.sceneapi.client.model.GuidePlanStop(
                                    order = index + 1,
                                    name = stop.place.name,
                                    placeId = stop.savablePlaceId,
                                    // 종류에 맞는 번호만 싣는다 — 한 줄은 촬영지·편의시설·개인 핀 셋 중 하나다(MZ2AZ-425·434).
                                    poiId = if (stop.placeMissing) null else stop.poiId,
                                    customPinId = if (stop.isPinned && !stop.placeMissing) stop.customPinId else null,
                                    latitude = stop.place.latitude,
                                    longitude = stop.place.longitude,
                                    address = stop.place.address,
                                    arriveMinute = stop.arriveMinute ?: 0,
                                    dwellMinutes = stop.stayMinutes,
                                )
                            },
                    )
                },
        )

    fun course(
        plan: GuidePlan,
        title: String,
        startDate: LocalDate?,
        pace: RoutePace,
    ): RouteCourse {
        val skipped = mutableListOf<String>()
        val days =
            plan.days.sortedBy { it.day }.map { day ->
                RouteDay(
                    stops =
                        day.stops.sortedBy { it.order }.mapNotNull { stop ->
                            val latitude = stop.latitude
                            val longitude = stop.longitude
                            if (latitude == null || longitude == null) {
                                skipped.add(tr("%s — 좌표 없음").format(stop.name))
                                return@mapNotNull null
                            }
                            // 종류와 ID가 불명확한 줄은 **이름으로 대체하지 않는다** — placeMissing 으로 남고 저장에서 빠진다.
                            val identity = identity(stop)
                            RouteStop(
                                place =
                                    PlaceSummary(
                                        id =
                                            when (identity) {
                                                is Identity.Place -> identity.id
                                                is Identity.Poi -> 0L
                                                is Identity.Pin -> -identity.id
                                                null -> missingId(stop.name, stop.order)
                                            },
                                        name = stop.name,
                                        type = null,
                                        address = stop.address,
                                        latitude = latitude,
                                        longitude = longitude,
                                    ),
                                isPinned = identity is Identity.Pin,
                                poiId = (identity as? Identity.Poi)?.id,
                                customPinId = (identity as? Identity.Pin)?.id,
                                stayMinutes = stop.dwellMinutes ?: RouteStop.DEFAULT_STAY_MINUTES,
                                arriveMinute = stop.arriveMinute,
                                placeMissing = identity == null,
                            )
                        },
                )
            }
        return RouteCourse(
            title = title,
            startDate = startDate,
            pace = pace,
            days = days.ifEmpty { listOf(RouteDay()) },
            madeByAI = true,
            draftNotes = notes(plan) + skipped.map { tr("뺀 곳 · %s").format(it) },
        )
    }

    /**
     * AI 가 순서·일차를 고친 일정([GuidePlan])을 지금 코스에 적용한다. 코스의 서버 번호·진행 상태는 이어받는다.
     * iOS `RouteGuidePlan.revising`(`RouteGuideIdentity`).
     *
     * 서로 다른 표의 같은 숫자는 다른 대상이다 — 촬영지·편의시설·개인 핀의 번호를 따로 견주고, 이름·좌표로 번호를
     * 복원하지 않는다. 숨김·폐업 표시([RouteStop.unavailable])는 **같은 대상의 모든 행**에 이어받는다. 항목 번호·방문
     * 표시·분류·표시말은 옛 코스와 새 일정에 그 대상이 **한 줄씩만** 있을 때만 이어받는다 — 같은 대상을 두 번 담은
     * 코스는 일정에 항목 번호가 없어 임의로 짝짓지 않는다(MZ2AZ-458·434).
     */
    fun revising(
        original: RouteCourse,
        plan: GuidePlan,
    ): RouteCourse {
        val fresh = course(plan, original.title, original.startDate, original.pace)
        val oldByIdentity = original.stops.mapNotNull { stop -> identity(stop)?.let { it to stop } }.groupBy({ it.first }, { it.second })
        val newCounts =
            fresh.stops
                .mapNotNull { identity(it) }
                .groupingBy { it }
                .eachCount()
        val unavailable = oldByIdentity.mapValues { (_, rows) -> rows.firstNotNullOfOrNull { it.unavailable } }
        val days =
            fresh.days.map { day ->
                day.copy(
                    stops =
                        day.stops.map { stop ->
                            val key = identity(stop) ?: return@map stop
                            val carried = stop.copy(unavailable = unavailable[key])
                            val previous = oldByIdentity[key]?.singleOrNull()
                            if (previous == null || newCounts[key] != 1) {
                                carried
                            } else {
                                carried.copy(
                                    serverItemId = previous.serverItemId,
                                    visited = previous.visited,
                                    poiText = previous.poiText,
                                    place = carried.place.copy(type = previous.place.type),
                                )
                            }
                        },
                )
            }
        return fresh.copy(
            serverId = original.serverId,
            isRunning = original.isRunning,
            detailLoaded = original.detailLoaded,
            days = days,
        )
    }

    /** 서로 다른 표의 같은 숫자는 다른 대상이다. 이름·좌표로 번호를 복원하지 않는다. */
    sealed interface Identity {
        data class Place(
            val id: Long,
        ) : Identity

        data class Poi(
            val id: Long,
        ) : Identity

        data class Pin(
            val id: Long,
        ) : Identity
    }

    /** 일정 줄이 가리키는 대상 — 번호가 **정확히 하나**이고 양수일 때만. 아니면 null(저장하지 못하는 줄). */
    fun identity(stop: GuidePlanStop): Identity? {
        val ids = listOfNotNull(stop.placeId, stop.poiId, stop.customPinId)
        if (ids.size != 1 || ids[0] <= 0) return null
        return when {
            stop.placeId != null -> Identity.Place(ids[0])
            stop.poiId != null -> Identity.Poi(ids[0])
            else -> Identity.Pin(ids[0])
        }
    }

    private fun identity(stop: RouteStop): Identity? {
        if (stop.placeMissing) return null
        return when {
            stop.isPinned -> stop.customPinId?.takeIf { it > 0 }?.let(Identity::Pin)
            stop.poiId != null -> Identity.Poi(stop.poiId)
            else -> stop.savablePlaceId?.let(Identity::Place)
        }
    }

    /** 사용자에게 알릴 것 — 뺀 곳과 이유, 에이전트의 주의, 거리의 근거. */
    fun notes(plan: GuidePlan): List<String> {
        val out = mutableListOf<String>()
        for (day in plan.days) {
            for (dropped in day.dropped.orEmpty()) {
                out.add(tr("%d일차에서 뺀 곳 · %s — %s").format(day.day, dropped.name, dropped.reason))
            }
        }
        val agentNotes = plan.notes.orEmpty().map { it.replace("**", "") }
        out.addAll(agentNotes)
        if (plan.travelBasis == TravelBasis.straightMinusLine && agentNotes.none { it.contains("직선") }) {
            out.add(tr("거리는 직선 어림이에요 — 실제 길은 더 길 수 있어요"))
        }
        return out
    }

    /** 알림줄의 한 줄 요약 — "뺀 곳 7 · 주의 3". */
    fun notesSummary(notes: List<String>): String {
        val dropped = notes.count { it.contains(tr("뺀 곳 ·")) }
        val others = notes.size - dropped
        val parts = mutableListOf<String>()
        if (dropped > 0) parts.add(tr("뺀 곳 %d").format(dropped))
        if (others > 0) parts.add(tr("주의 %d").format(others))
        return parts.joinToString(" · ")
    }

    /** "09:00". 계약은 0시 기준 정수 분(540)만 주고 표시 문자열은 화면이 만든다. */
    fun clock(minute: Int): String {
        val bounded = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(bounded / 60, bounded % 60)
    }

    /** `placeId` 가 없는 정지점의 임시 id. 촬영지 id·직접 찍은 핀과 겹치지 않는 자리를 쓴다. */
    private fun missingId(
        name: String,
        order: Int,
    ): Long = -1_000_000_000L - (kotlin.math.abs(name.hashCode() % 1_000_000)) - order
}
