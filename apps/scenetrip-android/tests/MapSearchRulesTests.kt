package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.MapSearchEmpty
import com.mz2az.scenetrip.routetab.MapSearchPhase
import com.mz2az.scenetrip.routetab.MapSearchResults
import com.mz2az.scenetrip.routetab.MapSearchRow
import com.mz2az.scenetrip.routetab.MapSearchRules
import com.mz2az.scenetrip.routetab.MapSearchTitle
import com.mz2az.scenetrip.routetab.MapViewport
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 지도 검색 규칙 (MZ2AZ-547 P5, 계획서 `docs/project/plans/ios-web-parity.md` M6·M7 — 2026-11-10 부터 가게·장소(`GET /pois?q`)만 찾는다).
 * iOS `MapSearchRulesTests` 를 하나씩 옮겼다. Kotlin 쪽은 값을 바꾸지 않고 새 값을 돌려주고, 시각은 epoch ms 다.
 */
class MapSearchRulesTests {
    // ── 도움 ──

    /** 편의시설 하나 — 갈래와 좌표만 바꿔 쓴다. */
    private fun poi(
        id: Long,
        group: PoiCategoryGroup = PoiCategoryGroup.food,
        lat: Double = 37.5,
        lng: Double = 127.0,
    ) = PoiSummary(id = id, name = "가게$id", category = "한식", categoryGroup = group, latitude = lat, longitude = lng)

    private fun ids(places: List<PoiSummary>): List<Long> = places.map { it.id }

    private val viewport =
        MapViewport(south = 37.4, west = 126.9, north = 37.6, east = 127.1, centerLat = 37.5, centerLng = 127.0, zoom = 13.0)

    private val start = 1_800_000_000_000L

    // ── M6 지도 검색 ──

    /** 2글자·0.35초·목록 20·결과 40·최대 100자. */
    @Test
    fun constants() {
        assertEquals(2, MapSearchRules.MIN_CHARS)
        assertEquals(350L, MapSearchRules.DEBOUNCE_MILLIS)
        assertEquals(20, MapSearchRules.LIST_LIMIT)
        assertEquals(40, MapSearchRules.RESULTS_LIMIT)
        assertEquals(100, MapSearchRules.MAX_LENGTH)
    }

    /** 앞뒤 공백을 떼고 2글자부터. */
    @Test
    fun queryTrimsAndNeedsTwoCharacters() {
        assertEquals("남산", MapSearchRules.query("  남산  "))
        assertEquals("ab", MapSearchRules.query("ab"))
        assertNull(MapSearchRules.query("남"))
        assertNull(MapSearchRules.query("  남 \n"))
        assertNull(MapSearchRules.query(""))
    }

    /** 글자 수는 보이는 글자로 센다 — 풀어 쓴 한글 「가」(ᄀ+ᅡ)는 한 글자. */
    @Test
    fun queryCountsCharactersNotCodeUnits() {
        assertNull(MapSearchRules.query("가"))
        assertEquals("가나", MapSearchRules.query("가나"))
    }

    /** 100자에서 자른다. */
    @Test
    fun queryTruncatesToMaxLength() {
        val long = "가".repeat(150)
        assertEquals("가".repeat(100), MapSearchRules.query(long))
        assertEquals(100, MapSearchRules.query(" $long ")?.length)
    }

    /** 엔터는 2글자부터. */
    @Test
    fun canCommitNeedsTwoCharacters() {
        assertTrue(MapSearchRules.canCommit("남산"))
        assertTrue(MapSearchRules.canCommit("  남산 "))
        assertFalse(MapSearchRules.canCommit("남"))
        assertFalse(MapSearchRules.canCommit("   "))
    }

    /** 「이 지역 밖에서도」 면 범위 없음, 아니면 지금 지도(서쪽,남쪽,동쪽,북쪽). */
    @Test
    fun bboxForWideOrHere() {
        assertEquals("126.900000,37.400000,127.100000,37.600000", MapSearchRules.bbox(wide = false, viewport = viewport))
        assertNull(MapSearchRules.bbox(wide = true, viewport = viewport))
        assertNull(MapSearchRules.bbox(wide = false, viewport = null))
    }

    /** 2글자 안 됨·받는 중·실패. */
    @Test
    fun rowsForIdleLoadingFailed() {
        assertEquals(emptyList<MapSearchRow>(), MapSearchRules.rows(MapSearchPhase.Idle))
        assertEquals(listOf(MapSearchRow.Loading), MapSearchRules.rows(MapSearchPhase.Loading))
        assertEquals(listOf(MapSearchRow.Failed), MapSearchRules.rows(MapSearchPhase.Failed))
    }

    /** 고른 곳 카드 높이·옮길 때의 최소 확대. */
    @Test
    fun focusConstants() {
        assertEquals(300f, MapSearchRules.CARD_COVER)
        assertEquals(16.0, MapSearchRules.FOCUS_MIN_ZOOM)
    }

    // ── 목록 줄 — 가게·장소만(촬영지는 찾지 않는다) ──

    /** 이 지역 — 가게·장소 제목 → 가게 → 그 아래 「이 지역 밖에서도 찾기」. */
    @Test
    fun rowsHereWithResults() {
        val rows = MapSearchRules.rows(MapSearchPhase.Done(shops = listOf(poi(1), poi(2)), wide = false))
        assertEquals(
            listOf(
                MapSearchRow.Title(MapSearchTitle.SHOPS_HERE),
                MapSearchRow.Shop(poi(1)),
                MapSearchRow.Shop(poi(2)),
                MapSearchRow.SearchEverywhere,
            ),
            rows,
        )
    }

    /** 이 지역에 없으면 「이 지역엔 없어요」, 그래도 「이 지역 밖에서도 찾기」. */
    @Test
    fun rowsHereEmpty() {
        val rows = MapSearchRules.rows(MapSearchPhase.Done(shops = emptyList(), wide = false))
        assertEquals(
            listOf(
                MapSearchRow.Title(MapSearchTitle.SHOPS_HERE),
                MapSearchRow.Empty(MapSearchEmpty.NONE_HERE),
                MapSearchRow.SearchEverywhere,
            ),
            rows,
        )
    }

    /** 전체에서 찾았으면 「이 지역 밖에서도 찾기」 가 없다. */
    @Test
    fun rowsWideWithResults() {
        val shop = poi(7, PoiCategoryGroup.stay)
        val rows = MapSearchRules.rows(MapSearchPhase.Done(shops = listOf(shop), wide = true))
        assertEquals(listOf(MapSearchRow.Title(MapSearchTitle.SHOPS_EVERYWHERE), MapSearchRow.Shop(shop)), rows)
    }

    /** 전체에서도 없으면 「찾는 곳이 없어요」. */
    @Test
    fun rowsWideEmpty() {
        val rows = MapSearchRules.rows(MapSearchPhase.Done(shops = emptyList(), wide = true))
        assertEquals(
            listOf(MapSearchRow.Title(MapSearchTitle.SHOPS_EVERYWHERE), MapSearchRow.Empty(MapSearchEmpty.NO_PLACES)),
            rows,
        )
    }

    // ── 검색하는 동안 지도에 남길 점 ──

    /** 결과가 오면 그것으로 바꾼다. */
    @Test
    fun pinnedReplacedByDoneShops() {
        val pinned = MapSearchRules.pinned(listOf(poi(1)), MapSearchPhase.Done(shops = listOf(poi(2), poi(3)), wide = false))
        assertEquals(listOf(2L, 3L), ids(pinned))
    }

    /** 받는 동안은 앞의 점을 그대로 둔다 — 글자마다 깜빡이지 않게. */
    @Test
    fun pinnedKeepsPreviousWhileLoading() {
        val pinned = MapSearchRules.pinned(listOf(poi(1), poi(2)), MapSearchPhase.Loading)
        assertEquals(listOf(1L, 2L), ids(pinned))
    }

    /** 2글자 아래로 지웠으면 비운다. */
    @Test
    fun pinnedClearedWhenIdle() {
        assertEquals(emptyList<PoiSummary>(), MapSearchRules.pinned(listOf(poi(1)), MapSearchPhase.Idle))
    }

    /** 못 찾았으면 비운다. */
    @Test
    fun pinnedClearedWhenFailed() {
        assertEquals(emptyList<PoiSummary>(), MapSearchRules.pinned(listOf(poi(1)), MapSearchPhase.Failed))
    }

    /** 결과 지도가 떠 있으면 그 결과(검색칸 여부와 상관없이). */
    @Test
    fun mapDotsPreferResults() {
        val dots = MapSearchRules.mapDots(searching = true, listed = listOf(poi(1)), results = listOf(poi(2), poi(3)), pickedId = null)
        assertEquals(listOf(2L, 3L), ids(dots))
        val closed = MapSearchRules.mapDots(searching = false, listed = emptyList(), results = listOf(poi(4)), pickedId = null)
        assertEquals(listOf(4L), ids(closed))
    }

    /** 결과 지도는 없고 검색칸이 열려 있으면 목록의 것. */
    @Test
    fun mapDotsUseListedWhileSearching() {
        val dots = MapSearchRules.mapDots(searching = true, listed = listOf(poi(1), poi(2)), results = null, pickedId = null)
        assertEquals(listOf(1L, 2L), ids(dots))
    }

    /** 둘 다 아니면 점이 없다. */
    @Test
    fun mapDotsEmptyWhenNotSearching() {
        assertEquals(
            emptyList<PoiSummary>(),
            MapSearchRules.mapDots(searching = false, listed = listOf(poi(1)), results = null, pickedId = null),
        )
    }

    /** 정보 카드가 떠 있는 곳은 고른 핀이 그리므로 뺀다 — 순서는 그대로. */
    @Test
    fun mapDotsDropPicked() {
        val fromList =
            MapSearchRules.mapDots(searching = true, listed = listOf(poi(1), poi(2), poi(3)), results = null, pickedId = 2L)
        assertEquals(listOf(1L, 3L), ids(fromList))
        val fromResults =
            MapSearchRules.mapDots(searching = false, listed = emptyList(), results = listOf(poi(3), poi(2), poi(1)), pickedId = 2L)
        assertEquals(listOf(3L, 1L), ids(fromResults))
    }

    // ── 고른 곳을 보이는 지도 가운데로 ──

    /** 덮인 높이의 반만큼 올린다 — 카드는 적어도 300 을 덮는다. */
    @Test
    fun centerShift() {
        assertEquals(150f, MapSearchRules.centerShift(0f))
        assertEquals(150f, MapSearchRules.centerShift(200f))
        assertEquals(150f, MapSearchRules.centerShift(300f))
        assertEquals(250f, MapSearchRules.centerShift(500f))
    }

    /** 지도 아래 여백이 이미 반만큼 올려 주니 그 위로 더 — 음수는 없다. */
    @Test
    fun extraLift() {
        assertEquals(150f, MapSearchRules.extraLift(0f))
        assertEquals(50f, MapSearchRules.extraLift(200f))
        assertEquals(0f, MapSearchRules.extraLift(300f))
        assertEquals(0f, MapSearchRules.extraLift(500f))
    }

    /** 지금과 16 중 큰 것 — 더 확대해 둔 화면을 풀지 않는다. */
    @Test
    fun focusZoom() {
        assertEquals(16.0, MapSearchRules.focusZoom(12.0))
        assertEquals(16.0, MapSearchRules.focusZoom(16.0))
        assertEquals(17.5, MapSearchRules.focusZoom(17.5))
    }

    /** 화면에서 위로 올리려면 카메라는 그만큼 남쪽을 가리킨다 — 경도는 그대로. */
    @Test
    fun focusTargetMovesSouth() {
        val (lat, lng) = MapSearchRules.focusTarget(latitude = 37.5, longitude = 127.0, lift = 150f, metersPerDp = 2.0)
        assertEquals(37.5 - 150.0 * 2.0 / 111_320.0, lat, 1e-9)
        assertEquals(127.0, lng, 1e-9)
    }

    /** 올릴 거리가 0 이면 그대로. */
    @Test
    fun focusTargetWithoutLiftIsUnchanged() {
        val (lat, lng) = MapSearchRules.focusTarget(latitude = 37.5, longitude = 127.0, lift = 0f, metersPerDp = 2.0)
        assertEquals(37.5, lat, 1e-9)
        assertEquals(127.0, lng, 1e-9)
    }

    // ── M7 엔터 뒤 결과 지도 ──

    /** 서랍 42%·78%, 앱이 옮긴 직후 0.9초. */
    @Test
    fun resultsConstants() {
        assertEquals(0.42, MapSearchResults.SHORT_SHARE)
        assertEquals(0.78, MapSearchResults.TALL_SHARE)
        assertEquals(900L, MapSearchResults.SELF_MOVE_GRACE_MILLIS)
    }

    /** 서랍은 42% 에서 시작해 손잡이로 78% ↔ 42%. */
    @Test
    fun drawerToggles() {
        val short = MapSearchResults(query = "남산", wide = false)
        assertEquals(0.42, short.drawerShare, 0.0001)
        val tall = short.toggleDrawer()
        assertTrue(tall.tall)
        assertEquals(0.78, tall.drawerShare, 0.0001)
        val back = tall.toggleDrawer()
        assertFalse(back.tall)
        assertEquals(0.42, back.drawerShare, 0.0001)
    }

    /** 검색어 알약의 「N곳」 — 받기 전 null. */
    @Test
    fun count() {
        val results = MapSearchResults(query = "남산", wide = false)
        assertNull(results.shops)
        assertNull(results.count)
        assertEquals(3, results.received(listOf(poi(1), poi(2), poi(3))).count)
        assertEquals(0, results.received(listOf(poi(1))).received(emptyList()).count)
    }

    /** 받으면 목록을 두고 실패를 거둔다. */
    @Test
    fun receivedStoresShopsAndClearsFailed() {
        val failed = MapSearchResults(query = "남산", wide = false).searchFailed()
        assertTrue(failed.failed)
        val got = failed.received(listOf(poi(1)))
        assertEquals(listOf(poi(1)), got.shops)
        assertFalse(got.failed)
    }

    /** 새로 받기 시작하면 목록을 비우고 실패·「다시 찾기」 를 거둔다. */
    @Test
    fun beginClearsState() {
        val messy = MapSearchResults(query = "남산", wide = false).received(listOf(poi(1))).cameraIdle(start).searchFailed()
        assertNotNull(messy.shops)
        assertTrue(messy.failed)
        assertTrue(messy.stale)
        val fresh = messy.begin()
        assertNull(fresh.shops)
        assertFalse(fresh.failed)
        assertFalse(fresh.stale)
    }

    /** 「이 지역에서 다시 찾기」 — 범위를 지금 지도로, 다시 받는다. */
    @Test
    fun searchHere() {
        val results = MapSearchResults(query = "남산", wide = true)
        val here = results.searchHere()
        assertFalse(here.wide)
        assertEquals(results.ask + 1, here.ask)
    }

    /** 「이 지역 밖에서도 찾기」 — 범위 없이, 다시 받는다. */
    @Test
    fun searchEverywhere() {
        val results = MapSearchResults(query = "남산", wide = false)
        val everywhere = results.searchEverywhere()
        assertTrue(everywhere.wide)
        assertEquals(results.ask + 1, everywhere.ask)
    }

    // ── 「다시 찾기」 ──

    /** 받기 전에는 지도를 옮겨도 없다. 받은 뒤 사람이 지도를 옮기면 「다시 찾기」, 새로 받으면 거둔다. */
    @Test
    fun cameraIdleAfterReceivedShowsRefresh() {
        val before = MapSearchResults(query = "남산", wide = false).cameraIdle(start)
        assertFalse(before.stale)
        assertFalse(before.showsRefresh)
        val moved = before.received(listOf(poi(1))).cameraIdle(start)
        assertTrue(moved.stale)
        assertTrue(moved.showsRefresh)
        val again = moved.received(listOf(poi(2)))
        assertFalse(again.stale)
        assertFalse(again.showsRefresh)
    }

    /** 앱이 카메라를 옮긴 직후(0.9초 안)의 멈춤은 사람이 옮긴 것이 아니다. */
    @Test
    fun selfMoveWithinGraceIsIgnored() {
        val marked = MapSearchResults(query = "남산", wide = false).received(listOf(poi(1))).markSelfMove(start)
        val soon = marked.cameraIdle(start + 500)
        assertFalse(soon.stale)
        val later = soon.cameraIdle(start + 1_000)
        assertTrue(later.stale)
    }

    /** 새로 받는 동안(가게 null)에는 「다시 찾기」 를 보이지 않는다. */
    @Test
    fun noRefreshWhileShopsNotReceived() {
        val moved = MapSearchResults(query = "남산", wide = false).received(listOf(poi(1))).cameraIdle(start)
        assertTrue(moved.showsRefresh)
        assertFalse(moved.begin().showsRefresh)
    }

    // ── 서랍 본문 ──

    /** 받기 전엔 받는 중, 받으면 가게 목록. */
    @Test
    fun bodyLoadingThenShops() {
        val results = MapSearchResults(query = "남산", wide = false)
        assertEquals(MapSearchResults.Body.Loading, results.body)
        assertEquals(MapSearchResults.Body.Shops(listOf(poi(1), poi(2))), results.received(listOf(poi(1), poi(2))).body)
    }

    /** 실패하면 실패 문구. */
    @Test
    fun bodyFailed() {
        val failed = MapSearchResults(query = "남산", wide = false).searchFailed()
        assertTrue(failed.failed)
        assertEquals(MapSearchResults.Body.Failed, failed.body)
    }

    /** 이 지역에 없으면 「이 지역엔 없어요」 + 「이 지역 밖에서도 찾기」 단추. */
    @Test
    fun bodyEmptyHereOffersEverywhere() {
        val results = MapSearchResults(query = "남산", wide = false).received(emptyList())
        assertEquals(MapSearchResults.Body.Empty(MapSearchEmpty.NONE_HERE, offerEverywhere = true), results.body)
    }

    /** 전체에서도 없으면 「찾는 곳이 없어요」, 단추 없음. */
    @Test
    fun bodyEmptyWide() {
        val results = MapSearchResults(query = "남산", wide = true).received(emptyList())
        assertEquals(MapSearchResults.Body.Empty(MapSearchEmpty.NO_PLACES, offerEverywhere = false), results.body)
    }
}
