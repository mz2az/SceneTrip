package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.hometab.CityCovers
import com.mz2az.scenetrip.hometab.CitySlideRules
import com.mz2az.scenetrip.hometab.HomeCityRules
import com.mz2az.scenetrip.hometab.HomeTripFloatRules
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.LatLng
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.searchtab.BottomSheetRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * 웹 시안 반영 — 떠 있는 탭바(T1–T3)와 홈 사진 카드·떠 있는 「여행 코스 짜기」 단추(H1–H4) (MZ2AZ-547).
 * iOS `FloatingTabBarRulesTests`·`BottomSheetRulesTests`·`HomeCityRulesTests`·`HomeTripFloatRulesTests` 를 옮겼다.
 */
class WebParityTabBarHomeTests {
    // ── T1–T2 떠 있는 탭바 ──

    @Test
    fun tabBarDimensionsMatchWeb() {
        assertEquals(16f, FloatingTabBarRules.SIDE_MARGIN, "좌우 여백 16")
        assertEquals(8f, FloatingTabBarRules.BOTTOM_GAP, "내비게이션 막대 위 8")
        assertEquals(58f, FloatingTabBarRules.HEIGHT, "알약 높이 58")
        assertEquals(5f, FloatingTabBarRules.INNER_PADDING, "안쪽 여백 5")
    }

    @Test
    fun listClearanceIs74() {
        assertEquals(74f, FloatingTabBarRules.CLEARANCE, "목록이 바닥에서 비우는 높이 74")
    }

    @Test
    fun selectionSlidesIn380Millis() {
        assertEquals(380, FloatingTabBarRules.SLIDE_MILLIS, "고른 탭 알약은 0.38초에 미끄러진다")
    }

    @Test
    fun selectionWidthSplitsInnerWidthEqually() {
        // 바깥 폭 361 − 안쪽 여백 10 = 351 → 탭 셋이면 117.
        assertEquals(117f, FloatingTabBarRules.selectionWidth(barWidth = 361f, tabCount = 3), 0.0001f)
        assertEquals(50f, FloatingTabBarRules.selectionWidth(barWidth = 110f, tabCount = 2), 0.0001f)
    }

    @Test
    fun selectionOffsetStartsAtInnerPaddingAndStepsByWidth() {
        assertEquals(5f, FloatingTabBarRules.selectionOffset(index = 0, barWidth = 361f, tabCount = 3), 0.0001f)
        assertEquals(122f, FloatingTabBarRules.selectionOffset(index = 1, barWidth = 361f, tabCount = 3), 0.0001f)
        assertEquals(239f, FloatingTabBarRules.selectionOffset(index = 2, barWidth = 361f, tabCount = 3), 0.0001f)
    }

    // ── T3 검색 탭 서랍 ──

    @Test
    fun sheetMaxHeightReachesJustBelowTopBar() {
        assertEquals(680f, BottomSheetRules.maxHeight(total = 800f, topInset = 120f), 0.0001f)
    }

    @Test
    fun sheetMaxHeightKeepsAtLeast80() {
        assertEquals(80f, BottomSheetRules.maxHeight(total = 100f, topInset = 90f), 0.0001f)
        assertEquals(80f, BottomSheetRules.maxHeight(total = 50f, topInset = 200f), 0.0001f)
    }

    @Test
    fun sheetCollapsedHeightKeepsVisibleShareAboveTabBar() {
        // 100 + (800 − 100) × 0.25 = 275
        val height =
            BottomSheetRules.height(fraction = 0.25f, isExpanded = false, total = 800f, topInset = 120f, bottomClearance = 100f)
        assertEquals(275f, height, 0.0001f)
    }

    @Test
    fun sheetZeroClearanceIsOldCalculation() {
        val height =
            BottomSheetRules.height(fraction = 0.4f, isExpanded = false, total = 800f, topInset = 120f, bottomClearance = 0f)
        assertEquals(320f, height, 0.0001f)
    }

    @Test
    fun sheetRatioInsetIsTakenOutOfRatioBase() {
        // 100 + (800 − 40 − 100) × 0.25 = 265
        assertEquals(
            265f,
            BottomSheetRules.height(
                fraction = 0.25f,
                isExpanded = false,
                total = 800f,
                topInset = 120f,
                bottomClearance = 100f,
                ratioInset = 40f,
            ),
            0.0001f,
        )
        // 비움 0 이면 (800 − 40) × 0.4 = 304
        assertEquals(
            304f,
            BottomSheetRules.height(
                fraction = 0.4f,
                isExpanded = false,
                total = 800f,
                topInset = 120f,
                bottomClearance = 0f,
                ratioInset = 40f,
            ),
            0.0001f,
        )
    }

    @Test
    fun sheetExpandedIsMaxHeight() {
        assertEquals(
            680f,
            BottomSheetRules.height(fraction = 0.25f, isExpanded = true, total = 800f, topInset = 120f, bottomClearance = 100f),
            0.0001f,
        )
        // 위 비움이 있어도 최대 단은 maxHeight 그대로다.
        assertEquals(
            680f,
            BottomSheetRules.height(
                fraction = 0.25f,
                isExpanded = true,
                total = 800f,
                topInset = 120f,
                bottomClearance = 100f,
                ratioInset = 40f,
            ),
            0.0001f,
        )
    }

    // ── H2 고정 8장 ──

    private fun city(
        key: String,
        count: Int,
    ) = PlaceCity(
        key = key,
        name = key,
        placeCount = count,
        center = LatLng(latitude = 37.0, longitude = 127.0),
        bbox = listOf(126.0, 36.0, 128.0, 38.0),
    )

    @Test
    fun eightCoversInWebOrder() {
        assertEquals(listOf("서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구"), CityCovers.all.map { it.key })
        assertEquals(listOf(186L, 403L, 390L, 295L, 199L, 410L, 195L, 140L), CityCovers.all.map { it.placeId })
    }

    @Test
    fun coverNamesKoEn() {
        assertEquals(
            listOf("Seoul", "Busan", "Gyeongju", "Jeju", "Incheon", "Suwon", "Pohang", "Daegu"),
            CityCovers.all.map { it.cityEn },
        )
        assertEquals(listOf("서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구"), CityCovers.all.map { it.cityKo })
        assertEquals(
            listOf("뚝섬한강공원", "감천문화마을", "경주 오릉", "군산오름", "월미도", "화성행궁", "호미곶해맞이광장", "서문시장"),
            CityCovers.all.map { it.placeKo },
        )
        assertEquals(
            listOf(
                "Ttukseom Hangang Park",
                "Gamcheon Culture Village",
                "Gyeongju Oreung Royal Tombs",
                "Gunsan Oreum",
                "Wolmido",
                "Hwaseong Haenggung Palace",
                "Homigot Sunrise Plaza",
                "Seomun Market",
            ),
            CityCovers.all.map { it.placeEn },
        )
        assertEquals(
            listOf("마이데몬", "유미의 세포들", "21세기 대군부인", "폭싹 속았수다", "브로커", "왕의 남자", "스프링 피버", "김비서가 왜 그럴까"),
            CityCovers.all.map { it.workKo },
        )
        assertEquals(
            listOf(
                "My Demon",
                "Yumi's Cells",
                "Perfect Crown",
                "When Life Gives You Tangerines",
                "Broker",
                "King and the Clown",
                "Spring Fever",
                "What's Wrong with Secretary Kim",
            ),
            CityCovers.all.map { it.workEn },
        )
    }

    @Test
    fun coverAssetsAreDrawableNamesPerCity() {
        // `res/drawable-nodpi/city_cover_*.jpg` 의 이름(확장자 없이), 서로 다르다.
        assertEquals(
            listOf(
                "city_cover_seoul",
                "city_cover_busan",
                "city_cover_gyeongju",
                "city_cover_jeju",
                "city_cover_incheon",
                "city_cover_suwon",
                "city_cover_pohang",
                "city_cover_daegu",
            ),
            CityCovers.all.map { it.asset },
        )
        assertEquals(
            8,
            CityCovers.all
                .map { it.asset }
                .toSet()
                .size,
        )
    }

    @Test
    fun fixedFindsCoverByKey() {
        assertEquals(186L, CityCovers.fixed("서울")?.placeId)
        assertEquals("Seomun Market", CityCovers.fixed("대구")?.placeEn)
        assertNull(CityCovers.fixed("강릉"))
    }

    // ── H1·H3 도시 칩 ──

    @Test
    fun chipsKeepServerOrderAndLimitEight() {
        assertEquals(8, HomeCityRules.CHIP_LIMIT)
        val keys = listOf("서울", "부산", "경주", "제주", "인천", "수원", "포항", "대구", "강릉", "전주")
        val chips = HomeCityRules.chips(keys.map { city(it, 5) })
        assertEquals(keys.take(8), chips.map { it.key })
    }

    @Test
    fun chipsDropCitiesWithoutPlaces() {
        val chips = HomeCityRules.chips(listOf(city("서울", 3), city("빈곳", 0), city("부산", 1)))
        assertEquals(listOf("서울", "부산"), chips.map { it.key })
    }

    // ── H2·H3 사진 ──

    @Test
    fun photoPrefersFixedAsset() {
        assertEquals(
            HomeCityRules.Photo.Asset("city_cover_seoul"),
            HomeCityRules.photo(cityKey = "서울", topPlaceImageUrl = "https://x.example/a.jpg"),
        )
    }

    @Test
    fun photoFallsBackToTopPlaceAbsoluteUrl() {
        assertEquals(
            HomeCityRules.Photo.Remote("https://img.example/p.jpg"),
            HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "https://img.example/p.jpg"),
        )
        assertEquals(
            HomeCityRules.Photo.Remote("http://img.example/p.jpg"),
            HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "http://img.example/p.jpg"),
        )
    }

    @Test
    fun photoRejectsNonHttpOrRelativeUrl() {
        // 대조: 같은 도시라도 절대 https 주소면 받는다.
        assertEquals(
            HomeCityRules.Photo.Remote("https://img.example/ok.jpg"),
            HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "https://img.example/ok.jpg"),
        )
        assertNull(HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = null))
        assertNull(HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "/img/p.jpg"))
        assertNull(HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "ftp://img.example/p.jpg"))
        assertNull(HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = "https://"))
        assertNull(HomeCityRules.photo(cityKey = "강릉", topPlaceImageUrl = ""))
    }

    // ── H1 사진 위 글 ──

    @Test
    fun captionKoEn() {
        val seoul = CityCovers.fixed("서울") ?: fail<Nothing>("서울 고정 사진 없음")
        assertEquals("서울 · 뚝섬한강공원", HomeCityRules.caption(seoul, Lang.ko))
        assertEquals("Seoul · Ttukseom Hangang Park", HomeCityRules.caption(seoul, Lang.en))
        // 한국어가 아니면 영어 이름.
        assertEquals("Seoul · Ttukseom Hangang Park", HomeCityRules.caption(seoul, Lang.ja))
    }

    // ── H2 슬라이드 ──

    @Test
    fun slideTiming() {
        assertEquals(4000L, CitySlideRules.INTERVAL_MILLIS, "4초마다")
        assertEquals(1200, CitySlideRules.CROSSFADE_MILLIS, "1.2초 겹침")
        assertEquals(5000, CitySlideRules.ZOOM_MILLIS, "5초 확대")
        assertEquals(1.06f, CitySlideRules.ZOOM_SCALE, 0.0001f)
    }

    @Test
    fun slideNextWraps() {
        assertEquals(1, CitySlideRules.next(after = 0, count = 8))
        assertEquals(0, CitySlideRules.next(after = 7, count = 8))
        assertEquals(0, CitySlideRules.next(after = 0, count = 0))
    }

    @Test
    fun slideAdvancesOnlyWithTwoOrMoreAndMotionAllowed() {
        assertTrue(CitySlideRules.advances(count = 8, reduceMotion = false))
        assertTrue(CitySlideRules.advances(count = 2, reduceMotion = false))
        assertFalse(CitySlideRules.advances(count = 1, reduceMotion = false))
        assertFalse(CitySlideRules.advances(count = 0, reduceMotion = false))
        assertFalse(CitySlideRules.advances(count = 8, reduceMotion = true))
    }

    @Test
    fun slideMountedOnlyCurrentPreviousNext() {
        assertEquals(listOf(2, 3, 4), (0 until 8).filter { CitySlideRules.isMounted(it, current = 3, count = 8) })
    }

    @Test
    fun slideMountedWrapsAtEdges() {
        assertEquals(listOf(0, 1, 7), (0 until 8).filter { CitySlideRules.isMounted(it, current = 0, count = 8) })
        assertEquals(listOf(0, 6, 7), (0 until 8).filter { CitySlideRules.isMounted(it, current = 7, count = 8) })
    }

    // ── H4 떠 있는 「여행 코스 짜기」 단추 ──

    private fun course(
        id: Long,
        running: Boolean = false,
    ): CourseSummary {
        val at = OffsetDateTime.parse("2026-11-01T09:00:00+09:00")
        return CourseSummary(
            id = id,
            title = "코스 $id",
            dayCount = 1,
            status = if (running) CourseStatus.active else CourseStatus.upcoming,
            origin = CourseOrigin.self,
            placeCount = 0,
            createdAt = at,
            updatedAt = at,
        )
    }

    @Test
    fun tripFloatConstants() {
        assertEquals(5, HomeTripFloatRules.MAX_COURSES, "내 코스는 최대 5")
        assertEquals(45, HomeTripFloatRules.STAGGER_MILLIS, "알약 사이 45ms")
    }

    @Test
    fun tripFloatCoursesRunningFirstMaxFive() {
        val all =
            listOf(
                course(1),
                course(2),
                course(3, running = true),
                course(4),
                course(5),
                course(6, running = true),
                course(7),
            )
        assertEquals(listOf(3L, 6L, 1L, 2L, 4L), HomeTripFloatRules.courses(all).map { it.id })
    }

    @Test
    fun tripFloatCollapsedIsPlanWithoutRunning() {
        assertEquals(HomeTripFloatRules.Collapsed.Plan, HomeTripFloatRules.collapsed(listOf(course(1), course(2))))
        assertEquals(HomeTripFloatRules.Collapsed.Plan, HomeTripFloatRules.collapsed(emptyList()))
        // 대조: 여행 중 코스가 끼면 Plan 이 아니다.
        val running = course(9, running = true)
        assertEquals(HomeTripFloatRules.Collapsed.Running(running), HomeTripFloatRules.collapsed(listOf(course(1), running)))
    }

    @Test
    fun tripFloatCollapsedIsFirstRunningCourse() {
        val first = course(3, running = true)
        val second = course(4, running = true)
        assertEquals(
            HomeTripFloatRules.Collapsed.Running(first),
            HomeTripFloatRules.collapsed(listOf(course(1), first, second)),
        )
    }

    @Test
    fun tripFloatPlanTapNewCourseOnlyWhenLoadedAndEmpty() {
        assertEquals(HomeTripFloatRules.PlanTap.NEW_COURSE, HomeTripFloatRules.planTap(RouteStore.CourseListState.LOADED, 0))
        assertEquals(HomeTripFloatRules.PlanTap.EXPAND, HomeTripFloatRules.planTap(RouteStore.CourseListState.LOADED, 2))
        assertEquals(HomeTripFloatRules.PlanTap.EXPAND, HomeTripFloatRules.planTap(RouteStore.CourseListState.UNKNOWN, 0))
        assertEquals(HomeTripFloatRules.PlanTap.EXPAND, HomeTripFloatRules.planTap(RouteStore.CourseListState.FAILED, 0))
    }

    @Test
    fun tripFloatItemsOrder() {
        val first = course(1)
        val second = course(2, running = true)
        assertEquals(
            listOf(
                HomeTripFloatRules.Item.Close,
                HomeTripFloatRules.Item.All,
                HomeTripFloatRules.Item.NewCourse,
                HomeTripFloatRules.Item.Course(first),
                HomeTripFloatRules.Item.Course(second),
            ),
            HomeTripFloatRules.items(listOf(first, second)),
        )
        assertEquals(
            listOf(HomeTripFloatRules.Item.Close, HomeTripFloatRules.Item.All, HomeTripFloatRules.Item.NewCourse),
            HomeTripFloatRules.items(emptyList()),
        )
    }

    @Test
    fun tripFloatDelayStaggersOnOpenZeroOnClose() {
        assertEquals(0, HomeTripFloatRules.delay(index = 0, open = true))
        assertEquals(45, HomeTripFloatRules.delay(index = 1, open = true))
        assertEquals(180, HomeTripFloatRules.delay(index = 4, open = true))
        assertEquals(0, HomeTripFloatRules.delay(index = 4, open = false))
    }
}
