package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.RouteNumberPinSpec
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.ListTab
import com.mz2az.scenetrip.searchtab.SearchMapPins
import com.mz2az.scenetrip.searchtab.SearchPinMark
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 검색 탭 지도 핀 (MZ2AZ-547 G1) — 첫 화면에는 보관함에 담은 곳만 번호 핀으로, 핀 모양은 웹 `PROTO_NUMBERED`.
 * iOS `SearchMapPinsTests` 를 하나씩 옮겼다. `marks` 는 Android 에서 더한 것으로, iOS MZ2AZ-519 강조 핀 동작을 따른다.
 */
class SearchMapPinsTests {
    // ── 보관함 → 지도 핀 ──

    /** 보관함 순서 그대로, 좌표가 없는 곳과 목록에서 빠진 곳(delisted)은 뺀다. */
    @Test
    fun savedKeepsCartOrderAndDropsItemsWithoutCoordinatesOrDelisted() {
        val items =
            listOf(
                cartItem(3, lat = 37.3, lng = 127.3),
                cartItem(1, lat = null, lng = 127.1), // 위도 없음
                cartItem(5, lat = 37.5, lng = 127.5, delisted = true), // 목록에서 빠짐
                cartItem(2, lat = 37.2, lng = 127.2, delisted = false),
                cartItem(4, lat = 37.4, lng = null), // 경도 없음
                cartItem(6, lat = 37.6, lng = 127.6),
            )
        val pins = SearchMapPins.saved(items)
        assertEquals(listOf(3L, 2L, 6L), pins.map { it.id })
        assertEquals(listOf("담은 곳 3", "담은 곳 2", "담은 곳 6"), pins.map { it.name })
        assertEquals(listOf(37.3, 37.2, 37.6), pins.map { it.latitude })
        assertEquals(listOf(127.3, 127.2, 127.6), pins.map { it.longitude })
    }

    /** 주소·사진도 옮겨 담는다. */
    @Test
    fun savedCarriesAddressAndImage() {
        val item =
            cartItem(7, lat = 37.7, lng = 127.7).copy(
                address = "강릉시 주문진읍",
                imageUrl = URI("https://example.com/7.jpg"),
            )
        val pin = SearchMapPins.saved(listOf(item)).single()
        assertEquals(7L, pin.id)
        assertEquals("강릉시 주문진읍", pin.address)
        assertEquals(URI("https://example.com/7.jpg"), pin.imageUrl)
    }

    @Test
    fun savedIsEmptyForEmptyCart() {
        assertTrue(SearchMapPins.saved(emptyList()).isEmpty())
    }

    // ── 지도에 꽂을 장소 ──

    /** 첫 화면(고른 작품 없음)에는 보관함에 담은 곳만 — 검색 결과(visible)가 아니다. */
    @Test
    fun initialScreenPinsOnlySavedPlaces() {
        val pins =
            SearchMapPins.places(
                contentSelected = false,
                contentPlaces = emptyList(),
                initial = true,
                saved = listOf(place(10), place(11)),
                visible = listOf(place(1), place(2)),
            )
        assertEquals(listOf(10L, 11L), ids(pins))
    }

    /** 첫 화면에 보관함이 비었으면 핀이 없다 — 검색 결과로 메우지 않는다. */
    @Test
    fun initialScreenWithEmptyCartHasNoPins() {
        val pins =
            SearchMapPins.places(
                contentSelected = false,
                contentPlaces = emptyList(),
                initial = true,
                saved = emptyList(),
                visible = listOf(place(1)),
            )
        assertEquals(emptyList<Long>(), ids(pins))
    }

    /** 작품을 골랐으면 그 작품의 촬영지 — 첫 화면 조건이 함께 켜져 있어도 작품이 이긴다. */
    @Test
    fun selectedContentWinsEvenOverInitial() {
        val pins =
            SearchMapPins.places(
                contentSelected = true,
                contentPlaces = listOf(place(20), place(21)),
                initial = true,
                saved = listOf(place(10)),
                visible = listOf(place(1)),
            )
        assertEquals(listOf(20L, 21L), ids(pins))
    }

    @Test
    fun selectedContentWinsOverSearchResults() {
        val pins =
            SearchMapPins.places(
                contentSelected = true,
                contentPlaces = listOf(place(20)),
                initial = false,
                saved = listOf(place(10)),
                visible = listOf(place(1), place(2)),
            )
        assertEquals(listOf(20L), ids(pins))
    }

    /** 검색했으면(첫 화면 아님, 작품 없음) 목록과 같은 검색 결과. */
    @Test
    fun otherwiseVisibleSearchResults() {
        val pins =
            SearchMapPins.places(
                contentSelected = false,
                contentPlaces = listOf(place(20)),
                initial = false,
                saved = listOf(place(10)),
                visible = listOf(place(1), place(2)),
            )
        assertEquals(listOf(1L, 2L), ids(pins))
    }

    // ── 번호 ──

    /** 첫 화면은 보관함 순서 번호라 어느 탭이든 번호를 찍는다 — 작품 탭에서도. */
    @Test
    fun initialScreenIsNumberedOnWorkTab() {
        assertTrue(SearchMapPins.numbered(initial = true, tab = ListTab.WORK, contentSelected = false))
    }

    @Test
    fun initialScreenIsNumberedOnPlaceTab() {
        assertTrue(SearchMapPins.numbered(initial = true, tab = ListTab.PLACE, contentSelected = false))
    }

    /** 첫 화면이 아니면 늘 목록 행 번호와 짝이 있어 번호를 찍는다. */
    @Test
    fun nonInitialIsAlwaysNumbered() {
        assertTrue(SearchMapPins.numbered(initial = false, tab = ListTab.WORK, contentSelected = false))
        assertTrue(SearchMapPins.numbered(initial = false, tab = ListTab.PLACE, contentSelected = false))
        assertTrue(SearchMapPins.numbered(initial = false, tab = ListTab.WORK, contentSelected = true))
        assertTrue(SearchMapPins.numbered(initial = false, tab = ListTab.PLACE, contentSelected = true))
    }

    // ── 꽂을 핀(번호·고른 핀) — Android 추가, iOS MZ2AZ-519 ──

    /** 번호는 순서대로 1부터, 아무것도 안 골랐으면 고른 핀이 없다. */
    @Test
    fun marksAreNumberedFromOneInOrder() {
        val marks = SearchMapPins.marks(listOf(place(3), place(1), place(2)), numbered = true, selected = null)
        assertEquals(listOf(3L, 1L, 2L), marks.map { it.place.id })
        assertEquals(listOf(1, 2, 3), marks.map { it.number })
        assertEquals(listOf(false, false, false), marks.map { it.selected })
    }

    /** 번호를 끄면 번호가 없다. */
    @Test
    fun marksWithoutNumbersHaveNullNumbers() {
        val marks = SearchMapPins.marks(listOf(place(1), place(2)), numbered = false, selected = null)
        assertEquals(listOf(1L, 2L), marks.map { it.place.id })
        assertEquals(listOf<Int?>(null, null), marks.map { it.number })
        assertEquals(listOf(false, false), marks.map { it.selected })
    }

    /** 열어 둔 장소가 목록에 있으면 그 핀만 고른 핀이 되고 번호는 그대로, 핀을 더 꽂지 않는다. */
    @Test
    fun selectedInPlacesMarksOnlyThatPinAndKeepsItsNumber() {
        val marks = SearchMapPins.marks(listOf(place(1), place(2), place(3)), numbered = true, selected = place(2))
        assertEquals(
            listOf(
                SearchPinMark(place(1), 1, false),
                SearchPinMark(place(2), 2, true),
                SearchPinMark(place(3), 3, false),
            ),
            marks,
        )
    }

    /** 고른 곳은 id 로 맞춘다 — 같은 id 면 이름·좌표가 달라도 그 핀이다. */
    @Test
    fun selectedIsMatchedById() {
        val other = PlaceSummary(id = 2, name = "다른 이름", latitude = 1.0, longitude = 2.0)
        val marks = SearchMapPins.marks(listOf(place(1), place(2)), numbered = true, selected = other)
        assertEquals(2, marks.size)
        assertEquals(listOf(false, true), marks.map { it.selected })
        assertEquals(listOf(1, 2), marks.map { it.number })
    }

    /** 목록에 없는 곳을 열어 뒀으면 번호 없는 고른 핀을 맨 뒤에 하나 더 꽂는다. */
    @Test
    fun selectedNotInPlacesAppendsOneUnnumberedSelectedPin() {
        val marks = SearchMapPins.marks(listOf(place(1), place(2)), numbered = true, selected = place(9))
        assertEquals(
            listOf(
                SearchPinMark(place(1), 1, false),
                SearchPinMark(place(2), 2, false),
                SearchPinMark(place(9), null, true),
            ),
            marks,
        )
    }

    /** 핀이 하나도 없어도 열어 둔 곳은 꽂힌다. */
    @Test
    fun selectedWithEmptyPlacesIsTheOnlyPin() {
        val marks = SearchMapPins.marks(emptyList(), numbered = true, selected = place(9))
        assertEquals(listOf(SearchPinMark(place(9), null, true)), marks)
    }

    @Test
    fun marksOfNothingIsEmpty() {
        assertTrue(SearchMapPins.marks(emptyList(), numbered = true, selected = null).isEmpty())
    }

    // ── 코스 번호 핀 모양 ──

    @Test
    fun pinSizeIs30By38() {
        assertEquals(30f, RouteNumberPinSpec.WIDTH, 0.001f)
        assertEquals(38f, RouteNumberPinSpec.HEIGHT, 0.001f)
        assertEquals(1f, RouteNumberPinSpec.scale(false), 0.0001f)
    }

    /** 고른 핀은 한 번만 1.2배 — 웹이 1.3배와 겹쳐 1.6배가 되던 것을 고쳤다. */
    @Test
    fun selectedScaleIsOnce1Point2() {
        assertEquals(1.2f, RouteNumberPinSpec.SELECTED_SCALE, 0.0001f)
        assertEquals(1.2f, RouteNumberPinSpec.scale(true), 0.0001f)
    }

    /** 고른 핀의 크기는 36 × 45.6. */
    @Test
    fun selectedPinSizeIs36By45Point6() {
        val scale = RouteNumberPinSpec.scale(true)
        assertEquals(36f, RouteNumberPinSpec.WIDTH * scale, 0.001f)
        assertEquals(45.6f, RouteNumberPinSpec.HEIGHT * scale, 0.001f)
    }

    /** 꼬리 끝 (15, 37) 이 좌표에 닿는다 — 비율로 (0.5, 37/38). */
    @Test
    fun anchorIsTheTailTip() {
        assertEquals(15f, RouteNumberPinSpec.TIP_X, 0.001f)
        assertEquals(37f, RouteNumberPinSpec.TIP_Y, 0.001f)
        assertEquals(0.5f, RouteNumberPinSpec.anchorX, 0.0001f)
        assertEquals(37f / 38f, RouteNumberPinSpec.anchorY, 0.0001f)
    }

    /** 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9. */
    @Test
    fun fontSizeByDigitCount() {
        assertEquals(13f, RouteNumberPinSpec.fontSize(1), 0.001f)
        assertEquals(13f, RouteNumberPinSpec.fontSize(9), 0.001f)
        assertEquals(11.5f, RouteNumberPinSpec.fontSize(10), 0.001f)
        assertEquals(11.5f, RouteNumberPinSpec.fontSize(99), 0.001f)
        assertEquals(9f, RouteNumberPinSpec.fontSize(100), 0.001f)
        assertEquals(9f, RouteNumberPinSpec.fontSize(250), 0.001f)
    }

    // ── 도우미 ──

    private fun ids(places: List<PlaceSummary>): List<Long> = places.map { it.id }

    private fun place(id: Long) = PlaceSummary(id = id, name = "장소 $id", latitude = 37.0 + id / 1000.0, longitude = 127.0)

    private fun cartItem(
        id: Long,
        lat: Double?,
        lng: Double?,
        delisted: Boolean? = null,
    ) = CartItem(
        placeId = id,
        name = "담은 곳 $id",
        addedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(id), ZoneOffset.UTC),
        latitude = lat,
        longitude = lng,
        delisted = delisted,
    )
}
