package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.AIConsentInterceptor
import com.mz2az.scenetrip.data.AIConsentRequired
import com.mz2az.scenetrip.data.AIConsentStorage
import com.mz2az.scenetrip.data.AIConsentStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.RetryInterceptor
import com.mz2az.scenetrip.data.UsageLedger
import com.mz2az.scenetrip.data.VersionedConsentStore
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.ui.AIConsentCopy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * MZ2AZ-468 AI 기능 동의. iOS `AIConsentTests` 를 옮겼다 — 실제 소켓·서버의 가이드 호출 없이 가짜 저장소·가짜 전송 계층만 쓴다.
 * 요청 계층 시험은 진짜 [OkHttpClient] 에 끝 인터셉터(전송 대신 횟수만 센다)를 달아 인터셉터 순서를 그대로 실행한다.
 */
class AIConsentTests {
    private val storage = MemoryConsentStorage()

    @Test
    fun firstUseRequestsSheetAndBlocksBothEndpoints() {
        val store = AIConsentStore(storage)
        val gate = AIConsentGate(store)
        var proceeded = false
        assertFalse(gate.isPresented, "앱 시작에는 묻지 않는다")
        gate.request { proceeded = true }
        assertTrue(gate.isPresented)
        assertFalse(proceeded)
        for (path in listOf("/guide/chat", "/guide/plan")) {
            val wire = Wire()
            assertThrows(AIConsentRequired::class.java) { wire.client(store).post(path) }
            assertEquals(0, wire.requests, "동의 전에는 $path 가 나가지 않는다")
        }
    }

    @Test
    fun allowContinuesOnceAndCoversBothFeatures() {
        val store = AIConsentStore(storage)
        val chat = AIConsentGate(store)
        var proceeds = 0
        chat.request { proceeds += 1 }
        chat.allow()
        assertEquals(0, proceeds, "시트가 닫힌 뒤 진행한다")
        chat.didDismiss()
        chat.didDismiss()
        assertEquals(1, proceeds)
        val planner = AIConsentGate(AIConsentStore(storage))
        planner.request { proceeds += 1 }
        assertFalse(planner.isPresented, "한 번의 동의가 코스 만들기까지 덮는다")
        assertEquals(2, proceeds)
        for (path in listOf("/guide/chat", "/guide/plan")) {
            val wire = Wire()
            assertEquals(200, wire.client(store).post(path).code)
            assertEquals(1, wire.requests)
        }
    }

    @Test
    fun cancelAndDismissPreserveTypedQuestion() {
        val store = AIConsentStore(storage)
        val gate = AIConsentGate(store)
        val draft = "  주변 촬영지 알려줘  "
        var current = draft
        // 취소 단추와 끌어 내려 닫기·바깥 누르기는 모두 같은 취소다.
        repeat(2) {
            gate.request { current = "" }
            gate.cancel()
            gate.didDismiss()
            assertEquals(draft, current)
            assertFalse(store.isAllowed)
            val wire = Wire()
            assertThrows(AIConsentRequired::class.java) { wire.client(store).post("/guide/chat") }
            assertEquals(0, wire.requests)
        }
        gate.request { current = "" }
        gate.allow()
        gate.didDismiss()
        assertEquals("", current)
    }

    @Test
    fun allowWithoutPendingActionIsIgnored() {
        val store = AIConsentStore(storage)
        val gate = AIConsentGate(store)
        gate.allow()
        assertFalse(store.isAllowed, "요청 없이 허용만 누를 수 없다")
    }

    @Test
    fun revokeViaSettingsAsksAgainAndCancelDoesNotEnable() {
        val store = AIConsentStore(storage)
        val gate = AIConsentGate(store)
        gate.setEnabled(true)
        assertFalse(store.isAllowed, "켜기만으로 동의하지 않는다")
        gate.cancel()
        gate.didDismiss()
        assertFalse(store.isAllowed)
        gate.setEnabled(true)
        gate.allow()
        gate.didDismiss()
        assertTrue(store.isAllowed)
        gate.setEnabled(false)
        assertFalse(store.isAllowed)
        gate.request { throw AssertionError("철회 뒤 동의 없이 진행") }
        assertTrue(gate.isPresented)
        val wire = Wire()
        assertThrows(AIConsentRequired::class.java) { wire.client(store).post("/guide/plan") }
        assertEquals(0, wire.requests)
    }

    @Test
    fun versionChangeRequiresNewConsent() {
        val original = AIConsentStore(storage, version = 1)
        original.allow()
        assertTrue(AIConsentStore(storage, version = 1).isAllowed)
        val updated = AIConsentStore(storage, version = 2)
        val gate = AIConsentGate(updated)
        gate.request {}
        assertTrue(gate.isPresented)
        val wire = Wire()
        assertThrows(AIConsentRequired::class.java) { wire.client(updated).post("/guide/chat") }
        assertEquals(0, wire.requests)
        gate.allow()
        gate.didDismiss()
        assertTrue(updated.isAllowed)
        assertFalse(original.isAllowed, "다른 버전을 묵시적으로 허용하지 않는다")
    }

    @Test
    fun otherRequestsAreUnaffected() {
        val store = AIConsentStore(storage)
        for (path in listOf("/courses", "/navigation/next-leg", "/events")) {
            val wire = Wire()
            assertEquals(200, wire.client(store).post(path).code)
            assertEquals(1, wire.requests, path)
        }
        val wire = Wire()
        assertEquals(200, wire.client(store).get("/guide/chat").code, "읽기는 대상이 아니다")
        assertEquals(1, wire.requests)
    }

    @Test
    fun storedConsentSurvivesRestartAndRevocationPersists() {
        val store = AIConsentStore(storage)
        assertFalse(store.isAllowed)
        store.allow()
        assertEquals(AIConsentStore.CURRENT_VERSION, storage.getInt("scenetrip.aiConsentVersion"), "iOS 와 같은 키·버전")
        val restored = AIConsentStore(storage)
        assertTrue(restored.isAllowed)
        restored.revoke()
        assertEquals(0, storage.getInt("scenetrip.aiConsentVersion"))
        assertFalse(store.isAllowed)
    }

    @Test
    fun revocationBeforeSheetFinishesDoesNotRunPendingAction() {
        val store = AIConsentStore(storage)
        val gate = AIConsentGate(store)
        gate.request { throw AssertionError("허용 후 철회되면 진행하지 않는다") }
        gate.allow()
        store.revoke()
        gate.didDismiss()
        gate.request {}
        assertTrue(gate.isPresented)
    }

    @Test
    fun revocationWhileWaitingStopsAutomaticChatRetry() {
        val store = AIConsentStore(storage)
        store.allow()
        val ledger = UsageLedger()
        // 서버가 한도 헤더를 알려 준 적이 있어야 멱등 키가 붙은 채팅이 재시도 대상이 된다.
        ledger.observe(
            response(
                Request.Builder().url("$API_BASE/guide/chat").build(),
                200,
                mapOf("RateLimit-Limit" to "15", "RateLimit-Remaining" to "14", "RateLimit-Reset" to "30"),
            ),
            ledger.generation,
            0,
        )
        val wire = Wire(code = 503)
        var now = 0L
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    RetryInterceptor(
                        clock = { now },
                        // 재시도 전 쉬는 동안 사용자가 설정에서 끈다.
                        pause = {
                            now += it
                            if (store.isAllowed) store.revoke()
                        },
                        ledger = ledger,
                    ),
                ).addInterceptor(AIConsentInterceptor(store))
                .addInterceptor(wire.terminal)
                .build()
        val request =
            Request
                .Builder()
                .url("$API_BASE/guide/chat")
                .header("Idempotency-Key", "k-1")
                .post("{}".toRequestBody())
                .build()
        assertThrows(AIConsentRequired::class.java) { client.newCall(request).execute() }
        assertEquals(1, wire.requests, "철회 뒤 재시도는 나가지 않는다")
    }

    @Test
    fun revocationAfterConsentCheckPreventsTransmissionStart() {
        val store = AIConsentStore(storage)
        for (path in listOf("/guide/chat", "/guide/plan")) {
            store.allow()
            var injected = false
            val wire = Wire()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        AIConsentInterceptor(store) {
                            injected = true
                            store.revoke()
                        },
                    ).addInterceptor(wire.terminal)
                    .build()
            assertThrows(AIConsentRequired::class.java) { client.post(path) }
            assertTrue(injected, "첫 동의 검사를 통과한 뒤 철회한다")
            assertEquals(0, wire.requests, "철회가 끝난 뒤에는 요청을 시작하지 않는다")
        }
    }

    @Test
    fun revocationWhileRequestIsOutCancelsItAsConsentError() {
        val store = AIConsentStore(storage)
        store.allow()
        var cancelled = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(AIConsentInterceptor(store))
                .addInterceptor { chain ->
                    store.revoke() // 응답을 기다리는 사이에 설정에서 끈다.
                    cancelled = chain.call().isCanceled()
                    throw IOException("Canceled")
                }.build()
        assertThrows(AIConsentRequired::class.java) { client.post("/guide/chat") }
        assertTrue(cancelled, "이미 나간 호출은 끊는다")
        assertFalse(store.isAllowed)
    }

    @Test
    fun finishedRequestsAreForgottenSoLaterRevocationCancelsNothing() {
        val store = AIConsentStore(storage)
        store.allow()
        val wire = Wire()
        assertEquals(200, wire.client(store).post("/guide/chat").code)
        store.revoke() // 끝난 호출은 등록에서 빠져 있어야 한다 — 던지지 않는다.
        assertFalse(store.isAllowed)
    }

    @Test
    fun consentFailureShowsAllowMessageNotConnectionError() {
        val error = AIConsentRequired()
        val wrapped = RuntimeException("wrapped", error)
        for (failure in listOf(NetworkFailure.of(error), NetworkFailure.of(wrapped))) {
            assertEquals(AIConsentRequired.CODE, failure.code)
            assertEquals(null, failure.status)
            assertEquals(AIConsentRequired.MESSAGE_KO, failure.message)
        }
        assertEquals("AI 기능 사용을 허용한 뒤 다시 시도해 주세요.", AIConsentRequired.MESSAGE_KO)
        assertEquals("Please allow AI features, then try again.", englishOf(AIConsentRequired.MESSAGE_KO))
        val api = ApiFailure.of(wrapped)
        assertEquals(AIConsentRequired.MESSAGE_KO, api.message)
        assertTrue(api.isRetryable, "허용한 뒤 같은 요청을 다시 해 볼 수 있다")
        assertNotNull(NetworkFailure.of(IOException("x")).message)
    }

    @Test
    fun copyMatchesIosAndHasEnglish() {
        assertEquals("AI 기능 사용 안내", AIConsentCopy.TITLE_KO)
        assertEquals(
            "AI 가이드와 AI 코스 만들기는 입력한 내용, 대화, 관심 작품·여행 조건·코스 맥락을 " +
                "미국 오리건에 있는 Amazon Bedrock(AWS)으로 보내 답변과 일정을 만듭니다. " +
                "이름·이메일은 보내지 않아요. AI 답변은 틀릴 수 있어요.",
            AIConsentCopy.BODY_KO,
        )
        assertEquals("개인정보 처리방침", AIConsentCopy.LINK_KO)
        assertEquals("허용하고 계속", AIConsentCopy.ALLOW_KO)
        assertEquals("취소", AIConsentCopy.CANCEL_KO)
        assertEquals("AI 기능 사용", AIConsentCopy.TOGGLE_KO)
        assertEquals("끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요.", AIConsentCopy.FOOTNOTE_KO)
        assertEquals("About AI features", englishOf(AIConsentCopy.TITLE_KO))
        assertEquals("Allow and continue", englishOf(AIConsentCopy.ALLOW_KO))
        assertEquals("AI features", englishOf(AIConsentCopy.TOGGLE_KO))
        assertTrue(englishOf(AIConsentCopy.BODY_KO)!!.contains("Amazon Bedrock (AWS) in Oregon, USA"))
        for (key in AIConsentCopy.copyKeys) assertNotNull(englishOf(key), key)
        assertEquals("https://scenetrip.io/privacy", LegalLinks.PRIVACY)
    }

    @Test
    fun otherConsentsReuseTheStoreAndRevokeIndependently() {
        // 위치기반서비스 동의(MZ2AZ-472)가 같은 저장소 클래스를 키·버전만 바꿔 쓰는 모양.
        val location = VersionedConsentStore(storage, "scenetrip.locationConsentVersion", 1)
        val ai = AIConsentStore(storage)
        ai.allow()
        location.allow()
        ai.revoke()
        assertFalse(ai.isAllowed)
        assertTrue(location.isAllowed, "한쪽 철회가 다른 동의에 영향을 주지 않는다")
        val gate = AIConsentGate(location)
        var ran = false
        gate.request { ran = true }
        assertTrue(ran)
        assertFalse(gate.isPresented)
    }

    // --- 가짜 전송 계층 ---

    private fun response(
        request: Request,
        code: Int,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body("{}".toResponseBody())
            .build()

    /** 소켓 대신 횟수만 센다. */
    private inner class Wire(
        private val code: Int = 200,
    ) {
        var requests = 0

        val terminal =
            Interceptor { chain ->
                requests += 1
                response(chain.request(), code)
            }

        fun client(store: VersionedConsentStore): OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(AIConsentInterceptor(store))
                .addInterceptor(terminal)
                .build()
    }

    private fun OkHttpClient.post(path: String): Response =
        newCall(
            Request
                .Builder()
                .url("$API_BASE$path")
                .post("{}".toRequestBody())
                .build(),
        ).execute()

    private fun OkHttpClient.get(path: String): Response = newCall(Request.Builder().url("$API_BASE$path").build()).execute()
}

private class MemoryConsentStorage : AIConsentStorage {
    private val values = mutableMapOf<String, Int>()

    override fun getInt(key: String): Int = values[key] ?: 0

    override fun setInt(
        key: String,
        value: Int,
    ) {
        values[key] = value
    }
}
