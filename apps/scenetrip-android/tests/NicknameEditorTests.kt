package com.mz2az.scenetrip

import com.mz2az.scenetrip.reviews.NicknameEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class NicknameEditorTests {
    @Test fun cancelledSaveUnlocksWithoutAnErrorOrClosing() {
        val editor = NicknameEditor("서울팬", false, { 1 }) { throw kotlinx.coroutines.CancellationException("cancel") }
        editor.text = "부산팬"
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { editor.submit { fail<Unit>("취소한 저장으로 닫으면 안 됩니다") } }
        }
        assertNull(editor.error)
        assertFalse(editor.busy)
    }

    @Test fun queuedRequestDoesNotWriteOrCloseAfterAnAccountChange() =
        runBlocking {
            var epoch = 1
            var writes = 0
            val editor =
                NicknameEditor("서울팬", false, { epoch }) {
                    writes++
                    null
                }
            editor.text = "부산팬"
            val requestEpoch = editor.epochSnapshot
            epoch++
            editor.submit(requestEpoch) { fail<Unit>("새 계정을 닫으면 안 됩니다") }
            assertEquals(0, writes)
            assertFalse(editor.busy)
        }

    @Test fun unchangedWelcomeNameStartsWithoutWritingButEditRequiresAChange() =
        runBlocking {
            var writes = 0
            var closed = 0
            val welcome =
                NicknameEditor("여행자12345", true, { 1 }) {
                    writes++
                    null
                }
            assertTrue(welcome.canSave)
            assertNull(welcome.shownProblem)
            welcome.submit { closed++ }
            assertEquals(0, writes)
            assertEquals(1, closed)
            assertFalse(NicknameEditor("여행자12345", false, { 1 }) { null }.canSave)
            welcome.text = "여행자56789"
            assertFalse(welcome.canSave)
            assertNotNull(welcome.shownProblem)
        }

    @Test fun normalizationDrivesCountValidationAndTheSavedName() =
        runBlocking {
            var saved = ""
            val editor =
                NicknameEditor("서울팬", false, { 1 }) {
                    saved = it
                    null
                }
            editor.text = "  \u1109\u1165\u110B\u116E\u11AF팬\n"
            assertEquals(3, editor.count)
            assertFalse(editor.canSave)
            editor.text = "  부산팬\n"
            editor.submit {}
            assertEquals("부산팬", saved)
            editor.text = "a".repeat(17)
            assertFalse(editor.canSave)
            assertEquals(17, editor.count)
        }

    @Test fun lateSuccessAndFailureCannotCloseOrAnnotateANewAccount() =
        runBlocking {
            for (failure in listOf(null, "이미 쓰는 닉네임입니다")) {
                var epoch = 1
                var closed = false
                val reply = CompletableDeferred<String?>()
                val editor = NicknameEditor("서울팬", false, { epoch }) { reply.await() }
                editor.text = "부산팬"
                val job = launch { editor.submit { closed = true } }
                kotlinx.coroutines.yield()
                assertTrue(editor.busy)
                epoch++
                reply.complete(failure)
                job.join()
                assertFalse(closed)
                assertNull(editor.error)
                assertFalse(editor.busy)
            }
        }

    @Test fun pendingSaveBlocksDuplicateRequestsAndRecoversAfterFailure() =
        runBlocking {
            var writes = 0
            val reply = CompletableDeferred<String?>()
            val editor =
                NicknameEditor("서울팬", false, { 1 }) {
                    writes++
                    reply.await()
                }
            editor.text = "부산팬"
            val job = launch { editor.submit {} }
            kotlinx.coroutines.yield()
            assertFalse(editor.canSave)
            editor.submit { fail<Unit>("중복 저장으로 닫히면 안 됩니다") }
            assertEquals(1, writes)
            reply.complete("이미 쓰는 닉네임입니다")
            job.join()
            assertEquals("이미 쓰는 닉네임입니다", editor.error)
            assertTrue(editor.canSave)
            editor.text = "제주팬"
            assertNull(editor.error)
        }

    @Test fun thrownSaveFailureUnlocksTheEditorWithoutClosing() =
        runBlocking {
            val editor = NicknameEditor("서울팬", false, { 1 }) { throw java.io.IOException("test") }
            editor.text = "부산팬"
            editor.submit { fail<Unit>("실패 응답으로 닫히면 안 됩니다") }
            assertNotNull(editor.error)
            assertFalse(editor.busy)
            assertTrue(editor.canSave)
        }
}
