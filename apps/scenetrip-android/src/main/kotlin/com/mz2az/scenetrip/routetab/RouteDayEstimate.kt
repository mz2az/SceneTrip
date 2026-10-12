package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr

/** 같은 경로에서는 체류 시간 변경만 서버의 이동 포함 합계에 반영한다. */
class RouteDayEstimate(
    stops: List<RouteStop>,
    private val totalMinutes: Int,
) {
    private data class Target(
        val pinned: Boolean,
        val poiId: Long?,
        val placeId: Long?,
        val latitude: Double,
        val longitude: Double,
        val missing: Boolean,
    )

    private val targets = stops.map(::target)
    private val dwellMinutes = dwell(stops)

    fun total(stops: List<RouteStop>): Int? {
        if (totalMinutes < dwellMinutes || targets != stops.map(::target)) return null
        return totalMinutes + dwell(stops) - dwellMinutes
    }

    companion object {
        private fun target(stop: RouteStop) =
            Target(
                stop.isPinned,
                stop.poiId,
                stop.savablePlaceId,
                stop.place.latitude,
                stop.place.longitude,
                stop.placeMissing,
            )

        private fun dwell(stops: List<RouteStop>) = stops.sumOf { it.stayMinutes.coerceAtLeast(0) }

        fun label(
            total: Int?,
            stops: List<RouteStop>,
        ): String =
            if (total != null) {
                tr("약 %s(이동 포함)").format(RouteFormat.minutes(total))
            } else {
                tr("머무는 시간 %s").format(RouteFormat.minutes(dwell(stops)))
            }
    }
}
