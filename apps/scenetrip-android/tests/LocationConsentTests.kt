package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.AIConsentStorage
import com.mz2az.scenetrip.data.AIConsentStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.CoordinateSources
import com.mz2az.scenetrip.data.KakaoDirectionsNotice
import com.mz2az.scenetrip.data.LatestRequestQueue
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.LocationConsentInterceptor
import com.mz2az.scenetrip.data.LocationConsentRequired
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.LocationDirectionsConsent
import com.mz2az.scenetrip.data.LocationNeed
import com.mz2az.scenetrip.data.LocationRequests
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.RetryInterceptor
import com.mz2az.scenetrip.data.UsageLedger
import com.mz2az.scenetrip.data.VersionedConsentStore
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.data.requestLocation
import com.mz2az.scenetrip.routetab.ExternalDirections
import com.mz2az.scenetrip.routetab.RouteGuideAnchor
import com.mz2az.scenetrip.routetab.RouteNearbySearch
import com.mz2az.scenetrip.searchtab.MapRegionSource
import com.mz2az.scenetrip.ui.AIConsentCopy
import com.mz2az.scenetrip.ui.LocationConsentCopy
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * MZ2AZ-472 위치기반서비스 이용 동의와 카카오 제공 알림. iOS `LocationConsentTests`·`LocationSourceTests` 를 옮겼다.
 * 실제 소켓과 navigation·guide 계열 호출 없이 가짜 저장소와 끝 인터셉터(전송 대신 요청을 기록한다)만 쓴다.
 */
class LocationConsentTests {
    private val storage = MemoryStorage()
    private val payload = """{"latitude":37.5,"longitude":127,"days":2}"""

    // --- 동의 흐름 ---

    @Test
    fun cancelRevokeAndVersionRequireConsentAgain() {
        val store = LocationConsentStore(storage)
        val gate = AIConsentGate(store)
        var runs = 0
        gate.request { runs += 1 }
        assertTrue(gate.isPresented)
        gate.cancel()
        gate.didDismiss()
        assertEquals(0, runs)
        gate.request { runs += 1 }
        gate.allow()
        gate.didDismiss()
        gate.didDismiss()
        assertEquals(1, runs)
        assertTrue(LocationConsentStore(storage).isAllowed)
        gate.setEnabled(false)
        gate.request { runs += 1 }
        assertTrue(gate.isPresented)
        assertEquals(1, runs)
        store.allow()
        assertFalse(LocationConsentStore(storage, version = 2).isAllowed, "버전이 바뀌면 이전 허용은 새 동의가 아니다")
    }

    @Test
    fun cancelHookRunsOnlyWhenSheetClosesWithoutApproval() {
        val store = LocationConsentStore(storage)
        val gate = AIConsentGate(store)
        var cancelled = 0
        gate.onCancel = { cancelled += 1 }
        gate.didDismiss()
        assertEquals(0, cancelled, "시트가 열린 적이 없으면 취소가 아니다")
        gate.request {}
        gate.cancel() // 취소 단추·바깥 누르기·끌어 내리기
        assertEquals(0, cancelled, "시트가 사라진 뒤에 부른다")
        gate.didDismiss()
        gate.didDismiss()
        assertEquals(1, cancelled)
        var proceeded = 0
        gate.request { proceeded += 1 }
        gate.allow()
        gate.didDismiss()
        assertEquals(1, proceeded)
        assertEquals(1, cancelled, "허용하면 취소 훅은 불리지 않는다")
        // 허용 뒤 시트가 닫히기 전에 철회돼도 사용자가 취소한 것은 아니다.
        gate.setEnabled(false)
        gate.request {}
        gate.allow()
        store.revoke()
        gate.didDismiss()
        assertEquals(1, cancelled)
    }

    @Test
    fun deviceCoordinatesAskButUserSelectedOnesDoNot() {
        val gate = AIConsentGate(LocationConsentStore(storage))
        var user = 0
        gate.requestLocation(CoordinateSource.USER_SELECTED) { user += 1 }
        assertEquals(1, user)
        assertFalse(gate.isPresented, "사용자가 고른 지도·목적지는 위치 동의가 필요 없다")
        var device = 0
        gate.requestLocation(CoordinateSource.DEVICE) { device += 1 }
        assertEquals(0, device)
        assertTrue(gate.isPresented)
        gate.allow()
        gate.didDismiss()
        assertEquals(1, device)
    }

    @Test
    fun storedKeyAndVersionAreIndependentOfAi() {
        val ai = AIConsentStore(storage)
        val location = LocationConsentStore(storage)
        ai.allow()
        assertFalse(location.isAllowed, "AI 동의가 위치 동의가 되지 않는다")
        location.allow()
        assertEquals(1, storage.getInt("scenetrip.locationConsentVersion"), "iOS 와 같은 키·버전")
        assertTrue(LocationConsentStore(storage).isAllowed)
        location.revoke()
        assertEquals(0, storage.getInt("scenetrip.locationConsentVersion"))
        assertTrue(ai.isAllowed, "위치를 끄면 AI 동의는 그대로다")
        ai.revoke()
        location.allow()
        assertTrue(location.isAllowed, "AI 를 끄면 위치 동의는 그대로다")
    }

    // --- 요청 계층 ---

    @Test
    fun requiredCoordinateRequestsNeverReachTransportWithoutConsent() {
        val store = LocationConsentStore(storage)
        for (path in listOf("/guide/chat", "/navigation/next-leg", "/navigation/route")) {
            val blocked = Wire()
            assertThrows(LocationConsentRequired::class.java) { blocked.client(store).post(path, payload) }
            assertEquals(0, blocked.requests.size, "동의 전에는 $path 가 나가지 않는다")
            store.allow()
            val allowed = Wire()
            assertEquals(200, allowed.client(store).post(path, payload).code)
            assertEquals(1, allowed.requests.size)
            store.revoke()
            val revoked = Wire()
            assertThrows(LocationConsentRequired::class.java) { revoked.client(store).post(path, payload) }
            assertEquals(0, revoked.requests.size, "철회 뒤에는 다시 막힌다")
        }
    }

    @Test
    fun optionalPlanCoordinatesAreRemovedButNothingElseChanges() {
        val store = LocationConsentStore(storage)
        val wire = Wire()
        assertEquals(200, wire.client(store).post("/guide/plan", payload).code)
        assertEquals("""{"days":2}""", wire.requests.single().bodyText(), "좌표만 빠지고 나머지 값의 서식은 그대로다")
        val nested = Wire()
        nested.client(store).post("/guide/plan", """{"titles":["도깨비"],"days":3,"pace":"packed","latitude":37.5,"longitude":127.0}""")
        assertEquals("""{"titles":["도깨비"],"days":3,"pace":"packed"}""", nested.requests.single().bodyText())
        val noCoordinates = Wire()
        noCoordinates.client(store).post("/guide/plan", """{"days":2}""")
        assertEquals("""{"days":2}""", noCoordinates.requests.single().bodyText())
        store.allow()
        val allowed = Wire()
        allowed.client(store).post("/guide/plan", payload)
        assertEquals(payload, allowed.requests.single().bodyText(), "동의하면 그대로 나간다")
        store.revoke()
        val revoked = Wire()
        revoked.client(store).post("/guide/plan", payload)
        assertEquals("""{"days":2}""", revoked.requests.single().bodyText(), "철회 뒤에는 다시 좌표가 빠진다")
    }

    @Test
    fun unreadableOptionalBodyIsNotSentWithoutConsent() {
        val store = LocationConsentStore(storage)
        val wire = Wire()
        assertThrows(LocationConsentRequired::class.java) { wire.client(store).post("/guide/plan", "{latitude:") }
        assertEquals(0, wire.requests.size, "좌표를 뺄 수 없으면 보내지 않는다")
        val oneShot = Wire()
        val request =
            Request
                .Builder()
                .url("$API_BASE/guide/plan")
                .post(
                    object : okhttp3.RequestBody() {
                        override fun contentType() = null

                        override fun writeTo(sink: okio.BufferedSink) {
                            sink.writeUtf8(payload)
                        }

                        override fun isOneShot() = true
                    },
                ).build()
        assertThrows(LocationConsentRequired::class.java) { oneShot.client(store).newCall(request).execute() }
        assertEquals(0, oneShot.requests.size)
    }

    @Test
    fun mapViewportFollowsTheCoordinateSource() {
        val store = LocationConsentStore(storage)
        val query = "?bbox=126,37,127,38&lat=37&lng=127"
        for (path in listOf("/pois", "/places")) {
            val user = Wire()
            CoordinateSources.with(CoordinateSource.USER_SELECTED) { assertEquals(200, user.client(store).get(path + query).code) }
            assertEquals(1, user.requests.size, "사용자가 고른 지도 범위는 기기 좌표가 아니다")
            val device = Wire()
            CoordinateSources.with(CoordinateSource.DEVICE) {
                assertThrows(LocationConsentRequired::class.java) { device.client(store).get(path + query) }
            }
            assertEquals(0, device.requests.size)
            val unmarked = Wire()
            assertThrows(LocationConsentRequired::class.java) { unmarked.client(store).get(path + query) }
            assertEquals(0, unmarked.requests.size, "출처가 없으면 기기 위치로 취급한다")
        }
        store.allow()
        val allowed = Wire()
        assertEquals(200, allowed.client(store).get("/pois$query").code)
        assertEquals(1, allowed.requests.size)
    }

    @Test
    fun requestsWithoutCoordinatesAreUnaffected() {
        val store = LocationConsentStore(storage)
        for (path in listOf("/places", "/places/12", "/pois/9", "/contents", "/courses", "/events")) {
            val wire = Wire()
            assertEquals(200, wire.client(store).get(path).code, path)
            assertEquals(1, wire.requests.size)
        }
        val chat = Wire()
        assertEquals(200, chat.client(store).post("/guide/chat", """{"messages":[]}""").code, "좌표 없는 질문은 위치 동의와 무관하다")
        assertEquals(1, chat.requests.size)
        val userChat = Wire()
        CoordinateSources.with(CoordinateSource.USER_SELECTED) {
            assertEquals(200, userChat.client(store).post("/guide/chat", payload).code, "지도·목적지 좌표 질문은 AI 동의만 필요하다")
        }
        assertEquals(1, userChat.requests.size)
        val deviceChat = Wire()
        assertThrows(LocationConsentRequired::class.java) { deviceChat.client(store).post("/guide/chat", payload) }
    }

    @Test
    fun classifiesNeedsLikeIos() {
        fun need(
            path: String,
            body: String? = null,
            source: CoordinateSource = CoordinateSource.DEVICE,
        ): LocationNeed {
            val builder = Request.Builder().url("$API_BASE$path")
            return LocationRequests.need(if (body == null) builder.build() else builder.post(body.toRequestBody()).build(), source)
        }
        assertEquals(LocationNeed.REQUIRED, need("/navigation/next-leg", payload))
        assertEquals(LocationNeed.REQUIRED, need("/navigation/next-leg", payload, CoordinateSource.USER_SELECTED))
        assertEquals(LocationNeed.REQUIRED, need("/guide/chat", payload))
        assertEquals(LocationNeed.NONE, need("/guide/chat", payload, CoordinateSource.USER_SELECTED))
        assertEquals(LocationNeed.NONE, need("/guide/chat", """{"messages":[]}"""))
        assertEquals(LocationNeed.OPTIONAL, need("/guide/plan", payload))
        assertEquals(LocationNeed.OPTIONAL, need("/guide/plan", payload, CoordinateSource.USER_SELECTED))
        assertEquals(LocationNeed.NONE, need("/guide/plan", """{"days":2}"""))
        assertEquals(LocationNeed.REQUIRED, need("/places?bbox=1,2,3,4"))
        assertEquals(LocationNeed.NONE, need("/places?bbox=1,2,3,4", source = CoordinateSource.USER_SELECTED))
        assertEquals(LocationNeed.NONE, need("/places?q=도깨비"))
        assertEquals(LocationNeed.REQUIRED, need("/pois?lat=37&lng=127"))
        assertEquals(LocationNeed.NONE, need("/pois/3"))
    }

    @Test
    fun revocationAfterConsentCheckPreventsTransmissionStart() {
        val store = LocationConsentStore(storage)
        for (path in listOf("/navigation/next-leg", "/guide/chat")) {
            store.allow()
            var injected = false
            val wire = Wire()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        LocationConsentInterceptor(store, afterFirstCheck = {
                            injected = true
                            store.revoke()
                        }),
                    ).addInterceptor(wire.terminal)
                    .build()
            assertThrows(LocationConsentRequired::class.java) { client.post(path, payload) }
            assertTrue(injected, "첫 동의 검사를 통과한 뒤 철회한다")
            assertEquals(0, wire.requests.size, "철회가 끝난 뒤에는 요청을 시작하지 않는다")
        }
    }

    @Test
    fun revocationJustBeforePlanStartStillStripsCoordinates() {
        val store = LocationConsentStore(storage)
        store.allow()
        val wire = Wire()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(LocationConsentInterceptor(store, afterFirstCheck = { store.revoke() }))
                .addInterceptor(wire.terminal)
                .build()
        assertEquals(200, client.post("/guide/plan", payload).code)
        assertEquals("""{"days":2}""", wire.requests.single().bodyText(), "선택 좌표는 철회 직전이어도 새지 않는다")
    }

    @Test
    fun revocationWhileRequestIsOutCancelsItAsConsentError() {
        val store = LocationConsentStore(storage)
        store.allow()
        var cancelled = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(LocationConsentInterceptor(store))
                .addInterceptor { chain ->
                    store.revoke() // 응답을 기다리는 사이에 설정에서 끈다.
                    cancelled = chain.call().isCanceled()
                    throw IOException("Canceled")
                }.build()
        assertThrows(LocationConsentRequired::class.java) { client.post("/navigation/next-leg", payload) }
        assertTrue(cancelled, "이미 나간 호출은 끊는다")
        assertFalse(store.isAllowed)
        store.allow()
        val unrelated = Wire()
        assertEquals(200, unrelated.client(store).get("/courses").code)
        store.revoke() // 끝난 호출·좌표 없는 호출은 등록에 남지 않는다 — 던지지 않는다.
    }

    @Test
    fun revocationWhileWaitingStopsAutomaticGuideResend() {
        val store = LocationConsentStore(storage)
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
                ).addInterceptor(LocationConsentInterceptor(store))
                .addInterceptor(wire.terminal)
                .build()
        val request =
            Request
                .Builder()
                .url("$API_BASE/guide/chat")
                .header("Idempotency-Key", "k-1")
                .post(payload.toRequestBody())
                .build()
        assertThrows(LocationConsentRequired::class.java) { client.newCall(request).execute() }
        assertEquals(1, wire.requests.size, "철회 뒤 재시도는 나가지 않는다")
    }

    @Test
    fun sourceMarkerNestsAndDefaultsToDevice() {
        assertEquals(CoordinateSource.DEVICE, CoordinateSources.current())
        CoordinateSources.with(CoordinateSource.USER_SELECTED) {
            assertEquals(CoordinateSource.USER_SELECTED, CoordinateSources.current())
            CoordinateSources.with(CoordinateSource.DEVICE) { assertEquals(CoordinateSource.DEVICE, CoordinateSources.current()) }
            assertEquals(CoordinateSource.USER_SELECTED, CoordinateSources.current(), "안쪽이 끝나면 바깥 값으로 돌아온다")
        }
        assertEquals(CoordinateSource.DEVICE, CoordinateSources.current(), "호출이 끝나면 표식이 남지 않는다")
        assertThrows(IllegalStateException::class.java) {
            CoordinateSources.with(CoordinateSource.USER_SELECTED) { throw IllegalStateException("boom") }
        }
        assertEquals(CoordinateSource.DEVICE, CoordinateSources.current(), "예외에도 표식을 거둔다")
        var other: CoordinateSource? = null
        CoordinateSources.with(CoordinateSource.USER_SELECTED) {
            Thread { other = CoordinateSources.current() }.also {
                it.start()
                it.join()
            }
        }
        assertEquals(CoordinateSource.DEVICE, other, "다른 스레드의 호출에는 표식이 새지 않는다")
    }

    @Test
    fun retryAndAuthResendKeepTheOriginalSource() {
        val store = LocationConsentStore(storage)
        val wire = Wire()
        var attempts = 0
        val twice =
            Interceptor { chain ->
                // 바깥 계층(재시도·인증 재전송)이 같은 호출 스레드에서 같은 요청을 두 번 흘려보낸다.
                attempts += 1
                chain.proceed(chain.request())
                chain.proceed(chain.request())
            }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(twice)
                .addInterceptor(LocationConsentInterceptor(store))
                .addInterceptor(wire.terminal)
                .build()
        CoordinateSources.with(CoordinateSource.USER_SELECTED) { client.get("/pois?bbox=1,2,3,4&lat=1&lng=2") }
        assertEquals(1, attempts)
        assertEquals(2, wire.requests.size, "재전송도 사용자가 고른 범위로 남는다")
        assertThrows(LocationConsentRequired::class.java) { client.get("/pois?bbox=1,2,3,4&lat=1&lng=2") }
    }

    // --- 카카오 알림과 길 안내 동의 ---

    @Test
    fun kakaoNoticeOncePerUserRequestAndIgnoresOldResponse() {
        val notice = KakaoDirectionsNotice()
        val first = notice.beginRequest()
        notice.succeeded(first)
        assertEquals(first, notice.visibleRequest)
        notice.hide(first)
        notice.succeeded(first) // 위치 관찰·중복 완료는 같은 사용자 요청이다.
        assertNull(notice.visibleRequest)
        val second = notice.beginRequest()
        notice.succeeded(first)
        assertNull(notice.visibleRequest)
        notice.succeeded(second)
        notice.hide(first)
        assertEquals(second, notice.visibleRequest)
    }

    @Test
    fun eachOverlappingSuccessfulRequestStillNotifies() {
        val notice = KakaoDirectionsNotice()
        val first = notice.beginRequest()
        val second = notice.beginRequest()
        notice.succeeded(first)
        assertEquals(first, notice.visibleRequest)
        notice.succeeded(second)
        assertEquals(second, notice.visibleRequest)
        notice.hide(first)
        assertEquals(second, notice.visibleRequest)
        notice.hide(second)
        notice.succeeded(first)
        assertNull(notice.visibleRequest)
    }

    @Test
    fun beginningARequestHidesTheOldNoticeAndNotifiesListeners() {
        val notice = KakaoDirectionsNotice()
        var changes = 0
        val unsubscribe = notice.addListener { changes += 1 }
        val id = notice.beginRequest()
        notice.succeeded(id)
        assertEquals(id, notice.visibleRequest)
        notice.beginRequest()
        assertNull(notice.visibleRequest, "새 요청이 시작되면 이전 알림은 거둔다")
        assertTrue(changes >= 3)
        unsubscribe()
        val before = changes
        notice.succeeded(notice.beginRequest())
        assertEquals(before, changes, "구독을 해제하면 부르지 않는다")
    }

    @Test
    fun directionsConsentAsksOncePerUserRequestAndResumesOnlyTheSameRequest() {
        val store = LocationConsentStore(storage)
        val directions = LocationDirectionsConsent(store)
        directions.beginRequest()
        var resumed = 0
        assertFalse(directions.proceedOrAsk { resumed += 1 })
        assertTrue(directions.gate.isPresented)
        // 위치가 연달아 와도 같은 사용자 요청에서는 다시 묻지 않는다.
        assertFalse(directions.proceedOrAsk { resumed += 1 })
        directions.gate.allow()
        directions.gate.didDismiss()
        assertEquals(1, resumed, "허용되면 한 번만 이어 간다")
        assertTrue(directions.proceedOrAsk { resumed += 1 }, "동의가 있으면 바로 진행한다")
        // 취소하면 이 요청에서는 더 묻지 않고, 다시 시도(새 요청)에서 다시 묻는다.
        store.revoke()
        directions.beginRequest()
        assertFalse(directions.proceedOrAsk { resumed += 1 })
        directions.gate.cancel()
        directions.gate.didDismiss()
        assertFalse(directions.proceedOrAsk { resumed += 1 })
        assertFalse(directions.gate.isPresented)
        assertEquals(1, resumed)
        directions.beginRequest()
        assertFalse(directions.proceedOrAsk { resumed += 1 })
        assertTrue(directions.gate.isPresented)
        // 시트가 떠 있는 동안 새 요청이 시작되면 옛 시트와 대기 동작은 사라진다.
        directions.beginRequest()
        assertFalse(directions.gate.isPresented)
        directions.gate.didDismiss()
        assertEquals(1, resumed)
    }

    @Test
    fun tripLikeFlowCancelRetryAllowNoticeAndRevoke() {
        // TripSession 이 하는 순서 그대로: 요청 시작 → 동의 확인 → (전송) → 성공 알림. 실제 전송은 하지 않는다.
        val store = LocationConsentStore(storage)
        val directions = LocationDirectionsConsent(store)
        var sent = 0

        fun fetch(): Boolean {
            if (!directions.proceedOrAsk { fetch() }) return false
            sent += 1
            directions.notice.succeeded(directions.requestId)
            return true
        }
        directions.beginRequest() // start
        assertFalse(fetch())
        assertTrue(directions.gate.isPresented)
        assertEquals(0, sent)
        assertNull(directions.notice.visibleRequest)
        directions.gate.cancel()
        directions.gate.didDismiss()
        assertFalse(fetch(), "위치 갱신이 와도 같은 요청에서는 다시 묻지 않는다")
        assertEquals(0, sent)
        directions.beginRequest() // 다시 시도
        assertFalse(fetch())
        directions.gate.allow()
        directions.gate.didDismiss()
        assertEquals(1, sent)
        val firstNotice = directions.notice.visibleRequest
        assertNotNull(firstNotice)
        assertTrue(fetch(), "자동 GPS 갱신으로 이어지는 호출은 알림을 늘리지 않는다")
        assertEquals(2, sent)
        assertEquals(firstNotice, directions.notice.visibleRequest)
        directions.notice.hide(firstNotice!!)
        assertTrue(fetch())
        assertNull(directions.notice.visibleRequest, "같은 사용자 요청은 한 번만 알린다")
        store.revoke()
        directions.beginRequest() // 철회 뒤 다시 시도
        assertFalse(fetch())
        assertEquals(3, sent)
        directions.gate.allow()
        directions.gate.didDismiss()
        assertEquals(4, sent)
        assertNotEquals(firstNotice, directions.notice.visibleRequest, "새 사용자 요청은 새로 알린다")
    }

    @Test
    fun latestDestinationRunsOnceAfterTheInFlightRequest() {
        val queue = LatestRequestQueue()
        assertTrue(queue.begin())
        assertFalse(queue.begin(), "진행 중이면 대기한다")
        assertFalse(queue.begin(), "중간 목적지는 최신 하나로 합쳐진다")
        assertTrue(queue.end(), "끝난 뒤 최신 하나를 한 번 더 보낸다")
        assertTrue(queue.begin())
        assertFalse(queue.end(), "대기가 없으면 더 보내지 않는다")
        assertTrue(queue.begin())
        assertFalse(queue.begin())
        queue.clearQueued() // 안내 종료·도착
        assertFalse(queue.end(), "끝났거나 도착했으면 대기 중이던 요청은 보내지 않는다")
    }

    // --- 주변 촬영지·가이드 기준 (MZ2AZ-474 × 472) ---

    @Test
    fun anchorCarriesTheCoordinateSource() {
        val device = 37.5 to 127.0
        val center = 35.2 to 129.1
        // 기기 위치 기준은 늘 기기 출처, 지도 중심은 보이는 범위의 출처를 따른다(기본은 사용자 선택).
        assertEquals(CoordinateSource.DEVICE, RouteGuideAnchor.resolve(device, center, preferMap = false)!!.source)
        assertEquals(CoordinateSource.USER_SELECTED, RouteGuideAnchor.resolve(device, center, preferMap = true)!!.source)
        assertEquals(CoordinateSource.USER_SELECTED, RouteGuideAnchor.resolve(null, center, preferMap = false)!!.source)
        assertEquals(
            CoordinateSource.DEVICE,
            RouteGuideAnchor.resolve(device, center, preferMap = true, mapSource = CoordinateSource.DEVICE)!!.source,
            "GPS 로 맞춘 지도의 중심은 기기 출처다",
        )
        assertEquals(
            CoordinateSource.DEVICE,
            RouteGuideAnchor.resolve(null, center, preferMap = false, mapSource = CoordinateSource.DEVICE)!!.source,
        )
        assertEquals(CoordinateSource.DEVICE, RouteGuideAnchor(1.0, 2.0, isMapCenter = false).source, "출처를 빠뜨리면 기기로 본다")
    }

    @Test
    fun nearbySearchRunsMapCenterWithoutConsentAndKeepsTheSourceInTheQuery() =
        runBlocking {
            val queries = mutableListOf<RouteNearbySearch.Query>()
            val search =
                RouteNearbySearch(fetch = {
                    queries += it
                    emptyList()
                }, locationAllowed = { false })
            search.load(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
            assertEquals(listOf(CoordinateSource.USER_SELECTED), queries.map { it.source }, "출처는 조회까지 보존된다")
        }

    @Test
    fun nearbySearchWithDeviceSourceNeedsConsentAndDoesNotCallTheServer() =
        runBlocking {
            var calls = 0
            var allowed = false
            val search =
                RouteNearbySearch(fetch = {
                    calls += 1
                    emptyList()
                }, locationAllowed = { allowed })
            val device = RouteGuideAnchor(37.5, 127.0, isMapCenter = false)
            search.load(device)
            assertEquals(RouteNearbySearch.Phase.NEEDS_CONSENT, search.state.phase)
            assertEquals(0, calls, "동의 없이는 조회하지 않는다")
            // GPS 로 맞춘 지도 중심도 기기 출처다.
            search.load(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.DEVICE))
            assertEquals(RouteNearbySearch.Phase.NEEDS_CONSENT, search.state.phase)
            allowed = true
            search.load(device)
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
            assertEquals(1, calls)
            allowed = false // 철회
            search.load(device)
            assertEquals(RouteNearbySearch.Phase.NEEDS_CONSENT, search.state.phase)
            assertEquals(1, calls)
        }

    @Test
    fun nearbyTransportBlockedByRevocationShowsNeedsConsent() =
        runBlocking {
            // UI 검사를 지난 뒤 전송 계층이 막은 경우도 실패 화면이 아니라 동의 안내가 된다.
            val search =
                RouteNearbySearch(fetch = { throw RuntimeException("wrapped", LocationConsentRequired()) }, locationAllowed = { true })
            search.load(RouteGuideAnchor(37.5, 127.0, isMapCenter = false))
            assertEquals(RouteNearbySearch.Phase.NEEDS_CONSENT, search.state.phase)
        }

    @Test
    fun nearbyBboxRequestFollowsItsSourceThroughTheTransport() {
        val store = LocationConsentStore(storage)
        val map = RouteNearbySearch.Query.of(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
        val device = RouteNearbySearch.Query.of(RouteGuideAnchor(37.5, 127.0, isMapCenter = false))
        val userWire = Wire()
        CoordinateSources.with(map.source) { userWire.client(store).get("/places?bbox=${map.bbox}&limit=40") }
        assertEquals(1, userWire.requests.size, "지도 중심(사용자 선택)은 동의 없이 나간다")
        val deviceWire = Wire()
        CoordinateSources.with(device.source) {
            assertThrows(LocationConsentRequired::class.java) { deviceWire.client(store).get("/places?bbox=${device.bbox}&limit=40") }
        }
        assertEquals(0, deviceWire.requests.size, "내 위치 기준은 동의 없이 나가지 않는다")
        store.allow()
        CoordinateSources.with(device.source) { deviceWire.client(store).get("/places?bbox=${device.bbox}&limit=40") }
        assertEquals(1, deviceWire.requests.size)
    }

    @Test
    fun guideQuestionAsksLocationConsentOnlyForDeviceCoordinates() {
        val store = LocationConsentStore(storage)
        val gate = AIConsentGate(store)
        var asked = 0
        // 지도 중심 앵커(사용자 선택): AI 동의만 필요 — 위치 시트 없음, 전송 계층도 통과.
        gate.requestLocation(CoordinateSource.USER_SELECTED) { asked += 1 }
        assertEquals(1, asked)
        assertFalse(gate.isPresented)
        val userWire = Wire()
        CoordinateSources.with(CoordinateSource.USER_SELECTED) {
            assertEquals(200, userWire.client(store).post("/guide/chat", payload).code)
        }
        // 기기 앵커: 위치 시트 → 허용 뒤에만 진행.
        gate.requestLocation(CoordinateSource.DEVICE) { asked += 1 }
        assertTrue(gate.isPresented)
        assertEquals(1, asked)
        gate.allow()
        gate.didDismiss()
        assertEquals(2, asked)
    }

    // --- 문구·링크·화면 규칙 ---

    @Test
    fun copyMatchesIosAndHasEnglish() {
        assertEquals("위치기반서비스 이용 동의", LocationConsentCopy.TITLE_KO)
        assertEquals(
            "현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. " +
                "위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다.",
            LocationConsentCopy.BODY_KO,
        )
        assertEquals("위치기반서비스 이용약관", LocationConsentCopy.LINK_KO)
        assertEquals("동의하고 계속", LocationConsentCopy.ALLOW_KO)
        assertEquals("취소", AIConsentCopy.CANCEL_KO)
        assertEquals("위치기반서비스 이용", LocationConsentCopy.TOGGLE_KO)
        assertEquals("끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.", LocationConsentCopy.FOOTNOTE_KO)
        assertEquals("길 안내를 위해 카카오에 현재 위치를 보냈어요.", LocationConsentCopy.KAKAO_NOTICE_KO)
        assertEquals("위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.", LocationConsentRequired.MESSAGE_KO)
        for (key in LocationConsentCopy.copyKeys) {
            val english = englishOf(key)
            assertNotNull(english, key)
            assertNotEquals(key, english, key)
        }
        assertEquals(
            "We use your current location to find nearby filming locations and places, for the AI guide, and for directions. " +
                "It is used only to handle the request and deleted right away; for directions it is sent to Kakao to calculate the route.",
            englishOf(LocationConsentCopy.BODY_KO),
        )
        assertEquals("Your current location was sent to Kakao to calculate directions.", englishOf(LocationConsentCopy.KAKAO_NOTICE_KO))
        assertEquals("Location-based service consent", englishOf(LocationConsentCopy.TITLE_KO))
        assertEquals("Location-based services", englishOf(LocationConsentCopy.TOGGLE_KO))
        assertEquals("Please agree to location-based services and try again.", englishOf(LocationConsentRequired.MESSAGE_KO))
    }

    @Test
    fun locationTermsLinkIsOurHttpsPageWithItsAnchor() {
        assertEquals("https://scenetrip.io/terms#location-services", LegalLinks.LOCATION_SERVICES)
        val uri = java.net.URI(LegalLinks.LOCATION_SERVICES)
        assertEquals("https", uri.scheme)
        assertEquals("scenetrip.io", uri.host)
        assertEquals("/terms", uri.path)
        assertEquals("location-services", uri.fragment)
        assertNull(uri.userInfo)
        assertNotNull(
            com.mz2az.scenetrip.data.WebAddress
                .url(LegalLinks.LOCATION_SERVICES),
            "외부 브라우저로 열 수 있는 주소다",
        )
    }

    @Test
    fun consentFailureShowsAgreeMessageNotConnectionError() {
        val error = LocationConsentRequired()
        val wrapped = RuntimeException("wrapped", error)
        for (failure in listOf(NetworkFailure.of(error), NetworkFailure.of(wrapped))) {
            assertEquals(LocationConsentRequired.CODE, failure.code)
            assertNull(failure.status)
            assertEquals(LocationConsentRequired.MESSAGE_KO, failure.message)
        }
        val api = ApiFailure.of(wrapped)
        assertEquals(LocationConsentRequired.MESSAGE_KO, api.message)
        assertTrue(api.isRetryable, "동의한 뒤 같은 요청을 다시 해 볼 수 있다")
    }

    @Test
    fun externalMapLinksNeverCarryTheCurrentPosition() {
        val destination = ExternalDirections.Spot("경복궁", 37.5796, 126.977)
        assertNull(ExternalDirections.kakaoApp(null, destination), "앱 스킴은 출발지가 있어야 쓴다 — 현재 좌표를 붙이지 않는다")
        assertNull(ExternalDirections.naver(null, destination))
        val web = ExternalDirections.kakaoWeb(null, destination)!!
        assertEquals("https://map.kakao.com/link/to/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579600,126.977000", web)
        assertFalse(web.contains("/by/"))
        // 출발지를 아는 꼴(저장한 장소 등)은 그대로 만든다.
        val start = ExternalDirections.Spot("출발", 37.5663, 126.9779)
        assertTrue(ExternalDirections.kakaoApp(start, destination)!!.startsWith("kakaomap://route?sp=37.566300,126.977900&ep="))
        assertTrue(ExternalDirections.naver(start, destination)!!.startsWith("nmap://route/public?slat=37.566300"))
        assertNull(ExternalDirections.kakaoApp(start.copy(longitude = Double.NaN), destination))
    }

    @Test
    fun mapRegionSourceFollowsTheCameraReason() {
        // 네이버 SDK 의 CameraUpdate.REASON_* 값: DEVELOPER 0, GESTURE -1, CONTROL -2, LOCATION -3, CONTENT_PADDING -4.
        val region = MapRegionSource()
        assertEquals(CoordinateSource.USER_SELECTED, region.source, "처음 범위(남한 전체)는 사용자가 고른 것이다")
        region.cameraWillChange(-3)
        assertEquals(CoordinateSource.DEVICE, region.source, "위치 추적이 옮긴 카메라는 기기 위치다")
        region.cameraWillChange(0)
        region.cameraWillChange(-4)
        assertEquals(CoordinateSource.DEVICE, region.source, "코드가 옮기거나 여백만 바뀐 카메라는 출처를 바꾸지 않는다")
        region.cameraWillChange(-1)
        assertEquals(CoordinateSource.USER_SELECTED, region.source, "손으로 밀면 사용자가 고른 영역이다")
        region.source = CoordinateSource.DEVICE
        region.cameraWillChange(-2)
        assertEquals(CoordinateSource.USER_SELECTED, region.source, "확대·축소 컨트롤도 사용자 선택이다")
    }

    @Test
    fun requestsForAnotherConsentStoreDoNotReuseLocationFlights() {
        // 위치 인터셉터는 위치 저장소의 호출만 등록한다 — AI 동의를 철회해도 나가 있는 길 안내는 끊기지 않는다.
        val location = LocationConsentStore(storage)
        val ai = AIConsentStore(storage)
        location.allow()
        ai.allow()
        var cancelled = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(LocationConsentInterceptor(location))
                .addInterceptor { chain ->
                    ai.revoke()
                    cancelled = chain.call().isCanceled()
                    response(chain.request(), 200)
                }.build()
        assertEquals(200, client.post("/navigation/next-leg", payload).code)
        assertFalse(cancelled)
        assertTrue(location.isAllowed)
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

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    /** 소켓 대신 나가는 요청을 기록한다. */
    private inner class Wire(
        private val code: Int = 200,
    ) {
        val requests = mutableListOf<Request>()

        val terminal =
            Interceptor { chain ->
                requests += chain.request()
                response(chain.request(), code)
            }

        fun client(store: VersionedConsentStore): OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(LocationConsentInterceptor(store))
                .addInterceptor(terminal)
                .build()
    }

    private fun OkHttpClient.post(
        path: String,
        body: String,
    ): Response =
        newCall(
            Request
                .Builder()
                .url("$API_BASE$path")
                .post(body.toRequestBody())
                .build(),
        ).execute()

    private fun OkHttpClient.get(path: String): Response = newCall(Request.Builder().url("$API_BASE$path").build()).execute()
}

private class MemoryStorage : AIConsentStorage {
    private val values = mutableMapOf<String, Int>()

    override fun getInt(key: String): Int = values[key] ?: 0

    override fun setInt(
        key: String,
        value: Int,
    ) {
        values[key] = value
    }
}
