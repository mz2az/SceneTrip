package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.UsageBlock
import com.mz2az.scenetrip.routetab.NavLimit
import com.mz2az.scenetrip.routetab.RouteLegMode
import com.mz2az.scenetrip.routetab.RouteNavFailure
import com.mz2az.scenetrip.routetab.RouteTrail
import com.mz2az.scenetrip.routetab.TripArrival
import com.mz2az.scenetrip.routetab.guidanceNote
import com.mz2az.scenetrip.routetab.stampTitle
import com.mz2az.scenetrip.routetab.tripTitle
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.RouteLeg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 여행 중 화면 동기화 (MZ2AZ-478) — 길찾기 오류 갈래·한도, 머무름 힌트, 지나온 경로 자르기, 번호 문구, 영문 노선 판정.
 * iOS `RouteNavFailureTests` · `NavLimitTests` · `TripModeTests` 의 규칙을 같은 값으로 시험한다.
 */
class TripScreenTests {
    private fun failure(
        status: Int,
        body: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
    ): RouteNavFailure =
        RouteNavFailure.of(ClientException("시험", status, ClientError<Unit>(body = body, statusCode = status, headers = headers)))

    @Test
    fun navigationErrorsSplitByContractResponse() {
        assertEquals(RouteNavFailure.SignInRequired, failure(401, """{"code":"SIGN_IN_REQUIRED"}"""))
        assertEquals(RouteNavFailure.SessionExpired, failure(401, """{"code":"ACCESS_TOKEN_EXPIRED"}"""))
        assertEquals(RouteNavFailure.SessionExpired, failure(401, """{"code":"SESSION_REQUIRED"}"""))
        assertEquals(RouteNavFailure.NotFound, failure(404))
        assertEquals(RouteNavFailure.CourseNotActive, failure(409))
        assertEquals(RouteNavFailure.NoRoute("ROUTE_NOT_FOUND"), failure(422))
        assertEquals(RouteNavFailure.NoRoute("NO_TRANSIT_NEARBY"), failure(422, """{"code":"NO_TRANSIT_NEARBY"}"""))
        assertEquals(RouteNavFailure.ProviderDown, failure(503))
        assertEquals(RouteNavFailure.Other(500), failure(500))
        assertEquals(RouteNavFailure.Unreachable, RouteNavFailure.of(IOException("offline")))
    }

    @Test
    fun rateLimitsAreToldApart() {
        val limited = failure(429, """{"code":"NAVIGATION_LIMIT_REACHED"}""", mapOf("Retry-After" to listOf("42")))
        assertEquals(RouteNavFailure.LimitReached(42), limited)
        assertTrue(limited.isLimit)
        // 코드 없는 게이트웨이의 429 와 분당 한도는 사람이 「다시 시도」 를 누르는 일반 한도다.
        assertEquals(RouteNavFailure.RateLimited, failure(429))
        assertEquals(RouteNavFailure.RateLimited, failure(429, """{"code":"RATE_LIMITED"}"""))
        assertEquals(RouteNavFailure.Other(429), failure(429, """{"code":"SOMETHING_ELSE"}"""))
    }

    @Test
    fun onlyAnswersThatCanChangeOfferRetry() {
        assertTrue(RouteNavFailure.ProviderDown.canRetry)
        assertTrue(RouteNavFailure.Unreachable.canRetry)
        assertTrue(RouteNavFailure.RateLimited.canRetry)
        assertTrue(RouteNavFailure.Other(500).canRetry)
        assertFalse(RouteNavFailure.SignInRequired.canRetry)
        assertFalse(RouteNavFailure.NoRoute("ROUTE_NOT_FOUND").canRetry)
        assertFalse(RouteNavFailure.NotFound.canRetry)
        // 한도는 풀린 뒤에만 다시 부를 수 있다 — 한도 안내가 따로 정한다.
        assertFalse(RouteNavFailure.LimitReached(30).canRetry)
    }

    @Test
    fun navigationMessagesMatchIos() {
        assertEquals("길찾기는 가입한 분만 쓸 수 있어요", RouteNavFailure.SignInRequired.message)
        assertEquals("코스를 시작한 뒤에 길찾기를 쓸 수 있어요", RouteNavFailure.CourseNotActive.message)
        assertEquals("근처에 정류장이 없어 대중교통 경로를 찾지 못했어요", RouteNavFailure.NoRoute("NO_TRANSIT_NEARBY").message)
        assertEquals("여기서는 경로를 찾지 못했어요", RouteNavFailure.NoRoute("ROUTE_NOT_FOUND").message)
        assertEquals("길찾기를 처리하지 못했어요 (500)", RouteNavFailure.Other(500).message)
        assertEquals("저장된 코스의 장소에서만 길찾기를 부를 수 있어요", RouteNavFailure.UnsavedCourse.message)
    }

    @Test
    fun limitWindowFollowsRetryAfter() {
        assertEquals(NavLimit.Window.UNKNOWN, NavLimit.window(null))
        assertEquals(NavLimit.Window.SHORT, NavLimit.window(60))
        assertEquals(NavLimit.Window.DAY, NavLimit.window(61))
        assertEquals("오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요", RouteNavFailure.LimitReached(3600).message)
        assertEquals("지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요", RouteNavFailure.LimitReached(null).message)
        val block = UsageBlock(90, receivedAt = 1_000)
        assertEquals(90, block.secondsLeft(1_000))
        assertEquals(1, block.secondsLeft(90_999))
        assertNull(block.secondsLeft(91_000))
    }

    @Test
    fun dwellHintCountsDownThenFallsBackToTheRule() {
        assertEquals("반경 100 m 에 머무르면 스탬프", TripArrival.hint(null))
        assertEquals("머무르면 도착 · 5분", TripArrival.hint(0))
        assertEquals("머무르면 도착 · 4분", TripArrival.hint(60_000))
        assertEquals("머무르면 도착 · 1분", TripArrival.hint(240_001))
        assertEquals("도착 확인 중", TripArrival.hint(300_000))
        val arrival = TripArrival()
        assertNull(arrival.dwelt(10_000))
        arrival.update(50.0, 1_000)
        assertEquals(9_000L, arrival.dwelt(10_000))
        arrival.update(150.0, 11_000)
        assertNull(arrival.dwelt(12_000))
    }

    @Test
    fun trailDropsWhatWeAlreadyWalked() {
        // 두 구간: 서쪽→동쪽(경도 126.000→126.003), 이어 북쪽으로 (위도 37.000→37.003).
        val first = listOf(listOf(126.000, 37.0), listOf(126.001, 37.0), listOf(126.002, 37.0), listOf(126.003, 37.0))
        val second = listOf(listOf(126.003, 37.0), listOf(126.003, 37.001), listOf(126.003, 37.003))
        val paths = listOf(first, second)
        assertEquals(paths, RouteTrail.remaining(paths, null))

        // 첫 구간 한가운데(126.0015 근처, 두 번째 꼭짓점 가까이)에 있다 — 앞 꼭짓점 하나는 지나왔다.
        val mid = RouteTrail.remaining(paths, 37.0 to 126.0011)
        assertEquals(second, mid[1])
        assertEquals(listOf(126.0011, 37.0), mid[0].first())
        assertEquals(listOf(126.001, 37.0), mid[0][1])
        assertEquals(4, mid[0].size) // 내 자리 + 가장 가까운 꼭짓점부터 끝까지

        // 둘째 구간에 들어섰다 — 첫 구간은 통째로 지워진다.
        val late = RouteTrail.remaining(paths, 37.0011 to 126.003)
        assertTrue(late[0].isEmpty())
        assertEquals(listOf(126.003, 37.0011), late[1].first())
    }

    @Test
    fun trailKeepsTheWholeRouteWhenOffRoute() {
        val paths = listOf(listOf(listOf(126.000, 37.0), listOf(126.001, 37.0)))
        // 길에서 수 km 벗어났다 — 잘못 잘라 길을 통째로 지우는 것보다 지나온 선이 남는 편이 낫다.
        assertEquals(paths, RouteTrail.remaining(paths, 37.05 to 126.05))
    }

    @Test
    fun tripWordingCarriesTheStopNumber() {
        assertEquals("2번 낙산공원로 가는 중", tripTitle("낙산공원", 2, arrived = false))
        assertEquals("2번 낙산공원 도착", tripTitle("낙산공원", 2, arrived = true))
        assertEquals("낙산공원로 가는 중", tripTitle("낙산공원", null, arrived = false))
        assertEquals("3번 성지 도착!", stampTitle(3))
        assertEquals("도착!", stampTitle(null))
    }

    @Test
    fun englishLineNumbersAreSubwayButBusKindsWin() {
        assertEquals(RouteLegMode.SUBWAY, RouteLegMode.from(RouteLeg.Mode.transit, "일반", "Line 2"))
        assertEquals(RouteLegMode.SUBWAY, RouteLegMode.from(RouteLeg.Mode.transit, null, " line  9 "))
        assertEquals(RouteLegMode.SUBWAY, RouteLegMode.from(RouteLeg.Mode.transit, "일반", "1호선"))
        // 숫자 호선 이름 전체가 일치할 때만 — 「Line 2 Express Bus」 같은 말은 지하철이 아니다.
        assertEquals(RouteLegMode.TRANSIT, RouteLegMode.from(RouteLeg.Mode.transit, "일반", "Line 2 Express"))
        assertEquals(RouteLegMode.BUS, RouteLegMode.from(RouteLeg.Mode.transit, "간선", "Line 2"))
        assertEquals(RouteLegMode.WALK, RouteLegMode.from(RouteLeg.Mode.walk, null, null))
    }

    @Test
    fun guidanceLanguageNoteOnlyWhenItDiffersFromTheApp() {
        assertNull(guidanceNote(Lang.ko, "ko"))
        assertNull(guidanceNote(Lang.en, "en"))
        assertEquals("안내는 English 로 와요 — 지명이 로마자라 표지판과 같아요", guidanceNote(Lang.ko, "en"))
        assertEquals("안내는 한국어로 와요", guidanceNote(Lang.en, "ko"))
        // 일본어 사용자도 서버는 영어 안내를 준다(계약 `guidanceLang` 설명).
        assertEquals("안내는 English 로 와요 — 지명이 로마자라 표지판과 같아요", guidanceNote(Lang.ja, "en"))
    }
}
