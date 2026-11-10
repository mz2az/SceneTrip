package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.CourseListPicking
import com.mz2az.scenetrip.routetab.CourseListRules
import com.mz2az.scenetrip.routetab.CourseListText
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.OffsetDateTime

/**
 * 코스 목록(「내 여행」)의 규칙 — 계획 `docs/project/plans/ios-web-parity.md` L1–L5 (MZ2AZ-547 P7).
 * iOS `CourseListRulesTests` 를 하나씩 옮겼다. Kotlin 쪽은 값을 바꾸지 않고 새 값을 돌려준다.
 * 시험은 한국어로 돈다(`tr` 이 한국어 원문을 그대로 돌려준다).
 */
class CourseListRulesTests {
    // ── 만들기 도우미 ──

    private fun place(
        id: Long,
        image: String? = null,
    ) = PlaceSummary(id = id, name = "곳$id", latitude = 37.5, longitude = 127.0, imageUrl = image?.let { URI(it) })

    private fun stop(
        id: Long,
        image: String? = null,
    ) = RouteStop(place = place(id, image))

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-10T09:00:00+09:00")

    private fun course(
        id: Long,
        title: String = "코스",
        running: Boolean = false,
    ) = CourseSummary(
        id = id,
        title = title,
        dayCount = 1,
        status = if (running) CourseStatus.active else CourseStatus.upcoming,
        origin = CourseOrigin.self,
        placeCount = 0,
        createdAt = now,
        updatedAt = now,
    )

    // ── 여러 개 고르기 — 상태 ──

    /** 처음에는 꺼져 있다 — 고른 것이 「빈 집합」이 아니라 null 이다. */
    @Test
    fun `처음에는 꺼져 있다`() {
        val picking = CourseListPicking()
        assertFalse(picking.isOn)
        assertNull(picking.picked)
        assertEquals(0, picking.count)
        assertFalse(picking.isPicked("s1"))
    }

    @Test
    fun `꾹 누르면 켜지고 그 줄이 골라진다`() {
        val picking = CourseListPicking().hold("s1")
        assertTrue(picking.isOn)
        assertEquals(setOf("s1"), picking.picked)
        assertEquals(1, picking.count)
        assertTrue(picking.isPicked("s1"))
        assertFalse(picking.isPicked("s2"))
    }

    @Test
    fun `켜진 동안 다른 줄을 꾹 눌러도 그대로다`() {
        val picking = CourseListPicking().hold("s1").hold("s2")
        assertEquals(setOf("s1"), picking.picked)
        assertEquals(1, picking.count)
    }

    /** 꺼져 있을 때 누르기는 아무 일도 없다(그때는 코스를 연다). */
    @Test
    fun `꺼진 채 누르기는 아무 일도 없다`() {
        val picking = CourseListPicking().toggle("s1")
        assertFalse(picking.isOn)
        assertNull(picking.picked)
    }

    @Test
    fun `켜진 동안 누르면 체크가 뒤집힌다`() {
        var picking = CourseListPicking().hold("s1").toggle("s2")
        assertEquals(setOf("s1", "s2"), picking.picked)
        picking = picking.toggle("s1")
        assertEquals(setOf("s2"), picking.picked)
        assertFalse(picking.isPicked("s1"))
        assertTrue(picking.isPicked("s2"))
    }

    @Test
    fun `마지막 하나를 풀어도 꺼지지 않는다 — 켜진 채 0개`() {
        val picking = CourseListPicking().hold("s1").toggle("s1")
        assertTrue(picking.isOn)
        assertEquals(emptySet<String>(), picking.picked)
        assertEquals(0, picking.count)
    }

    @Test
    fun `꺼져 있거나 목록이 비면 다 골랐다가 아니다`() {
        val off = CourseListPicking()
        assertFalse(off.allPicked(listOf("s1")), "꺼져 있는데 다 골랐다고 했다")
        assertFalse(off.allPicked(emptyList()), "꺼진 채 빈 목록")

        val on = off.hold("s1")
        assertFalse(on.allPicked(emptyList()), "목록이 비었는데 다 골랐다고 했다")
    }

    @Test
    fun `다 골랐나는 목록을 따른다`() {
        var picking = CourseListPicking().hold("s1")
        assertTrue(picking.allPicked(listOf("s1")))
        assertFalse(picking.allPicked(listOf("s1", "s2")))
        picking = picking.toggle("s2")
        assertTrue(picking.allPicked(listOf("s1", "s2")))
    }

    /** [전체 선택] 은 목록 전부를, 다 고른 뒤 [선택 해제] 는 모두 풀되 켜진 채로 둔다. */
    @Test
    fun `전체 선택 뒤 선택 해제는 켜진 채 0개`() {
        val keys = listOf("s1", "s2", "s3")
        var picking = CourseListPicking().hold("s2")

        picking = picking.toggleAll(keys)
        assertEquals(keys.toSet(), picking.picked)
        assertTrue(picking.allPicked(keys))

        picking = picking.toggleAll(keys)
        assertTrue(picking.isOn, "선택 해제가 고르기를 껐다")
        assertEquals(emptySet<String>(), picking.picked)
        assertEquals(0, picking.count)
    }

    @Test
    fun `취소는 끈다`() {
        val picking = CourseListPicking().hold("s1").toggle("s2").cancel()
        assertFalse(picking.isOn)
        assertNull(picking.picked)
        assertEquals(0, picking.count)
    }

    @Test
    fun `목록이 바뀌면 없어진 줄의 열쇠를 뺀다`() {
        val picking =
            CourseListPicking()
                .hold("s1")
                .toggle("s2")
                .toggle("s3")
                .keepOnly(listOf("s2", "s9"))
        assertTrue(picking.isOn)
        assertEquals(setOf("s2"), picking.picked)
    }

    /** 꺼져 있으면 목록이 바뀌어도 꺼진 채다(빈 집합으로 켜지면 안 된다). */
    @Test
    fun `꺼진 채 목록이 바뀌어도 꺼진 채다`() {
        val picking = CourseListPicking().keepOnly(listOf("s1", "s2"))
        assertFalse(picking.isOn)
        assertNull(picking.picked)
    }

    // ── 글자 ──

    @Test
    fun `머리 제목`() {
        var picking = CourseListPicking()
        assertEquals("내 여행", CourseListText.headerTitle(picking))

        picking = picking.hold("s1").toggle("s2").toggle("s3")
        assertEquals("3개 선택", CourseListText.headerTitle(picking))

        picking = picking.toggleAll(listOf("s1", "s2", "s3"))
        assertEquals("코스 선택", CourseListText.headerTitle(picking), "켜진 채 0개")
    }

    @Test
    fun `전체 선택 단추`() {
        assertEquals("전체 선택", CourseListText.allButton(false))
        assertEquals("선택 해제", CourseListText.allButton(true))
    }

    @Test
    fun `삭제 단추`() {
        assertEquals("2개 삭제", CourseListText.deleteButton(2, removing = false))
        assertEquals("지우는 중…", CourseListText.deleteButton(2, removing = true))
    }

    @Test
    fun `여러 개 지우기 확인 제목`() {
        assertEquals("코스 2개를 지울까요?", CourseListText.bulkConfirmTitle(2))
    }

    @Test
    fun `여행 중이면 일차가 앞에 붙는다`() {
        assertEquals(
            "2일차 · 1박 2일 · 5곳",
            CourseListText.subtitle(isRunning = true, currentDayNo = 2, dayCount = 2, placeCount = 5),
        )
    }

    @Test
    fun `여행 중인데 일차를 모르면 1일차`() {
        assertEquals(
            "1일차 · 2박 3일 · 4곳",
            CourseListText.subtitle(isRunning = true, currentDayNo = null, dayCount = 3, placeCount = 4),
        )
    }

    @Test
    fun `다가오는 여행에는 일차가 없다 — 하루면 당일치기`() {
        assertEquals(
            "당일치기 · 3곳",
            CourseListText.subtitle(isRunning = false, currentDayNo = null, dayCount = 1, placeCount = 3),
        )
        assertEquals(
            "2박 3일 · 7곳",
            CourseListText.subtitle(isRunning = false, currentDayNo = 2, dayCount = 3, placeCount = 7),
            "여행 중이 아닌데 일차가 붙었다",
        )
    }

    // ── 상수·꾹 누르기 ──

    @Test
    fun `상수`() {
        assertEquals(200L, CourseListRules.HOLD_MILLIS)
        assertEquals(8f, CourseListRules.HOLD_SLOP_DP)
        assertEquals(2, CourseListRules.WORK_LIMIT)
        assertEquals(3, CourseListRules.WORK_STOPS)
    }

    @Test
    fun `0점2초 이상 눌렀고 8dp 안에서만 움직였으면 꾹 누르기다`() {
        assertTrue(CourseListRules.isHold(200L, 0f))
        assertTrue(CourseListRules.isHold(500L, 8f))
        assertFalse(CourseListRules.isHold(199L, 0f), "너무 짧게 눌렀다")
        assertFalse(CourseListRules.isHold(300L, 8.5f), "움직였으면 스크롤이다")
    }

    // ── 열쇠 ──

    @Test
    fun `열쇠는 서버 id 로 만든다`() {
        assertEquals("s123", CourseListRules.key(course(123)))
    }

    /** 서버 id 가 같으면 목록을 다시 받아 다른 값이 와도 열쇠가 같다. */
    @Test
    fun `다시 받아도 같은 코스는 같은 열쇠`() {
        assertEquals(CourseListRules.key(course(7, "앞")), CourseListRules.key(course(7, "뒤")))
    }

    // ── 묶음 ──

    @Test
    fun `여행 중과 다가오는 여행으로 나누고 순서를 지킨다`() {
        val list =
            listOf(
                course(1, "다가옴1"),
                course(2, "여행중1", running = true),
                course(3, "다가옴2"),
                course(4, "여행중2", running = true),
            )
        val (running, upcoming) = CourseListRules.sections(list)
        assertEquals(listOf("여행중1", "여행중2"), running.map { it.title })
        assertEquals(listOf("다가옴1", "다가옴2"), upcoming.map { it.title })
    }

    // ── 지울 코스 ──

    /** 고른 순서가 아니라 목록 순서대로, 고른 것만. */
    @Test
    fun `지울 코스는 고른 것만 목록 순서대로`() {
        val list = listOf(course(1, "가"), course(2, "나"), course(3, "다"), course(4, "라"))
        val picking = CourseListPicking().hold("s4").toggle("s1").toggle("s3")
        assertEquals(listOf("가", "다", "라"), CourseListRules.doomed(list, picking).map { it.title })
    }

    @Test
    fun `꺼져 있으면 지울 코스가 없다`() {
        val list = listOf(course(1), course(2))
        assertTrue(CourseListRules.doomed(list, CourseListPicking()).isEmpty())
    }

    // ── 작품 줄 ──

    @Test
    fun `작품은 장소마다 첫 작품을 겹치지 않게 최대 두 개`() {
        val stops = listOf(stop(1), stop(2), stop(3), stop(4))
        val titles = mapOf(1L to "도깨비", 2L to "도깨비", 3L to "눈물의 여왕", 4L to "더 글로리")
        assertEquals(listOf("도깨비", "눈물의 여왕"), CourseListRules.works(stops) { titles[it.place.id] })
    }

    @Test
    fun `작품을 모르거나 빈 글인 곳은 건너뛴다`() {
        val stops = listOf(stop(1), stop(2), stop(3))
        val titles = mapOf(2L to "", 3L to "도깨비")
        assertEquals(listOf("도깨비"), CourseListRules.works(stops) { titles[it.place.id] })
    }

    @Test
    fun `아는 작품이 없으면 빈 목록`() {
        assertEquals(emptyList<String>(), CourseListRules.works(listOf(stop(1), stop(2))) { null })
    }

    // ── 줄 사진 ──

    @Test
    fun `대표 사진이 먼저다`() {
        val stops = listOf(stop(1, image = "https://img.example/stop.jpg"))
        assertEquals("https://img.example/thumb.jpg", CourseListRules.photo("https://img.example/thumb.jpg", stops))
    }

    @Test
    fun `대표 사진이 없으면 첫 사진 있는 장소`() {
        val stops =
            listOf(stop(1), stop(2, image = "https://img.example/b.jpg"), stop(3, image = "https://img.example/c.jpg"))
        assertEquals("https://img.example/b.jpg", CourseListRules.photo(null, stops))
    }

    @Test
    fun `빈 대표 사진과 빈 장소 사진은 없는 것으로 본다`() {
        val stops = listOf(stop(1, image = ""), stop(2, image = "https://img.example/b.jpg"))
        assertEquals("https://img.example/b.jpg", CourseListRules.photo("", stops))
    }

    @Test
    fun `사진이 하나도 없으면 null`() {
        assertNull(CourseListRules.photo(null, listOf(stop(1), stop(2))))
        assertNull(CourseListRules.photo(null, emptyList()))
    }

    // ── 하나씩 지우기 ──

    /** 앞의 지우기가 끝나야 다음을 부른다 — 동시에 둘이 돌면 안 된다. */
    @Test
    fun `하나씩 차례로 지운다`() =
        runBlocking {
            val events = mutableListOf<String>()
            var inFlight = 0
            var maxInFlight = 0
            val list = listOf(course(1, "가"), course(2, "나"), course(3, "다"))

            CourseListRules.removeOneByOne(list) { doomed ->
                events += "시작 ${doomed.title}"
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
                yield()
                delay(10)
                yield()
                events += "끝 ${doomed.title}"
                inFlight -= 1
            }

            assertEquals(listOf("시작 가", "끝 가", "시작 나", "끝 나", "시작 다", "끝 다"), events)
            assertEquals(1, maxInFlight, "지우기가 겹쳐 돌았다")
        }

    @Test
    fun `빈 목록이면 아무것도 부르지 않는다`() =
        runBlocking {
            val events = mutableListOf<String>()
            CourseListRules.removeOneByOne(emptyList<CourseSummary>()) { events += "시작 ${it.title}" }
            assertEquals(emptyList<String>(), events)
        }
}
