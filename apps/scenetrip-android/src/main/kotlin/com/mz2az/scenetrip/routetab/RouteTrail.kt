package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.haversineKm

/**
 * **지나온 길은 지우고 남은 길만 그린다** (MZ2AZ-478 E6, iOS `RouteNavModels.swift` 의 `RouteTrail`).
 *
 * 안내 중에 경로선이 걸어온 길 위에 그대로 남으면 어디까지 왔는지가 선으로는 안 보인다. 계산은 **꼭짓점 기준**이다 — 선분에 수선을
 * 내리지 않는다. 경로 좌표는 수 m 간격이고 GPS 자체가 그보다 거칠어서, 더 정확히 재 봐야 눈에 보이는 차이가 없다.
 */
object RouteTrail {
    /** 이만큼 떨어져 있으면 **길을 벗어난 것**으로 보고 자르지 않는다. 잘못 잘라 길을 통째로 지우는 것보다 지나온 선이 남는 편이 낫다. */
    const val OFF_ROUTE_METERS = 120.0

    /**
     * 지금 자리에서 **앞으로 남은** 경로 좌표. 순서·개수는 [paths] 와 같고, 지나온 구간은 빈 목록이 된다(그리는 쪽이 점 2개
     * 미만을 건너뛴다).
     *
     * @param paths 구간마다의 `[경도, 위도]` 목록.
     * @param here 지금 자리(위도, 경도). 없으면 자르지 않는다.
     */
    fun remaining(
        paths: List<List<List<Double>>>,
        here: Pair<Double, Double>?,
        limit: Double = OFF_ROUTE_METERS,
    ): List<List<List<Double>>> {
        if (here == null) return paths
        var bestLeg = 0
        var bestPoint = 0
        var bestMeters = Double.POSITIVE_INFINITY
        paths.forEachIndexed { legIndex, path ->
            path.forEachIndexed { pointIndex, pair ->
                if (pair.size >= 2) {
                    val meters = haversineKm(here.first, here.second, pair[1], pair[0]) * 1000
                    if (meters < bestMeters) {
                        bestMeters = meters
                        bestLeg = legIndex
                        bestPoint = pointIndex
                    }
                }
            }
        }
        if (bestMeters > limit) return paths
        return paths.mapIndexed { index, path ->
            when {
                index < bestLeg -> emptyList()

                // 이미 다 지나온 구간
                index > bestLeg -> path

                // 아직 안 온 구간
                // 지금 걷고 있는 구간 — 선이 발밑에서 시작하게 지금 자리를 앞에 붙인다.
                else -> listOf(listOf(here.second, here.first)) + path.drop(bestPoint)
            }
        }
    }
}
