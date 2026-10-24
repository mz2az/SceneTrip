package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.LocationFix
import com.mz2az.scenetrip.data.LocationReading
import com.mz2az.scenetrip.routetab.RouteStopFocus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 위치 고르기 규칙 (MZ2AZ-509) — iOS `tests/LocationFixTests.swift`(PR #327)를 그대로 옮겼다.
 * 서면역이 전포로 뜨던 것(2026-10-16 사용자 실기 지적)의 바탕이다.
 *
 * iOS 의 마지막 시험(「정확한 위치」 문구가 InfoPlist.strings 에 들어 있는가)은 iOS 번들 자원 시험이라
 * 옮기지 않았다. Android 의 같은 역할은 시스템 권한 대화상자(ACCESS_FINE_LOCATION 재요청)다.
 */
class LocationFixTests {
    private val now = 1_800_000_000_000L

    /** 서면역 근처. */
    private val seomyeonLat = 35.1578
    private val seomyeonLng = 129.0600

    private fun fix(
        accuracy: Float,
        age: Long = 0,
        lat: Double = seomyeonLat,
    ) = LocationReading(lat, seomyeonLng, accuracy, now - age * 1000)

    /** 북쪽으로 미터만큼 옮긴 자리의 위도. */
    private fun north(meters: Double) = seomyeonLat + meters / 111_000

    @Test fun invalidAndStaleFixesAreNotUsable() {
        assertTrue(LocationFix.isUsable(fix(30f), now))
        assertFalse(LocationFix.isUsable(fix(-1f), now))
        assertFalse(LocationFix.isUsable(fix(10f, age = 30), now))
        // 경계: 정확히 10 초는 쓴다, 그보다 오래된 것은 버린다.
        assertTrue(LocationFix.isUsable(fix(10f, age = 10), now))
        assertFalse(LocationFix.isUsable(LocationReading(seomyeonLat, seomyeonLng, 10f, now - 10_001), now))
    }

    @Test fun firstFixIsAlwaysTaken() {
        assertTrue(LocationFix.shouldReplace(null, fix(500f)))
    }

    /** 대략값(수백 m) 다음에 GPS 값(수 m)이 오면 갈아탄다 — 첫 값에 멈추지 않는다. */
    @Test fun moreAccurateFixReplacesCoarseOne() {
        val coarse = fix(400f, age = 2, lat = north(700.0))
        assertTrue(LocationFix.shouldReplace(coarse, fix(8f, age = 1)))
    }

    /** 조금 흐려져도 더 새것이면 따라간다. 확 흐려진 값(실내 기지국)은 버틴다. */
    @Test fun newerSlightlyWorseFixReplacesButMuchWorseDoesNot() {
        val shown = fix(10f, age = 3)
        assertTrue(LocationFix.shouldReplace(shown, fix(14f, age = 0, lat = north(15.0))))
        assertFalse(LocationFix.shouldReplace(shown, fix(300f, age = 0, lat = north(300.0))))
        assertFalse(LocationFix.shouldReplace(fix(10f, age = 0), fix(30f, age = 2)))
    }

    @Test fun recentersOnlyWhenBetterAndVisiblyMoved() {
        val coarse = fix(400f, lat = north(700.0))
        assertTrue(LocationFix.worthRecentering(coarse, fix(8f)))
        // 더 정확해졌어도 거의 같은 자리면 카메라를 흔들지 않는다.
        assertFalse(LocationFix.worthRecentering(fix(30f, lat = north(5.0)), fix(8f)))
        // 덜 정확한 값으로는 다시 맞추지 않는다.
        assertFalse(LocationFix.worthRecentering(fix(8f), fix(50f, lat = north(100.0))))
    }

    @Test fun tripDropsVeryCoarseFixOnceWeHaveOne() {
        assertTrue(LocationFix.acceptsTripFix(fix(500f), hasFix = false))
        assertFalse(LocationFix.acceptsTripFix(fix(500f), hasFix = true))
        assertTrue(LocationFix.acceptsTripFix(fix(40f), hasFix = true))
        assertTrue(LocationFix.acceptsTripFix(fix(100f), hasFix = true))
    }

    @Test fun waitingTimesMatchIos() {
        assertTrue(LocationFix.MAX_AGE_MS == 10_000L)
        assertTrue(LocationFix.ONE_SHOT_WAIT_MS == 3_000L)
        assertTrue(LocationFix.SETTLE_WINDOW_MS == 5_000L)
        assertTrue(LocationFix.GOOD_ENOUGH_METERS == 20f)
    }

    // --- 코스 지도에서 정지점을 눌렀을 때 (MZ2AZ-509, iOS PR #326) ---

    private val cityHall = 37.5663 to 126.9779

    /** 서울시청에서 북쪽으로 미터만큼. */
    private fun cityHallNorth(meters: Double) = (cityHall.first + meters / 111_000) to cityHall.second

    @Test fun nearbyStopIsShownTogetherWithMe() {
        assertTrue(RouteStopFocus.showsWithMe(true, cityHall, cityHallNorth(1_500.0)))
        assertTrue(RouteStopFocus.showsWithMe(true, cityHall, cityHallNorth(2_900.0)))
    }

    /** 서울시청↔인천처럼 멀면 둘을 담은 화면이 일차 전체와 거의 같다 — 그곳만 확대한다. */
    @Test fun farStopIsShownAloneBeyondThreeKilometers() {
        assertFalse(RouteStopFocus.showsWithMe(true, cityHall, cityHallNorth(3_200.0)))
        val incheon = 37.4764 to 126.6170
        assertFalse(RouteStopFocus.showsWithMe(true, cityHall, incheon))
    }

    @Test fun toggleOffOrUnknownPositionShowsStopAlone() {
        assertFalse(RouteStopFocus.showsWithMe(false, cityHall, cityHallNorth(500.0)))
        assertFalse(RouteStopFocus.showsWithMe(true, null, cityHallNorth(500.0)))
    }

    @Test fun focusZoomIsFourteen() {
        assertTrue(RouteStopFocus.ZOOM == 14.0)
        assertTrue(RouteStopFocus.WITH_ME_RADIUS_METERS == 3_000.0)
    }
}
