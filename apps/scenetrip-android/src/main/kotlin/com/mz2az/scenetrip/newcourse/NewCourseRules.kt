package com.mz2az.scenetrip.newcourse

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentRef
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlan
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanRequest
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace

// 새 코스 만들기의 순수 규칙 (MZ2AZ-547 P3 — F·Q·R·S·A, 계획 docs/project/plans/ios-web-parity.md).
// iOS `NewCourse/NewCourseRules.swift` 를 옮긴 것이다. 화면은 얇게 두고 판단은 전부 여기서 한다.
// 웹 기준: `features/proto/{protoState.ts, protoParts.tsx, ProtoPlanFlows.tsx}`.

/** 새 코스를 무엇으로 시작하나 — 새 코스 시트(F1)의 세 줄과 홈 도시 칩. */
sealed class NewCourseStart {
    /** 도시로 시작. 도시를 주면(홈 카드의 도시 칩) 「며칠?」(Q2)부터. */
    data class City(
        val key: String?,
    ) : NewCourseStart()

    /** 작품으로(AI) — A1·A2·A3. */
    data object Works : NewCourseStart()

    /** 담은 곳으로 — A4. */
    data object Saved : NewCourseStart()
}

// ---- 도시로 여행 짜기 질문 (Q1–Q3) ----

/** 도시 → 며칠 → 어떻게 → (알아서 짜기 R | 직접 고르기 S) 의 단계. */
enum class CityStep { CITY, DAYS, HOW, PLAN, PICK }

/**
 * 도시 질문의 상태. 값 타입이다 — 단계를 옮기는 함수는 **새 값**을 돌려준다(Compose 상태에 그대로 넣는다).
 *
 * [startCity] 는 홈 도시 칩으로 들어왔을 때의 도시다. 이때 「며칠?」 의 뒤로는 닫기다.
 */
data class CityQuestionFlow(
    val startCity: String?,
    val step: CityStep,
    val city: String?,
    val days: Int?,
) {
    /** Q1 에서 도시를 골랐다 → Q2. */
    fun pickCity(key: String): CityQuestionFlow = copy(city = key, step = CityStep.DAYS)

    /** Q2 에서 며칠을 골랐다 → Q3. 도시가 없으면 그대로. */
    fun pickDays(count: Int): CityQuestionFlow = if (city == null) this else copy(days = count, step = CityStep.HOW)

    /** Q3 「알아서 짜 주세요」 → R. 「어떻게?」 단계에서 며칠이 있을 때만. */
    fun planForMe(): CityQuestionFlow = if (step != CityStep.HOW || days == null) this else copy(step = CityStep.PLAN)

    /** Q3 「직접 고를게요」 → S. 「어떻게?」 단계에서 며칠이 있을 때만. */
    fun pickMyself(): CityQuestionFlow = if (step != CityStep.HOW || days == null) this else copy(step = CityStep.PICK)

    /**
     * 뒤로. 질문 흐름을 **닫아야 하면 null** 이다 — 도시 질문에서, 또는 홈 도시 칩으로 들어와 「며칠?」 에서.
     * 그 밖에는 한 단계 앞(추천 코스·직접 고르기 → 어떻게, 어떻게 → 며칠, 며칠 → 도시).
     */
    fun back(): CityQuestionFlow? =
        when (step) {
            CityStep.CITY -> null

            // 홈 도시 칩으로 들어왔으면 도시 질문이 없었다 — 뒤로는 닫기다.
            CityStep.DAYS -> if (startCity != null) null else copy(step = CityStep.CITY)

            CityStep.HOW -> copy(step = CityStep.DAYS)

            CityStep.PLAN, CityStep.PICK -> copy(step = CityStep.HOW)
        }

    /** 단계 점에 그릴 번호(1…3). 추천 코스·직접 고르기는 점이 없다(null). */
    val stepNumber: Int?
        get() =
            when (step) {
                CityStep.CITY -> 1
                CityStep.DAYS -> 2
                CityStep.HOW -> 3
                CityStep.PLAN, CityStep.PICK -> null
            }

    companion object {
        /** 「며칠?」 의 고를 거리 — 당일치기 · 1박 2일 · 2박 3일 · 3박 4일. */
        val DAY_OPTIONS: List<Int> = listOf(1, 2, 3, 4)

        /** 처음 상태 — 도시를 주면 「며칠?」 부터, 아니면 도시부터. */
        fun start(startCity: String?): CityQuestionFlow =
            CityQuestionFlow(startCity, if (startCity == null) CityStep.CITY else CityStep.DAYS, startCity, null)
    }
}

// ---- 추천 코스 (R1–R5) — 서버 AI 가 짠다(`POST /guide/plan {city}`, MZ2AZ-548) ----

object CityPlanRules {
    /** 하루 몇 곳 — relaxed 3 · normal 5 · packed 7. */
    fun stopsPerDay(pace: TripPace): Int =
        when (pace) {
            TripPace.relaxed -> 3
            TripPace.normal -> 5
            TripPace.packed -> 7
        }

    /**
     * 「다른 곳으로 바꾸기」 — [day] 일의 [index] 번째를 [nearby] 가운데 코스에 아직 없는 **첫 곳**으로 바꾼 계획. 없거나 범위 밖이면 그대로.
     * [nearby] 는 누를 때 서버에 물은 그 자리 근처 촬영지, 가까운 순(`GET /places?city&lat&lng&sort=distance&limit=10`,
     * 계획 city-ai-course.md §5). 거리를 다시 재지 않고 받은 차례를 그대로 쓴다.
     */
    fun swapped(
        plan: List<List<PlaceSummary>>,
        day: Int,
        index: Int,
        nearby: List<PlaceSummary>,
    ): List<List<PlaceSummary>> {
        val stops = plan.getOrNull(day) ?: return plan
        if (index !in stops.indices) return plan
        val used = plan.flatten().map { it.id }.toSet()
        val next = nearby.firstOrNull { it.id !in used } ?: return plan
        return plan.mapIndexed { at, each -> if (at == day) each.toMutableList().also { it[index] = next } else each }
    }

    /** 서버 AI 일정 요청(MZ2AZ-548, 계획 city-ai-course.md §5) — 도시·일수·속도만. 작품은 보내지 않는다. */
    fun aiRequest(
        city: String,
        days: Int,
        pace: TripPace,
    ): GuidePlanRequest =
        GuidePlanRequest(
            city = city,
            days = days,
            pace =
                when (pace) {
                    TripPace.relaxed -> GuidePlanRequest.Pace.relaxed
                    TripPace.normal -> GuidePlanRequest.Pace.normal
                    TripPace.packed -> GuidePlanRequest.Pace.packed
                },
        )

    /**
     * 서버가 준 일정을 카드로 — 응답만으로 그린다(도시 촬영지 목록 없이, 계획 city-ai-course.md §5). 정지점은 `order` 순,
     * 번호(`placeId`)나 좌표가 없는 줄은 빼고, 빈 날도 뺀다. 사진·작품은 정지점의 `imageUrl`·`contents`
     * (`imageUrl` 이 주소로 읽히지 않으면 사진 없음).
     */
    fun fromGuide(plan: GuidePlan): List<List<PlaceSummary>> =
        plan.days
            .map { day ->
                day.stops.sortedBy { it.order }.mapNotNull { stop ->
                    val id = stop.placeId ?: return@mapNotNull null
                    val lat = stop.latitude ?: return@mapNotNull null
                    val lng = stop.longitude ?: return@mapNotNull null
                    PlaceSummary(
                        id = id,
                        name = stop.name,
                        latitude = lat,
                        longitude = lng,
                        address = stop.address,
                        imageUrl = stop.imageUrl?.takeIf { NewCourseText.usablePhoto(it) }?.let { java.net.URI(it) },
                        contents = stop.contents,
                        sceneDescription = stop.sceneDescription,
                    )
                }
            }.filter { it.isNotEmpty() }

    /** [day] 일의 [index] 번째를 뺀 계획(그날이 비어도 날은 남긴다). 범위 밖이면 그대로. */
    fun removed(
        plan: List<List<PlaceSummary>>,
        day: Int,
        index: Int,
    ): List<List<PlaceSummary>> {
        val stops = plan.getOrNull(day) ?: return plan
        if (index !in stops.indices) return plan
        return plan.mapIndexed { at, each -> if (at == day) each.toMutableList().also { it.removeAt(index) } else each }
    }

    /** 고른 곳을 며칠로 나눈다(직접 고르기 S1) — 하루 `ceil(n / days)` 곳(최소 1)씩 담은 순서대로. 길이는 언제나 [days](0 이하면 빈 목록). */
    fun <T> splitDays(
        items: List<T>,
        days: Int,
    ): List<List<T>> {
        if (days <= 0) return emptyList()
        val per = maxOf(1, (items.size + days - 1) / days)
        return (0 until days).map { day -> items.drop(day * per).take(per) }
    }

    /** 서버 코스의 속도 — `packed` 만 빡빡하게, 나머지는 널널하게(웹 `saveCourse`). */
    fun routePace(pace: TripPace): RoutePace = if (pace == TripPace.packed) RoutePace.TIGHT else RoutePace.LOOSE

    /** 저장할 코스 — 날마다 촬영지를 차례로 넣는다. */
    fun course(
        title: String,
        plan: List<List<PlaceSummary>>,
        pace: TripPace,
        madeByAI: Boolean,
    ): RouteCourse =
        RouteCourse(
            title = title,
            pace = routePace(pace),
            days = plan.map { stops -> RouteDay(stops = stops.map { RouteStop(place = it) }) },
            madeByAI = madeByAI,
        )
}

// ---- 담은 곳으로 (A4) — 웹 `ProtoPlanFlows.tsx` `arrange`·`splitEven` ----

object SavedPlanRules {
    /** 시·도 이름을 짧게 — 「경상북도」→「경북」. */
    val PROVINCES: Map<String, String> =
        mapOf(
            "경상북도" to "경북",
            "경상남도" to "경남",
            "전라북도" to "전북",
            "전북특별자치도" to "전북",
            "전라남도" to "전남",
            "충청북도" to "충북",
            "충청남도" to "충남",
            "강원도" to "강원",
            "강원특별자치도" to "강원",
            "제주특별자치도" to "제주",
            "경기도" to "경기",
            "세종특별자치시" to "세종",
        )

    /** 주소 첫 낱말의 시·도 — 「서울특별시」→「서울」, 「부산광역시」→「부산」, 「경상북도」→「경북」. 주소가 없거나 비면 「기타」. */
    fun regionOf(address: String?): String {
        val head =
            address
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                .orEmpty()
        if (head.isEmpty()) return "기타"
        PROVINCES[head]?.let { return it }
        for (suffix in listOf("특별시", "광역시")) {
            if (head.endsWith(suffix)) return head.removeSuffix(suffix)
        }
        return head
    }

    /** 시·도 묶음을 큰 것부터(같으면 먼저 나온 묶음), 묶음 안에서는 앞 장소에서 가장 가까운 곳을 차례로. 좌표가 없는 곳(0,0)은 그 묶음 맨 뒤. */
    fun arrange(places: List<PlaceSummary>): List<PlaceSummary> {
        // 시·도 묶음 — 처음 나온 순서를 기억해 크기가 같으면 먼저 나온 묶음이 앞(안정 정렬).
        val groups = LinkedHashMap<String, MutableList<PlaceSummary>>()
        for (place in places) groups.getOrPut(regionOf(place.address)) { mutableListOf() }.add(place)
        val sorted = groups.entries.sortedByDescending { it.value.size }
        val out = mutableListOf<PlaceSummary>()
        for ((_, group) in sorted) {
            val located = group.filter { it.latitude != 0.0 || it.longitude != 0.0 }.toMutableList()
            val rest = group.filter { it.latitude == 0.0 && it.longitude == 0.0 }
            if (located.isNotEmpty()) {
                var at = located.removeAt(0)
                out.add(at)
                while (located.isNotEmpty()) {
                    val from = at
                    var next = 0
                    for (index in 1 until located.size) {
                        if (near(from, located[index]) < near(from, located[next])) next = index
                    }
                    at = located.removeAt(next)
                    out.add(at)
                }
            }
            out.addAll(rest)
        }
        return out
    }

    /** 지도 위 거리의 눈대중(위도·경도 차의 제곱합, 경도는 0.8배). 같은 시·도 안에서 순서를 정하는 데만 쓴다. */
    private fun near(
        lhs: PlaceSummary,
        rhs: PlaceSummary,
    ): Double {
        val lat = lhs.latitude - rhs.latitude
        val lng = (lhs.longitude - rhs.longitude) * 0.8
        return lat * lat + lng * lng
    }

    /** 차례대로 날마다 고르게 — 남는 것은 앞날부터 하나씩. 날이 장소보다 많으면 뒷날은 빈다. 길이는 언제나 [days](0 이하면 빈 목록). */
    fun <T> splitEven(
        items: List<T>,
        days: Int,
    ): List<List<T>> {
        if (days <= 0) return emptyList()
        val base = items.size / days
        val extra = items.size % days
        var at = 0
        return (0 until days).map { day ->
            val count = base + if (day < extra) 1 else 0
            items.subList(at, at + count).toList().also { at += count }
        }
    }

    /** 보관함 → 코스에 넣을 장소. 판매 중단(`delisted == true`)은 빼고, 좌표가 없으면 (0,0), 담은 작품이 있으면 contents 에 하나. */
    fun places(items: List<CartItem>): List<PlaceSummary> =
        items.filter { it.delisted != true }.map { item ->
            PlaceSummary(
                id = item.placeId,
                name = item.name,
                latitude = item.latitude ?: 0.0,
                longitude = item.longitude ?: 0.0,
                address = item.address,
                imageUrl = item.imageUrl,
                contents = item.sourceContentId?.let { listOf(ContentRef(contentId = it, title = item.sourceContentTitle ?: "")) },
            )
        }
}

// ---- 작품으로 (A2·A3) ----

data class WorkToggle(
    val selected: Set<Long>,
    /** 이미 최대만큼 골라서 더 고르지 못했다 — 경고를 띄운다. */
    val tooMany: Boolean,
)

object WorkPickRules {
    /** 서버(`POST /guide/plan`)가 받는 작품 제목 수. */
    const val MAX_WORKS = 5

    /** 찜한 작품을 미리 골라 둔다 — 작품 목록 순서대로 최대 [MAX_WORKS] 개. */
    fun preselect(
        works: List<ContentSummary>,
        liked: Set<Long>,
    ): Set<Long> =
        works
            .filter { it.id in liked }
            .take(MAX_WORKS)
            .map { it.id }
            .toSet()

    /** 포스터를 눌렀다 — 고른 것이면 빼고, 아니면 넣되 이미 [MAX_WORKS] 개면 그대로 두고 tooMany. */
    fun toggle(
        selected: Set<Long>,
        id: Long,
    ): WorkToggle {
        if (id in selected) return WorkToggle(selected - id, false)
        if (selected.size >= MAX_WORKS) return WorkToggle(selected, true)
        return WorkToggle(selected + id, false)
    }
}

/** 짜는 중 화면의 세 줄(A3) — 시간이 지나면 앞 줄부터 체크(0 · 3.5 · 8초). */
object PlanningStages {
    val STARTS_MILLIS: List<Long> = listOf(0L, 3_500L, 8_000L)

    /** 지금 돌고 있는 줄의 번호(0…2). 음수 시간은 0. */
    fun current(elapsedMillis: Long): Int = STARTS_MILLIS.indexOfLast { elapsedMillis >= it }.coerceAtLeast(0)
}

// ---- 정한 여행 (Q4) — `/me/trip` ----

/** 정한 여행 — 도시·며칠·속도·만든 코스. 기기에 기억하고 서버(`/me/trip`)와 맞춘다. */
data class TripMemo(
    val city: String,
    val days: Int,
    val pace: TripPace,
    val courseId: Long?,
)

/** 서버에 물어본 결과. */
sealed class ServerTrip {
    data class Found(
        val trip: TripMemo,
    ) : ServerTrip()

    /** 404 — 서버에 없다. */
    data object Missing : ServerTrip()

    /** 묻지 못했다(망·5xx). */
    data object Failed : ServerTrip()
}

data class TripReconcile(
    /** 화면이 쓸 여행. */
    val use: TripMemo?,
    /** 기기 것을 서버에 올려야 하는가. */
    val push: Boolean,
)

object TripMemoryRules {
    /** 서버가 정본이다 — 있으면 그것. 서버에 없고 기기에만 있으면 기기 것을 쓰고 올린다. 묻지 못했으면 기기 것 그대로, 올리지 않는다. */
    fun reconcile(
        local: TripMemo?,
        server: ServerTrip,
    ): TripReconcile =
        when (server) {
            is ServerTrip.Found -> TripReconcile(server.trip, false)
            ServerTrip.Missing -> TripReconcile(local, local != null)
            ServerTrip.Failed -> TripReconcile(local, false)
        }
}

// ---- 문구 ----

object NewCourseText {
    /** R6 안내 띠가 떠 있는 시간(밀리초). */
    const val BANNER_MILLIS: Long = 6_000L

    /** 「당일치기」·「1박 2일」(1 이하는 당일치기). */
    fun days(count: Int): String = if (count <= 1) tr("당일치기") else tr("%d박 %d일").format(count - 1, count)

    /** R2 제목 — 「부산 1박 2일 추천 코스」. */
    fun planTitle(
        city: String,
        days: Int,
    ): String = tr("%s %s 추천 코스").format(city, days(days))

    /** R5 저장 제목 — 「부산 1박 2일」. */
    fun courseTitle(
        city: String,
        days: Int,
    ): String = tr("%s %s", "도시 코스 제목").format(city, days(days))

    /** S1 저장 제목 — 「부산 여행」. */
    fun pickedTitle(city: String): String = tr("%s 여행").format(city)

    /** R6 안내 띠 — 「부산 1박 2일 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요」. */
    fun createdBanner(
        city: String,
        days: Int,
    ): String = tr("%s %s 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요").format(city, days(days))

    /** F1 ③ 「담은 곳으로」 설명 — 담은 곳이 있으면 「담아 둔 N곳을 날마다 나눠 넣어요」, 없으면 「빈 코스에서 하나씩 넣어요」. */
    fun savedCaption(count: Int): String = if (count > 0) tr("담아 둔 %d곳을 날마다 나눠 넣어요").format(count) else tr("빈 코스에서 하나씩 넣어요")

    /** 사진으로 쓸 수 있는 주소인가 — 절대 http(s) 이고 호스트가 있을 때만. */
    fun usablePhoto(raw: String?): Boolean {
        val uri = runCatching { java.net.URI(raw ?: return false) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }
}

/** 도시 질문에 내보일 도시 — 촬영지 0곳은 빼고 서버 순서로 앞에서 8개. */
object CityListRules {
    const val SHOWN_CITIES = 8

    /** 도시 촬영지 한 번에 받는 수 — 추천(최대 7×4×2=56)·직접 고르기(30)에 넉넉하다. */
    const val PLACE_PAGE = 200

    /** 직접 고르기(S1) 목록에 늘어놓는 수. */
    const val PICK_LIMIT = 30

    fun shown(
        cities: List<com.mz2az.scenetrip.sceneapi.client.model.PlaceCity>,
    ): List<com.mz2az.scenetrip.sceneapi.client.model.PlaceCity> = cities.filter { it.placeCount > 0 }.take(SHOWN_CITIES)
}

// ---- 입구 (F2·Q5) — `TabRouter` 가 따르는 판단. 라우터는 Compose 상태를 들어 JVM 시험에서 만들 수 없으므로 판단만 뗀다. ----

/** 새 코스 입구를 누르면 무엇을 여는가. */
sealed class NewCourseEntry {
    /** 「새 여행 코스」 시트(F1) — 「+ 새 코스」·코스 0개의 「여행 코스 짜기」. */
    data object Sheet : NewCourseEntry()

    /** 시트 없이 바로 그 흐름 — 홈 카드의 도시 칩은 그 도시의 「며칠?」(Q2)부터. */
    data class Flow(
        val start: NewCourseStart,
    ) : NewCourseEntry()
}

object NewCourseEntryRules {
    /** 도시를 주면 그 도시로 바로 질문 흐름, 아니면 시트. 빈 도시 이름("", 공백)은 없는 것으로 본다. */
    fun entry(city: String?): NewCourseEntry =
        if (city.isNullOrBlank()) NewCourseEntry.Sheet else NewCourseEntry.Flow(NewCourseStart.City(city))

    /** 방금 만든 코스인가 — 표시된 id 가 있고 같을 때만. */
    fun isFresh(
        fresh: Long?,
        id: Long?,
    ): Boolean = fresh != null && id != null && fresh == id
}
