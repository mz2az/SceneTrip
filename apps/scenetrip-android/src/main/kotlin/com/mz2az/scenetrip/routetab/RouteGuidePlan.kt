package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.GuidePlan
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TravelBasis
import java.time.LocalDate

/**
 * 계약의 일정 초안(`GuidePlan`) → 화면의 코스(`RouteCourse`). iOS
 * `RouteTab/RouteGuidePlan.swift`의 **읽는 방향만** 옮긴 것이다 — 챗봇으로 초안을 고쳐
 * 쓰는 반대 방향(`plan(from: RouteCourse)`)은 RouteGuide(챗봇) 자체가 아직 없어 이식하지
 * 않았다.
 *
 * 저장된 코스와 다른 모양이고 일부러 합치지 않는다 — 초안에는 도착 시각과 "넣지 못한 곳"이
 * 있고 DB id 가 없다.
 */
object RouteGuidePlan {
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
                                skipped.add("${stop.name} — 좌표 없음")
                                return@mapNotNull null
                            }
                            RouteStop(
                                place =
                                    PlaceSummary(
                                        id = stop.placeId ?: missingId(stop.name, stop.order),
                                        name = stop.name,
                                        type = null,
                                        address = stop.address,
                                        latitude = latitude,
                                        longitude = longitude,
                                    ),
                                stayMinutes = stop.dwellMinutes ?: RouteStop.DEFAULT_STAY_MINUTES,
                                arriveMinute = stop.arriveMinute,
                                placeMissing = stop.placeId == null,
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
            draftNotes = notes(plan) + skipped.map { "뺀 곳 · $it" },
        )
    }

    /** 사용자에게 알릴 것 — 뺀 곳과 이유, 에이전트의 주의, 거리의 근거. */
    fun notes(plan: GuidePlan): List<String> {
        val out = mutableListOf<String>()
        for (day in plan.days) {
            for (dropped in day.dropped.orEmpty()) {
                out.add("${day.day}일차에서 뺀 곳 · ${dropped.name} — ${dropped.reason}")
            }
        }
        val agentNotes = plan.notes.orEmpty().map { it.replace("**", "") }
        out.addAll(agentNotes)
        if (plan.travelBasis == TravelBasis.straightMinusLine && agentNotes.none { it.contains("직선") }) {
            out.add("거리는 직선 어림이에요 — 실제 길은 더 길 수 있어요")
        }
        return out
    }

    /** 알림줄의 한 줄 요약 — "뺀 곳 7 · 주의 3". */
    fun notesSummary(notes: List<String>): String {
        val dropped = notes.count { it.contains("뺀 곳 ·") }
        val others = notes.size - dropped
        val parts = mutableListOf<String>()
        if (dropped > 0) parts.add("뺀 곳 $dropped")
        if (others > 0) parts.add("주의 $others")
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
