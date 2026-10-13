package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.VisitKey
import com.mz2az.scenetrip.routetab.VisitStatus
import com.mz2az.scenetrip.routetab.VisitSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisitSyncTests {
    @Test
    fun unexpectedTransportFailureDoesNotLeaveVisitPermanentlyPending() =
        runBlocking {
            val sync = VisitSync(persist = { _, _ -> throw java.io.IOException("offline") })
            assertFalse(sync.save(1, 2))
            assertEquals(VisitStatus.FAILED, sync.states[VisitKey(1, 2)])
        }

    @Test
    fun failedVisitStaysFailedAndOnlyManualRetryCanCompleteIt() =
        runBlocking {
            var fail = true
            val sync = VisitSync(persist = { _, _ -> if (fail) Result.failure(Exception("offline")) else Result.success(Unit) })
            assertFalse(sync.save(1, 2))
            assertEquals(VisitStatus.FAILED, sync.states[VisitKey(1, 2)])
            fail = false
            assertTrue(sync.save(1, 2))
            assertEquals(VisitStatus.SAVED, sync.states[VisitKey(1, 2)])
        }

    @Test
    fun pendingVisitDoesNotCompleteOrStartASecondWrite() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Result<Unit>>()
            var requests = 0
            val sync =
                VisitSync(persist = { _, _ ->
                    requests++
                    started.complete(Unit)
                    answer.await()
                })
            val first = async { sync.save(1, 2) }
            started.await()
            assertEquals(VisitStatus.PENDING, sync.states[VisitKey(1, 2)])
            assertFalse(sync.save(1, 2))
            answer.complete(Result.success(Unit))
            assertTrue(first.await())
            assertEquals(1, requests)
        }

    @Test
    fun previousAccountReplyCannotMarkTheNewAccountAsVisited() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Result<Unit>>()
            var account = 1
            val sync =
                VisitSync(persist = { _, _ ->
                    started.complete(Unit)
                    answer.await()
                }, epoch = { account })
            val first = async { sync.save(1, 2) }
            started.await()
            account = 2
            sync.reset()
            answer.complete(Result.success(Unit))
            assertFalse(first.await())
            assertTrue(sync.states.isEmpty())
        }
}
