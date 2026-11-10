package com.mz2az.scenetrip

import com.mz2az.scenetrip.coach.CoachAction
import com.mz2az.scenetrip.coach.CoachOutcome
import com.mz2az.scenetrip.coach.CoachQueue
import com.mz2az.scenetrip.coach.CoachRect
import com.mz2az.scenetrip.coach.CoachRules
import com.mz2az.scenetrip.coach.CoachSeenStorage
import com.mz2az.scenetrip.coach.CoachSeenStore
import com.mz2az.scenetrip.coach.CoachSize
import com.mz2az.scenetrip.coach.CoachStep
import com.mz2az.scenetrip.coach.CoachTour
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 처음 보는 사람을 위한 짧은 안내(코치 마크) 규칙 (MZ2AZ-547 P6, 계획서 `docs/project/plans/ios-web-parity.md` C1–C5·M8).
 * iOS `CoachRulesTests` 를 하나씩 옮겼다. 시간은 초, 자리·크기는 Float 다.
 * 지도 검색 첫 안내 문구는 계획서 C5 가 아니라 iOS 시험(2026-11-10 고친 문구)을 따른다.
 */
class CoachRulesTests {
    // ── 도움 ──

    /** SharedPreferences 대신 쓰는 메모리 저장소. */
    private class MemoryCoachStorage : CoachSeenStorage {
        val values = mutableMapOf<String, List<String>>()

        override fun getIds(key: String): List<String>? = values[key]

        override fun setIds(
            key: String,
            ids: List<String>,
        ) {
            values[key] = ids
        }
    }

    private val iPhone = CoachSize(390f, 844f)

    private val tol = 0.001f

    private fun assertRect(
        expected: CoachRect,
        actual: CoachRect,
    ) {
        assertEquals(expected.x, actual.x, tol, "x")
        assertEquals(expected.y, actual.y, tol, "y")
        assertEquals(expected.width, actual.width, tol, "width")
        assertEquals(expected.height, actual.height, tol, "height")
    }

    private fun layout(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        container: CoachSize = iPhone,
        safeTop: Float = 47f,
        safeBottom: Float = 34f,
        bubbleHeight: Float = 150f,
    ) = CoachRules.layout(CoachRect(x, y, w, h), container, safeTop, safeBottom, bubbleHeight)

    // ── 상수 (C1) ──

    @Test
    fun `웹과 같은 상수`() {
        assertEquals(6f, CoachRules.PAD)
        assertEquals(280f, CoachRules.BUBBLE_WIDTH)
        assertEquals(12f, CoachRules.GAP)
        assertEquals(12f, CoachRules.EDGE)
        assertEquals(4.0, CoachRules.MISSING_TARGET_WAIT, 0.0001)
        assertEquals(0.4, CoachRules.RETRY_INTERVAL, 0.0001)
    }

    // ── 안내 목록과 문구 (C2–C5) ──

    @Test
    fun `홈 여행코스짜기 안내`() {
        val tour = CoachTour.homePlanTrip
        assertEquals("home-plan-trip", tour.id)
        assertEquals(0.9, tour.delaySeconds, 0.0001)
        assertEquals(
            listOf(
                CoachStep(
                    target = "plan-trip",
                    title = "첫 여행 계획을 짜 보세요",
                    text = "여기를 누르면 도시·작품·담은 곳 중 하나로 여행 코스를 만들 수 있어요.",
                ),
            ),
            tour.steps,
        )
    }

    @Test
    fun `새코스 세갈래 안내`() {
        val tour = CoachTour.forkOptions
        assertEquals("fork-options", tour.id)
        assertEquals(0.55, tour.delaySeconds, 0.0001)
        assertEquals(
            listOf(
                CoachStep(
                    target = "fork-city",
                    title = "도시로 시작",
                    text = "어디로, 며칠 가는지만 고르면 그 도시 인기 촬영지를 동선대로 이어 코스를 바로 만들어 드려요.",
                ),
                CoachStep(
                    target = "fork-shows",
                    title = "작품으로",
                    text = "좋아하는 드라마·영화를 고르면 AI 가 그 작품들의 촬영지로 코스를 짜 줘요.",
                ),
                CoachStep(
                    target = "fork-saved",
                    title = "담은 곳으로",
                    text = "보관함에 담아 둔 곳을 날마다 나눠 넣어요. 담은 곳이 없으면 빈 코스에서 하나씩 직접 넣어요.",
                ),
            ),
            tour.steps,
        )
    }

    @Test
    fun `처음만든 계획화면 안내`() {
        val tour = CoachTour.editorFirst
        assertEquals("editor-first", tour.id)
        assertEquals(1.2, tour.delaySeconds, 0.0001)
        assertEquals(
            listOf(
                CoachStep(
                    target = "editor-map",
                    title = "지도",
                    text = "눌러서 코스를 지도로 펼쳐 동선을 확인해요. 다시 누르면 목록만 봐요.",
                ),
                CoachStep(
                    target = "editor-guide",
                    title = "해태 AI",
                    text = "「하루 줄여 줘」 「카페 하나 넣어 줘」처럼 말로 부탁하면 코스를 고쳐 줘요.",
                ),
                CoachStep(
                    target = "editor-add",
                    title = "장소 검색해서 담기",
                    text = "지금은 편집 중이라 장소를 검색해서 더 넣을 수 있어요. 보관함에 담아 둔 곳도 여기서 넣어요.",
                ),
                CoachStep(
                    target = "editor-save",
                    title = "다 고쳤으면 저장",
                    text = "저장해야 코스가 남아요. 저장하지 않고 나가면 한 번 더 물어봐요.",
                ),
            ),
            tour.steps,
        )
    }

    @Test
    fun `지도검색 안내`() {
        val tour = CoachTour.mapSearch
        assertEquals("map-search", tour.id)
        assertEquals(0.7, tour.delaySeconds, 0.0001)
        assertEquals(
            listOf(
                CoachStep(
                    target = "map-search",
                    title = "지도에서 검색",
                    text = "촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요.",
                ),
                CoachStep(
                    target = "map-chips",
                    title = "주변 둘러보기",
                    text = "전체·음식·관광지·숙소 칩을 누르면 지금 보이는 지도 안의 장소가 핀으로 떠요.",
                ),
            ),
            tour.steps,
        )
    }

    /** 영어 화면에 한국어 안내가 섞이지 않게 — 지도 검색 첫 안내의 영어 문구. */
    @Test
    fun `지도검색 안내 영어`() {
        val korean = "촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요."
        val english =
            "Find places beyond filming spots — search restaurants, cafés, stays and sights by name, then add them to your course."
        assertEquals(english, englishOf(korean))
        assertEquals(english, tr(korean, Lang.en))
        // 안내 목록에 실린 문구가 바로 그 한국어여야 영어로 바뀐다.
        assertEquals(
            english,
            tr(
                CoachTour.mapSearch.steps
                    .firstOrNull()
                    ?.text ?: "",
                Lang.en,
            ),
        )
    }

    /** 모든 안내의 제목·본문과 단추 글자에 영어가 있다 — 번역이 빠지면 영어 화면에 한국어가 남는다. */
    @Test
    fun `모든 안내 문구와 단추에 영어가 있다`() {
        val steps = CoachTour.all.flatMap { it.steps }
        // 네 안내 1 + 3 + 4 + 2 = 10 단계 — 빈 목록이 그냥 통과하지 않게.
        assertEquals(10, steps.size)
        val korean = steps.flatMap { listOf(it.title, it.text) } + listOf("건너뛰기", "다음", "알겠어요")
        val missing = korean.filter { englishOf(it) == null }
        assertTrue(missing.isEmpty(), "영어 없음: $missing")
    }

    @Test
    fun `전체목록은 네개`() {
        assertEquals(listOf("home-plan-trip", "fork-options", "editor-first", "map-search"), CoachTour.all.map { it.id })
        assertEquals(
            listOf(CoachTour.homePlanTrip, CoachTour.forkOptions, CoachTour.editorFirst, CoachTour.mapSearch),
            CoachTour.all,
        )
    }

    // ── 본 것 기억 (C1) ──

    @Test
    fun `처음엔 아무것도 안봤다`() {
        val store = CoachSeenStore(MemoryCoachStorage())
        assertFalse(store.isSeen("home-plan-trip"))
    }

    @Test
    fun `본것으로 표시하면 기억하고 저장소에 문자열목록으로 남는다`() {
        val storage = MemoryCoachStorage()
        val store = CoachSeenStore(storage)
        store.markSeen("fork-options")
        assertTrue(store.isSeen("fork-options"))
        assertEquals("scenetrip.coach.seen", CoachSeenStore.KEY)
        assertEquals(listOf("fork-options"), storage.getIds("scenetrip.coach.seen"))
    }

    @Test
    fun `다른 안내는 영향없다`() {
        val store = CoachSeenStore(MemoryCoachStorage())
        store.markSeen("fork-options")
        assertFalse(store.isSeen("map-search"))
    }

    @Test
    fun `같은 안내를 두번 표시해도 한번만 남는다`() {
        val storage = MemoryCoachStorage()
        val store = CoachSeenStore(storage)
        store.markSeen("map-search")
        store.markSeen("map-search")
        assertEquals(listOf("map-search"), storage.getIds(CoachSeenStore.KEY))
    }

    @Test
    fun `여러개를 순서대로 기억한다`() {
        val storage = MemoryCoachStorage()
        val store = CoachSeenStore(storage)
        store.markSeen("home-plan-trip")
        store.markSeen("editor-first")
        assertEquals(listOf("home-plan-trip", "editor-first"), storage.getIds(CoachSeenStore.KEY))
    }

    @Test
    fun `저장소에 이미 있던 기록을 읽는다`() {
        val storage = MemoryCoachStorage()
        storage.setIds("scenetrip.coach.seen", listOf("editor-first"))
        val store = CoachSeenStore(storage)
        assertTrue(store.isSeen("editor-first"))
        assertFalse(store.isSeen("home-plan-trip"))
    }

    @Test
    fun `새 저장소 객체도 같은 기록을 본다`() {
        val storage = MemoryCoachStorage()
        CoachSeenStore(storage).markSeen("home-plan-trip")
        val again = CoachSeenStore(storage)
        assertTrue(again.isSeen("home-plan-trip"))
        // 표시하지 않은 안내는 여전히 안 본 것이다.
        assertFalse(again.isSeen("fork-options"))
    }

    @Test
    fun `reset 은 모두 지운다`() {
        val storage = MemoryCoachStorage()
        val store = CoachSeenStore(storage)
        store.markSeen("home-plan-trip")
        store.markSeen("map-search")
        store.reset()
        assertFalse(store.isSeen("home-plan-trip"))
        assertFalse(store.isSeen("map-search"))
        assertEquals(emptyList<String>(), storage.getIds(CoachSeenStore.KEY) ?: emptyList<String>())
    }

    // ── 한 번에 하나 (C1) ──

    @Test
    fun `아무것도 없으면 띄운다`() {
        val queue = CoachQueue()
        assertTrue(queue.start("home-plan-trip", seen = false))
        assertEquals("home-plan-trip", queue.active)
    }

    @Test
    fun `본 안내는 띄우지 않는다`() {
        val queue = CoachQueue()
        assertFalse(queue.start("home-plan-trip", seen = true))
        assertNull(queue.active)
        // 본 안내가 자리를 차지하지 않으니 다른 안내는 바로 뜬다.
        assertTrue(queue.start("fork-options", seen = false))
        assertEquals("fork-options", queue.active)
    }

    @Test
    fun `다른 안내가 떠있으면 기다린다`() {
        val queue = CoachQueue()
        assertTrue(queue.start("editor-first", seen = false))
        assertFalse(queue.start("map-search", seen = false))
        assertEquals("editor-first", queue.active)
    }

    @Test
    fun `같은 안내가 이미 떠있어도 false`() {
        val queue = CoachQueue()
        assertTrue(queue.start("editor-first", seen = false))
        assertFalse(queue.start("editor-first", seen = false))
        assertEquals("editor-first", queue.active)
    }

    @Test
    fun `끝나면 다음 안내를 띄울 수 있다`() {
        val queue = CoachQueue()
        assertTrue(queue.start("editor-first", seen = false))
        queue.end("editor-first")
        assertNull(queue.active)
        assertTrue(queue.start("map-search", seen = false))
        assertEquals("map-search", queue.active)
    }

    @Test
    fun `다른 안내의 끝내기는 지금 안내를 지우지 않는다`() {
        val queue = CoachQueue()
        assertTrue(queue.start("editor-first", seen = false))
        queue.end("map-search")
        assertEquals("editor-first", queue.active)
    }

    // ── 단계 넘기기 (C1) ──

    @Test
    fun `다음은 한칸 넘긴다`() {
        assertEquals(CoachOutcome.Step(1), CoachRules.advance(0, 3, CoachAction.NEXT))
        assertEquals(CoachOutcome.Step(2), CoachRules.advance(1, 3, CoachAction.NEXT))
    }

    @Test
    fun `마지막에서 다음은 끝나고 본것으로`() {
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(2, 3, CoachAction.NEXT))
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(0, 1, CoachAction.NEXT))
    }

    @Test
    fun `덮개를 누르면 다음과 같다`() {
        assertEquals(CoachOutcome.Step(1), CoachRules.advance(0, 2, CoachAction.TAP_SCRIM))
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(1, 2, CoachAction.TAP_SCRIM))
    }

    @Test
    fun `단추가 없으면 그 단계를 건너뛴다`() {
        assertEquals(CoachOutcome.Step(2), CoachRules.advance(1, 4, CoachAction.TARGET_MISSING))
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(3, 4, CoachAction.TARGET_MISSING))
    }

    @Test
    fun `건너뛰기는 어디서든 끝나고 본것으로`() {
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(0, 4, CoachAction.SKIP))
        assertEquals(CoachOutcome.Finished(markSeen = true), CoachRules.advance(2, 4, CoachAction.SKIP))
    }

    @Test
    fun `화면을 떠나면 접되 본것으로 치지 않는다`() {
        assertEquals(CoachOutcome.Finished(markSeen = false), CoachRules.advance(0, 3, CoachAction.LEAVE))
        assertEquals(CoachOutcome.Finished(markSeen = false), CoachRules.advance(2, 3, CoachAction.LEAVE))
    }

    @Test
    fun `4초를 넘어야 건너뛴다`() {
        assertFalse(CoachRules.shouldSkipMissing(0.0))
        assertFalse(CoachRules.shouldSkipMissing(3.9))
        assertFalse(CoachRules.shouldSkipMissing(4.0))
        assertTrue(CoachRules.shouldSkipMissing(4.01))
        assertTrue(CoachRules.shouldSkipMissing(10.0))
    }

    // ── 말풍선 글자·단추 (C1) ──

    @Test
    fun `마지막 단계인가`() {
        assertFalse(CoachRules.isLast(0, 3))
        assertFalse(CoachRules.isLast(1, 3))
        assertTrue(CoachRules.isLast(2, 3))
        assertTrue(CoachRules.isLast(0, 1))
    }

    @Test
    fun `몇번째인지 1부터 센다`() {
        assertEquals("1 / 3", CoachRules.counter(0, 3))
        assertEquals("4 / 4", CoachRules.counter(3, 4))
        assertEquals("1 / 1", CoachRules.counter(0, 1))
    }

    @Test
    fun `건너뛰기는 마지막에만 없다`() {
        assertTrue(CoachRules.showsSkip(0, 3))
        assertTrue(CoachRules.showsSkip(1, 3))
        assertFalse(CoachRules.showsSkip(2, 3))
        assertFalse(CoachRules.showsSkip(0, 1))
    }

    @Test
    fun `오른쪽 단추는 마지막에 알겠어요`() {
        assertEquals("다음", CoachRules.primaryTitle(0, 2))
        assertEquals("알겠어요", CoachRules.primaryTitle(1, 2))
        assertEquals("알겠어요", CoachRules.primaryTitle(0, 1))
    }

    // ── 구멍·말풍선 자리 (C1) ──
    // 기대값은 웹 `CoachTour.tsx` 계산을 그대로 따라 구한 iOS 시험 값이다.
    // 390×844, 위 안전영역 47·아래 34 이면 안쪽 = 위 59 · 왼 12 · 오른 378 · 아래 798.

    @Test
    fun `아래쪽 단추면 말풍선은 위`() {
        val l = layout(150f, 740f, 90f, 50f)
        assertRect(CoachRect(144f, 734f, 102f, 62f), l.hole)
        assertEquals(22f, l.holeRadius, tol)
        assertFalse(l.below)
        // 구멍 위 734 - 12 - 150 = 572, 가운데 맞춤 195 - 140 = 55.
        assertRect(CoachRect(55f, 572f, 280f, 150f), l.bubble)
        assertEquals(132f, l.arrowX, tol)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `위쪽 단추면 말풍선은 아래`() {
        val l = layout(20f, 70f, 44f, 44f)
        assertRect(CoachRect(14f, 64f, 56f, 56f), l.hole)
        assertTrue(l.below)
        // 구멍 아래 120 + 12 = 132, 왼쪽 가장자리 12 로 밀려남.
        assertRect(CoachRect(12f, 132f, 280f, 150f), l.bubble)
        assertEquals(22f, l.arrowX, tol)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `오른쪽 끝 단추면 말풍선이 안쪽으로 밀린다`() {
        val l = layout(340f, 300f, 44f, 44f)
        // 구멍 오른쪽은 화면 폭 - 4 = 386 에서 멈춘다.
        assertRect(CoachRect(334f, 294f, 52f, 56f), l.hole)
        assertTrue(l.below)
        // 말풍선 오른쪽 끝이 378(= 390 - 12) 에 맞는다.
        assertRect(CoachRect(98f, 362f, 280f, 150f), l.bubble)
        assertEquals(378f, l.bubble.maxX, tol)
        // 화살표는 폭 - 34 = 246 에서 멈춘다.
        assertEquals(246f, l.arrowX, tol)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `왼쪽 끝 단추면 구멍은 4 말풍선은 12 화살표는 18`() {
        val l = layout(0f, 300f, 40f, 40f)
        assertRect(CoachRect(4f, 294f, 42f, 52f), l.hole)
        assertRect(CoachRect(12f, 358f, 280f, 150f), l.bubble)
        assertEquals(18f, l.arrowX, tol)
        assertTrue(l.below)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `폭 320 화면에서도 말풍선은 280`() {
        val l = layout(140f, 400f, 40f, 40f, container = CoachSize(320f, 568f), safeTop = 20f, safeBottom = 0f, bubbleHeight = 120f)
        assertRect(CoachRect(134f, 394f, 52f, 52f), l.hole)
        // 아래 446 + 12 + 120 = 578 > 556 이라 위로: 394 - 12 - 120 = 262.
        assertFalse(l.below)
        assertRect(CoachRect(20f, 262f, 280f, 120f), l.bubble)
        assertEquals(132f, l.arrowX, tol)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `폭 290 화면이면 말풍선이 안쪽 폭으로 줄어든다`() {
        val l = layout(125f, 400f, 40f, 40f, container = CoachSize(290f, 568f), safeTop = 20f, safeBottom = 0f, bubbleHeight = 120f)
        // 안쪽 폭 = 290 - 24 = 266.
        assertRect(CoachRect(12f, 262f, 266f, 120f), l.bubble)
        assertFalse(l.below)
        assertEquals(125f, l.arrowX, tol)
    }

    @Test
    fun `어느쪽에도 안맞는 큰 말풍선은 위로 가두고 화살표를 숨긴다`() {
        val l = layout(150f, 400f, 90f, 40f, bubbleHeight = 800f)
        assertRect(CoachRect(144f, 394f, 102f, 52f), l.hole)
        // 위로도 자리가 없으니 아래를 고르고, 안쪽 위(59)로 밀어 넣는다 — 구멍과 겹친다.
        assertTrue(l.below)
        assertRect(CoachRect(55f, 59f, 280f, 800f), l.bubble)
        assertFalse(l.showsArrow)
    }

    @Test
    fun `상태줄에 걸친 단추는 구멍이 안전영역 아래로 가둬진다`() {
        val l = layout(100f, 40f, 60f, 40f)
        // 구멍 위 = max(40 - 6, 59 - 6) = 53.
        assertRect(CoachRect(94f, 53f, 72f, 33f), l.hole)
        assertEquals(16.5f, l.holeRadius, tol)
        assertTrue(l.below)
        assertRect(CoachRect(12f, 98f, 280f, 150f), l.bubble)
        assertEquals(110f, l.arrowX, tol)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `홈막대에 걸친 단추는 구멍이 아래 안전영역에서 멈춘다`() {
        val l = layout(100f, 780f, 60f, 40f)
        // 구멍 아래 = min(820 + 6, 798 + 6) = 804.
        assertRect(CoachRect(94f, 774f, 72f, 30f), l.hole)
        assertEquals(15f, l.holeRadius, tol)
        assertFalse(l.below)
        assertRect(CoachRect(12f, 612f, 280f, 150f), l.bubble)
        assertTrue(l.showsArrow)
    }

    @Test
    fun `구멍 모서리는 높이의 반 최대 22`() {
        val small = layout(100f, 300f, 60f, 20f)
        assertEquals(32f, small.hole.height, tol)
        assertEquals(16f, small.holeRadius, tol)

        val big = layout(100f, 300f, 60f, 100f)
        assertEquals(22f, big.holeRadius, tol)
    }

    // ── 언제 띄우나 (C2·C4·C5) ──

    @Test
    fun `홈은 여행중 아니고 줄접힘 가려지지 않았을때만`() {
        assertTrue(CoachRules.homeActive(running = false, floatOpen = false, covered = false))
        assertFalse(CoachRules.homeActive(running = true, floatOpen = false, covered = false))
        assertFalse(CoachRules.homeActive(running = false, floatOpen = true, covered = false))
        assertFalse(CoachRules.homeActive(running = false, floatOpen = false, covered = true))
    }

    @Test
    fun `계획화면은 편집중이고 새코스나 방금만든코스이며 묻는창이 없을때`() {
        assertTrue(CoachRules.editorFirstActive(editing = true, isNew = true, fresh = false, confirming = false))
        assertTrue(CoachRules.editorFirstActive(editing = true, isNew = false, fresh = true, confirming = false))
        assertTrue(CoachRules.editorFirstActive(editing = true, isNew = true, fresh = true, confirming = false))
        assertFalse(CoachRules.editorFirstActive(editing = true, isNew = false, fresh = false, confirming = false))
        assertFalse(CoachRules.editorFirstActive(editing = false, isNew = true, fresh = true, confirming = false))
        assertFalse(CoachRules.editorFirstActive(editing = true, isNew = true, fresh = true, confirming = true))
    }

    @Test
    fun `지도검색은 칩줄이 보이고 검색칸과 결과가 닫혀있을때`() {
        assertTrue(CoachRules.mapSearchActive(barShown = true, searching = false, resultsOpen = false))
        assertFalse(CoachRules.mapSearchActive(barShown = false, searching = false, resultsOpen = false))
        assertFalse(CoachRules.mapSearchActive(barShown = true, searching = true, resultsOpen = false))
        assertFalse(CoachRules.mapSearchActive(barShown = true, searching = false, resultsOpen = true))
    }
}
