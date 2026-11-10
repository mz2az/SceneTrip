package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.routetab.MapSearchOrigin
import com.mz2az.scenetrip.routetab.MapSearchPagingRules
import com.mz2az.scenetrip.routetab.MapViewport
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 지도 검색 결과 서랍의 「더 받기」 — 20개씩, 끝에서 한 줄 안이 보이면 다음 쪽, total·빈 쪽에서 멈춤,
 * 같은 id 는 다시 넣지 않음, 지도 맞추기는 처음 쪽만(계획 docs/project/plans/motion-works-drawer-parity.md §7-4).
 * 가까운 순의 기준점·핀 상한 100(§8). iOS `MapSearchPagingRulesTests` 를 하나씩 옮겼다.
 */
class MapSearchPagingRulesTests {
    private data class Shop(
        val id: String,
        val name: String,
    )

    private fun shops(ids: List<String>): List<Shop> = ids.map { Shop(it, "가게 $it") }

    private fun mergeShops(
        loaded: List<Shop>,
        page: List<Shop>,
    ): List<Shop> = MapSearchPagingRules.merge(loaded, page) { it.id }

    private fun poi(id: Long) =
        PoiSummary(id = id, name = "가게 $id", category = "카페", categoryGroup = PoiCategoryGroup.food, latitude = 37.28, longitude = 127.01)

    // ── 쪽 크기 ──

    @Test
    fun pageSizeIsTwenty() {
        assertEquals(20, MapSearchPagingRules.PAGE_SIZE)
    }

    // ── 다음 offset ──

    @Test
    fun firstPageAsksFromZero() {
        assertEquals(0, MapSearchPagingRules.nextOffset(loaded = 0, total = null, lastPageEmpty = false))
    }

    @Test
    fun nextOffsetIsLoadedCountWhenTotalUnknown() {
        assertEquals(20, MapSearchPagingRules.nextOffset(loaded = 20, total = null, lastPageEmpty = false))
        assertEquals(37, MapSearchPagingRules.nextOffset(loaded = 37, total = null, lastPageEmpty = false))
    }

    @Test
    fun nextOffsetIsLoadedCountBelowTotal() {
        assertEquals(20, MapSearchPagingRules.nextOffset(loaded = 20, total = 55, lastPageEmpty = false))
        assertEquals(40, MapSearchPagingRules.nextOffset(loaded = 40, total = 55, lastPageEmpty = false))
    }

    @Test
    fun stopsWhenTotalIsZero() {
        assertNull(MapSearchPagingRules.nextOffset(loaded = 0, total = 0, lastPageEmpty = false))
    }

    /** total 40 — 20·20 을 받았으면 끝. 빈 쪽을 한 번 더 묻지 않는다. */
    @Test
    fun stopsAtExactMultipleOfPageSize() {
        assertNull(MapSearchPagingRules.nextOffset(loaded = 40, total = 40, lastPageEmpty = false))
        assertEquals(20, MapSearchPagingRules.nextOffset(loaded = 20, total = 40, lastPageEmpty = false))
    }

    /** 받는 사이 서버 쪽 개수가 줄었을 수 있다. */
    @Test
    fun stopsWhenLoadedPassesTotal() {
        assertNull(MapSearchPagingRules.nextOffset(loaded = 45, total = 40, lastPageEmpty = false))
    }

    /** 빈 쪽이 오면 멈춘다 — total 이 더 남았다고 해도. */
    @Test
    fun stopsAfterEmptyPage() {
        assertNull(MapSearchPagingRules.nextOffset(loaded = 20, total = null, lastPageEmpty = true))
        assertNull(MapSearchPagingRules.nextOffset(loaded = 20, total = 60, lastPageEmpty = true))
    }

    // ── 끝 가까이 보이면 ──

    /** 20개 받음 → 18번(끝에서 두 번째) 줄이 보이면 묻는다. */
    @Test
    fun loadsMoreWhenRowBeforeLastIsVisible() {
        assertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 18, loaded = 20))
        assertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 19, loaded = 20))
    }

    @Test
    fun doesNotLoadMoreFarFromEnd() {
        assertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 17, loaded = 20))
        assertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 5, loaded = 20))
    }

    /** 1개뿐 → 0번이 곧 끝 줄. 2개 → 0번이 끝에서 두 번째. 빈 목록이면 볼 줄이 없으니 묻지 않는다. */
    @Test
    fun shortListLoadsMoreFromFirstRow() {
        assertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 0, loaded = 1))
        assertTrue(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 0, loaded = 2))
        assertFalse(MapSearchPagingRules.shouldLoadMore(lastVisibleIndex = 0, loaded = 0))
    }

    // ── 합치기 ──

    @Test
    fun mergeAppendsInServerOrder() {
        val merged = mergeShops(shops(listOf("a", "b")), shops(listOf("c", "d")))
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun mergeSkipsAlreadyLoadedIds() {
        val merged = mergeShops(shops(listOf("a", "b", "c")), shops(listOf("c", "d", "a", "e")))
        assertEquals(listOf("a", "b", "c", "d", "e"), merged.map { it.id })
    }

    /** 같은 id 면 먼저 받은 것을 둔다. */
    @Test
    fun mergeKeepsExistingItemOverNewCopy() {
        val merged = mergeShops(listOf(Shop("a", "먼저 받은 이름")), listOf(Shop("a", "나중 이름"), Shop("b", "새 가게")))
        assertEquals(listOf(Shop("a", "먼저 받은 이름"), Shop("b", "새 가게")), merged)
    }

    @Test
    fun mergeDropsDuplicatesInsideOnePage() {
        val merged = mergeShops(shops(listOf("a")), shops(listOf("b", "b", "c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun mergeWithEmptySides() {
        assertEquals(listOf("a", "b"), mergeShops(emptyList(), shops(listOf("a", "b"))).map { it.id })
        assertEquals(listOf("a", "b"), mergeShops(shops(listOf("a", "b")), emptyList()).map { it.id })
    }

    /** 지도 검색이 실제로 쓰는 줄 모양(`PoiSummary`, id 는 숫자). */
    @Test
    fun mergeWorksWithPoiSummaries() {
        val merged = MapSearchPagingRules.merge(listOf(poi(1)), listOf(poi(1), poi(2))) { it.id }
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    // ── 지도 맞추기 ──

    @Test
    fun onlyFirstPageFitsMap() {
        assertTrue(MapSearchPagingRules.fitsMap(offset = 0))
        assertFalse(MapSearchPagingRules.fitsMap(offset = 20))
        assertFalse(MapSearchPagingRules.fitsMap(offset = 40))
    }

    // ── 핀 상한 (§8-2) ──

    @Test
    fun pinLimitIsOneHundred() {
        assertEquals(100, MapSearchPagingRules.PIN_LIMIT)
    }

    @Test
    fun pinsEveryResultWhenShorterThanLimit() {
        val list = shops((0 until 37).map { "s$it" })
        assertEquals(list, MapSearchPagingRules.pinnedResults(list))
    }

    @Test
    fun pinsEveryResultWhenExactlyAtLimit() {
        val list = shops((0 until 100).map { "s$it" })
        assertEquals(list, MapSearchPagingRules.pinnedResults(list))
    }

    /** 목록은 250개까지 늘었어도 지도에는 앞(가까운) 100곳만. */
    @Test
    fun pinsOnlyFirstHundredWhenLongerThanLimit() {
        val list = shops((0 until 250).map { "s$it" })
        val pinned = MapSearchPagingRules.pinnedResults(list)
        assertEquals(100, pinned.size)
        assertEquals(list.take(100), pinned)
    }

    /** 가까운 순으로 받은 차례를 바꾸지 않는다(이름순으로 다시 늘어놓지 않음). */
    @Test
    fun pinnedResultsKeepListOrder() {
        val list = shops(listOf("z", "a", "m", "b"))
        assertEquals(listOf("z", "a", "m", "b"), MapSearchPagingRules.pinnedResults(list).map { it.id })
    }

    @Test
    fun noPinsForEmptyList() {
        assertTrue(MapSearchPagingRules.pinnedResults(emptyList<Shop>()).isEmpty())
    }

    // ── 기준점 (§8-1) ──

    /** 수원 화성 언저리 — 가운데가 네모 꼭짓점의 한가운데가 아니어도 받은 가운데 값을 그대로 쓴다. */
    private fun viewport(source: CoordinateSource) =
        MapViewport(
            south = 37.26,
            west = 126.99,
            north = 37.30,
            east = 127.04,
            centerLat = 37.2871,
            centerLng = 127.0152,
            zoom = 14.0,
            source = source,
        )

    @Test
    fun originIsViewportCentre() {
        val origin = MapSearchPagingRules.origin(viewport(CoordinateSource.USER_SELECTED))
        assertEquals(37.2871, origin?.lat)
        assertEquals(127.0152, origin?.lng)
    }

    @Test
    fun originCarriesUserSelectedSource() {
        assertEquals(
            MapSearchOrigin(lat = 37.2871, lng = 127.0152, source = CoordinateSource.USER_SELECTED),
            MapSearchPagingRules.origin(viewport(CoordinateSource.USER_SELECTED)),
        )
    }

    /** 내 위치로 맞춘 지도면 기준점도 기기 위치에서 온 것으로 알린다(위치 동의). */
    @Test
    fun originCarriesDeviceSource() {
        assertEquals(
            MapSearchOrigin(lat = 37.2871, lng = 127.0152, source = CoordinateSource.DEVICE),
            MapSearchPagingRules.origin(viewport(CoordinateSource.DEVICE)),
        )
    }

    /** 지도가 아직 없으면 기준점 없이 묻는다(서버 기본 이름순). */
    @Test
    fun noOriginWithoutViewport() {
        assertNull(MapSearchPagingRules.origin(null))
    }
}
