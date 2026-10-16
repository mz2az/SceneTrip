package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartLoadState
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.CartHintStorage
import com.mz2az.scenetrip.routetab.CartOutcome
import com.mz2az.scenetrip.routetab.PlannerExit
import com.mz2az.scenetrip.routetab.RouteCartHint
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteGuideAnchor
import com.mz2az.scenetrip.routetab.RouteGuideStartCopy
import com.mz2az.scenetrip.routetab.RouteNearbySearch
import com.mz2az.scenetrip.routetab.RouteNearbyState
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

/** MZ2AZ-474 C3~C6 — 빈 코스 첫 안내와 모델 없는 주변 촬영지(iOS MZ2AZ-444, `RouteCartHintTests`·`RouteNearbySearchTests`). */
class FirstCourseGuideTests {
    private val center = 35.2 to 129.1
    private val device = 37.5 to 127.0

    // MARK: 가이드 기준 (RouteGuideAnchor)

    @Test
    fun anchorPrefersDeviceLocationUnlessMapIsChosenAndFallsBackToMapCenter() {
        val gps = RouteGuideAnchor.resolve(current = device, mapCenter = center, preferMap = false)
        assertEquals(RouteGuideAnchor(37.5, 127.0, isMapCenter = false), gps)
        val map = RouteGuideAnchor.resolve(current = device, mapCenter = center, preferMap = true)
        assertEquals(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED), map)
        // 위치를 못 받았거나 거절해도 지도 중심이 있으면 가이드를 쓸 수 있다.
        assertEquals(
            RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED),
            RouteGuideAnchor.resolve(null, center, preferMap = false),
        )
        assertNull(RouteGuideAnchor.resolve(null, null, preferMap = false))
        // preferMap 이어도 지도 중심이 없으면 기기 위치다.
        assertEquals(gps, RouteGuideAnchor.resolve(device, null, preferMap = true))
    }

    @Test
    fun anchorIgnoresOutOfRangeCoordinates() {
        assertNull(RouteGuideAnchor.resolve(91.0 to 0.0, Double.NaN to 10.0, preferMap = false))
        assertEquals(
            RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED),
            RouteGuideAnchor.resolve(0.0 to 181.0, center, preferMap = false),
        )
    }

    // MARK: 지도 주변 촬영지 (RouteNearbySearch)

    @Test
    fun searchBoundsAreCenteredOnChosenMapPosition() {
        val query = RouteNearbySearch.Query.of(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
        val bounds = query.bbox.split(",").map { it.toDouble() }
        assertEquals(4, bounds.size)
        // minLng, minLat, maxLng, maxLat — GeoJSON 순서(계약).
        assertEquals(129.1, (bounds[0] + bounds[2]) / 2, 0.000_001)
        assertEquals(35.2, (bounds[1] + bounds[3]) / 2, 0.000_001)
        assertTrue(bounds[0] < 129.1 && bounds[1] < 35.2 && bounds[2] > 129.1 && bounds[3] > 35.2)
        // 남북 약 6km(±3km).
        assertEquals(6.0 / 111.0, bounds[3] - bounds[1], 0.0001)
    }

    @Test
    fun searchBoundsStayInsideValidRangeNearThePoles() {
        val bounds =
            RouteNearbySearch.Query
                .of(RouteGuideAnchor(89.99, 179.99, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
                .bbox
                .split(",")
                .map { it.toDouble() }
        assertTrue(bounds[2] <= 180.0 && bounds[3] <= 90.0)
    }

    @Test
    fun mapCenterSearchLoadsPlacesWithoutAnyAiSession() =
        runBlocking {
            val queries = mutableListOf<RouteNearbySearch.Query>()
            val seen = mutableListOf<RouteNearbyState>()
            val search =
                RouteNearbySearch(
                    fetch = {
                        queries += it
                        listOf(place(42))
                    },
                    changed = { seen += it },
                )
            search.load(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
            assertEquals(listOf(35.2), queries.map { it.centerLatitude })
            assertEquals(listOf(129.1), queries.map { it.centerLongitude })
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
            assertEquals(listOf(42L), search.state.places.map { it.id })
            assertEquals(listOf(RouteNearbySearch.Phase.LOADING, RouteNearbySearch.Phase.LOADED), seen.map { it.phase })
            assertEquals(40, RouteNearbySearch.LIMIT)
        }

    @Test
    fun nearbyFailureCanBeRetriedWithoutAiAndKeepsCartIndependent() =
        runBlocking {
            var calls = 0
            val search =
                RouteNearbySearch(fetch = {
                    calls += 1
                    if (calls == 1) throw IOException("offline")
                    emptyList()
                })
            val anchor = RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED)
            search.load(anchor)
            assertEquals(RouteNearbySearch.Phase.FAILED, search.state.phase)
            assertNotNull(search.state.failure)
            search.load(anchor)
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
            assertEquals(2, calls)

            // 장바구니 조회는 주변 촬영지 조회와 상태를 나누지 않는 별개 세션이다 — 서로의 실패가 번지지 않는다.
            val cart = CartSession(cartClient(listOf(cartItem(42))))
            cart.refresh()
            assertEquals(CartLoadState.LOADED, cart.state.loadState)
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
        }

    @Test
    fun lateOldMapResponseDoesNotReplaceNewLocation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<List<PlaceSummary>>()
            val search =
                RouteNearbySearch(fetch = { query ->
                    if (query.centerLatitude == 35.2) {
                        started.complete(Unit)
                        reply.await()
                    } else {
                        emptyList()
                    }
                })
            val old = async { search.load(RouteGuideAnchor(35.2, 129.1, isMapCenter = true, source = CoordinateSource.USER_SELECTED)) }
            started.await()
            search.load(RouteGuideAnchor(37.5, 127.0, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
            reply.complete(listOf(place(42)))
            old.await()
            assertEquals(RouteNearbySearch.Phase.LOADED, search.state.phase)
            assertTrue(search.state.places.isEmpty())
        }

    @Test
    fun invalidCoordinateFailsWithoutCallingTheServer() =
        runBlocking {
            var calls = 0
            val search =
                RouteNearbySearch(fetch = {
                    calls += 1
                    emptyList()
                })
            search.load(RouteGuideAnchor(200.0, 0.0, isMapCenter = true, source = CoordinateSource.USER_SELECTED))
            assertEquals(RouteNearbySearch.Phase.FAILED, search.state.phase)
            assertEquals(0, calls)
        }

    // MARK: 장바구니 조회 상태

    @Test
    fun cartLoadStateDistinguishesNotLoadedLoadedAndFailedAndKeepsItemsOnFailure() =
        runBlocking {
            var fail = false
            var items = listOf(cartItem(7))
            val cart =
                CartSession(
                    object : CartClient {
                        override fun fetch(): List<CartItem> = if (fail) throw IOException("offline") else items

                        override fun add(
                            placeId: Long,
                            sourceContentId: Long?,
                        ) = Unit

                        override fun remove(placeId: Long) = Unit
                    },
                )
            assertEquals(CartLoadState.NOT_LOADED, cart.state.loadState)
            cart.refresh()
            assertEquals(CartLoadState.LOADED, cart.state.loadState)
            assertEquals(listOf(7L), cart.items.map { it.placeId })
            fail = true
            cart.refresh()
            // 실패한 조회는 받아 둔 목록을 지우지 않고 실패만 알린다.
            assertEquals(CartLoadState.FAILED, cart.state.loadState)
            assertEquals(listOf(7L), cart.items.map { it.placeId })
            items = emptyList()
        }

    // MARK: 직접 짜기 첫 안내 (RouteCartHint)

    private class MemoryStorage(
        var seenFlag: Boolean = false,
    ) : CartHintStorage {
        override fun seen() = seenFlag

        override fun markSeen() {
            seenFlag = true
        }
    }

    private fun newManualDraft() = RouteCourse(title = "내 코스", days = listOf(RouteDay()))

    @Test
    fun onlyNewManualEmptyCourseConsumesHintAndOnlyOnce() {
        val hint = RouteCartHint(MemoryStorage())
        val draft = newManualDraft()
        // 저장된 코스(상세를 받았든 아니든)·AI 초안·이미 장소가 있는 초안은 대상이 아니다.
        assertFalse(hint.claim(draft.copy(serverId = 42)))
        assertFalse(hint.claim(draft.copy(serverId = 42, detailLoaded = true)))
        assertFalse(hint.claim(draft.copy(madeByAI = true)))
        val filled = draft.copy(days = listOf(RouteDay(stops = listOf(RouteStop(place = place(1))))))
        assertFalse(hint.claim(filled))
        assertTrue(hint.claim(draft))
        assertFalse(hint.claim(draft))
    }

    @Test
    fun seenFlagSurvivesHintRecreation() {
        val storage = MemoryStorage()
        assertTrue(RouteCartHint(storage).claim(newManualDraft()))
        // 표시 시점에 기록하므로 닫기·시간 만료·화면 이탈 뒤에 새로 만들어도 다시 나타나지 않는다.
        assertFalse(RouteCartHint(storage).claim(newManualDraft()))
    }

    @Test
    fun cartCopyDependsOnSavedPlaces() {
        assertEquals("장소를 담으면 여기서 코스에 넣을 수 있어요", RouteCartHint.messageKey(cartIsEmpty = true))
        assertEquals("담아둔 장소로 코스를 시작해 보세요", RouteCartHint.messageKey(cartIsEmpty = false))
    }

    @Test
    fun slowNonEmptyCartShowsSavedPlacesCopy() =
        assertDelayedCartHint(CartOutcome(CartLoadState.LOADED, isEmpty = false), cartIsEmpty = false)

    @Test
    fun slowEmptyCartShowsEmptyCopyOnlyAfterSuccess() =
        assertDelayedCartHint(CartOutcome(CartLoadState.LOADED, isEmpty = true), cartIsEmpty = true)

    @Test
    fun failedCartLoadShowsGenericCopyEvenThoughTheListIsEmpty() =
        assertDelayedCartHint(CartOutcome(CartLoadState.FAILED, isEmpty = true), cartIsEmpty = false)

    private fun assertDelayedCartHint(
        outcome: CartOutcome,
        cartIsEmpty: Boolean,
    ) = runBlocking {
        val storage = MemoryStorage()
        val states = mutableListOf<com.mz2az.scenetrip.routetab.RouteCartHintState>()
        val hint = RouteCartHint(storage) { states += it }
        val reply = CompletableDeferred<CartOutcome>()
        val windowStarted = CompletableDeferred<Unit>()
        val finishWindow = CompletableDeferred<Unit>()
        val announcements = mutableListOf<String>()
        val durations = mutableListOf<Long>()
        val presentation =
            async {
                hint.present(
                    course = newManualDraft(),
                    cart = { reply.await() },
                    announce = { announcements += it },
                    sleep = {
                        durations += it
                        windowStarted.complete(Unit)
                        finishWindow.await()
                    },
                )
            }
        // 조회가 끝나기 전에는 링만 켜져 있다 — 문구·낭독·기록·노출 시간이 시작되지 않는다.
        while (!hint.state.isHighlighted) kotlinx.coroutines.yield()
        assertNull(hint.state.message)
        assertTrue(announcements.isEmpty())
        assertTrue(durations.isEmpty())
        assertFalse(storage.seenFlag)

        reply.complete(outcome)
        windowStarted.await()
        val expected = tr(RouteCartHint.messageKey(cartIsEmpty))
        assertEquals(expected, hint.state.message)
        assertEquals(listOf(expected), announcements)
        assertEquals(listOf(2500L), durations)
        assertTrue(storage.seenFlag)
        assertTrue(hint.state.isHighlighted)

        finishWindow.complete(Unit)
        presentation.await()
        assertNull(hint.state.message)
        assertFalse(hint.state.isHighlighted)
    }

    @Test
    fun dismissingBeforeTheCartAnswersNeverShowsOrRecordsTheHint() =
        runBlocking {
            val storage = MemoryStorage()
            val hint = RouteCartHint(storage)
            val reply = CompletableDeferred<CartOutcome>()
            val announcements = mutableListOf<String>()
            val presentation = async { hint.present(newManualDraft(), { reply.await() }, { announcements += it }) { } }
            while (!hint.state.isHighlighted) kotlinx.coroutines.yield()
            hint.dismiss()
            reply.complete(CartOutcome(CartLoadState.LOADED, isEmpty = true))
            presentation.await()
            assertTrue(announcements.isEmpty())
            assertFalse(storage.seenFlag)
        }

    @Test
    fun leavingTheEditorWhileWaitingClearsTheRingAndKeepsTheHintForNextTime() =
        runBlocking {
            val storage = MemoryStorage()
            val hint = RouteCartHint(storage)
            val never = CompletableDeferred<CartOutcome>()
            val presentation = async { hint.present(newManualDraft(), { never.await() }, {}) { } }
            while (!hint.state.isHighlighted) kotlinx.coroutines.yield()
            presentation.cancelAndJoin()
            assertFalse(hint.state.isHighlighted)
            assertNull(hint.state.message)
            assertFalse(storage.seenFlag)
        }

    @Test
    fun aSecondPresentWhileHighlightedIsIgnored() =
        runBlocking {
            val hint = RouteCartHint(MemoryStorage())
            val never = CompletableDeferred<CartOutcome>()
            var asked = 0
            val first = async { hint.present(newManualDraft(), { never.await() }, {}) { } }
            while (!hint.state.isHighlighted) kotlinx.coroutines.yield()
            hint.present(newManualDraft(), {
                asked += 1
                never.await()
            }, {}) { }
            assertEquals(0, asked)
            first.cancelAndJoin()
        }

    // MARK: 가이드 → AI 마법사로 나가기 (PlannerExit)

    /** `AlertCell`: 어느 단추든 onDismiss 를 먼저, 그다음 단추 동작을 부른다. */
    private fun press(
        onDismiss: () -> Unit,
        action: () -> Unit,
    ) {
        onDismiss()
        action()
    }

    @Test
    fun discardingAfterAskingForThePlannerOpensTheWizardEvenThoughDismissRunsFirst() {
        var pending = false
        var closed = 0
        val exit = PlannerExit { pending = it }
        assertTrue(exit.request(planner = true, changed = true) { closed += 1 })
        assertFalse(pending) // 묻는 동안에는 표시가 없다
        press(onDismiss = {}) { exit.discard { closed += 1 } }
        assertEquals(1, closed)
        assertTrue(pending)
    }

    @Test
    fun keepEditingAndOutsideDismissLeaveNoPlannerFlag() {
        var pending = false
        val exit = PlannerExit { pending = it }
        assertTrue(exit.request(planner = true, changed = true) {})
        press(onDismiss = {}) { exit.keepEditing() }
        assertFalse(pending)
        // 바깥을 누르거나 뒤로 가기로 닫은 경우: 단추 동작 없이 onDismiss 만 불린다.
        assertTrue(exit.request(planner = true, changed = true) {})
        assertFalse(pending)
        // 그 뒤 일반 「취소」→「버리기」는 마법사를 열지 않는다(옛 의도가 남지 않는다).
        assertTrue(exit.request(planner = false, changed = true) {})
        press(onDismiss = {}) { exit.discard {} }
        assertFalse(pending)
    }

    @Test
    fun unchangedCourseClosesAtOnceAndOpensTheWizardOnlyWhenAsked() {
        var pending = false
        var closed = 0
        val exit = PlannerExit { pending = it }
        assertFalse(exit.request(planner = true, changed = false) { closed += 1 })
        assertTrue(pending)
        assertFalse(exit.request(planner = false, changed = false) { closed += 1 })
        assertFalse(pending)
        assertEquals(2, closed)
    }

    // MARK: 문구

    @Test
    fun newCopyIsInKoreanAndEnglishTables() {
        val keys =
            listOf(
                RouteCartHint.messageKey(cartIsEmpty = true),
                RouteCartHint.messageKey(cartIsEmpty = false),
                "지도 주변 촬영지 보기",
                "코스 추천받기 (AI)",
                "AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요.",
                "무엇을 도와드릴까요?",
                "장소를 코스에 담기 전에도 주변을 찾아볼 수 있어요.",
                "AI에게 주변 장소 물어보기",
                "추천 장소는 골라 담으세요. 일정 변경 요청은 초안에 반영돼요.",
                "장바구니에 담긴 장소가 없어요.",
                "장바구니 장소 보기",
                "지도 중심 주변",
                "내 위치 주변",
                "현재 지도 중심 주변을 찾아 드립니다.",
                "지도에 표시된 내 위치 주변을 찾아 드립니다.",
                "지도의 위치를 확인하고 있어요. 질문을 미리 적어 두세요.",
                "주변 촬영지",
                "지도 중심 주변 촬영지입니다. AI를 사용하지 않아요.",
                "내 위치 주변 촬영지입니다. AI를 사용하지 않아요.",
                "내 위치로",
                "내 위치를 사용할 수 없어 지도 중심으로 찾아요.",
                "검색 기준을 선택해 주세요",
                "주변에 촬영지가 없어요",
                "지도를 다른 곳으로 옮겨 다시 찾아보세요.",
                "%s 코스에 담기",
                "%s · 코스에 담김",
                "코스를 불러오지 못했어요",
                "다시 시도",
                "새 일정은 「AI 로 짜기」에서도 만들 수 있어요",
                "AI 로 짜기 열기",
            ) + RouteGuideStartCopy.PROMPTS_KO
        for (key in keys) {
            val english = englishOf(key)
            assertNotNull(english, key)
            assertNotEquals(key, english, key)
        }
        assertEquals(
            listOf("주변 촬영지 알려줘", "주변 음식점 알려줘", "주변 카페 알려줘", "주변 명소 알려줘"),
            RouteGuideStartCopy.PROMPTS_KO,
        )
        assertEquals("Save places to add them to your course here", englishOf("장소를 담으면 여기서 코스에 넣을 수 있어요"))
        assertEquals("Start your course with places you saved", englishOf("담아둔 장소로 코스를 시작해 보세요"))
    }

    private fun place(id: Long) = PlaceSummary(id = id, name = "촬영지 $id", latitude = 35.2, longitude = 129.1)

    private fun cartItem(id: Long) = CartItem(placeId = id, name = "담아둔 곳", addedAt = OffsetDateTime.parse("2026-10-16T00:00:00+09:00"))

    private fun cartClient(items: List<CartItem>) =
        object : CartClient {
            override fun fetch(): List<CartItem> = items

            override fun add(
                placeId: Long,
                sourceContentId: Long?,
            ) = Unit

            override fun remove(placeId: Long) = Unit
        }
}
