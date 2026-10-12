package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CommunityClient
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunitySession
import com.mz2az.scenetrip.data.CommunitySubmission
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostList
import com.mz2az.scenetrip.sceneapi.client.model.TripPostSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime

class CommunityTests {
    @Test
    fun postValidationTrimsAndEnforcesAllLimits() {
        assertTrue(CommunityRules.canPost(" title ", " body ", 8))
        assertFalse(CommunityRules.canPost(" ", "body", 0))
        assertFalse(CommunityRules.canPost("title", "\n", 0))
        assertFalse(CommunityRules.canPost("x".repeat(101), "body", 0))
        assertFalse(CommunityRules.canPost("title", "x".repeat(5001), 0))
        assertFalse(CommunityRules.canPost("title", "body", -1))
        assertFalse(CommunityRules.canPost("title", "body", 9))
    }

    @Test
    fun refreshFailurePreservesRowsAndMoreUsesOffsetWithoutDuplicates() =
        runBlocking {
            val client = FakeCommunityClient()
            val session = CommunitySession(client)
            session.refresh()
            assertEquals(listOf(1L), session.state.posts.map { it.serverId })
            client.listFailure = IOException("offline")
            session.refresh()
            assertEquals(listOf(1L), session.state.posts.map { it.serverId })
            assertNotNull(session.state.message)
            assertFalse(session.state.loading)
            client.listFailure = null
            client.rows = listOf(summary(1), summary(2))
            session.refresh(more = true)
            assertEquals(1, client.lastOffset)
            assertEquals(listOf(1L, 2L), session.state.posts.map { it.serverId })
        }

    @Test
    fun accountChangeDropsOldRowsAndIgnoresLateResponse() =
        runBlocking {
            val client = FakeCommunityClient()
            var epoch = 0
            val session = CommunitySession(client, accountEpoch = { epoch })
            session.refresh(mine = true)
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            client.waitForList = {
                requested.complete(Unit)
                release.await()
            }
            val pending = launch { session.refresh() }
            requested.await()
            epoch++
            session.syncAccount()
            assertTrue(session.state.mine.isEmpty())
            assertEquals(0, session.state.mineTotal)
            release.complete(Unit)
            pending.join()
            assertTrue(session.state.posts.isEmpty())
            assertFalse(session.state.loading)
        }

    @Test
    fun deletingSomeoneElsesPostNeverCallsServerAndFailureKeepsMine() =
        runBlocking {
            val client = FakeCommunityClient()
            val session = CommunitySession(client)
            session.refresh()
            val mine = session.state.posts.single()
            session.remove(mine.copy(serverIsMine = false))
            assertEquals(0, client.deletes)
            client.deleteFailure = IOException("offline")
            session.remove(mine)
            assertEquals(1, client.deletes)
            assertEquals(1, session.state.posts.size)
            client.deleteFailure = null
            session.remove(mine)
            assertTrue(session.state.posts.isEmpty())
        }

    @Test
    fun lostPostResponseCanBeConfirmedWithoutPostingTwice() =
        runBlocking {
            val client = FakeCommunityClient()
            val attempt = CommunitySubmission(client, now = { CREATED.toInstant().toEpochMilli() })
            client.createFailure = IOException("response lost")
            val input = TripPostCreate("Title", "Body", listOf("posts/unique-upload.jpg"))
            assertNull(attempt.submit(input))
            assertTrue(attempt.state.uncertain)
            assertNull(attempt.submit(input))
            assertEquals(1, client.creates)
            client.rows = listOf(summary(2))
            client.details[2] = detail(2, input)
            assertEquals(2L, attempt.check()?.id)
            assertTrue(attempt.state.completed)
            assertNull(attempt.submit(input))
            assertEquals(1, client.creates)
        }

    @Test
    fun postWithoutPhotosStaysUncertainAndDoesNotResend() =
        runBlocking {
            val client = FakeCommunityClient()
            val attempt = CommunitySubmission(client)
            client.createFailure = IOException("response lost")
            val input = TripPostCreate("Title", "Body", emptyList())
            attempt.submit(input)
            assertNull(attempt.check())
            assertTrue(attempt.state.uncertain)
            assertNotNull(attempt.state.message)
            assertEquals(1, client.creates)
        }

    @Test
    fun postingStopsIfBaselineCannotBeReadOrAccountChanges() =
        runBlocking {
            val client = FakeCommunityClient()
            val input = TripPostCreate("Title", "Body", emptyList())
            client.listFailure = IOException("offline")
            val attempt = CommunitySubmission(client)
            assertNull(attempt.submit(input))
            assertEquals(0, client.creates)
            assertFalse(attempt.state.uncertain)
            var epoch = 0
            client.listFailure = null
            client.waitForList = { epoch++ }
            val switched = CommunitySubmission(client, accountEpoch = { epoch })
            assertNull(switched.submit(input))
            assertEquals(0, client.creates)
            assertFalse(switched.state.working)
        }

    @Test
    fun confirmationRequiresMatchingUniqueUploadsAndDoesNotAcceptOldRows() =
        runBlocking {
            val client = FakeCommunityClient()
            val attempt = CommunitySubmission(client, now = { CREATED.toInstant().toEpochMilli() })
            val input = TripPostCreate("Title", "Body", listOf("posts/unique-upload.jpg"))
            client.createFailure = IOException("response lost")
            attempt.submit(input)
            client.details[1] = detail(1, input)
            assertNull(attempt.check())
            client.rows = listOf(summary(2))
            client.details[2] = detail(2, input.copy(photoKeys = listOf("posts/somebody-else.jpg")))
            assertNull(attempt.check())
            assertFalse(attempt.state.completed)
            assertEquals(1, client.creates)
        }

    private class FakeCommunityClient : CommunityClient {
        var rows = listOf(summary(1))
        val details = mutableMapOf<Long, TripPostDetail>()
        var listFailure: Exception? = null
        var createFailure: Exception? = null
        var deleteFailure: Exception? = null
        var waitForList: (suspend () -> Unit)? = null
        var lastOffset = -1
        var creates = 0
        var deletes = 0

        override suspend fun list(
            mine: Boolean,
            limit: Int,
            offset: Int,
        ): TripPostList {
            lastOffset = offset
            listFailure?.let { throw it }
            waitForList?.invoke()
            return TripPostList(rows, rows.size)
        }

        override suspend fun detail(id: Long): TripPostDetail = details.getValue(id)

        override suspend fun create(input: TripPostCreate): TripPostDetail {
            creates++
            createFailure?.let { throw it }
            return detail(2, input)
        }

        override suspend fun delete(id: Long) {
            deletes++
            deleteFailure?.let { throw it }
        }

        override suspend fun saveCourse(id: Long): CourseDetail = error("이 시험은 코스 복사를 요청하지 않는다")
    }

    companion object {
        private val CREATED = OffsetDateTime.parse("2026-10-12T00:00:00Z")

        private fun summary(id: Long) = TripPostSummary(id, "Title", "Body", CREATED, 1, true)

        private fun detail(
            id: Long,
            input: TripPostCreate,
        ) = TripPostDetail(
            id,
            input.title,
            input.body,
            CREATED,
            input.photoKeys.map { URI("https://images.example.test/" + it) },
            true,
        )
    }
}
