package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.CityListRules
import com.mz2az.scenetrip.newcourse.CityPlanRules
import com.mz2az.scenetrip.newcourse.CityQuestionFlow
import com.mz2az.scenetrip.newcourse.CityStep
import com.mz2az.scenetrip.newcourse.NewCourseText
import com.mz2az.scenetrip.newcourse.PlanningStages
import com.mz2az.scenetrip.newcourse.ServerTrip
import com.mz2az.scenetrip.newcourse.TripMemo
import com.mz2az.scenetrip.newcourse.TripMemoryRules
import com.mz2az.scenetrip.newcourse.TripReconcile
import com.mz2az.scenetrip.newcourse.WorkPickRules
import com.mz2az.scenetrip.newcourse.WorkToggle
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.LatLng
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 시험에 쓰는 부산 촬영지 — 실제 좌표라 「가장 가까운 곳」 이 헷갈리지 않는다. iOS `BusanSpots` 를 옮겼다.
 *
 * 하버사인 거리(km)로 잰 이음 순서(해운대에서 시작): 해운대 → 광안리(3.8) → 부산역(8.1) → 자갈치(2.3) → 감천(1.8) → 태종대(8.6) → 용궁사(19.6).
 * 동백섬은 해운대 바로 옆(약 0.8km)이다.
 */
internal object BusanSpots {
    val haeundae = spot(1, "해운대", 35.1587, 129.1604, "부산광역시 해운대구 우동")
    val gamcheon = spot(2, "감천문화마을", 35.0975, 129.0106, "부산광역시 사하구 감내2로")
    val gwangalli = spot(3, "광안리", 35.1532, 129.1186, "부산광역시 수영구 광안해변로")
    val taejongdae = spot(4, "태종대", 35.0518, 129.0874, "부산광역시 영도구 전망로")
    val busanStation = spot(5, "부산역", 35.1151, 129.0422, "부산광역시 동구 중앙대로")
    val jagalchi = spot(6, "자갈치시장", 35.0966, 129.0306, "부산광역시 중구 자갈치해안로")
    val yonggungsa = spot(7, "해동용궁사", 35.1884, 129.2233, "부산광역시 기장군 기장읍")
    val dongbaek = spot(8, "동백섬", 35.1525, 129.1520, "부산광역시 해운대구 우동")

    /** 인기순(서버 `?city=` 순서)이라고 치는 목록. */
    val popular = listOf(haeundae, gamcheon, gwangalli, taejongdae, busanStation, jagalchi, yonggungsa)

    fun spot(
        id: Long,
        name: String,
        lat: Double,
        lng: Double,
        address: String? = null,
    ) = PlaceSummary(id = id, name = name, latitude = lat, longitude = lng, address = address)

    fun ids(places: List<PlaceSummary>): List<Long> = places.map { it.id }

    fun idPlan(plan: List<List<PlaceSummary>>): List<List<Long>> = plan.map { day -> day.map { it.id } }
}

/**
 * 새 코스 만들기의 순수 규칙 (MZ2AZ-547 P3 — F1·Q1–Q5·R1–R6·S1·A1–A3).
 * iOS `NewCourseRulesTests`·`NewCourseFlowTests` 를 옮기고, Android 에만 있는 [CityListRules]·[NewCourseText.usablePhoto] 를 더했다.
 */
class NewCourseRulesTests {
    private val b = BusanSpots

    // ── Q1–Q3 도시 질문 흐름 ──

    @Test
    fun dayOptionsAreOneToFour() {
        assertEquals(listOf(1, 2, 3, 4), CityQuestionFlow.DAY_OPTIONS)
    }

    @Test
    fun startsAtCityWithoutStartCity() {
        val flow = CityQuestionFlow.start(null)
        assertEquals(CityStep.CITY, flow.step)
        assertNull(flow.city)
        assertNull(flow.days)
        assertEquals(1, flow.stepNumber, "도시 질문은 점 1")
    }

    /** 홈 도시 칩으로 들어오면 「며칠?」 부터, 도시는 이미 정해져 있다. */
    @Test
    fun startsAtDaysWithStartCity() {
        val flow = CityQuestionFlow.start("부산")
        assertEquals(CityStep.DAYS, flow.step)
        assertEquals("부산", flow.city)
        assertEquals("부산", flow.startCity)
        assertEquals(2, flow.stepNumber)
    }

    @Test
    fun forwardToPlan() {
        val days = CityQuestionFlow.start(null).pickCity("경주")
        assertEquals(CityStep.DAYS, days.step)
        assertEquals("경주", days.city)
        assertEquals(2, days.stepNumber)

        val how = days.pickDays(3)
        assertEquals(CityStep.HOW, how.step)
        assertEquals(3, how.days)
        assertEquals(3, how.stepNumber)

        val plan = how.planForMe()
        assertEquals(CityStep.PLAN, plan.step)
        assertNull(plan.stepNumber, "추천 코스에는 점이 없다")
        assertEquals("경주", plan.city)
        assertEquals(3, plan.days)
    }

    @Test
    fun forwardToPick() {
        val flow = CityQuestionFlow.start("부산").pickDays(1).pickMyself()
        assertEquals(CityStep.PICK, flow.step)
        assertNull(flow.stepNumber)
        assertEquals(1, flow.days)
    }

    /** 전이는 새 값을 돌려준다 — 처음 값은 그대로다. */
    @Test
    fun transitionsReturnNewValue() {
        val start = CityQuestionFlow.start(null)
        val next = start.pickCity("부산")
        assertEquals(CityStep.CITY, start.step)
        assertNull(start.city)
        assertEquals(CityStep.DAYS, next.step)
    }

    /** 도시가 없으면 며칠을 골라도 그대로, 「어떻게?」 가 아니면 알아서 짜기·직접 고르기도 그대로. */
    @Test
    fun guardedTransitionsStayPut() {
        val atCity = CityQuestionFlow.start(null)
        assertEquals(atCity, atCity.pickDays(2), "도시 없이 며칠은 못 고른다")
        val atDays = CityQuestionFlow.start("부산")
        assertEquals(atDays, atDays.planForMe(), "며칠 전에 알아서 짜기는 안 된다")
        assertEquals(atDays, atDays.pickMyself(), "며칠 전에 직접 고르기는 안 된다")
        // 위 셋이 그대로여도, 정상 경로는 움직여야 한다.
        assertEquals(CityStep.HOW, atDays.pickDays(2).step)
    }

    @Test
    fun backAtCityCloses() {
        assertNull(CityQuestionFlow.start(null).back())
    }

    @Test
    fun backAtDaysGoesToCityWithoutStartCity() {
        val days = CityQuestionFlow.start(null).pickCity("부산")
        assertEquals(CityStep.DAYS, days.step)
        val back = days.back()
        assertEquals(CityStep.CITY, back?.step, "닫기(null)가 아니라 도시 질문으로")
    }

    /** 도시 칩으로 들어왔으면 「며칠?」 의 뒤로는 닫기다. */
    @Test
    fun backAtDaysClosesWithStartCity() {
        assertNull(CityQuestionFlow.start("부산").back())
    }

    @Test
    fun backFromHowGoesToDays() {
        val back =
            CityQuestionFlow
                .start(null)
                .pickCity("부산")
                .pickDays(2)
                .back()
        assertEquals(CityStep.DAYS, back?.step)
    }

    @Test
    fun backFromPlanAndPickGoesToHow() {
        val how = CityQuestionFlow.start(null).pickCity("부산").pickDays(2)
        assertEquals(CityStep.HOW, how.planForMe().back()?.step)
        assertEquals(CityStep.HOW, how.pickMyself().back()?.step)
    }

    /** 도시 칩으로 들어와 끝까지 갔다가 뒤로만 누르면: 추천 → 어떻게 → 며칠 → 닫기. */
    @Test
    fun backAllTheWayWithStartCity() {
        val plan = CityQuestionFlow.start("부산").pickDays(2).planForMe()
        val how = plan.back()
        assertEquals(CityStep.HOW, how?.step)
        val days = how?.back()
        assertEquals(CityStep.DAYS, days?.step)
        assertTrue(days != null && days.back() == null, "며칠에서 뒤로는 닫기")
    }

    // ── R1 하루 몇 곳 ──
    // 앱 안 도시 계산(chain·plan)·가장 가까운 곳 바꾸기·꾹 눌러 옮기기 시험은 뺐다 — 도시 추천 코스는 서버만 짠다
    // (MZ2AZ-548, city-ai-course.md §5). 요청·응답·바꾸기는 CityAiCourseRulesTests.

    @Test
    fun stopsPerDayByPace() {
        assertEquals(3, CityPlanRules.stopsPerDay(TripPace.relaxed))
        assertEquals(5, CityPlanRules.stopsPerDay(TripPace.normal))
        assertEquals(7, CityPlanRules.stopsPerDay(TripPace.packed))
    }

    // ── R3 빼기 ──

    @Test
    fun removedTakesOutOneStop() {
        val plan = listOf(listOf(b.haeundae, b.gwangalli, b.busanStation), listOf(b.jagalchi))
        assertEquals(
            b.idPlan(listOf(listOf(b.haeundae, b.busanStation), listOf(b.jagalchi))),
            b.idPlan(CityPlanRules.removed(plan, day = 0, index = 1)),
        )
    }

    /** 그날이 비어도 날은 남긴다. 범위 밖이면 그대로. */
    @Test
    fun removedKeepsEmptyDayAndIgnoresOutOfRange() {
        val plan = listOf(listOf(b.haeundae, b.gwangalli), listOf(b.jagalchi))
        assertEquals(
            listOf(b.ids(listOf(b.haeundae, b.gwangalli)), emptyList()),
            b.idPlan(CityPlanRules.removed(plan, day = 1, index = 0)),
        )
        assertEquals(b.idPlan(plan), b.idPlan(CityPlanRules.removed(plan, day = 2, index = 0)))
        assertEquals(b.idPlan(plan), b.idPlan(CityPlanRules.removed(plan, day = 1, index = 1)))
        assertEquals(b.idPlan(plan), b.idPlan(CityPlanRules.removed(plan, day = -1, index = 0)))
    }

    // ── S1 며칠로 나누기 (웹 `splitDays`) ──

    @Test
    fun splitDaysCeilChunks() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), CityPlanRules.splitDays(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), CityPlanRules.splitDays(listOf(1, 2, 3, 4, 5, 6), 3))
    }

    /** 하루 `ceil(n / days)` 곳씩이라 뒷날이 빌 수 있다 — 고르게 나누기(`splitEven`)와 다르다. */
    @Test
    fun splitDaysCanLeaveTrailingDaysEmpty() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), emptyList()), CityPlanRules.splitDays(listOf(1, 2, 3, 4), 3))
        assertEquals(listOf(listOf(1), emptyList(), emptyList()), CityPlanRules.splitDays(listOf(1), 3))
    }

    /** 길이는 언제나 days, 0 이하면 빈 목록. */
    @Test
    fun splitDaysAlwaysHasDaysLength() {
        assertEquals(listOf(emptyList<Int>(), emptyList()), CityPlanRules.splitDays(emptyList<Int>(), 2))
        assertEquals(emptyList<List<Int>>(), CityPlanRules.splitDays(listOf(1, 2), 0))
    }

    // ── R5 저장할 코스 ──

    @Test
    fun routePaceOnlyPackedIsTight() {
        assertEquals(RoutePace.TIGHT, CityPlanRules.routePace(TripPace.packed))
        assertEquals(RoutePace.LOOSE, CityPlanRules.routePace(TripPace.normal))
        assertEquals(RoutePace.LOOSE, CityPlanRules.routePace(TripPace.relaxed))
    }

    @Test
    fun courseHasDaysAndStopsInOrder() {
        val plan = listOf(listOf(b.haeundae, b.gwangalli), listOf(b.jagalchi))
        val course = CityPlanRules.course("부산 1박 2일", plan, TripPace.normal, madeByAI = true)
        assertEquals("부산 1박 2일", course.title)
        assertEquals(b.idPlan(plan), course.days.map { day -> day.stops.map { it.place.id } })
        assertEquals(RoutePace.LOOSE, course.pace)
        assertTrue(course.madeByAI)
        assertNull(course.serverId)
    }

    @Test
    fun coursePackedNotByAI() {
        val course = CityPlanRules.course("부산 여행", listOf(listOf(b.haeundae)), TripPace.packed, madeByAI = false)
        assertEquals("부산 여행", course.title)
        assertEquals(RoutePace.TIGHT, course.pace)
        assertFalse(course.madeByAI)
        assertEquals(1, course.days.size)
    }

    // ── A2 작품 고르기·A3 짜는 중 ──

    private fun work(id: Long) = ContentSummary(id = id, category = ContentCategory.drama, title = "작품$id", placeCount = 3)

    @Test
    fun maxWorksIsFive() {
        assertEquals(5, WorkPickRules.MAX_WORKS)
    }

    @Test
    fun preselectLikedWorksOnly() {
        val works = listOf(1L, 2L, 3L, 4L).map { work(it) }
        assertEquals(setOf(2L, 4L), WorkPickRules.preselect(works, setOf(2L, 4L, 99L)))
        assertEquals(emptySet<Long>(), WorkPickRules.preselect(works, emptySet()))
    }

    /** 찜이 다섯보다 많으면 작품 목록 순서로 앞의 다섯. */
    @Test
    fun preselectTakesFirstFiveInWorksOrder() {
        val works = listOf(10L, 9L, 8L, 7L, 6L, 5L, 4L).map { work(it) }
        assertEquals(setOf(10L, 9L, 8L, 7L, 6L), WorkPickRules.preselect(works, (4L..10L).toSet()))
    }

    @Test
    fun toggleAddsAndRemoves() {
        assertEquals(WorkToggle(setOf(1L, 2L), false), WorkPickRules.toggle(setOf(1L), 2L))
        assertEquals(WorkToggle(setOf(2L), false), WorkPickRules.toggle(setOf(1L, 2L), 1L))
    }

    @Test
    fun toggleAtLimitWarnsAndKeeps() {
        val five = setOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(WorkToggle(five, true), WorkPickRules.toggle(five, 6L))
    }

    /** 다섯을 고른 상태에서도 빼기는 된다. */
    @Test
    fun toggleAtLimitStillRemoves() {
        assertEquals(WorkToggle(setOf(1L, 2L, 4L, 5L), false), WorkPickRules.toggle(setOf(1L, 2L, 3L, 4L, 5L), 3L))
    }

    @Test
    fun planningStageStarts() {
        assertEquals(listOf(0L, 3500L, 8000L), PlanningStages.STARTS_MILLIS)
    }

    @Test
    fun planningStageCurrent() {
        assertEquals(0, PlanningStages.current(0))
        assertEquals(0, PlanningStages.current(3499))
        assertEquals(1, PlanningStages.current(3500))
        assertEquals(1, PlanningStages.current(7999))
        assertEquals(2, PlanningStages.current(8000))
        assertEquals(2, PlanningStages.current(120_000))
        assertEquals(0, PlanningStages.current(-500), "음수 시간은 0")
    }

    // ── Q4 정한 여행 맞추기 ──

    private val localTrip = TripMemo(city = "부산", days = 2, pace = TripPace.normal, courseId = null)
    private val serverTrip = TripMemo(city = "경주", days = 1, pace = TripPace.packed, courseId = 42)

    @Test
    fun serverWins() {
        assertEquals(TripReconcile(serverTrip, false), TripMemoryRules.reconcile(localTrip, ServerTrip.Found(serverTrip)))
        assertEquals(TripReconcile(serverTrip, false), TripMemoryRules.reconcile(null, ServerTrip.Found(serverTrip)))
    }

    /** 서버에 없고 기기에만 있으면 기기 것을 쓰고 올린다. 둘 다 없으면 아무것도 안 한다. */
    @Test
    fun missingOnServerPushesLocal() {
        assertEquals(TripReconcile(localTrip, true), TripMemoryRules.reconcile(localTrip, ServerTrip.Missing))
        assertEquals(TripReconcile(null, false), TripMemoryRules.reconcile(null, ServerTrip.Missing))
    }

    /** 서버에 묻지 못했으면 기기 것 그대로, 올리지 않는다. */
    @Test
    fun failedKeepsLocalWithoutPush() {
        assertEquals(TripReconcile(localTrip, false), TripMemoryRules.reconcile(localTrip, ServerTrip.Failed))
        assertEquals(TripReconcile(null, false), TripMemoryRules.reconcile(null, ServerTrip.Failed))
    }

    // ── 문구 (한국어 — JVM 시험의 AppLanguage.current 기본값은 ko) ──

    @Test
    fun daysLabel() {
        assertEquals("당일치기", NewCourseText.days(1))
        assertEquals("1박 2일", NewCourseText.days(2))
        assertEquals("2박 3일", NewCourseText.days(3))
        assertEquals("3박 4일", NewCourseText.days(4))
        assertEquals("당일치기", NewCourseText.days(0), "1 이하는 당일치기")
    }

    @Test
    fun titles() {
        assertEquals("부산 1박 2일 추천 코스", NewCourseText.planTitle("부산", 2))
        assertEquals("경주 당일치기 추천 코스", NewCourseText.planTitle("경주", 1))
        assertEquals("부산 1박 2일", NewCourseText.courseTitle("부산", 2))
        assertEquals("경주 당일치기", NewCourseText.courseTitle("경주", 1))
        assertEquals("부산 여행", NewCourseText.pickedTitle("부산"))
    }

    @Test
    fun createdBanner() {
        assertEquals(
            "부산 1박 2일 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요",
            NewCourseText.createdBanner("부산", 2),
        )
        assertEquals(6000L, NewCourseText.BANNER_MILLIS)
    }

    @Test
    fun savedCaption() {
        assertEquals("담아 둔 3곳을 날마다 나눠 넣어요", NewCourseText.savedCaption(3))
        assertEquals("빈 코스에서 하나씩 넣어요", NewCourseText.savedCaption(0))
    }

    /** 사진으로 쓸 수 있는 주소 — 절대 http(s) 이고 호스트가 있을 때만. */
    @Test
    fun usablePhotoNeedsAbsoluteHttpWithHost() {
        assertTrue(NewCourseText.usablePhoto("https://img.example/a.jpg"))
        assertTrue(NewCourseText.usablePhoto("http://img.example/a.jpg"))
        assertFalse(NewCourseText.usablePhoto(null))
        assertFalse(NewCourseText.usablePhoto(""))
        assertFalse(NewCourseText.usablePhoto("/images/a.jpg"), "상대 주소")
        assertFalse(NewCourseText.usablePhoto("ftp://img.example/a.jpg"), "http(s) 가 아님")
        assertFalse(NewCourseText.usablePhoto("https:///a.jpg"), "호스트 없음")
        assertFalse(NewCourseText.usablePhoto("그냥 글자"))
    }

    // ── Q1 도시 칸 ──

    private fun city(
        key: String,
        count: Int,
    ) = PlaceCity(key = key, name = key, placeCount = count, center = LatLng(37.0, 127.0), bbox = listOf(126.0, 36.0, 128.0, 38.0))

    @Test
    fun cityListConstants() {
        assertEquals(8, CityListRules.SHOWN_CITIES)
        assertEquals(200, CityListRules.PLACE_PAGE)
        assertEquals(30, CityListRules.PICK_LIMIT)
    }

    /** 촬영지 0곳은 빼고, 서버 순서 그대로(수로 다시 정렬하지 않는다), 앞에서 8개. */
    @Test
    fun shownCitiesDropEmptyKeepOrderTakeEight() {
        val cities =
            listOf(
                city("서울", 50),
                city("빈 도시", 0),
                city("부산", 90),
                city("경주", 10),
                city("인천", 5),
                city("강릉", 7),
                city("전주", 3),
                city("여수", 2),
                city("또 빈 도시", 0),
                city("제주", 40),
                city("수원", 1),
            )
        assertEquals(
            listOf("서울", "부산", "경주", "인천", "강릉", "전주", "여수", "제주"),
            CityListRules.shown(cities).map { it.key },
        )
        assertEquals(listOf("부산"), CityListRules.shown(listOf(city("부산", 1), city("빈", 0))).map { it.key })
    }
}
