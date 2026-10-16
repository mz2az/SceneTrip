package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cos

/** [RouteNearbySearch]가 화면에 보이는 값. */
data class RouteNearbyState(
    val phase: RouteNearbySearch.Phase = RouteNearbySearch.Phase.IDLE,
    val places: List<PlaceSummary> = emptyList(),
    val failure: ApiFailure? = null,
)

/**
 * AI 세션과 독립된 촬영지 조회 — 생성된 `GET /places`의 bbox 검색만 쓴다 (MZ2AZ-444). 모델을 부르지 않고
 * 가이드 대화·오류와 상태를 나누지 않는다. iOS `RouteNearbySearch`를 옮긴 것이다.
 *
 * Compose 상태를 직접 들지 않는다(JVM 단위 시험에서 Looper 가 없다) — 값이 바뀔 때마다 [changed]로 알린다.
 * 늦게 온 옛 응답은 세대 번호로 버린다.
 *
 * TODO(MZ2AZ-472): iOS 는 기기 위치 기준 조회 앞에서 위치기반서비스 동의를 확인한다(`needsConsent`). Android 는
 * 동의 기능이 아직 없어 기기 위치도 기존 위치 사용(편집 화면이 여는 순간 한 번 읽는 값) 그대로 쓴다.
 */
class RouteNearbySearch(
    private val fetch: suspend (Query) -> List<PlaceSummary>,
    private val changed: (RouteNearbyState) -> Unit = {},
) {
    enum class Phase { IDLE, LOADING, LOADED, FAILED }

    /** 중심에서 남북·동서 약 3km 인 사각 영역. 고위도·날짜 변경선에서는 유효 범위로 제한한다. */
    data class Query(
        val centerLatitude: Double,
        val centerLongitude: Double,
        val bbox: String,
    ) {
        companion object {
            private const val HALF_SIDE_KILOMETERS = 3.0
            private const val KILOMETERS_PER_DEGREE = 111.0
            private const val MINIMUM_LONGITUDE_SCALE = 0.01

            fun of(anchor: RouteGuideAnchor): Query {
                val latitudeDelta = HALF_SIDE_KILOMETERS / KILOMETERS_PER_DEGREE
                val longitudeDelta = latitudeDelta / maxOf(cos(Math.toRadians(anchor.latitude)), MINIMUM_LONGITUDE_SCALE)
                val box =
                    listOf(
                        maxOf(-180.0, anchor.longitude - longitudeDelta),
                        maxOf(-90.0, anchor.latitude - latitudeDelta),
                        minOf(180.0, anchor.longitude + longitudeDelta),
                        minOf(90.0, anchor.latitude + latitudeDelta),
                    )
                return Query(anchor.latitude, anchor.longitude, box.joinToString(",") { "%.6f".format(Locale.US, it) })
            }
        }
    }

    var state = RouteNearbyState()
        private set

    private var generation = 0

    private fun publish(value: RouteNearbyState) {
        state = value
        changed(value)
    }

    /** 새 기준으로 다시 찾는다. 앞선 조회가 늦게 끝나도 이 조회의 결과를 덮지 않는다. */
    suspend fun load(anchor: RouteGuideAnchor) {
        generation += 1
        val asked = generation
        publish(RouteNearbyState(phase = Phase.LOADING))
        try {
            if (!RouteGuideAnchor.isValid(anchor.latitude to anchor.longitude)) throw IOException("invalid coordinate")
            val places = fetch(Query.of(anchor))
            if (generation == asked) publish(RouteNearbyState(phase = Phase.LOADED, places = places))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == asked) publish(RouteNearbyState(phase = Phase.FAILED, failure = ApiFailure.of(error)))
        }
    }

    companion object {
        /** 한 번에 받는 촬영지 수. */
        const val LIMIT = 40
    }
}
