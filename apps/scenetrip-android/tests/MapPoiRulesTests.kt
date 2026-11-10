package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.routetab.MapPoiChips
import com.mz2az.scenetrip.routetab.MapPoiFetch
import com.mz2az.scenetrip.routetab.MapPoiPill
import com.mz2az.scenetrip.routetab.MapPoiRequest
import com.mz2az.scenetrip.routetab.MapPoiSpread
import com.mz2az.scenetrip.routetab.MapViewport
import com.mz2az.scenetrip.routetab.RouteEditorMode
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * 지도 편의시설 칩·상태 알약·골고루 찍기 규칙 (MZ2AZ-547 P5, 계획서 `docs/project/plans/ios-web-parity.md` M1–M4).
 * iOS `MapPoiRulesTests` 를 하나씩 옮겼다. Kotlin 쪽은 값을 바꾸지 않고 새 값을 돌려준다.
 */
class MapPoiRulesTests {
    // ── 도움 ──

    /** 편의시설 하나 — 갈래와 좌표만 바꿔 쓴다. */
    private fun poi(
        id: Long,
        group: PoiCategoryGroup = PoiCategoryGroup.food,
        lat: Double = 37.5,
        lng: Double = 127.0,
    ) = PoiSummary(id = id, name = "가게$id", category = "한식", categoryGroup = group, latitude = lat, longitude = lng)

    private fun ids(places: List<PoiSummary>): List<Long> = places.map { it.id }

    private val allGroups = PoiCategoryGroup.values().toSet()

    // ── 지도 범위 ──

    /** 범위는 서쪽,남쪽,동쪽,북쪽 — 소수 여섯 자리. */
    @Test
    fun viewportBboxIsWestSouthEastNorthWithSixDecimals() {
        val viewport =
            MapViewport(south = 37.5, west = 126.9, north = 37.6, east = 127.1, centerLat = 37.55, centerLng = 127.0, zoom = 13.0)
        assertEquals("126.900000,37.500000,127.100000,37.600000", viewport.bbox)
    }

    /** 기기 언어가 쉼표 소수점(독일어)이어도 점을 쓴다. */
    @Test
    fun viewportBboxIgnoresDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val viewport =
                MapViewport(south = 37.4, west = 126.9, north = 37.6, east = 127.1, centerLat = 37.5, centerLng = 127.0, zoom = 13.0)
            assertEquals("126.900000,37.400000,127.100000,37.600000", viewport.bbox)
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** 좌표 출처는 정하지 않으면 사용자가 고른 곳. */
    @Test
    fun viewportSourceDefaultsToUserSelected() {
        val viewport = MapViewport(south = 37.4, west = 126.9, north = 37.6, east = 127.1, centerLat = 37.5, centerLng = 127.0, zoom = 13.0)
        assertEquals(CoordinateSource.USER_SELECTED, viewport.source)
    }

    // ── M1·M2 칩 줄 ──

    /** 처음엔 모두 꺼져 있고 받지 않는다. 칩 하나를 켜면 켜지고 받는다. */
    @Test
    fun startsAllOffAndToggleOnFetches() {
        val start = MapPoiChips()
        assertTrue(start.groupsOn.isEmpty())
        assertEquals(0, start.ask)
        assertFalse(start.anyOn)
        assertFalse(start.allOn)
        assertNull(start.request(15.0))
        val chips = start.toggle(PoiCategoryGroup.food)
        assertNotNull(chips.request(15.0))
        assertTrue(chips.isOn(PoiCategoryGroup.food))
        assertFalse(chips.isOn(PoiCategoryGroup.stay))
        assertTrue(chips.anyOn)
        assertFalse(chips.allOn)
        assertEquals(1, chips.ask)
    }

    /** 켜진 것이 남아 있으면 끌 때도 다시 받는다. 다 끄면 받지 않는다(ask 0). */
    @Test
    fun toggleOffRefetchesWhileAnyRemainAndStopsWhenAllOff() {
        var chips = MapPoiChips().toggle(PoiCategoryGroup.food).toggle(PoiCategoryGroup.sight)
        assertEquals(2, chips.ask)
        chips = chips.toggle(PoiCategoryGroup.food)
        assertFalse(chips.isOn(PoiCategoryGroup.food))
        assertTrue(chips.isOn(PoiCategoryGroup.sight))
        assertEquals(3, chips.ask)
        chips = chips.toggle(PoiCategoryGroup.sight)
        assertFalse(chips.anyOn)
        assertEquals(0, chips.ask)
    }

    /** 칩을 누르면 새 값을 돌려주고 원래 값은 그대로다. */
    @Test
    fun toggleReturnsNewValueWithoutChangingOriginal() {
        val start = MapPoiChips()
        val next = start.toggle(PoiCategoryGroup.stay)
        assertTrue(next.isOn(PoiCategoryGroup.stay))
        assertTrue(start.groupsOn.isEmpty())
        assertEquals(0, start.ask)
    }

    /** 「전체」 — 다 꺼져 있으면 다 켜고 받는다. */
    @Test
    fun allFromNoneTurnsAllOnAndFetches() {
        val chips = MapPoiChips().toggleAll()
        assertEquals(allGroups, chips.groupsOn)
        assertTrue(chips.allOn)
        assertEquals(1, chips.ask)
    }

    /** 「전체」 — 일부만 켜져 있어도 다 켜고 다시 받는다. */
    @Test
    fun allFromSomeTurnsAllOnAndRefetches() {
        val chips = MapPoiChips().toggle(PoiCategoryGroup.stay).toggleAll()
        assertTrue(chips.allOn)
        assertEquals(2, chips.ask)
    }

    /** 「전체」 — 넷이 다 켜져 있으면 다 끄고 받지 않는다. */
    @Test
    fun allWhenAllOnTurnsAllOff() {
        val on = MapPoiChips().toggleAll()
        assertTrue(on.allOn)
        val off = on.toggleAll()
        assertTrue(off.groupsOn.isEmpty())
        assertFalse(off.allOn)
        assertEquals(0, off.ask)
    }

    /** 칩을 하나씩 넷 다 켜도 「전체」 가 켜진 모양이다. */
    @Test
    fun allOnWhenEachTurnedOnOneByOne() {
        var chips = MapPoiChips()
        for (group in PoiCategoryGroup.values()) chips = chips.toggle(group)
        assertTrue(chips.allOn)
        assertEquals(4, chips.ask)
    }

    /** 「다시 찾기」 — 켜진 것이 있을 때만 받는다. */
    @Test
    fun refreshOnlyWhenAnyOn() {
        val none = MapPoiChips().refresh()
        assertEquals(0, none.ask)
        val chips = none.toggle(PoiCategoryGroup.transit).refresh()
        assertEquals(2, chips.ask)
    }

    /** 보기로 돌아가거나 여행을 시작하면 다 끄고 받지 않는다. */
    @Test
    fun resetTurnsAllOff() {
        val chips = MapPoiChips().toggleAll().refresh()
        assertEquals(2, chips.ask)
        val reset = chips.reset()
        assertTrue(reset.groupsOn.isEmpty())
        assertEquals(0, reset.ask)
        assertNull(reset.request(14.0))
    }

    /** 칩 줄의 순서 — 음식점 · 명소 · 숙소 · 교통. */
    @Test
    fun chipOrder() {
        assertEquals(
            listOf(PoiCategoryGroup.food, PoiCategoryGroup.sight, PoiCategoryGroup.stay, PoiCategoryGroup.transit),
            MapPoiChips.ORDER,
        )
    }

    // ── 요청 (M2) ──

    /** 하나만 켜면 서버가 거른다 — sort=spread, spreadZoom=내림, limit=200. */
    @Test
    fun requestWithOneGroupFiltersOnServer() {
        val chips = MapPoiChips().toggle(PoiCategoryGroup.food)
        assertEquals(
            MapPoiRequest(sort = PoisApi.SortListPois.spread, spreadZoom = 12, limit = 200, categoryGroup = PoiCategoryGroup.food),
            chips.request(12.7),
        )
        assertEquals(200, MapPoiRequest.LIMIT)
    }

    /** 갈래마다 계약의 같은 이름으로 간다. */
    @Test
    fun requestMapsEachGroupToContract() {
        for (group in PoiCategoryGroup.values()) {
            val chips = MapPoiChips().toggle(group)
            assertEquals(group, chips.request(10.0)?.categoryGroup, "$group")
        }
    }

    /** 여럿 켜면 갈래를 보내지 않는다(다 받아 앱이 거른다). */
    @Test
    fun requestWithSeveralGroupsSendsNoGroup() {
        val chips = MapPoiChips().toggle(PoiCategoryGroup.food).toggle(PoiCategoryGroup.stay)
        assertEquals(
            MapPoiRequest(sort = PoisApi.SortListPois.spread, spreadZoom = 9, limit = 200, categoryGroup = null),
            chips.request(9.2),
        )
        val all = chips.toggleAll()
        assertNotNull(all.request(9.2))
        assertNull(all.request(9.2)?.categoryGroup)
    }

    /** spreadZoom 은 0…22 로 자른다. 확대 제한은 없다 — 아주 넓어도 묻는다. */
    @Test
    fun spreadZoomClampsAndHasNoMinimum() {
        val chips = MapPoiChips().toggle(PoiCategoryGroup.sight)
        assertEquals(0, chips.request(-1.5)?.spreadZoom)
        assertEquals(3, chips.request(3.99)?.spreadZoom)
        assertEquals(22, chips.request(25.3)?.spreadZoom)
        assertEquals(22, chips.request(22.0)?.spreadZoom)
    }

    // ── 거르기·개수 ──

    /** 켜진 갈래만 남기고 순서는 그대로. */
    @Test
    fun keepOnlyGroupsOn() {
        val chips = MapPoiChips().toggle(PoiCategoryGroup.food).toggle(PoiCategoryGroup.stay)
        val places =
            listOf(
                poi(1, PoiCategoryGroup.food),
                poi(2, PoiCategoryGroup.sight),
                poi(3, PoiCategoryGroup.stay),
                poi(4, PoiCategoryGroup.transit),
                poi(5, PoiCategoryGroup.food),
            )
        assertEquals(listOf(1L, 3L, 5L), ids(chips.keep(places)))
    }

    /** 다 꺼져 있으면 아무것도 남지 않는다. */
    @Test
    fun keepNothingWhenAllOff() {
        assertTrue(MapPoiChips().keep(listOf(poi(1, PoiCategoryGroup.food), poi(2, PoiCategoryGroup.stay))).isEmpty())
    }

    // ── 칩 줄이 보이나 (M1) ──

    /** 편집 중이고 지도가 펼쳐져 있고 여행 안내 중이 아닐 때만. */
    @Test
    fun barShowsOnlyWhenEditingWithMapOpenAndNotTripping() {
        assertTrue(MapPoiChips.showsBar(RouteEditorMode.EDITING, mapOpen = true, tripActive = false))
        assertFalse(MapPoiChips.showsBar(RouteEditorMode.VIEWING, mapOpen = true, tripActive = false))
        assertFalse(MapPoiChips.showsBar(RouteEditorMode.EDITING, mapOpen = false, tripActive = false))
        assertFalse(MapPoiChips.showsBar(RouteEditorMode.EDITING, mapOpen = true, tripActive = true))
        assertFalse(MapPoiChips.showsBar(RouteEditorMode.VIEWING, mapOpen = false, tripActive = false))
    }

    // ── M3 상태 알약 ──

    /** 다 꺼져 있으면 알약이 없다 — 받는 중이어도. */
    @Test
    fun noPillWhenNothingOn() {
        val fetch = MapPoiFetch()
        assertEquals(MapPoiPill.NONE, fetch.pill(anyOn = false, zoom = 15.0))
        val loading = fetch.begin()
        assertEquals(MapPoiPill.NONE, loading.pill(anyOn = false, zoom = 15.0))
        assertEquals(MapPoiPill.SEARCHING, loading.pill(anyOn = true, zoom = 15.0), "켜진 것이 있으면 보인다")
    }

    /** 받는 중이면 「찾는 중」. */
    @Test
    fun searchingWhileLoading() {
        val fetch = MapPoiFetch().begin()
        assertTrue(fetch.loading)
        assertEquals(MapPoiPill.SEARCHING, fetch.pill(anyOn = true, zoom = 15.0))
    }

    /** 다 받고 지도가 그대로면 알약이 없다. */
    @Test
    fun noPillAfterFinishWithoutMove() {
        val loading = MapPoiFetch().begin()
        assertTrue(loading.loading)
        val done = loading.finish()
        assertFalse(done.loading)
        assertFalse(done.stale)
        assertEquals(MapPoiPill.NONE, done.pill(anyOn = true, zoom = 15.0))
    }

    /** 받은 뒤 지도가 움직이면 「다시 찾기」, 다시 받기 시작하면 거둔다. */
    @Test
    fun refreshAfterMoveAndBeginClearsIt() {
        val moved = MapPoiFetch().begin().finish().cameraIdle()
        assertTrue(moved.stale)
        assertEquals(MapPoiPill.REFRESH, moved.pill(anyOn = true, zoom = 15.0))
        val again = moved.begin()
        assertFalse(again.stale)
        assertEquals(MapPoiPill.SEARCHING, again.pill(anyOn = true, zoom = 15.0))
    }

    /** 받는 사이 지도가 움직였으면 다 받은 뒤에도 「다시 찾기」 가 남는다. */
    @Test
    fun moveDuringLoadingSurvivesFinish() {
        val moving = MapPoiFetch().begin().cameraIdle()
        assertEquals(MapPoiPill.SEARCHING, moving.pill(anyOn = true, zoom = 15.0), "받는 중이 먼저")
        val done = moving.finish()
        assertTrue(done.stale)
        assertEquals(MapPoiPill.REFRESH, done.pill(anyOn = true, zoom = 15.0))
    }

    /** 다 끄면 알약도 상태도 없다. */
    @Test
    fun clearResets() {
        val busy = MapPoiFetch().begin().cameraIdle()
        assertTrue(busy.loading)
        assertTrue(busy.stale)
        val cleared = busy.clear()
        assertFalse(cleared.loading)
        assertFalse(cleared.stale)
        assertEquals(MapPoiPill.NONE, cleared.pill(anyOn = true, zoom = 15.0))
    }

    /** 확대 제한이 없다 — 넓게 봐도 「조금 더 확대하면 보여요」 가 뜨지 않는다. */
    @Test
    fun noZoomInPillAtOrdinaryZooms() {
        assertEquals(0.0, MapPoiFetch.MIN_ZOOM)
        val fetch = MapPoiFetch()
        for (zoom in listOf(0.0, 3.0, 8.0, 12.0)) {
            assertEquals(MapPoiPill.NONE, fetch.pill(anyOn = true, zoom = zoom), "zoom $zoom")
        }
        val moved = fetch.begin().finish().cameraIdle()
        for (zoom in listOf(0.0, 3.0, 8.0, 12.0)) {
            assertEquals(MapPoiPill.REFRESH, moved.pill(anyOn = true, zoom = zoom), "zoom $zoom")
        }
    }

    // ── M4 골고루 찍기 ──

    /** 줌 10 에서 1·2·3 은 한 칸, 4 는 다른 칸. 줌 16 에서는 3 이 따로 떨어진다(웹 메르카토르, 256·2^z/72 칸). */
    private val spreadPlaces =
        listOf(
            poi(1, lat = 37.5, lng = 127.0),
            poi(2, lat = 37.5001, lng = 127.0001),
            poi(3, lat = 37.5, lng = 127.002),
            poi(4, lat = 37.6, lng = 127.3),
        )

    /** 칸 72dp, 작은 점은 보이는 9·누르는 16. */
    @Test
    fun spreadConstants() {
        assertEquals(72.0, MapPoiSpread.CELL)
        assertEquals(9f, MapPoiSpread.MINOR_VISIBLE)
        assertEquals(16f, MapPoiSpread.MINOR_HIT)
    }

    /** 받은 순서가 순위 — 칸마다 먼저 온 것이 큰 점, 나머지는 작은 점. 각 목록의 순서는 그대로. 빈 입력은 빈 답. */
    @Test
    fun firstInCellIsMajorRestMinor() {
        val (noneMajor, noneMinor) = MapPoiSpread.split(emptyList(), zoom = 12.0)
        assertTrue(noneMajor.isEmpty() && noneMinor.isEmpty())
        val (major, minor) = MapPoiSpread.split(spreadPlaces, zoom = 10.0)
        assertEquals(listOf(1L, 4L), ids(major))
        assertEquals(listOf(2L, 3L), ids(minor))
    }

    /** 받은 순서를 바꾸면 그 칸의 큰 점도 바뀐다(좌표가 아니라 순서가 순위). */
    @Test
    fun orderDecidesMajor() {
        val reordered = listOf(spreadPlaces[2], spreadPlaces[0], spreadPlaces[1], spreadPlaces[3])
        val (major, minor) = MapPoiSpread.split(reordered, zoom = 10.0)
        assertEquals(listOf(3L, 4L), ids(major))
        assertEquals(listOf(1L, 2L), ids(minor))
    }

    /** 칸은 줌의 내림으로 정한다 — 10.9 는 10 과 같다. */
    @Test
    fun usesFloorOfZoom() {
        val atTen = MapPoiSpread.split(spreadPlaces, zoom = 10.0)
        val atTenNine = MapPoiSpread.split(spreadPlaces, zoom = 10.9)
        assertEquals(listOf(1L, 4L), ids(atTenNine.first))
        assertEquals(ids(atTen.first), ids(atTenNine.first))
        assertEquals(ids(atTen.second), ids(atTenNine.second))
    }

    /** 확대하면 다시 받지 않고 다시 솎는다 — 큰 점은 늘기만 한다. */
    @Test
    fun zoomingInOnlyGrowsMajor() {
        val wide = MapPoiSpread.split(spreadPlaces, zoom = 10.0)
        val close = MapPoiSpread.split(spreadPlaces, zoom = 16.0)
        assertEquals(listOf(1L, 3L, 4L), ids(close.first))
        assertEquals(listOf(2L), ids(close.second))
        assertTrue(ids(close.first).containsAll(ids(wide.first)))
    }

    /** 칸은 지도에 고정 — 아주 가까워도 칸 경계(줌 10 의 경도 127.012939453125)를 넘으면 다른 칸이다. */
    @Test
    fun cellsAreFixedToTheMap() {
        val boundary = 127.012939453125
        val pair = listOf(poi(1, lat = 37.5, lng = boundary - 0.0001), poi(2, lat = 37.5, lng = boundary + 0.0001))
        val (major, minor) = MapPoiSpread.split(pair, zoom = 10.0)
        assertEquals(listOf(1L, 2L), ids(major))
        assertTrue(minor.isEmpty())
    }

    /** tileSize 를 두 배로 하면 한 줌 더 확대한 것과 같다. */
    @Test
    fun tileSizeScalesWorld() {
        val doubled = MapPoiSpread.split(spreadPlaces, zoom = 15.0, tileSize = 512.0)
        val next = MapPoiSpread.split(spreadPlaces, zoom = 16.0)
        assertEquals(listOf(1L, 3L, 4L), ids(doubled.first))
        assertEquals(ids(next.first), ids(doubled.first))
        assertEquals(ids(next.second), ids(doubled.second))
    }

    /** 큰 점의 id 는 split 의 큰 점과 같다. */
    @Test
    fun majorIdsMatchSplit() {
        assertEquals(setOf(1L, 4L), MapPoiSpread.majorIds(spreadPlaces, zoom = 10.0))
        assertEquals(setOf(1L, 3L, 4L), MapPoiSpread.majorIds(spreadPlaces, zoom = 16.0))
        assertEquals(setOf(1L, 3L, 4L), MapPoiSpread.majorIds(spreadPlaces, zoom = 15.0, tileSize = 512.0))
    }
}
