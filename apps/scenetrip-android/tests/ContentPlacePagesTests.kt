package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.ContentPlacePages
import com.mz2az.scenetrip.sceneapi.client.model.PlaceList
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ContentPlacePagesTests {
    @Test fun requestsRespectTheContentEndpointMaximumOf100() =
        runBlocking {
            var askedId = 0L
            var askedLimit = 0
            val pager =
                ContentPlacePages { id, limit, offset ->
                    askedId = id
                    askedLimit = limit
                    require(limit <= 100) { "listContentPlaces.limit: 100 이하여야 합니다" }
                    page(listOf(227), 1, limit, offset)
                }
            assertTrue(pager.load(33).isSuccess)
            assertEquals(33L, askedId)
            assertEquals(100, askedLimit)
        }

    @Test fun multiplePagesKeepServerOrderAndOffsetDespiteDuplicateIds() =
        runBlocking {
            val offsets = mutableListOf<Int>()
            val pager =
                ContentPlacePages { _, limit, offset ->
                    offsets += offset
                    page(if (offset == 0) (1L..100L).toList() else listOf(1L, 102L, 103L), 103, limit, offset)
                }
            assertEquals((1L..100L).toList() + listOf(102L, 103L), pager.load(33).getOrThrow().map { it.id })
            assertEquals(listOf(0, 100), offsets)
        }

    @Test fun failedLoadCanRetryWithoutReturningASilentEmptySuccess() =
        runBlocking {
            var fail = true
            val pager =
                ContentPlacePages { _, limit, offset ->
                    if (fail) throw IOException("offline")
                    page(listOf(227), 1, limit, offset)
                }
            assertTrue(pager.load(33).isFailure)
            fail = false
            assertEquals(listOf(227L), pager.load(33).getOrThrow().map { it.id })
        }

    @Test fun emptyPageStopsWhenTheServerTotalIsStale() =
        runBlocking {
            var calls = 0
            val pager =
                ContentPlacePages { _, limit, offset ->
                    calls++
                    page(emptyList(), 17, limit, offset)
                }
            assertTrue(pager.load(33).getOrThrow().isEmpty())
            assertEquals(1, calls)
        }

    @Test fun invalidContentIdsDoNotMakeRequests() =
        runBlocking {
            var called = false
            val pager =
                ContentPlacePages { _, limit, offset ->
                    called = true
                    page(emptyList(), 0, limit, offset)
                }
            assertTrue(pager.load(0).isFailure)
            assertFalse(called)
        }

    private fun page(
        ids: List<Long>,
        total: Int,
        limit: Int,
        offset: Int,
    ) = PlaceList(
        ids.map { PlaceSummary(it, "Place", latitude = 37.5, longitude = 127.0) },
        total,
        limit,
        offset,
    )
}
