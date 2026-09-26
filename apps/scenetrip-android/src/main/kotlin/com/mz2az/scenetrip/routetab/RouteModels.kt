package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.KoreaBounds
import com.mz2az.scenetrip.data.haversineKm
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 경로여정(코스) 탭이 쓰는 값 타입. iOS `RouteTab/RouteModels.swift`를 옮긴 것이다.
 *
 * **서버 계약이 아직 없다** — 경로 관련 스키마가 계약에 없고 백엔드가 따로 만드는
 * 중이다. 그래서 여기 타입은 화면이 쓰는 로컬 모형이지 계약이 아니다.
 *
 * 장소만은 검색 탭과 같은 [PlaceSummary]를 그대로 쓴다.
 *
 * Swift `struct`(값 타입, `var` 필드로 그 자리 수정)와 달리 Kotlin에서는 불변
 * `data class` + `copy()`로 옮겼다 — Compose 상태(`mutableStateOf`)와 더 잘 맞고,
 * "그 자리 수정"이 만드는 얕은 복사 버그를 원천적으로 막는다.
 */
data class RouteCourse(
    val id: UUID = UUID.randomUUID(),
    /** 서버가 준 코스 id. 아직 저장 전이면 null이다. */
    val serverId: Long? = null,
    val title: String,
    /** 떠나는 날. 비어 있어도 된다 — 요일에 따라 문을 닫는 곳이 있어 남겨 둔다. */
    val startDate: LocalDate? = null,
    val pace: RoutePace = RoutePace.TIGHT,
    val days: List<RouteDay> = emptyList(),
    /** AI가 짠 초안인가. 편집 화면 맨 위의 파란 띠를 띄울지 정한다. */
    val madeByAI: Boolean = false,
    /** "코스 시작"을 눌러 여행 중인가. 여행 중에만 "여기서 길 찾기"가 나온다. */
    val isRunning: Boolean = false,
    /** 고른 작품에 촬영지가 없어 인기 장소로 대신 채웠나. 저장하면 사라지는 값이다. */
    val filledFromPopular: Boolean = false,
    /** 초안이 함께 준 알림 — 뺀 곳과 이유 등. 저장하면 사라지는 값이다. */
    val draftNotes: List<String> = emptyList(),
    /** 서버가 세어 준 장소 수. 목록 카드에서만 쓴다(상세를 안 받은 코스). */
    val placeCountFromServer: Int? = null,
) {
    val stops: List<RouteStop> get() = days.flatMap { it.stops }

    /** 카드에 적을 장소 수. 상세를 받았으면 실제 개수, 목록만 받았으면 서버 값이다. */
    val placeCount: Int get() = if (stops.isEmpty()) (placeCountFromServer ?: 0) else stops.size

    /** "당일치기"·"1박 2일". 몇 밤을 자는지로 말한다. */
    val spanLabel: String get() = RouteSpan.of(days.size).label

    /** 돌아오는 날 = 떠나는 날 + (일차 − 1). 사용자에게 묻지 않고 계산한다. */
    val endDate: LocalDate? get() = startDate?.plusDays((days.size - 1).toLong())

    /** "8월 22일 (금) – 8월 24일 (일)". 날짜를 안 정했으면 null이다. */
    val dateLabel: String?
        get() {
            val start = startDate ?: return null
            val end = endDate ?: return null
            val text = RouteFormat.day(start)
            return if (days.size == 1) text else "$text – ${RouteFormat.day(end)}"
        }

    fun date(ofDay: Int): LocalDate? = startDate?.plusDays(ofDay.toLong())

    companion object {
        /** 일차를 하루보다 적게, 15일보다 많게 만들지 않는다. */
        val DAY_LIMIT = 1..15
    }
}

data class RouteDay(
    val id: UUID = UUID.randomUUID(),
    val stops: List<RouteStop> = emptyList(),
)

/**
 * 코스에 담긴 장소 하나. [PlaceSummary]를 그대로 안고 체류 시간만 얹는다. 같은
 * 장소를 서로 다른 일차에 두 번 담을 수 있으므로 장소 id가 아니라 자기 id로 구분한다.
 */
data class RouteStop(
    val id: UUID = UUID.randomUUID(),
    val place: PlaceSummary,
    /** 서버가 준 아이템 id. 편집 완료에서 이 값을 그대로 돌려보내야 방문 체크가 안 날아간다. */
    val serverItemId: Long? = null,
    val stayMinutes: Int = DEFAULT_STAY_MINUTES,
    /** 지도를 눌러 직접 찍은 핀인가. */
    val isPinned: Boolean = false,
    /** 서버에 방문(`visitedAt`)이 찍혔나. */
    val visited: Boolean = false,
    /** 초안이 준 도착 시각 — 0시 기준 정수 분(540 = 09:00). */
    val arriveMinute: Int? = null,
    /** 초안에 placeId가 없던 줄. 화면에는 남되 저장에서 빠진다. */
    val placeMissing: Boolean = false,
) {
    val stayLabel: String get() = RouteFormat.minutes(stayMinutes)

    /** 서버에 placeId로 보낼 수 있는 값. 직접 찍은 핀과 id 없는 초안 줄은 없다. */
    val savablePlaceId: Long?
        get() = if (isPinned || placeMissing || place.id <= 0) null else place.id

    companion object {
        const val DEFAULT_STAY_MINUTES = 30
        val STAY_OPTIONS = listOf(15, 30, 45, 60, 90, 120, 180)
    }
}

/** 기간. 당일치기부터 5박 6일까지 여섯 개다. */
enum class RouteSpan(
    val nights: Int,
) {
    SAME_DAY(0),
    ONE_NIGHT(1),
    TWO_NIGHTS(2),
    THREE_NIGHTS(3),
    FOUR_NIGHTS(4),
    FIVE_NIGHTS(5),
    ;

    val days: Int get() = nights + 1
    val label: String get() = if (nights == 0) "당일치기" else "${nights}박 ${days}일"

    companion object {
        /** 일차 +/- 로 6일을 넘긴 코스도 있으므로 목록에 없는 값이 들어올 수 있다. */
        fun of(days: Int): RouteSpan = entries.find { it.nights == (days - 1).coerceAtLeast(0) } ?: FIVE_NIGHTS
    }
}

/**
 * "빡빡하게 / 널널하게". UI만 있고 로직은 없다 — 이 답이 일정을 어떻게 바꾸는지는
 * 아직 정하지 않았다. 값만 들고 다니고 초안 생성에는 쓰지 않는다.
 */
enum class RoutePace(
    val label: String,
    val caption: String,
) {
    TIGHT("빡빡하게", "하루를 알차게 채웁니다"),
    LOOSE("널널하게", "여유 있게 돌아봅니다"),
}

/**
 * 좌표만으로 거리를 재고 순서를 다시 잡는다. **길찾기 API를 부르지 않는다** — 여행
 * 전 계획에서는 직선거리만 쓰고, 실제 길찾기는 여행 중에만 부른다.
 */
object RouteGeometry {
    fun kilometers(
        start: PlaceSummary,
        end: PlaceSummary,
    ): Double = haversineKm(start.latitude, start.longitude, end.latitude, end.longitude)

    fun totalKilometers(stops: List<RouteStop>): Double {
        if (stops.size <= 1) return 0.0
        return stops.zipWithNext().sumOf { (a, b) -> kilometers(a.place, b.place) }
    }

    fun isInKorea(place: PlaceSummary): Boolean = KoreaBounds.contains(place.latitude, place.longitude)

    /** 동선 최적화의 기준점으로 쓸 만한가 — 한국 안이고, 가장 가까운 정지점이 [limit]km 안일 때만. */
    fun usableAnchor(
        here: PlaceSummary?,
        stops: List<RouteStop>,
        limit: Double = 100.0,
    ): PlaceSummary? {
        if (here == null || !isInKorea(here) || stops.isEmpty()) return null
        val nearest = stops.minOf { kilometers(here, it.place) }
        return if (nearest <= limit) here else null
    }

    /** 여행 중에 담는 곳이 들어갈 자리 — 바로 다음 차례. */
    fun nextSlot(
        stops: List<RouteStop>,
        target: RouteStop?,
        arrived: Boolean,
    ): Int {
        if (!arrived && target != null) {
            val index = stops.indexOfFirst { it.id == target.id }
            if (index >= 0) return index + 1
        }
        val firstUnvisited = stops.indexOfFirst { !it.visited }
        return if (firstUnvisited >= 0) firstUnvisited else stops.size
    }

    /** 지금 선 자리에서 가장 가까운 곳을 맨 앞으로. 나머지 순서는 그대로. */
    fun startingNearest(
        stops: List<RouteStop>,
        here: PlaceSummary,
    ): List<RouteStop> {
        if (stops.size <= 1) return stops
        val nearestIndex = stops.indices.minByOrNull { kilometers(here, stops[it].place) } ?: return stops
        val mutable = stops.toMutableList()
        val first = mutable.removeAt(nearestIndex)
        mutable.add(0, first)
        return mutable
    }

    /**
     * 동선 최적화 — 세 방법(최근접 이웃 / 2-opt / 완전탐색≤8개)을 다 돌려 가장 짧은
     * 것을 고른다. API를 부르지 않는다 — 위경도로 직선거리만 재는 순수 계산이다.
     *
     * `pinStart`/`pinEnd`로 양끝 고정 여부 넷을 다 표현한다: 숙소에서 나와 아무 데서나
     * 끝내기, 아무 데서나 시작해 숙소로 돌아오기, 양끝 고정, 통째로 자유.
     */
    fun optimized(
        stops: List<RouteStop>,
        pinStart: Boolean = true,
        pinEnd: Boolean = false,
    ): List<RouteStop> {
        if (stops.size <= 2) return stops

        val matrix = distanceMatrix(stops)
        val head = if (pinStart) 0 else null
        val tail = if (pinEnd) stops.size - 1 else null
        val middle = stops.indices.filter { it != head && it != tail }
        if (middle.size <= 1) return stops

        var best = orderNearest(matrix, middle, head, tail)
        var bestCost = cost(matrix, best)

        val twoOpt = orderTwoOpt(matrix, best, head, tail)
        val twoOptCost = cost(matrix, twoOpt)
        if (twoOptCost < bestCost) {
            best = twoOpt
            bestCost = twoOptCost
        }

        val exact = orderExact(matrix, middle, head, tail)
        if (exact != null && cost(matrix, exact) < bestCost) {
            best = exact
        }
        return best.map { stops[it] }
    }

    private fun distanceMatrix(stops: List<RouteStop>): Array<DoubleArray> =
        Array(stops.size) { row ->
            DoubleArray(stops.size) { column ->
                if (row == column) 0.0 else kilometers(stops[row].place, stops[column].place)
            }
        }

    private fun cost(
        matrix: Array<DoubleArray>,
        order: List<Int>,
    ): Double {
        if (order.size <= 1) return 0.0
        return order.zipWithNext().sumOf { (a, b) -> matrix[a][b] }
    }

    private fun assemble(
        middle: List<Int>,
        head: Int?,
        tail: Int?,
    ): List<Int> = listOfNotNull(head) + middle + listOfNotNull(tail)

    /** 최근접 이웃. 출발이 고정되지 않으면 가운데 각 지점을 출발으로 한 번씩 다 재 본다. */
    private fun orderNearest(
        matrix: Array<DoubleArray>,
        middle: List<Int>,
        head: Int?,
        tail: Int?,
    ): List<Int> {
        fun walk(
            first: Int,
            pool: List<Int>,
        ): List<Int> {
            var current = first
            val remaining = pool.filter { it != first }.toMutableList()
            val order = mutableListOf(first)
            while (remaining.isNotEmpty()) {
                val anchor = current
                val index = remaining.indices.minBy { matrix[anchor][remaining[it]] }
                current = remaining.removeAt(index)
                order.add(current)
            }
            return order
        }

        if (head != null) {
            var current = head!!
            val remaining = middle.toMutableList()
            val order = mutableListOf<Int>()
            while (remaining.isNotEmpty()) {
                val anchor = current
                val index = remaining.indices.minBy { matrix[anchor][remaining[it]] }
                current = remaining.removeAt(index)
                order.add(current)
            }
            return assemble(order, head, tail)
        }

        var best: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (first in middle) {
            val candidate = assemble(walk(first, middle), null, tail)
            val value = cost(matrix, candidate)
            if (value < bestCost) {
                best = candidate
                bestCost = value
            }
        }
        return best ?: assemble(middle, head, tail)
    }

    /** 2-opt. 선이 꼬인 곳을 찾아 그 구간을 뒤집는다. 나아지지 않을 때까지 돈다. */
    private fun orderTwoOpt(
        matrix: Array<DoubleArray>,
        seed: List<Int>,
        head: Int?,
        tail: Int?,
        rounds: Int = 60,
    ): List<Int> {
        var best = seed
        val lower = if (head == null) 0 else 1
        val upper = best.size - 1 - (if (tail == null) 0 else 1)
        if (upper <= lower) return best
        repeat(rounds) {
            var moved = false
            for (start in lower until upper) {
                for (end in (start + 1)..upper) {
                    val candidate = best.toMutableList()
                    val sub = candidate.subList(start, end + 1)
                    sub.reverse()
                    if (cost(matrix, candidate) < cost(matrix, best) - 1e-9) {
                        best = candidate
                        moved = true
                    }
                }
            }
            if (!moved) return best
        }
        return best
    }

    /** 모든 경우의 수. 가운데가 8개까지만 — 9개면 362,880 가지가 되어 화면이 멈춘다. */
    private fun orderExact(
        matrix: Array<DoubleArray>,
        middle: List<Int>,
        head: Int?,
        tail: Int?,
    ): List<Int>? {
        if (middle.size > 8) return null
        var best: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY
        permutations(middle) { permutation ->
            val order = assemble(permutation, head, tail)
            val value = cost(matrix, order)
            if (value < bestCost) {
                best = order
                bestCost = value
            }
        }
        return best
    }

    private fun permutations(
        items: List<Int>,
        body: (List<Int>) -> Unit,
    ) {
        val array = items.toMutableList()

        fun recurse(start: Int) {
            if (start == array.size) {
                body(array.toList())
                return
            }
            for (index in start until array.size) {
                val tmp = array[start]
                array[start] = array[index]
                array[index] = tmp
                recurse(start + 1)
                val tmp2 = array[start]
                array[start] = array[index]
                array[index] = tmp2
            }
        }
        recurse(0)
    }
}

/**
 * 같은 곳을 두 번 담지 못하게 거른다. 촬영지는 서버 id로 가르고, 직접 찍은
 * 핀·가이드가 준 곳은 id가 시각으로 매겨져 **이름 + 좌표**로도 본다.
 */
object RouteDedupe {
    /** 한 곳을 가리키는 열쇠. 좌표는 소수 다섯 자리(약 1m)까지만 본다. */
    fun key(place: PlaceSummary): String = "%s|%.5f|%.5f".format(Locale.US, place.name, place.latitude, place.longitude)

    /** 이미 담긴 것과 겹치지 않는 것만 남긴다. 이번에 담는 것끼리도 본다. */
    fun fresh(
        places: List<PlaceSummary>,
        takenIds: Set<Long>,
        takenKeys: Set<String>,
    ): List<PlaceSummary> {
        val keys = takenKeys.toMutableSet()
        val out = mutableListOf<PlaceSummary>()
        for (place in places) {
            if (place.id > 0 && takenIds.contains(place.id)) continue
            val key = key(place)
            if (keys.contains(key)) continue
            keys.add(key)
            out.add(place)
        }
        return out
    }
}

object RouteFormat {
    private val dayFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

    /** "8월 22일 (금)". 요일까지 적는 이유 — 주말에 문을 닫는 촬영지가 있다. */
    fun day(date: LocalDate): String = date.format(dayFormatter)

    /** "30분"·"1시간 30분". */
    fun minutes(total: Int): String {
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0 -> "${rest}분"
            rest == 0 -> "${hours}시간"
            else -> "${hours}시간 ${rest}분"
        }
    }

    /** "1.2 km". */
    fun kilometers(value: Double): String = "%.1f km".format(value)
}
