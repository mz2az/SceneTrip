package com.mz2az.scenetrip.routetab

/**
 * 코스의 표시 순서·번호는 그대로 두고 이동에 쓰는 곳만 고른다. iOS `RouteAvailability`(MZ2AZ-458).
 *
 * 숨김·폐업 행은 지도 핀·행 번호·저장 ID를 유지한 채 다음 목적지, 시작 안내, 행 사이·총 직선거리, 동선 최적화,
 * 머무는 시간 합에서만 빠진다. 서버의 이동 포함 시간은 그 행을 포함한 값이므로 맞지 않아 쓰지 않는다.
 */
object RouteAvailability {
    /** 아직 안 간 첫 방문 가능 행과 그 번호(1부터, 숨김·폐업 행도 센다). */
    fun nextUnvisited(stops: List<RouteStop>): Pair<Int, RouteStop>? =
        stops.indexOfFirst { it.canNavigate && !it.visited }.takeIf { it >= 0 }?.let { (it + 1) to stops[it] }

    /** 미방문 행이 숨김·폐업뿐이면 방문 완료가 아니다. 전부 방문했다면 재시작할 곳을 살핀다. */
    fun hasOnlyUnavailableRemaining(stops: List<RouteStop>): Boolean {
        val remaining = stops.filter { !it.visited }
        val candidates = remaining.ifEmpty { stops }
        return candidates.isNotEmpty() && candidates.none { it.canNavigate }
    }

    /** 시작할 목적지. 없으면(null) 화면·서버의 활성 상태를 바꾸지 않는다. */
    fun startTarget(stops: List<RouteStop>): RouteStop? {
        if (hasOnlyUnavailableRemaining(stops)) return null
        return nextUnvisited(stops)?.second ?: stops.firstOrNull { it.canNavigate }
    }

    /** 코스를 시작 상태로 바꾸고 첫 목적지를 함께 돌려준다. 시작할 곳이 없으면 null. iOS `RouteAvailability.start`. */
    fun start(
        course: RouteCourse,
        dayIndex: Int,
    ): Pair<RouteCourse, RouteStop>? {
        val stops = course.days.getOrNull(dayIndex)?.stops ?: return null
        val target = startTarget(stops) ?: return null
        return course.copy(isRunning = true) to target
    }

    /** [index] 행에서 다음 방문 가능 행까지의 직선거리(km). 그 행이 숨김·폐업이거나 다음이 없으면 null. */
    fun nextKilometers(
        index: Int,
        stops: List<RouteStop>,
    ): Double? {
        val here = stops.getOrNull(index)?.takeIf { it.canNavigate } ?: return null
        val next = stops.drop(index + 1).firstOrNull { it.canNavigate } ?: return null
        return RouteGeometry.kilometers(here.place, next.place)
    }

    /** 방문 가능한 곳의 머무는 시간 합(분). */
    fun stayTotal(stops: List<RouteStop>): Int = stops.filter { it.canNavigate }.sumOf { it.stayMinutes.coerceAtLeast(0) }

    /** 숨김·폐업 행은 원래 자리에 보존하고 방문 가능한 곳의 동선만 정렬한다. */
    fun reordering(
        stops: List<RouteStop>,
        transform: (List<RouteStop>) -> List<RouteStop>,
    ): List<RouteStop> {
        val ordered = transform(stops.filter { it.canNavigate }).iterator()
        return stops.map { if (it.canNavigate && ordered.hasNext()) ordered.next() else it }
    }
}
