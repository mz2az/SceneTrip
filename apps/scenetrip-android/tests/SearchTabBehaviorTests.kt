package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ServerException
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.searchtab.MapCoordinate
import com.mz2az.scenetrip.searchtab.NearbyLabel
import com.mz2az.scenetrip.searchtab.PinCaption
import com.mz2az.scenetrip.searchtab.PinSpread
import com.mz2az.scenetrip.searchtab.RadarPulseShape
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import kotlin.math.abs

/** MZ2AZ-479: 검색 탭 동작이 iOS 와 같은 규칙을 따르는지. */
class SearchTabBehaviorTests {
    // MARK: 핀 이름표 (iOS PinCaption)

    @Test
    fun shortPinCaptionIsKeptAndLongOnesAreCutToTheHead() {
        assertEquals("여의도공원", PinCaption.text("  여의도공원 \n"))
        val exact = "a".repeat(PinCaption.LIMIT)
        assertEquals(exact, PinCaption.text(exact))
        val long = "Korea National University of Arts Seokgwan-dong Campus Main Hall"
        val cut = PinCaption.text(long)
        assertTrue(cut.endsWith("…"))
        assertEquals(PinCaption.LIMIT, cut.codePointCount(0, cut.length))
        assertEquals("Korea National University ", cut.removeSuffix("…").take(26))
    }

    @Test
    fun pinCaptionDropsTheSpaceBeforeTheEllipsis() {
        // 27 번째 글자가 공백이면 「… 」 가 아니라 「…」 로 닫는다.
        val name = "a".repeat(26) + " " + "b".repeat(10)
        assertEquals("a".repeat(26) + "…", PinCaption.text(name))
    }

    // MARK: 같은 좌표 핀 펼침 (iOS Coordinator.spread)

    @Test
    fun pinsAtDistinctCoordinatesStayWhereTheyAre() {
        val pins = listOf(MapCoordinate(37.5, 127.0), MapCoordinate(37.6, 127.1))
        assertEquals(pins, PinSpread.spread(pins))
    }

    @Test
    fun pinsOnTheSameCoordinateAreSpreadAboutEightMetersAndKeepTheirOrder() {
        val same = MapCoordinate(37.5665, 126.9780)
        val other = MapCoordinate(35.1796, 129.0756)
        val pins = listOf(same, other, same, same)
        val spread = PinSpread.spread(pins)
        assertEquals(pins.size, spread.size)
        assertEquals(other, spread[1])
        val group = listOf(spread[0], spread[2], spread[3])
        assertEquals(3, group.toSet().size)
        for (pin in group) {
            val north = (pin.latitude - same.latitude) * 111_000.0
            val east = (pin.longitude - same.longitude) * 111_000.0 * Math.cos(Math.toRadians(same.latitude))
            assertEquals(8.0, Math.hypot(north, east), 0.01)
        }
        // 첫 번째는 위쪽(북)에서 시작한다.
        assertTrue(spread[0].latitude > same.latitude)
        assertTrue(abs(spread[0].longitude - same.longitude) < 1e-9)
    }

    // MARK: 주변 검색 단추 글자 (iOS nearbyCountLabel)

    @Test
    fun nearbyLabelShowsHowManyOfTheTotalWereLoaded() {
        assertEquals("이 지도에서 12곳 · 해제", NearbyLabel.text(12, 12))
        assertEquals("이 지도에서 486곳 중 200곳 · 해제", NearbyLabel.text(200, 486))
    }

    // MARK: 레이더 파문 (iOS RadarPulse)

    @Test
    fun radarWavesAreStaggeredAndFadeOutAsTheyGrow() {
        val starts = (0 until RadarPulseShape.WAVES).map { RadarPulseShape.progress(0, it) }
        assertEquals(3, starts.toSet().size)
        assertEquals(0f, starts[0], 0f)
        assertEquals(RadarPulseShape.START_SCALE, RadarPulseShape.scale(0f), 0f)
        assertEquals(1f, RadarPulseShape.scale(1f), 0f)
        assertEquals(0.9f, RadarPulseShape.opacity(0f), 0f)
        assertEquals(0f, RadarPulseShape.opacity(1f), 0f)
        assertTrue(RadarPulseShape.opacity(0.5f) < 0.9f * 0.5f)
        // 주기가 지나면 같은 자리로 돌아온다.
        assertEquals(RadarPulseShape.progress(500, 1), RadarPulseShape.progress(500 + RadarPulseShape.PERIOD_MS, 1), 1e-6f)
    }

    // MARK: ApiFailure (iOS SceneData.swift)

    @Test
    fun retryableFailuresMatchIos() {
        for (code in listOf(429, 500, 502, 503, 504)) assertTrue(ApiFailure(code).isRetryable, "$code")
        assertTrue(ApiFailure(null).isRetryable)
        for (code in listOf(400, 401, 404, 409)) assertFalse(ApiFailure(code).isRetryable, "$code")
    }

    @Test
    fun failureMessagesMatchIos() {
        assertEquals("인터넷 연결을 확인해 주세요.", ApiFailure(null, isOffline = true).message)
        assertEquals("서버에 연결하지 못했습니다.", ApiFailure(null).message)
        for (code in listOf(500, 502, 503, 504)) assertEquals("잠시 문제가 생겼습니다.", ApiFailure(code).message, "$code")
        assertEquals("요청이 많아요. 잠시 뒤 다시 시도해 주세요.", ApiFailure(429).message)
        assertEquals("요청을 처리하지 못했습니다.", ApiFailure(404).message)
    }

    @Test
    fun offlineMeansTheAddressCouldNotBeResolved() {
        assertTrue(ApiFailure.of(UnknownHostException("api")).isOffline)
        assertTrue(ApiFailure.of(IOException("wrapped", UnknownHostException("api"))).isOffline)
        assertFalse(ApiFailure.of(ConnectException("refused")).isOffline)
        assertEquals("서버에 연결하지 못했습니다.", ApiFailure.of(ConnectException("refused")).message)
        assertEquals("인터넷 연결을 확인해 주세요.", ApiFailure.of(UnknownHostException("api")).message)
    }

    @Test
    fun traceIdIsKeptFromServerErrorBodies() {
        val body = "{\"code\":\"INTERNAL_ERROR\",\"message\":\"x\",\"traceId\":\"4bf92f3577b34da6\"}"
        val server = ServerException("오류", 500, ServerError<Unit>(body = body, statusCode = 500, headers = emptyMap()))
        val failure = ApiFailure.of(server)
        assertEquals(500, failure.statusCode)
        assertEquals("4bf92f3577b34da6", failure.traceId)
        val client =
            ClientException("오류", 404, ClientError<Unit>(body = "{\"code\":\"NOT_FOUND\"}", statusCode = 404, headers = emptyMap()))
        assertNull(ApiFailure.of(client).traceId)
        assertNull(ApiFailure.of(IOException("x")).traceId)
    }

    // MARK: 장바구니 (iOS CartStore + AccountDataReset)

    @Test
    fun cartIgnoresAnswersThatArriveAfterTheAccountChanged() =
        runBlocking {
            var epoch = 1
            val client =
                object : CartClient {
                    override fun fetch(): List<CartItem> {
                        epoch += 1 // 요청이 가 있는 동안 계정이 바뀌었다.
                        return listOf(item(7))
                    }

                    override fun add(
                        placeId: Long,
                        sourceContentId: Long?,
                    ) {
                        epoch += 1
                    }

                    override fun remove(placeId: Long) {
                        epoch += 1
                    }
                }
            val cart = CartSession(client, epoch = { epoch })
            cart.refresh()
            assertTrue(cart.items.isEmpty())
            assertFalse(cart.contains(7))

            assertFalse(cart.add(9))
            assertFalse(cart.contains(9))
            assertNull(cart.toast)

            cart.remove(9)
            assertNull(cart.toast)
        }

    @Test
    fun cartResetClearsTheOldAccountsItems() =
        runBlocking {
            val client =
                object : CartClient {
                    override fun fetch(): List<CartItem> = listOf(item(7))

                    override fun add(
                        placeId: Long,
                        sourceContentId: Long?,
                    ) = Unit

                    override fun remove(placeId: Long) = Unit
                }
            val cart = CartSession(client)
            cart.refresh()
            assertTrue(cart.contains(7))
            cart.reset()
            assertTrue(cart.items.isEmpty())
            assertFalse(cart.contains(7))
        }

    private fun item(id: Long) = CartItem(placeId = id, name = "장소 $id", addedAt = OffsetDateTime.parse("2026-10-16T00:00:00+09:00"))
}
