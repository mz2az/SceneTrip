package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import java.text.BreakIterator

// 지도 검색의 규칙 (MZ2AZ-547 P5 M6·M7, iOS `RouteTab/MapSearchRules.swift`, 웹 `proto/protoPoi.tsx` `SearchPanel`·`ResultsView`).
// 칩·골고루는 `MapPoiRules.kt`.
//
// 2026-11-10 사용자 지적으로 바뀌었다(웹 72f87416) — 가게·장소(`GET /pois?q`)만 찾는다(촬영지는 찾지 않는다), 고른 곳은
// 시트·서랍·정보 카드가 덮지 않은 지도 가운데로.
// 같은 날 계획 docs/project/plans/motion-works-drawer-parity.md §7·§10 으로 다시 바뀌었다 — 글자 치는 동안 지도에는 찍지 않고,
// 목록 맨 위에 「‘q’ 검색」, 검색은 범위 없이 화면 가운데에서 가까운 순 하나(「이 지역 밖에서도 찾기」 없음).

/** 검색 목록의 상태. */
sealed interface MapSearchPhase {
    /** 2글자가 안 된다 — 목록을 안 보인다. */
    data object Idle : MapSearchPhase

    data object Loading : MapSearchPhase

    data object Failed : MapSearchPhase

    data class Done(
        val shops: List<PoiSummary>,
    ) : MapSearchPhase
}

enum class MapSearchTitle {
    /** 「가게·장소 · 가까운 순」(§10-2) */
    SHOPS_NEARBY,
}

enum class MapSearchEmpty {
    /** 「찾는 곳이 없어요」 */
    NO_PLACES,
}

/** 검색 목록의 한 줄. */
sealed interface MapSearchRow {
    data object Loading : MapSearchRow

    /** 「찾지 못했어요. 잠시 후 다시 해 주세요」 */
    data object Failed : MapSearchRow

    data class Title(
        val title: MapSearchTitle,
    ) : MapSearchRow

    data class Shop(
        val place: PoiSummary,
    ) : MapSearchRow

    data class Empty(
        val empty: MapSearchEmpty,
    ) : MapSearchRow

    /** 목록 맨 위 「‘q’ 검색」 — 누르면 이 검색어로 결과 서랍을 연다(키보드 검색과 같다, §7-2). */
    data class Commit(
        val query: String,
    ) : MapSearchRow
}

object MapSearchRules {
    const val MIN_CHARS = 2

    /** 입력이 멎고 묻기까지(ms). */
    const val DEBOUNCE_MILLIS = 350L

    /** 목록(엔터 전)에서 한 번에 받는 수. */
    const val LIST_LIMIT = 20

    /** 예전 결과 지도(엔터 뒤)의 한 번 받는 수 — 지금 결과 서랍은 20개씩 더 받는다([MapSearchPagingRules.PAGE_SIZE], §7-4). 시험이 이 값을 본다. */
    const val RESULTS_LIMIT = 40

    /** 계약의 `q` 최대 길이. */
    const val MAX_LENGTH = 100

    /** 고른 곳의 정보 카드가 지도 아래를 덮는 높이(dp) — 이만큼은 비킨다(웹 `CARD_COVER`). */
    const val CARD_COVER: Float = 300f

    /** 고른 곳으로 옮길 때의 최소 확대(웹 `Math.max(map.getZoom(), 16)`). */
    const val FOCUS_MIN_ZOOM: Double = 16.0

    /** 입력을 서버에 보낼 검색어로 — 앞뒤 공백을 떼고 2글자가 안 되면 null, 길면 100자에서 자른다. */
    fun query(text: String): String? {
        val trimmed = text.trim()
        // 보이는 글자로 센다(iOS `String.count`) — 풀어 쓴 한글(ᄀ+ᅡ)·이모지도 한 글자다.
        val breaks = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
        var count = 0
        var end = 0
        while (true) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) break
            count += 1
            if (count <= MAX_LENGTH) end = next
        }
        if (count < MIN_CHARS) return null
        return trimmed.substring(0, end)
    }

    /** 엔터를 받을 수 있나(2글자 이상). */
    fun canCommit(text: String): Boolean = query(text) != null

    /** 목록 줄 — 「가게·장소 · 가까운 순」 제목 → 가게(또는 「찾는 곳이 없어요」). */
    fun rows(phase: MapSearchPhase): List<MapSearchRow> =
        when (phase) {
            MapSearchPhase.Idle -> {
                emptyList()
            }

            MapSearchPhase.Loading -> {
                listOf(MapSearchRow.Loading)
            }

            MapSearchPhase.Failed -> {
                listOf(MapSearchRow.Failed)
            }

            is MapSearchPhase.Done -> {
                buildList {
                    add(MapSearchRow.Title(MapSearchTitle.SHOPS_NEARBY))
                    if (phase.shops.isEmpty()) {
                        add(MapSearchRow.Empty(MapSearchEmpty.NO_PLACES))
                    } else {
                        phase.shops.forEach { add(MapSearchRow.Shop(it)) }
                    }
                }
            }
        }

    /**
     * 검색칸 아래 목록 전체 — 2글자 이상이면 맨 위 「‘q’ 검색」([MapSearchRow.Commit], 다듬은 검색어) 다음에 [rows].
     */
    fun panelRows(
        text: String,
        phase: MapSearchPhase,
    ): List<MapSearchRow> {
        val query = query(text) ?: return emptyList()
        return listOf(MapSearchRow.Commit(query)) + rows(phase)
    }

    /**
     * 목록 결과를 들고 있을 곳 — 새 결과가 올 때까지는 앞의 것을 둔다. 2글자 아래로 지웠거나 못 찾았으면 비운다.
     * 글자 치는 동안은 지도에 찍지 않는다(§7-2) — 지도는 이것을 [mapDots] 에 `searching = false` 로만 넘긴다.
     */
    fun pinned(
        previous: List<PoiSummary>,
        phase: MapSearchPhase,
    ): List<PoiSummary> =
        when (phase) {
            MapSearchPhase.Idle, MapSearchPhase.Failed -> emptyList()
            MapSearchPhase.Loading -> previous
            is MapSearchPhase.Done -> phase.shops
        }

    /**
     * 지도에 찍을 검색 점 — 결과 지도가 떠 있으면 그 결과, 검색칸이 열려 있으면 목록의 것, 둘 다 아니면 없음.
     * 정보 카드가 떠 있는 곳([pickedId])은 고른 핀이 그리므로 뺀다.
     */
    fun mapDots(
        searching: Boolean,
        listed: List<PoiSummary>,
        results: List<PoiSummary>?,
        pickedId: Long?,
    ): List<PoiSummary> {
        val shown = results ?: if (searching) listed else emptyList()
        return shown.filter { it.id != pickedId }
    }

    /** 지도 칸 전체의 가운데에서 고른 곳을 위로 올릴 거리 — 아래를 덮은 높이(시트·결과 서랍, 카드는 적어도 300)의 반. */
    fun centerShift(covered: Float): Float = maxOf(covered, CARD_COVER) / 2

    /** 지도의 아래 여백(content padding)이 이미 그 반만큼 올려 준다 — 그 위로 더 올릴 거리. */
    fun extraLift(contentInset: Float): Float = maxOf(centerShift(contentInset) - contentInset / 2, 0f)

    /** 고른 곳으로 옮길 때의 확대 — 지금과 16 중 큰 것(더 확대해 둔 화면을 풀지 않는다). */
    fun focusZoom(current: Double): Double = maxOf(current, FOCUS_MIN_ZOOM)

    /** 화면에서 [lift](dp) 위에 고른 곳이 오게 하는 카메라 자리 — 그만큼 남쪽을 가리킨다. [metersPerDp] 는 그 확대의 축척. */
    fun focusTarget(
        latitude: Double,
        longitude: Double,
        lift: Float,
        metersPerDp: Double,
    ): Pair<Double, Double> = (latitude - lift * metersPerDp / METERS_PER_DEGREE_LATITUDE) to longitude

    /** 위도 1도의 길이(m) — 몇 백 m 안의 옮김이라 지구를 공으로 봐도 된다. */
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
}

/** 결과 지도의 상태 (M7) — 서랍 높이·받은 것·「다시 찾기」. 값을 바꾸지 않고 다음 값을 돌려준다. */
data class MapSearchResults(
    val query: String,
    val tall: Boolean = false,
    /** 찾은 가게·장소 — 받기 전이면 null. */
    val shops: List<PoiSummary>? = null,
    val failed: Boolean = false,
    val stale: Boolean = false,
    /** 받기 차례 번호 — 처음·「다시 찾기」 에 오른다. 늦게 온 다음 쪽이 새 물음에 섞이지 않게 맞춰 본다. */
    val ask: Int = 0,
    /** 앱이 카메라를 옮긴 때(ms) — 그 직후의 멈춤은 사람이 옮긴 것이 아니다. */
    val selfMoveAt: Long? = null,
    /** 서버가 알려 준 전체 개수(`PoiList.total`) — 머리의 「N곳」 과 더 받기의 멈춤(§7-4). */
    val total: Int? = null,
    /** 마지막으로 받은 쪽이 비어 왔나 — 그러면 더 묻지 않는다. */
    val lastPageEmpty: Boolean = false,
    /** 다음 쪽을 받는 중 — 목록 끝에 도는 줄. */
    val loadingMore: Boolean = false,
    /** 다음 쪽을 못 받았다 — 「더 불러오지 못했어요 · 다시 시도」 줄. 누를 때까지 더 묻지 않는다. */
    val moreFailed: Boolean = false,
) {
    /** 서랍 본문. */
    sealed interface Body {
        data object Loading : Body

        data object Failed : Body

        data class Empty(
            val empty: MapSearchEmpty,
        ) : Body

        data class Shops(
            val shops: List<PoiSummary>,
        ) : Body
    }

    val drawerShare: Double get() = if (tall) TALL_SHARE else SHORT_SHARE

    fun toggleDrawer(): MapSearchResults = copy(tall = !tall)

    /** 새로 받기 시작 — 목록과 쪽 상태를 비운다. */
    fun begin(): MapSearchResults =
        copy(shops = null, failed = false, stale = false, total = null, lastPageEmpty = false, loadingMore = false, moreFailed = false)

    /** 처음 쪽을 받았다 — 「다시 찾기」 를 거둔다. [total] 은 서버의 전체 개수(모르면 null). */
    fun received(
        shops: List<PoiSummary>,
        total: Int? = null,
    ): MapSearchResults = copy(shops = shops, total = total, lastPageEmpty = shops.isEmpty(), failed = false, stale = false)

    // ── 더 받기(§7-4) ──

    /** 다음에 물을 offset — 받은 것이 없거나, 받는 중·실패한 채이거나, 끝에 닿았으면 null. */
    val nextOffset: Int?
        get() {
            val loaded = shops ?: return null
            if (loadingMore || moreFailed) return null
            return MapSearchPagingRules.nextOffset(loaded.size, total, lastPageEmpty)
        }

    /** 다음 쪽을 받기 시작. */
    fun beginMore(): MapSearchResults = copy(loadingMore = true, moreFailed = false)

    /** 다음 쪽을 받았다 — 이미 있는 가게는 다시 넣지 않는다. [total] 이 null 이면 앞의 값을 둔다. */
    fun receivedMore(
        shops: List<PoiSummary>,
        total: Int?,
    ): MapSearchResults =
        copy(
            shops = MapSearchPagingRules.merge(this.shops.orEmpty(), shops) { it.id },
            total = total ?: this.total,
            lastPageEmpty = shops.isEmpty(),
            loadingMore = false,
        )

    /** 다음 쪽을 못 받았다. */
    fun moreFailedNow(): MapSearchResults = copy(loadingMore = false, moreFailed = true)

    /** 「다시 시도」 — 실패 표시를 거두고 다시 물을 수 있게. */
    fun retryMore(): MapSearchResults = copy(moreFailed = false)

    /** 검색어 알약의 「N곳」 — 서버의 전체 개수(모르면 받은 수). 받기 전이면 null. */
    val headerCount: Int? get() = shops?.let { total ?: it.size }

    fun searchFailed(): MapSearchResults = copy(failed = true)

    /** 「↻ 이 지역에서 다시 찾기」 — 지금 화면 가운데를 기준으로 처음 쪽부터(§10-2). */
    fun searchHere(): MapSearchResults = copy(ask = ask + 1)

    /** 앱이 카메라를 옮겼다(결과에 맞춤·고른 곳으로). */
    fun markSelfMove(atMillis: Long): MapSearchResults = copy(selfMoveAt = atMillis)

    /** 지도가 멈췄다 — 사람이 옮긴 것이고 받은 것이 있으면 「다시 찾기」. */
    fun cameraIdle(atMillis: Long): MapSearchResults {
        if (shops == null) return this
        val moved = selfMoveAt
        if (moved != null && atMillis - moved < SELF_MOVE_GRACE_MILLIS) return this
        return copy(stale = true)
    }

    /** 「↻ 이 지역에서 다시 찾기」 를 보이나. */
    val showsRefresh: Boolean get() = stale && shops != null

    /** 받은 가게 수 — 받기 전이면 null. */
    val count: Int? get() = shops?.size

    val body: Body
        get() {
            if (failed) return Body.Failed
            val found = shops ?: return Body.Loading
            if (found.isEmpty()) {
                return Body.Empty(MapSearchEmpty.NO_PLACES)
            }
            return Body.Shops(found)
        }

    companion object {
        /** 서랍이 덮는 지도 비율 — 처음 42%, 손잡이를 누르면 78%. */
        const val SHORT_SHARE: Double = 0.42
        const val TALL_SHARE: Double = 0.78

        /** 우리가 카메라를 옮긴 직후 이만큼은 사람이 옮긴 것이 아니다(ms). */
        const val SELF_MOVE_GRACE_MILLIS: Long = 900L
    }
}
