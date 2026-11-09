package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.SavedPlanRules
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.OffsetDateTime

/**
 * 담은 곳으로(A4) — 시·도 묶기, 가까운 순 잇기, 날마다 고르게, 보관함 → 장소 (MZ2AZ-547 P3, 웹 `ProtoPlanFlows.tsx`).
 * iOS `SavedPlanRulesTests` 를 옮겼다. 촬영지는 [BusanSpots] 를 함께 쓴다.
 */
class SavedPlanRulesTests {
    private val b = BusanSpots

    // ── 시·도 이름 ──

    @Test
    fun regionStripsMetropolitanSuffix() {
        assertEquals("서울", SavedPlanRules.regionOf("서울특별시 중구 세종대로 110"))
        assertEquals("부산", SavedPlanRules.regionOf("부산광역시 해운대구 우동"))
        assertEquals("인천", SavedPlanRules.regionOf("인천광역시 중구"))
    }

    @Test
    fun regionShortensProvinces() {
        assertEquals("경북", SavedPlanRules.regionOf("경상북도 경주시 황남동"))
        assertEquals("경남", SavedPlanRules.regionOf("경상남도 통영시"))
        assertEquals("전북", SavedPlanRules.regionOf("전라북도 전주시"))
        assertEquals("전북", SavedPlanRules.regionOf("전북특별자치도 전주시"))
        assertEquals("전남", SavedPlanRules.regionOf("전라남도 여수시"))
        assertEquals("충북", SavedPlanRules.regionOf("충청북도 청주시"))
        assertEquals("충남", SavedPlanRules.regionOf("충청남도 공주시"))
        assertEquals("강원", SavedPlanRules.regionOf("강원도 강릉시"))
        assertEquals("강원", SavedPlanRules.regionOf("강원특별자치도 강릉시"))
        assertEquals("제주", SavedPlanRules.regionOf("제주특별자치도 제주시"))
        assertEquals("경기", SavedPlanRules.regionOf("경기도 수원시"))
        assertEquals("세종", SavedPlanRules.regionOf("세종특별자치시 한누리대로"))
    }

    @Test
    fun provinceTableHasShortNames() {
        assertEquals("경북", SavedPlanRules.PROVINCES["경상북도"])
        assertEquals("제주", SavedPlanRules.PROVINCES["제주특별자치도"])
    }

    /** 표에 없는 머리말은 그대로 — 이미 짧은 「경기」·「서울」 같은 것. */
    @Test
    fun regionKeepsUnknownHead() {
        assertEquals("경기", SavedPlanRules.regionOf("경기 수원시"))
        assertEquals("서울", SavedPlanRules.regionOf("서울 중구"))
    }

    @Test
    fun regionTrimsSpaces() {
        assertEquals("경기", SavedPlanRules.regionOf("  경기도   수원시"))
    }

    @Test
    fun regionWithoutAddressIsOther() {
        assertEquals("기타", SavedPlanRules.regionOf(null))
        assertEquals("기타", SavedPlanRules.regionOf(""))
        assertEquals("기타", SavedPlanRules.regionOf("   "))
    }

    // ── 가까운 곳 잇기 ──

    /** 큰 묶음(부산 5곳)이 먼저, 묶음 안에서는 첫 곳부터 가까운 곳 차례로, 좌표 없는 곳은 그 묶음 맨 뒤. 그다음 서울 2곳. */
    @Test
    fun arrangeGroupsByRegionBiggestFirst() {
        val cityHall = b.spot(101, "서울시청", 37.5665, 126.9780, "서울특별시 중구 세종대로 110")
        val namsan = b.spot(102, "남산타워", 37.5512, 126.9882, "서울특별시 용산구 남산공원길")
        val nowhere = b.spot(103, "좌표 없는 곳", 0.0, 0.0, "부산광역시 어딘가")
        val input = listOf(cityHall, b.haeundae, b.gamcheon, nowhere, b.gwangalli, namsan, b.busanStation)
        assertEquals(
            b.ids(listOf(b.haeundae, b.gwangalli, b.busanStation, b.gamcheon, nowhere, cityHall, namsan)),
            b.ids(SavedPlanRules.arrange(input)),
        )
    }

    /** 같은 크기면 먼저 나온 묶음이 먼저 — 섞여 들어와도 묶음끼리 모인다. */
    @Test
    fun arrangeTiesKeepFirstSeenOrder() {
        val oreung = b.spot(201, "경주 오릉", 35.8255, 129.2115, "경상북도 경주시 탑동")
        val bulguksa = b.spot(202, "불국사", 35.7900, 129.3320, "경상북도 경주시 불국로")
        val cityHall = b.spot(101, "서울시청", 37.5665, 126.9780, "서울특별시 중구 세종대로 110")
        val namsan = b.spot(102, "남산타워", 37.5512, 126.9882, "서울특별시 용산구 남산공원길")
        assertEquals(
            listOf(201L, 202L, 101L, 102L),
            b.ids(SavedPlanRules.arrange(listOf(oreung, cityHall, bulguksa, namsan))),
        )
        assertEquals(emptyList<Long>(), b.ids(SavedPlanRules.arrange(emptyList())))
    }

    // ── 날마다 고르게 ──

    @Test
    fun splitEvenGivesExtrasToEarlierDays() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), SavedPlanRules.splitEven(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5), listOf(6, 7)), SavedPlanRules.splitEven(listOf(1, 2, 3, 4, 5, 6, 7), 3))
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4)), SavedPlanRules.splitEven(listOf(1, 2, 3, 4), 3))
    }

    @Test
    fun splitEvenLeavesLaterDaysEmptyWhenShort() {
        assertEquals(listOf(listOf(1), listOf(2), emptyList()), SavedPlanRules.splitEven(listOf(1, 2), 3))
        assertEquals(listOf(emptyList<Int>(), emptyList()), SavedPlanRules.splitEven(emptyList<Int>(), 2))
        assertEquals(emptyList<List<Int>>(), SavedPlanRules.splitEven(listOf(1, 2), 0), "0 이하면 빈 목록")
    }

    // ── 보관함 → 장소 ──

    @Test
    fun placesFromCartDropsDelistedAndZeroFillsCoords() {
        val date = OffsetDateTime.parse("2023-11-14T22:13:20Z")
        val items =
            listOf(
                CartItem(
                    placeId = 1,
                    name = "해운대",
                    addedAt = date,
                    address = "부산광역시 해운대구",
                    latitude = 35.1587,
                    longitude = 129.1604,
                    imageUrl = URI("https://img.example/a.jpg"),
                    sourceContentId = 77,
                    sourceContentTitle = "도깨비",
                ),
                CartItem(placeId = 2, name = "문 닫은 곳", addedAt = date, latitude = 35.1, longitude = 129.0, delisted = true),
                CartItem(placeId = 3, name = "좌표 없음", addedAt = date, delisted = false),
            )
        val places = SavedPlanRules.places(items)
        assertEquals(listOf(1L, 3L), places.map { it.id })
        val first = places.first()
        assertEquals("해운대", first.name)
        assertEquals("부산광역시 해운대구", first.address)
        assertEquals(35.1587, first.latitude)
        assertEquals(129.1604, first.longitude)
        assertEquals(URI("https://img.example/a.jpg"), first.imageUrl)
        assertEquals(listOf(77L), first.contents?.map { it.contentId })
        assertEquals(listOf("도깨비"), first.contents?.map { it.title })
        val last = places.last()
        assertEquals("좌표 없음", last.name)
        assertEquals(0.0, last.latitude)
        assertEquals(0.0, last.longitude)
        assertNull(last.address)
        assertTrue(last.contents.isNullOrEmpty(), "담은 작품이 없으면 작품도 없다")
    }
}
