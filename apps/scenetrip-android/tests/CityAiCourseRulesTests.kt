package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.CityPlanRules
import com.mz2az.scenetrip.sceneapi.client.model.ContentRef
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlan
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanDay
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanRequest
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanStop
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * 도시 추천 코스는 서버만 짠다 (MZ2AZ-548, 계획 docs/project/plans/city-ai-course.md §5).
 * 요청 만들기(도시·일수·속도만), 응답만으로 카드 그리기, 「다른 곳으로 바꾸기」(근처 10곳 가운데 코스에 없는 첫 곳).
 * iOS `NewCourseRulesTests` 의 같은 시험을 옮겼다. 모델·서버는 부르지 않는다.
 */
class CityAiCourseRulesTests {
    private val b = BusanSpots

    // ── 요청 — POST /guide/plan {city, days, pace} ──

    @Test
    fun aiRequestCarriesCityDaysPaceAndNoTitles() {
        val request = CityPlanRules.aiRequest(city = "부산", days = 2, pace = TripPace.normal)
        assertEquals("부산", request.city)
        assertEquals(2, request.days)
        assertEquals(GuidePlanRequest.Pace.normal, request.pace)
        // 작품은 보내지 않는다 — 계약은 titles 와 city 중 정확히 하나(빈 목록도 안 보낸 것으로 보지만 아예 비워 둔다).
        assertNull(request.titles)
    }

    /** 도시 값은 `GET /places/cities` 의 key 그대로 — 띄어쓰기도 남긴다. */
    @Test
    fun aiRequestKeepsCityKeyAsIs() {
        val request = CityPlanRules.aiRequest(city = "경기 광주", days = 1, pace = TripPace.normal)
        assertEquals("경기 광주", request.city)
        assertEquals(1, request.days)
    }

    @Test
    fun aiRequestMapsEachPace() {
        assertEquals(GuidePlanRequest.Pace.relaxed, CityPlanRules.aiRequest("부산", 3, TripPace.relaxed).pace)
        assertEquals(GuidePlanRequest.Pace.normal, CityPlanRules.aiRequest("부산", 3, TripPace.normal).pace)
        assertEquals(GuidePlanRequest.Pace.packed, CityPlanRules.aiRequest("부산", 3, TripPace.packed).pace)
    }

    /** 출발 좌표·꼭/빼기는 보내지 않는다 — 도시 질문은 그런 것을 묻지 않는다. */
    @Test
    fun aiRequestSendsNothingElse() {
        val request = CityPlanRules.aiRequest(city = "부산", days = 2, pace = TripPace.normal)
        assertEquals(GuidePlanRequest(city = "부산", days = 2, pace = GuidePlanRequest.Pace.normal), request)
        assertNull(request.latitude)
        assertNull(request.longitude)
        assertNull(request.must)
        assertNull(request.avoid)
    }

    // ── 응답만으로 카드 ──

    private fun stop(
        order: Int,
        place: PlaceSummary?,
        name: String = place?.name ?: "이름만",
        latitude: Double? = place?.latitude,
        longitude: Double? = place?.longitude,
        imageUrl: String? = null,
        contents: List<ContentRef>? = null,
        address: String? = place?.address,
        sceneDescription: String? = null,
    ) = GuidePlanStop(
        order = order,
        name = name,
        arriveMinute = 540 + order * 60,
        placeId = place?.id,
        address = address,
        latitude = latitude,
        longitude = longitude,
        imageUrl = imageUrl,
        contents = contents,
        sceneDescription = sceneDescription,
    )

    private fun day(
        number: Int,
        vararg stops: GuidePlanStop,
    ) = GuidePlanDay(day = number, stops = stops.toList())

    private fun plan(vararg days: GuidePlanDay) = GuidePlan(days = days.toList())

    /** 하루 한 장인 계획의 그 카드 — 모양이 다르면 꺼내기 전에 실패로 알린다. */
    private fun onlyCard(out: List<List<PlaceSummary>>): PlaceSummary {
        assertEquals(listOf(1), out.map { it.size }, "하루 한 장이어야 한다")
        return out[0][0]
    }

    /** 정지점 하나만으로 카드 한 장 — 번호·이름·주소·좌표·사진·작품·장면 설명을 그대로 옮긴다. */
    @Test
    fun fromGuideBuildsCardFromStopAlone() {
        val contents = listOf(ContentRef(contentId = 11, title = "도깨비"))
        val one =
            stop(
                1,
                b.haeundae,
                imageUrl = "https://img.example.com/haeundae.jpg",
                contents = contents,
                sceneDescription = "바닷가에서 만나는 장면",
            )
        val out = CityPlanRules.fromGuide(plan(day(1, one)))
        assertEquals(1, out.size)
        assertEquals(1, out[0].size)
        val card = out[0][0]
        assertEquals(b.haeundae.id, card.id)
        assertEquals("해운대", card.name)
        assertEquals("부산광역시 해운대구 우동", card.address)
        assertEquals(b.haeundae.latitude, card.latitude)
        assertEquals(b.haeundae.longitude, card.longitude)
        assertEquals(URI.create("https://img.example.com/haeundae.jpg"), card.imageUrl)
        assertEquals(contents, card.contents)
        assertEquals("바닷가에서 만나는 장면", card.sceneDescription)
    }

    /** 작품은 서버 순서 그대로 — 카드 작품 표·뒤집기(contents[0])가 이 차례를 쓴다. */
    @Test
    fun fromGuideKeepsContentsOrder() {
        val contents =
            listOf(
                ContentRef(contentId = 3, title = "셋"),
                ContentRef(contentId = 1, title = "하나"),
                ContentRef(contentId = 2, title = "둘"),
            )
        val out = CityPlanRules.fromGuide(plan(day(1, stop(1, b.haeundae, contents = contents))))
        assertEquals(listOf(3L, 1L, 2L), onlyCard(out).contents?.map { it.contentId })
    }

    /** 사진·작품·설명이 없으면 비운다 — 도시 촬영지 목록으로 메우지 않는다. */
    @Test
    fun fromGuideLeavesMissingExtrasEmpty() {
        val card = onlyCard(CityPlanRules.fromGuide(plan(day(1, stop(1, b.gamcheon)))))
        assertEquals(b.gamcheon.id, card.id)
        assertNull(card.imageUrl)
        assertNull(card.contents)
        assertNull(card.sceneDescription)
    }

    /** 사진 주소가 주소로 읽히지 않으면 사진 없음 — 화면이 죽지 않는다. */
    @Test
    fun fromGuideDropsUnreadableImageUrl() {
        val card = onlyCard(CityPlanRules.fromGuide(plan(day(1, stop(1, b.gamcheon, imageUrl = "그냥 글자 주소")))))
        assertEquals(b.gamcheon.id, card.id)
        assertNull(card.imageUrl)
    }

    /** 정지점은 `order` 순 — 응답 안 차례가 섞여 와도. */
    @Test
    fun fromGuideOrdersStopsByOrder() {
        val out =
            CityPlanRules.fromGuide(
                plan(day(1, stop(3, b.jagalchi), stop(1, b.haeundae), stop(2, b.gwangalli))),
            )
        assertEquals(listOf(b.ids(listOf(b.haeundae, b.gwangalli, b.jagalchi))), b.idPlan(out))
    }

    /** 번호(`placeId`)가 없는 줄은 저장할 수 없으니 뺀다 — 이름으로 대신 찾지 않는다. */
    @Test
    fun fromGuideDropsStopsWithoutPlaceId() {
        val out =
            CityPlanRules.fromGuide(
                plan(
                    day(1, stop(1, b.haeundae), stop(2, null, name = "이름만 있는 곳", latitude = 35.1, longitude = 129.0), stop(3, b.gamcheon)),
                ),
            )
        assertEquals(listOf(b.ids(listOf(b.haeundae, b.gamcheon))), b.idPlan(out))
    }

    /** 좌표가 하나라도 없으면 지도에 못 놓으니 뺀다. */
    @Test
    fun fromGuideDropsStopsWithoutCoordinates() {
        val out =
            CityPlanRules.fromGuide(
                plan(
                    day(
                        1,
                        stop(1, b.haeundae),
                        stop(2, b.gwangalli, latitude = null),
                        stop(3, b.jagalchi, longitude = null),
                        stop(4, b.gamcheon),
                    ),
                ),
            )
        assertEquals(listOf(b.ids(listOf(b.haeundae, b.gamcheon))), b.idPlan(out))
    }

    /** 쓸 줄이 하나도 남지 않은 날은 뺀다. */
    @Test
    fun fromGuideDropsDaysLeftEmpty() {
        val out =
            CityPlanRules.fromGuide(
                plan(
                    day(1, stop(1, b.haeundae)),
                    day(2, stop(1, null, name = "번호 없음", latitude = 35.0, longitude = 129.0)),
                    day(3),
                    day(4, stop(1, b.jagalchi)),
                ),
            )
        assertEquals(listOf(listOf(b.haeundae.id), listOf(b.jagalchi.id)), b.idPlan(out))
    }

    /** 쓸 것이 하나도 없으면 빈 계획 — 화면은 「코스를 못 짰어요」 를 띄운다. */
    @Test
    fun fromGuideEmptyWhenNothingUsable() {
        val nothing = plan(day(1, stop(1, null, name = "번호 없음", latitude = 35.0, longitude = 129.0)), day(2))
        assertEquals(0, CityPlanRules.fromGuide(nothing).size)
        assertEquals(0, CityPlanRules.fromGuide(plan()).size)
    }

    @Test
    fun fromGuideKeepsMultipleDays() {
        val out =
            CityPlanRules.fromGuide(
                plan(
                    day(1, stop(1, b.haeundae), stop(2, b.gwangalli)),
                    day(2, stop(1, b.jagalchi), stop(2, b.gamcheon), stop(3, b.taejongdae)),
                ),
            )
        assertEquals(
            b.idPlan(listOf(listOf(b.haeundae, b.gwangalli), listOf(b.jagalchi, b.gamcheon, b.taejongdae))),
            b.idPlan(out),
        )
    }

    // ── 「다른 곳으로 바꾸기」 — 근처 10곳(가까운 순) 가운데 코스에 없는 첫 곳 ──

    /** 그 카드 한 장만 바뀐다. */
    @Test
    fun swappedPicksFirstUnusedAndReplacesOnlyThatCard() {
        val current = listOf(listOf(b.haeundae, b.gwangalli), listOf(b.jagalchi, b.gamcheon))
        val nearby = listOf(b.jagalchi, b.busanStation, b.taejongdae)
        val next = CityPlanRules.swapped(current, day = 1, index = 0, nearby = nearby)
        assertEquals(
            b.idPlan(listOf(listOf(b.haeundae, b.gwangalli), listOf(b.busanStation, b.gamcheon))),
            b.idPlan(next),
        )
    }

    /** 다른 날에 든 곳도 「이미 코스에 있는 곳」 이다. */
    @Test
    fun swappedSkipsPlacesUsedOnOtherDays() {
        val current = listOf(listOf(b.haeundae), listOf(b.dongbaek))
        val nearby = listOf(b.haeundae, b.dongbaek, b.gwangalli, b.yonggungsa)
        assertEquals(
            b.idPlan(listOf(listOf(b.gwangalli), listOf(b.dongbaek))),
            b.idPlan(CityPlanRules.swapped(current, day = 0, index = 0, nearby = nearby)),
        )
    }

    /** 받은 차례를 그대로 쓴다 — 거리를 다시 재지 않는다(목록 첫 곳 해운대가 감천에서 더 멀어도 해운대). */
    @Test
    fun swappedTakesListOrderNotRecomputedDistance() {
        val current = listOf(listOf(b.gamcheon))
        val nearby = listOf(b.haeundae, b.jagalchi)
        assertEquals(
            listOf(listOf(b.haeundae.id)),
            b.idPlan(CityPlanRules.swapped(current, day = 0, index = 0, nearby = nearby)),
        )
    }

    /** 바꿀 곳이 없으면(모두 코스에 있거나 근처가 비면) 그대로. */
    @Test
    fun swappedUnchangedWhenAllUsedOrNoNearby() {
        val current = listOf(listOf(b.haeundae, b.gwangalli))
        assertEquals(
            b.idPlan(current),
            b.idPlan(CityPlanRules.swapped(current, day = 0, index = 1, nearby = listOf(b.haeundae, b.gwangalli))),
        )
        assertEquals(b.idPlan(current), b.idPlan(CityPlanRules.swapped(current, day = 0, index = 1, nearby = emptyList())))
    }

    /** 범위 밖이면 그대로. */
    @Test
    fun swappedUnchangedWhenOutOfRange() {
        val current = listOf(listOf(b.haeundae, b.gwangalli))
        assertEquals(b.idPlan(current), b.idPlan(CityPlanRules.swapped(current, day = 3, index = 0, nearby = b.popular)))
        assertEquals(b.idPlan(current), b.idPlan(CityPlanRules.swapped(current, day = 0, index = 5, nearby = b.popular)))
        assertEquals(b.idPlan(current), b.idPlan(CityPlanRules.swapped(current, day = -1, index = 0, nearby = b.popular)))
    }
}
