package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.CoordinateSource

// 지도 검색 결과 서랍의 「더 받기」·가까운 순 기준점·핀 상한 (MZ2AZ-547, iOS `RouteTab/MapSearchPagingRules.swift`,
// 계획 docs/project/plans/motion-works-drawer-parity.md §7-4·§8).
// 한 쪽은 20개. 목록 끝에서 한 줄 안이 보이면 다음 쪽을 묻고, `total` 에 닿거나 빈 쪽이 오면 멈춘다.
// 이미 받은 가게(같은 id)는 다시 넣지 않는다. 지도를 결과에 맞추는 것은 처음 쪽(offset 0)에서만.
//
// 지금은 시험을 먼저 쓰려고 둔 자리표시(일부러 틀린 값)다 — 구현은 다음 커밋에서.

object MapSearchPagingRules {
    /** 한 번에 받는 개수(`limit`). */
    const val PAGE_SIZE: Int = 0

    /** 지도에 결과 핀을 찍는 최대 개수(§8-2). 목록은 이보다 길어질 수 있다. */
    const val PIN_LIMIT: Int = 0

    /**
     * 다음에 물을 `offset`. 더 받을 것이 없으면 null.
     * [loaded] 지금까지 목록에 담긴 개수, [total] 서버가 알려 준 전체 개수(모르면 null), [lastPageEmpty] 마지막 쪽이 비어 왔나.
     */
    @Suppress("UNUSED_PARAMETER")
    fun nextOffset(
        loaded: Int,
        total: Int?,
        lastPageEmpty: Boolean,
    ): Int? = null

    /** 보이는 줄 가운데 가장 아래 줄의 번호가 [lastVisibleIndex] 일 때 다음 쪽을 물을까 — 끝에서 한 줄 안. 빈 목록이면 묻지 않는다. */
    @Suppress("UNUSED_PARAMETER")
    fun shouldLoadMore(
        lastVisibleIndex: Int,
        loaded: Int,
    ): Boolean = false

    /** 받은 쪽을 목록 뒤에 붙인다 — 이미 있는 id 는 빼고, 순서는 (있던 것, 새것을 서버 순서대로). */
    @Suppress("UNUSED_PARAMETER")
    fun <T, K> merge(
        loaded: List<T>,
        page: List<T>,
        id: (T) -> K,
    ): List<T> = loaded

    /** 이 쪽을 받았을 때 지도를 결과에 맞출까 — 처음 쪽(offset 0)만. */
    @Suppress("UNUSED_PARAMETER")
    fun fitsMap(offset: Int): Boolean = false

    /** 결과 목록 가운데 지도에 핀으로 찍을 것 — 앞에서부터 [PIN_LIMIT] 개(가까운 순이므로 가까운 곳들). */
    @Suppress("UNUSED_PARAMETER")
    fun <T> pinnedResults(shops: List<T>): List<T> = emptyList()

    /** 검색의 기준점(§8-1) — 물을 때의 화면 가운데. 화면을 모르면 null(서버 기본 이름순). */
    @Suppress("UNUSED_PARAMETER")
    fun origin(viewport: MapViewport?): MapSearchOrigin? = null
}

/** 지도 검색이 「가까운 순」 으로 물을 기준점(`lat`·`lng`, `sort=distance`). */
data class MapSearchOrigin(
    val lat: Double,
    val lng: Double,
    /** 좌표 출처 — 화면 범위의 출처를 그대로 알린다(위치 동의). */
    val source: CoordinateSource,
)
