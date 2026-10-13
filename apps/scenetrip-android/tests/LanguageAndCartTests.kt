package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.SceneLanguageInterceptor
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class LanguageAndCartTests {
    @Test
    fun generatedSearchDetailAndCoursesAlwaysUseTheLatestUiLanguage() {
        var language = Lang.ko
        val seen = mutableListOf<String?>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(SceneLanguageInterceptor { language })
                .addInterceptor { chain ->
                    seen += chain.request().header("Accept-Language")
                    val json =
                        when {
                            chain
                                .request()
                                .url.encodedPath
                                .endsWith("/places/8") -> {
                                """{"id":8,"name":"Place","latitude":37.5,"longitude":127.0,"contents":[],"scenes":[]}"""
                            }

                            chain
                                .request()
                                .url.encodedPath
                                .endsWith("/contents") -> {
                                """{"items":[],"total":0,"limit":20,"offset":0}"""
                            }

                            else -> {
                                """{"items":[],"totalCount":0}"""
                            }
                        }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("ok")
                        .body(json.toResponseBody())
                        .build()
                }.build()
        repeat(2) {
            ContentsApi("http://localhost/v1", client).listContents(acceptLanguage = Lang.ko)
            PlacesApi("http://localhost/v1", client).getPlace(8, acceptLanguage = Lang.ko)
            CoursesApi("http://localhost/v1", client).listCourses(UUID(0, 1), acceptLanguage = Lang.ko)
            language = Lang.en
        }
        assertEquals(listOf("ko", "ko", "ko", "en", "en", "en"), seen)
    }

    @Test
    fun errorsUseCodesAndNeverExposeServerSentences() {
        val reply =
            ClientError<Unit>(
                body = """{"code":"REVIEW_PHOTO_INVALID","message":"서버에서 내려온 문장"}""",
                statusCode = 400,
                headers = emptyMap(),
            )
        val failure = NetworkFailure.of(ClientException("서버에서 내려온 문장", 400, reply))
        assertEquals("REVIEW_PHOTO_INVALID", failure.code)
        assertFalse(failure.message.contains("서버에서 내려온 문장"))
    }

    @Test
    fun cartKeepsTheWorkSourceWhileAddingRefreshingAndRemoving() =
        runBlocking {
            val added = OffsetDateTime.parse("2026-10-13T00:00:00Z")
            var items = emptyList<CartItem>()
            var received: Pair<Long, Long?>? = null
            val client =
                object : CartClient {
                    override fun fetch() = items

                    override fun add(
                        placeId: Long,
                        sourceContentId: Long?,
                    ) {
                        received = placeId to sourceContentId
                        items = listOf(CartItem(placeId, "Place", added, sourceContentId = sourceContentId))
                    }

                    override fun remove(placeId: Long) {
                        items = items.filterNot { it.placeId == placeId }
                    }
                }
            val cart = CartSession(client)
            assertTrue(cart.add(227, 33))
            assertEquals(227L to 33L, received)
            assertEquals(33L, cart.items.single().sourceContentId)
            cart.remove(227)
            assertTrue(cart.items.isEmpty())
            assertFalse(cart.contains(227))
        }
}
