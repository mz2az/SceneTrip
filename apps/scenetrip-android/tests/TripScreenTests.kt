package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.UsageBlock
import com.mz2az.scenetrip.routetab.GuideChipPlacement
import com.mz2az.scenetrip.routetab.NavLimit
import com.mz2az.scenetrip.routetab.RouteGuideFailure
import com.mz2az.scenetrip.routetab.RouteLegMode
import com.mz2az.scenetrip.routetab.RouteNavFailure
import com.mz2az.scenetrip.routetab.RoutePlaceSearch
import com.mz2az.scenetrip.routetab.RouteTrail
import com.mz2az.scenetrip.routetab.TripArrival
import com.mz2az.scenetrip.routetab.guidanceNote
import com.mz2az.scenetrip.routetab.stampTitle
import com.mz2az.scenetrip.routetab.tripTitle
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.RouteLeg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    // --- 코스 편집의 장소 검색 (MZ2AZ-482 E10, iOS `RoutePlaceSearchTests`) ---

    private fun place(id: Long) = PlaceSummary(id = id, name = "장소 $id", latitude = 37.5, longitude = 127.0)

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test
    fun searchKeywordIsTrimmedCappedAndEmptyMeansEverything() {
        assertNull(RoutePlaceSearch.keyword("   "))
        assertEquals("경복궁", RoutePlaceSearch.keyword("  경복궁 "))
        assertEquals(RoutePlaceSearch.MAX_LENGTH, RoutePlaceSearch.keyword("가".repeat(300))!!.length)
        // 빈 검색어는 바로 보인다 — 기다릴 타자가 없다.
        assertEquals(0L, RoutePlaceSearch.delayMillis(null))
        assertEquals(RoutePlaceSearch.DEBOUNCE_MS, RoutePlaceSearch.delayMillis("경복궁"))
    }

    @Test
    fun aLateAnswerNeverOverwritesTheNewerSearch() =
        runBlocking {
            val slow = CompletableDeferred<PlaceList>()
            val asked = mutableListOf<String?>()
            val search =
                RoutePlaceSearch(CoroutineScope(Dispatchers.Default), debounceMs = 0) { query, _, _ ->
                    synchronized(asked) { asked += query }
                    if (query == "a") slow.await() else PlaceList(listOf(place(2)), total = 1, limit = 40, offset = 0)
                }
            search.search("a")
            until { synchronized(asked) { asked.contains("a") } }
            search.search("b")
            until { search.phase == RoutePlaceSearch.Phase.LOADED }
            slow.complete(PlaceList(listOf(place(1)), total = 1, limit = 40, offset = 0))
            delay(50)
            assertEquals(listOf(2L), search.results.map { it.id })
            assertEquals(place(2), search.place(2))
        }

    @Test
    fun failureIsReportedAndLoadMoreMergesWithoutDuplicates() =
        runBlocking {
            var failing = true
            val search =
                RoutePlaceSearch(CoroutineScope(Dispatchers.Default), debounceMs = 0) { _, _, offset ->
                    if (failing) throw IOException("offline")
                    val items = if (offset == 0) listOf(place(1), place(2)) else listOf(place(2), place(3))
                    PlaceList(items, total = 4, limit = 2, offset = offset)
                }
            search.search("")
            until { search.phase == RoutePlaceSearch.Phase.FAILED }
            assertTrue(search.failure!!.isRetryable)
            failing = false
            search.search("")
            until { search.phase == RoutePlaceSearch.Phase.LOADED }
            assertTrue(search.paging.hasMore)
            search.loadMore()
            until { search.results.size == 3 }
            assertEquals(listOf(1L, 2L, 3L), search.results.map { it.id })
        }

    // --- 일정 생성 실패 문구 (MZ2AZ-482 E11, iOS `RouteGuideFailure`) ---

    private fun guideFailure(
        status: Int,
        body: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
    ) = RouteGuideFailure.of(ClientException("시험", status, ClientError<Unit>(body = body, statusCode = status, headers = headers)))

    @Test
    fun planFailureSaysWhyInsteadOfOneGenericLine() {
        assertEquals(RouteGuideFailure.BadRequest, guideFailure(400))
        assertEquals(RouteGuideFailure.SignInRequired, guideFailure(401, """{"code":"SIGN_IN_REQUIRED"}"""))
        assertEquals(RouteGuideFailure.SessionExpired, guideFailure(401, """{"code":"ACCESS_TOKEN_EXPIRED"}"""))
        assertEquals(RouteGuideFailure.TimedOut, guideFailure(409, """{"code":"IDEMPOTENCY_IN_PROGRESS"}"""))
        assertEquals(RouteGuideFailure.KeyReused, guideFailure(422, """{"code":"IDEMPOTENCY_KEY_REUSED"}"""))
        assertEquals(RouteGuideFailure.RateLimited, guideFailure(429))
        assertEquals(RouteGuideFailure.Unavailable, guideFailure(503))
        assertEquals(RouteGuideFailure.Unavailable, guideFailure(504))
        assertEquals(RouteGuideFailure.Other(500), guideFailure(500))
        assertEquals(RouteGuideFailure.Offline, RouteGuideFailure.of(UnknownHostException("x")))
        assertEquals(RouteGuideFailure.TimedOut, RouteGuideFailure.of(SocketTimeoutException("x")))
        assertEquals(RouteGuideFailure.Unreachable, RouteGuideFailure.of(IOException("x")))
        assertEquals("가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요", RouteGuideFailure.Unavailable.message)
        assertEquals("가이드 요청을 처리하지 못했어요 (500)", RouteGuideFailure.Other(500).message)
    }

    @Test
    fun guideLimitMessageFollowsTheWindow() {
        val limited = guideFailure(429, """{"code":"GUIDE_LIMIT_REACHED"}""", mapOf("Retry-After" to listOf("125")))
        assertEquals(RouteGuideFailure.LimitReached(125), limited)
        assertEquals("가이드에게 잠시 많이 물어봤어요. 3분 뒤에 다시 물어볼 수 있어요", limited.message)
        assertEquals("오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요", RouteGuideFailure.limitMessage(7_200))
        assertEquals("지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요", RouteGuideFailure.limitMessage(null))
    }

    @Test
    fun everyChangeNotifiesTheScreenWithTheNewValuesAlreadyInPlace() =
        runBlocking {
            val seen = mutableListOf<Pair<RoutePlaceSearch.Phase, List<Long>>>()
            lateinit var search: RoutePlaceSearch
            search =
                RoutePlaceSearch(CoroutineScope(Dispatchers.Default), debounceMs = 0) { query, _, _ ->
                    PlaceList(listOf(place(if (query == "hanok") 7 else 1)), total = 1, limit = 40, offset = 0)
                }
            search.onChange = { synchronized(seen) { seen += search.phase to search.results.map { it.id } } }
            search.search("")
            until { synchronized(seen) { seen.any { it.first == RoutePlaceSearch.Phase.LOADED } } }
            search.search("hanok")
            until { synchronized(seen) { seen.lastOrNull() == (RoutePlaceSearch.Phase.LOADED to listOf(7L)) } }
            // 화면이 알림마다 값을 다시 읽으므로, 알림 시점에 새 결과가 이미 들어 있어야 한다.
            assertTrue(synchronized(seen) { seen.any { it.first == RoutePlaceSearch.Phase.LOADED && it.second == listOf(1L) } })
        }

    // --- 해태 칩이 카드와 단추를 비켜 서는 자리 (iOS `RouteGuideFloatingChip` 규칙) ---

    @Test
    fun chipRisesAboveTheCardAndLeavesUserPlacedSpotsAlone() {
        // 카드 없음 — 저장된 자리 그대로. 카드 윗변 300 + 틈 8 - 바닥 76 = 232 만큼 올라선다.
        assertEquals(-40f, GuideChipPlacement.offsetY(-40f, null, 76f, 8f))
        assertEquals(-232f, GuideChipPlacement.offsetY(0f, 300f, 76f, 8f))
        // 사용자가 이미 더 위에 둔 자리는 건드리지 않는다.
        assertEquals(-500f, GuideChipPlacement.offsetY(-500f, 300f, 76f, 8f))
    }

    @Test
    fun chipStepsLeftOfTheStayButtonOnlyWhenItWouldCoverIt() {
        val button = GuideChipPlacement.Clearance(left = 300f, low = 300f, high = 340f)
        // 카드 윗변 300 → 칩 아랫변 308, 세로로 단추(300~340)와 겹치고 가로로도(오른쪽 12 < 300) 겹친다 → 단추 왼쪽으로 비킨다.
        assertEquals(-(300f + 8f - 12f), GuideChipPlacement.offsetX(0f, 0f, 300f, button, 76f, 12f, 46f, 8f))
        // 카드가 없으면 비키지 않는다.
        assertEquals(0f, GuideChipPlacement.offsetX(0f, 0f, null, button, 76f, 12f, 46f, 8f))
        // 칩이 단추보다 높이 올라서면 겹치지 않는다.
        val low = GuideChipPlacement.Clearance(left = 300f, low = 100f, high = 140f)
        assertEquals(0f, GuideChipPlacement.offsetX(0f, 0f, 300f, low, 76f, 12f, 46f, 8f))
        // 사용자가 스스로 카드보다 위에 둔 자리는 단추 위여도 그 사람이 둔 자리다.
        assertEquals(0f, GuideChipPlacement.offsetX(0f, -500f, 300f, button, 76f, 12f, 46f, 8f))
    }
}
