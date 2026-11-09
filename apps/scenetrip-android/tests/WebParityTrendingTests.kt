package com.mz2az.scenetrip

import com.mz2az.scenetrip.hometab.PinnedWorks
import com.mz2az.scenetrip.hometab.TrendingRules
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

/**
 * 웹 시안 반영 — 「지금 뜨는 작품」 고정 작품·전체 보기(H6)(MZ2AZ-547).
 * iOS `PinnedWorksTests`·`TrendingRulesTests` 를 옮겼다.
 *
 * iOS `NewCourseHookTests`(TabRouter.startNewCourse·openTrending)는 옮기지 않았다 — Android `TabRouter` 는 Compose
 * `mutableStateOf` 를 쓰는데 JVM 시험에서는 `android.os.Looper` 가 없어 객체 초기화부터 깨진다.
 */
class WebParityTrendingTests {
    private fun work(id: Long) = ContentSummary(id = id, category = ContentCategory.drama, title = "작품 $id", placeCount = 1)

    private fun works(range: LongRange) = range.map { work(it) }

    private fun detail(
        id: Long,
        delisted: Boolean? = null,
    ) = ContentDetail(
        id = id,
        category = ContentCategory.movie,
        title = "상세 $id",
        placeCount = 7,
        posterUrl = URI("https://img.example/$id.jpg"),
        delisted = delisted,
    )

    // ── H6 고정 작품 ──

    @Test
    fun pinnedIdsIsKpopDemonHunters() {
        assertEquals(listOf(32L), PinnedWorks.ids)
    }

    @Test
    fun pinnedMissingListsIdsNotInList() {
        assertEquals(listOf(32L, 40L), PinnedWorks.missing(listOf(work(1), work(2)), listOf(32L, 2L, 40L)))
        assertEquals(emptyList<Long>(), PinnedWorks.missing(listOf(work(32)), listOf(32L)))
    }

    @Test
    fun pinnedSummaryCopiesDetailFields() {
        val summary = PinnedWorks.summary(detail(32, delisted = false))
        assertEquals(32L, summary?.id)
        assertEquals("상세 32", summary?.title)
        assertEquals(ContentCategory.movie, summary?.category)
        assertEquals(7, summary?.placeCount)
        assertEquals(URI("https://img.example/32.jpg"), summary?.posterUrl)
        assertNotNull(PinnedWorks.summary(detail(33)))
    }

    @Test
    fun pinnedSummaryNullForDelisted() {
        assertNull(PinnedWorks.summary(detail(32, delisted = true)))
        // 대조: 숨기지 않은 작품은 목록 줄이 된다.
        assertEquals(32L, PinnedWorks.summary(detail(32, delisted = false))?.id)
    }

    @Test
    fun pinnedArrangeMovesPinnedFrontWithoutDuplicate() {
        val out = PinnedWorks.arrange(listOf(work(1), work(2), work(32), work(3)), emptyMap(), listOf(32L))
        assertEquals(listOf(32L, 1L, 2L, 3L), out.map { it.id })
    }

    @Test
    fun pinnedArrangeUsesFetchedWhenMissingAndSkipsUnknown() {
        val out = PinnedWorks.arrange(listOf(work(1), work(2)), mapOf(40L to work(40)), listOf(32L, 40L))
        assertEquals(listOf(40L, 1L, 2L), out.map { it.id })
    }

    @Test
    fun pinnedArrangeKeepsIdsOrder() {
        val out = PinnedWorks.arrange(listOf(work(1), work(5), work(9)), emptyMap(), listOf(9L, 5L))
        assertEquals(listOf(9L, 5L, 1L), out.map { it.id })
    }

    @Test
    fun pinnedApplyFetchesOnlyMissing() =
        runBlocking {
            val asked = mutableListOf<Long>()
            val out =
                PinnedWorks.apply(listOf(work(1), work(5)), listOf(5L, 32L)) { id ->
                    asked += id
                    detail(id)
                }
            assertEquals(listOf(32L), asked)
            assertEquals(listOf(5L, 32L, 1L), out.map { it.id })
        }

    @Test
    fun pinnedApplyToleratesFailedFetch() =
        runBlocking {
            // 32 받기는 실패해도 목록에 있던 5 는 여전히 앞에 선다.
            val out = PinnedWorks.apply(listOf(work(1), work(5), work(2)), listOf(32L, 5L)) { null }
            assertEquals(listOf(5L, 1L, 2L), out.map { it.id })
        }

    @Test
    fun pinnedApplySkipsDelistedFetch() =
        runBlocking {
            // 숨긴 32 는 건너뛰고, 숨기지 않은 40 은 앞에 선다.
            val out = PinnedWorks.apply(listOf(work(1)), listOf(32L, 40L)) { id -> detail(id, delisted = id == 32L) }
            assertEquals(listOf(40L, 1L), out.map { it.id })
        }

    @Test
    fun pinnedApplyDoesNotFetchWhenPresent() =
        runBlocking {
            var asked = 0
            val out =
                PinnedWorks.apply(listOf(work(1), work(32)), listOf(32L)) {
                    asked += 1
                    null
                }
            assertEquals(0, asked)
            assertEquals(listOf(32L, 1L), out.map { it.id })
        }

    // ── H6 전체 보기 — 100편씩 이어 받기 ──

    @Test
    fun trendingConstants() {
        assertEquals(100, TrendingRules.PAGE_LIMIT, "계약의 limit 상한 100")
        assertEquals(5, TrendingRules.MAX_PAGES, "이어 받기 최대 5번")
    }

    @Test
    fun trendingMergeDedupesKeepingFirst() {
        val merged = TrendingRules.merge(listOf(work(1), work(2), work(3)), listOf(work(3), work(4), work(1), work(5)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), merged.map { it.id })
    }

    @Test
    fun trendingShouldContinueOnlyWhenPageFullAndBelowTotal() {
        assertTrue(TrendingRules.shouldContinue(pageCount = 100, collected = 100, total = 128))
        assertFalse(TrendingRules.shouldContinue(pageCount = 28, collected = 128, total = 300))
        assertFalse(TrendingRules.shouldContinue(pageCount = 100, collected = 200, total = 200))
        assertFalse(TrendingRules.shouldContinue(pageCount = 100, collected = 250, total = 200))
    }

    @Test
    fun trendingLoadFetchesPagesUntilTotalAndPinsFront() =
        runBlocking {
            // 1~100, 101~128 (32 는 첫 쪽에 있다).
            val offsets = mutableListOf<Int>()
            var detailAsked = 0
            val out =
                TrendingRules.load(
                    fetchPage = { offset ->
                        offsets += offset
                        if (offset == 0) works(1L..100L) to 128 else works(101L..128L) to 128
                    },
                    fetchDetail = {
                        detailAsked += 1
                        null
                    },
                )
            assertEquals(listOf(0, 100), offsets)
            assertEquals(128, out.size)
            assertEquals(32L, out.firstOrNull()?.id)
            assertEquals(128, out.map { it.id }.toSet().size)
            assertEquals(0, detailAsked)
        }

    @Test
    fun trendingLoadStopsAtMaxPages() =
        runBlocking {
            var calls = 0
            val out =
                TrendingRules.load(
                    fetchPage = { offset ->
                        calls += 1
                        val start = offset + 1000L
                        works(start..start + 99) to 100_000
                    },
                    fetchDetail = { null },
                )
            assertEquals(5, calls)
            assertEquals(500, out.size)
        }

    @Test
    fun trendingLoadFetchesMissingPinnedDetail() =
        runBlocking {
            val asked = mutableListOf<Long>()
            val out =
                TrendingRules.load(
                    fetchPage = { works(1L..3L) to 3 },
                    fetchDetail = { id ->
                        asked += id
                        ContentDetail(id = id, category = ContentCategory.movie, title = "케이팝 데몬 헌터스", placeCount = 4)
                    },
                )
            assertEquals(listOf(32L), asked)
            assertEquals(listOf(32L, 1L, 2L, 3L), out.map { it.id })
        }

    @Test
    fun trendingLoadThrowsWhenPageFails() {
        val boom = IOException("연결 끊김")
        try {
            runBlocking {
                TrendingRules.load(fetchPage = { throw boom }, fetchDetail = { null })
            }
            fail<Unit>("쪽 받기 실패는 던져야 한다")
        } catch (e: IOException) {
            assertSame(boom, e)
        }
    }
}
