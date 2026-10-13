package com.mz2az.scenetrip

import com.mz2az.scenetrip.analytics.SearchUsageIntent
import com.mz2az.scenetrip.analytics.UsageEventRequestFactory
import com.mz2az.scenetrip.analytics.UsageTransport
import com.mz2az.scenetrip.data.Paging
import com.mz2az.scenetrip.data.SearchClient
import com.mz2az.scenetrip.data.SearchPhase
import com.mz2az.scenetrip.data.SearchSession
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentList
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.UsageEntityType
import com.mz2az.scenetrip.sceneapi.client.model.UsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SearchParityTests {
    @Test fun pagingAdvancesByReceivedRowsAndStopsOnEmptyPage() {
        val first = Paging().advanced(2, 9)
        val merged = Paging.merged(listOf(1, 2), listOf(2, 3)) { it }
        assertEquals(listOf(1, 2, 3), merged)
        val next = first.advanced(2, 9)
        assertEquals(4, next.offset)
        assertTrue(next.hasMore)
        assertFalse(next.advanced(0, 9).hasMore)
        assertEquals(10, next.shownTotal(10))
    }

    @Test fun usageIntentRejectsInvalidQueryAndUsesTheSelectedTabTotal() {
        assertNull(SearchUsageIntent.make(" ", UsageEntityType.content))
        assertNull(SearchUsageIntent.make("가".repeat(101), UsageEntityType.place))
        assertNull(SearchUsageIntent.make("제주", UsageEntityType.poi))
        assertEquals(521, SearchUsageIntent.make(" 제주 ", UsageEntityType.place)?.event(128, 521)?.resultCount)
        assertEquals("제주", SearchUsageIntent.make(" 제주 ", UsageEntityType.content)?.event(128, 521)?.query)
        assertEquals(0, SearchUsageIntent.make("없음", UsageEntityType.place)?.event(3, 0)?.resultCount)
    }

    @Test fun newSearchUsesFreshTotalAndCancelledSearchCannotRecord() =
        runBlocking {
            val client = FakeSearchClient()
            val events = mutableListOf<UsageEvent>()
            val session = SearchSession(this, client, recordUsage = { events.add(it) })
            session.commitSearch("옛검색", null, UsageEntityType.content)
            client.waitFor("옛검색")
            session.commitSearch(" 새검색 ", null, UsageEntityType.place)
            client.waitFor("새검색")
            assertTrue(events.isEmpty())
            client.reply("새검색", 7, 521)
            waitFor { events.size == 1 }
            client.reply("옛검색", 128, 0)
            yield()
            assertEquals(listOf("새검색"), events.map { it.query })
            assertEquals(521, events.single().resultCount)
            assertEquals(521, session.state.placePaging.total)
        }

    @Test fun failedCommitKeepsFailureAndRetryDoesNotRepeatUsage() =
        runBlocking {
            val client = FakeSearchClient()
            val events = mutableListOf<UsageEvent>()
            var commits = 0
            val session = SearchSession(this, client, recordUsage = { events.add(it) }, onCommit = { _, _ -> commits++ })
            session.commitSearch("제주", null, UsageEntityType.content)
            client.waitFor("제주")
            client.pending.remove("제주")!!.completeExceptionally(IOException("offline"))
            waitFor { session.state.phase == SearchPhase.FAILED }
            assertTrue(events.isEmpty())
            session.retry()
            client.waitFor("제주")
            client.reply("제주", 7, 2)
            waitFor { session.state.phase == SearchPhase.LOADED }
            assertTrue(events.isEmpty())
            assertEquals(1, commits)
        }

    @Test fun paginationKeepsRowsOnFailureAndDeduplicatesWithoutLosingOffset() =
        runBlocking {
            val client = FakeSearchClient()
            val session = SearchSession(this, client)
            session.search("")
            client.waitFor("")
            client.reply("", 9, 9)
            waitFor { session.state.phase == SearchPhase.LOADED }
            client.pageFailure = true
            assertFalse(session.loadNextPlaces())
            assertEquals(listOf(1L), session.state.places.map { it.id })
            assertTrue(session.state.morePlacesFailed)
            client.pageFailure = false
            client.placePage = places(listOf(1, 2), 9)
            assertTrue(session.loadNextPlaces())
            assertEquals(1, client.lastOffset)
            assertEquals(listOf(1L, 2L), session.state.places.map { it.id })
            assertEquals(3, session.state.placePaging.offset)
        }

    @Test fun viewportCancelsSearchAndDoesNotSendUsage() =
        runBlocking {
            val client = FakeSearchClient()
            val events = mutableListOf<UsageEvent>()
            val session = SearchSession(this, client, recordUsage = { events.add(it) })
            session.commitSearch("제주", null, UsageEntityType.place)
            client.waitFor("제주")
            client.placePage = places(listOf(2), 17)
            session.searchInViewport("126,35,127,36")
            waitFor { session.state.phase == SearchPhase.LOADED }
            client.reply("제주", 7, 521)
            yield()
            assertTrue(events.isEmpty())
            assertEquals(17, session.state.placePaging.total)
        }

    @Test fun eventHeadersKeepCapturedTokenAndLanguageWithoutRefreshOrRetry() {
        var captured: Request? = null
        var attempts = 0
        val transport =
            Call.Factory { request ->
                captured = request
                attempts++
                throw IOException("offline")
            }
        val factory = UsageEventRequestFactory(Lang.ko, "captured-token", transport)
        assertThrows(IOException::class.java) {
            factory.newCall(
                Request
                    .Builder()
                    .url("http://localhost/events")
                    .header("Authorization", "Bearer later-token")
                    .build(),
            )
        }
        assertEquals("Bearer captured-token", captured?.header("Authorization"))
        assertEquals("ko", captured?.header("Accept-Language"))
        assertEquals(1, attempts)
        assertFalse(UsageTransport.client.retryOnConnectionFailure)
        assertTrue(UsageTransport.client.interceptors.isEmpty())
        assertTrue(UsageTransport.client.networkInterceptors.isEmpty())
        assertNull(UsageTransport.client.cache)
        assertEquals(10_000, UsageTransport.client.callTimeoutMillis)
    }

    @Test fun viewportKeepsTheContentQueryWhenLoadingTheNextPage() =
        runBlocking {
            val client = FakeSearchClient()
            val session = SearchSession(this, client)
            session.search("도깨비")
            client.waitFor("도깨비")
            client.reply("도깨비", 9, 9)
            waitFor { session.state.phase == SearchPhase.LOADED }
            session.searchInViewport("126,35,127,36")
            waitFor { session.state.phase == SearchPhase.LOADED }
            assertTrue(session.loadNextContents())
            assertEquals("도깨비", client.contentQuery)
        }

    @Test fun apiResultAcceptsSuspendingWorkAndRethrowsCancellation() =
        runBlocking<Unit> {
            assertEquals(7, apiResult { CompletableDeferred(7).await() }.getOrThrow())
            assertThrows(CancellationException::class.java) {
                runBlocking { apiResult<Int> { throw CancellationException("cancelled") } }
            }
        }
}

private suspend fun waitFor(test: () -> Boolean) = withTimeout(2000) { while (!test()) yield() }

private fun contents(
    ids: List<Int>,
    total: Int,
) = ContentList(ids.map { ContentSummary(it.toLong(), ContentCategory.movie, "시험", 1) }, total, 50, 0)

private fun places(
    ids: List<Int>,
    total: Int,
) = PlaceList(ids.map { PlaceSummary(it.toLong(), "시험", latitude = 37.5, longitude = 127.0) }, total, 200, 0)

private class FakeSearchClient : SearchClient {
    val pending = mutableMapOf<String, CompletableDeferred<Pair<ContentList, PlaceList>>>()
    var pageFailure = false
    var lastOffset = 0
    var placePage = places(listOf(2), 9)
    var contentQuery: String? = null

    override suspend fun search(query: String?): Pair<ContentList, PlaceList> =
        CompletableDeferred<Pair<ContentList, PlaceList>>().also { pending[query ?: ""] = it }.await()

    override suspend fun contents(
        query: String?,
        offset: Int,
    ): ContentList {
        contentQuery = query
        return contents(listOf(2), 9)
    }

    override suspend fun places(
        query: String?,
        bbox: String?,
        offset: Int,
    ): PlaceList {
        lastOffset = offset
        if (pageFailure) throw IOException("offline")
        return placePage
    }

    suspend fun waitFor(query: String) {
        waitFor { pending.containsKey(query) }
    }

    fun reply(
        query: String,
        works: Int,
        spots: Int,
    ) {
        pending.remove(query)!!.complete(contents(listOf(1), works) to places(listOf(1), spots))
    }
}
