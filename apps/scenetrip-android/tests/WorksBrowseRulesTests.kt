package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.WorkPickRules
import com.mz2az.scenetrip.newcourse.WorksBrowseRules
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 작품 고르기 단계의 쪽 넘김·검색·고른 것 유지 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §1).
 * 서버 작품 전부를 30개씩 받고, 검색칸으로 찾고, 고른 작품은 검색·쪽 넘김과 상관없이 남는다.
 */
class WorksBrowseRulesTests {
    private fun work(
        id: Long,
        title: String = "작품$id",
    ) = ContentSummary(id = id, category = ContentCategory.drama, title = title, placeCount = 3)

    // ---- 쪽 넘김 ----

    @Test
    fun pageSizeIsThirty() {
        assertEquals(30, WorksBrowseRules.PAGE_SIZE)
    }

    @Test
    fun nextOffsetIsLoadedCountUntilTotal() {
        assertEquals(30, WorksBrowseRules.nextOffset(loaded = 30, total = 128))
        assertEquals(60, WorksBrowseRules.nextOffset(loaded = 60, total = 128))
        assertEquals(120, WorksBrowseRules.nextOffset(loaded = 120, total = 128))
    }

    @Test
    fun nextOffsetStopsAtTotal() {
        assertNull(WorksBrowseRules.nextOffset(loaded = 128, total = 128))
        // 서버가 total 보다 많이 줘도 더 받지 않는다.
        assertNull(WorksBrowseRules.nextOffset(loaded = 130, total = 128))
        // 결과가 0 개인 검색 — 받을 것이 없다.
        assertNull(WorksBrowseRules.nextOffset(loaded = 0, total = 0))
    }

    @Test
    fun nextOffsetWithoutTotalKeepsGoing() {
        assertEquals(30, WorksBrowseRules.nextOffset(loaded = 30, total = null))
    }

    @Test
    fun nearEndWhenLastRowIsVisible() {
        // 30개, 3열 — 마지막 줄은 27·28·29.
        assertTrue(WorksBrowseRules.nearEnd(lastVisibleIndex = 29, loaded = 30, columns = 3))
        assertTrue(WorksBrowseRules.nearEnd(lastVisibleIndex = 27, loaded = 30, columns = 3))
        assertFalse(WorksBrowseRules.nearEnd(lastVisibleIndex = 26, loaded = 30, columns = 3))
        assertFalse(WorksBrowseRules.nearEnd(lastVisibleIndex = 5, loaded = 30, columns = 3))
    }

    @Test
    fun nearEndIsFalseWhenNothingLoaded() {
        assertFalse(WorksBrowseRules.nearEnd(lastVisibleIndex = -1, loaded = 0, columns = 3))
    }

    @Test
    fun appendedAddsPageAndSkipsDuplicates() {
        val first = listOf(work(1), work(2), work(3))
        val page = listOf(work(3, "바뀐 제목"), work(4), work(5))
        val out = WorksBrowseRules.appended(first, page)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), out.map { it.id })
        // 이미 있던 것은 앞의 것을 둔다.
        assertEquals("작품3", out[2].title)
    }

    @Test
    fun appendedToEmptyIsPage() {
        assertEquals(listOf(7L, 8L), WorksBrowseRules.appended(emptyList(), listOf(work(7), work(8))).map { it.id })
    }

    // ---- 검색 ----

    @Test
    fun searchDebounceIsThreeTenths() {
        assertEquals(300L, WorksBrowseRules.SEARCH_DEBOUNCE_MILLIS)
    }

    @Test
    fun blankSearchMeansNoQuery() {
        assertNull(WorksBrowseRules.searchQuery(""))
        assertNull(WorksBrowseRules.searchQuery("   "))
        assertNull(WorksBrowseRules.searchQuery("\t\n "))
    }

    @Test
    fun searchQueryIsTrimmed() {
        assertEquals("도깨비", WorksBrowseRules.searchQuery("  도깨비 "))
        // 한 글자도 찾는다(지도 검색의 2글자 규칙과 다르다).
        assertEquals("도", WorksBrowseRules.searchQuery("도"))
    }

    @Test
    fun searchQueryIsCutAtContractMax() {
        val long = "a".repeat(150)
        assertEquals("a".repeat(100), WorksBrowseRules.searchQuery(long))
    }

    @Test
    fun noMatchMessageQuotesQuery() {
        assertEquals("「도깨비」 에 맞는 작품이 없어요", WorksBrowseRules.noMatch("도깨비"))
    }

    // ---- 찜 먼저 ----

    @Test
    fun likedWorksComeFirstInServerOrder() {
        val works = listOf(work(1), work(2), work(3), work(4), work(5))
        val out = WorksBrowseRules.ordered(works, liked = setOf(4, 2))
        assertEquals(listOf(2L, 4L, 1L, 3L, 5L), out.map { it.id })
    }

    @Test
    fun orderedWithoutLikesIsServerOrder() {
        val works = listOf(work(3), work(1), work(2))
        assertEquals(listOf(3L, 1L, 2L), WorksBrowseRules.ordered(works, emptySet()).map { it.id })
    }

    @Test
    fun likedIdsNotLoadedAreIgnored() {
        val works = listOf(work(1), work(2))
        assertEquals(listOf(2L, 1L), WorksBrowseRules.ordered(works, liked = setOf(2, 99)).map { it.id })
    }

    // ---- 고른 것 유지 ----

    @Test
    fun pickedWorkSurvivesSearchThatDoesNotContainIt() {
        // 기본 목록에서 1 을 골랐다.
        val defaultList = listOf(work(1, "도깨비"), work(2, "오징어 게임"))
        val afterPick = WorksBrowseRules.rememberPicked(emptyMap(), setOf(1L), defaultList)
        assertEquals(setOf(1L), afterPick.keys)
        // 검색 「미생」 — 결과에 1 이 없다. 거기서 9 를 더 골랐다.
        val results = listOf(work(9, "미생"))
        val afterSearch = WorksBrowseRules.rememberPicked(afterPick, setOf(1L, 9L), results)
        assertEquals(setOf(1L, 9L), afterSearch.keys)
        assertEquals("도깨비", afterSearch.getValue(1L).title)
        assertEquals(listOf("도깨비", "미생"), WorksBrowseRules.requestTitles(setOf(1L, 9L), afterSearch, popular = defaultList))
    }

    @Test
    fun unpickedWorkIsForgotten() {
        val remembered = mapOf(1L to work(1), 2L to work(2))
        val out = WorksBrowseRules.rememberPicked(remembered, setOf(2L), emptyList())
        assertEquals(setOf(2L), out.keys)
    }

    @Test
    fun rememberPickedTakesFreshValueForSameId() {
        val remembered = mapOf(1L to work(1, "옛 제목"))
        val out = WorksBrowseRules.rememberPicked(remembered, setOf(1L), listOf(work(1, "새 제목")))
        assertEquals("새 제목", out.getValue(1L).title)
    }

    @Test
    fun rememberPickedIgnoresSeenButUnpicked() {
        val out = WorksBrowseRules.rememberPicked(emptyMap(), setOf(1L), listOf(work(1), work(2), work(3)))
        assertEquals(setOf(1L), out.keys)
    }

    @Test
    fun requestTitlesFollowPickOrder() {
        val remembered = mapOf(1L to work(1, "가"), 2L to work(2, "나"), 3L to work(3, "다"))
        val picked = linkedSetOf(3L, 1L)
        assertEquals(listOf("다", "가"), WorksBrowseRules.requestTitles(picked, remembered, popular = emptyList()))
    }

    @Test
    fun noPicksMeansFirstThreePopularTitles() {
        val popular = listOf(work(1, "가"), work(2, "나"), work(3, "다"), work(4, "라"))
        assertEquals(listOf("가", "나", "다"), WorksBrowseRules.requestTitles(emptySet(), emptyMap(), popular))
    }

    @Test
    fun noPicksWithFewerPopularUsesWhatExists() {
        val popular = listOf(work(1, "가"), work(2, "나"))
        assertEquals(listOf("가", "나"), WorksBrowseRules.requestTitles(emptySet(), emptyMap(), popular))
    }

    @Test
    fun pickedIdsMissingFromMemoryAreSkipped() {
        val remembered = mapOf(1L to work(1, "가"))
        assertEquals(listOf("가"), WorksBrowseRules.requestTitles(setOf(1L, 42L), remembered, popular = listOf(work(5, "마"))))
    }

    @Test
    fun maxPicksRuleUnchangedAcrossSearch() {
        // 기본 목록에서 5 개를 고른 뒤 검색 결과의 새 작품을 눌러도 더 고르지 못한다 — 고른 것이 화면에 없어도 센다.
        val picked = setOf(1L, 2L, 3L, 4L, 5L)
        val tap = WorkPickRules.toggle(picked, 9L)
        assertTrue(tap.tooMany)
        assertEquals(picked, tap.selected)
        // 검색 뒤에도 다섯 제목이 그대로 요청에 들어간다.
        val remembered = WorksBrowseRules.rememberPicked(emptyMap(), picked, (1L..5L).map { work(it) })
        assertEquals((1L..5L).map { "작품$it" }, WorksBrowseRules.requestTitles(tap.selected, remembered, popular = emptyList()))
    }
}
