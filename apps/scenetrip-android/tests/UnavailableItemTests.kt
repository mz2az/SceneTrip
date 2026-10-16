package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.data.CourseServer
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.RouteCourseSession
import com.mz2az.scenetrip.data.UnavailableItem
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.routetab.RouteAvailability
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteDayEstimate
import com.mz2az.scenetrip.routetab.RouteGeometry
import com.mz2az.scenetrip.routetab.RouteGuidePlan
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.infrastructure.Serializer
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseCreate
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemSource
import com.mz2az.scenetrip.sceneapi.client.model.CourseReplace
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

/**
 * MZ2AZ-458 — 담아 둔 항목이 목록에서 빠졌거나(숨김) 폐업했을 때의 안내와 이동 규칙. iOS `UnavailableItemTests`와 같은
 * 시험을 옮겼다. 서버가 이 칸을 빼고 보내면 정상이고, 지우거나 저장 ID를 바꾸는 근거로 쓰지 않는다.
 */
class UnavailableItemTests {
    private val added = OffsetDateTime.parse("2026-10-16T00:00:00+09:00")

    @Test
    fun generatedClientDecodesOptionalStateWithoutInventingTrue() {
        val adapter = Serializer.moshi.adapter(ContentSummary::class.java)
        for (suffix in listOf("", ",\"delisted\":false", ",\"delisted\":true")) {
            val json = "{\"id\":2,\"category\":\"drama\",\"title\":\"작품\",\"placeCount\":1$suffix}"
            val work = adapter.fromJson(json)!!
            assertEquals(if (suffix.contains("true")) UnavailableItem.CONTENT else null, UnavailableItem.notice(work))
        }
        val cart =
            Serializer.moshi.adapter(CartItem::class.java).fromJson(
                "{\"placeId\":1,\"name\":\"장소\",\"addedAt\":\"2026-10-16T00:00:00+09:00\",\"delisted\":true}",
            )!!
        assertEquals(UnavailableItem.PLACE, UnavailableItem.notice(cart))
        val course =
            Serializer.moshi.adapter(CourseItem::class.java).fromJson(
                "{\"id\":3,\"source\":\"poi\",\"name\":\"가게\",\"latitude\":37.0,\"longitude\":127.0,\"dwellMinutes\":30,\"poiId\":9,\"closed\":true}",
            )!!
        assertEquals(UnavailableItem.CLOSED_POI, UnavailableItem.notice(course))
    }

    @Test
    fun savedRowsDefaultToAvailableAndRecoverWhenFalse() {
        var cart = CartItem(placeId = 1, name = "장소", addedAt = added)
        var work = work()
        assertNull(UnavailableItem.notice(cart))
        assertNull(UnavailableItem.notice(work))
        cart = cart.copy(delisted = true)
        work = work.copy(delisted = true)
        assertEquals(UnavailableItem.PLACE, UnavailableItem.notice(cart))
        assertEquals(UnavailableItem.CONTENT, UnavailableItem.notice(work))
        cart = cart.copy(delisted = false)
        work = work.copy(delisted = false)
        assertNull(UnavailableItem.notice(cart))
        assertNull(UnavailableItem.notice(work))
    }

    @Test
    fun courseNoticeUsesOnlyTheFlagForItsSource() {
        var item = courseItem(CourseItemSource.place)
        assertNull(UnavailableItem.notice(item))
        item = item.copy(delisted = true, closed = true)
        assertEquals(UnavailableItem.PLACE, UnavailableItem.notice(item))
        item = item.copy(source = CourseItemSource.poi)
        assertEquals(UnavailableItem.CLOSED_POI, UnavailableItem.notice(item))
        item = item.copy(closed = false)
        assertNull(UnavailableItem.notice(item))
        item = item.copy(source = CourseItemSource.customPin, closed = true)
        assertNull(UnavailableItem.notice(item))
    }

    @Test
    fun detailAllowsRemovalButBlocksNewAdds() {
        for (flag in listOf<Boolean?>(null, false, true)) {
            assertEquals(flag != true, UnavailableItem.canToggle(delisted = flag, saved = false))
            assertTrue(UnavailableItem.canToggle(delisted = flag, saved = true))
        }
        val summary = work().copy(delisted = true)
        var detail = detail(delisted = false)
        assertTrue(UnavailableItem.contentDelisted(summary, null))
        assertFalse(UnavailableItem.contentDelisted(summary, detail), "다시 소개하면 최신 상세가 우선")
        detail = detail(delisted = true)
        assertTrue(UnavailableItem.contentDelisted(summary, detail))
        detail = detail(delisted = null)
        assertFalse(UnavailableItem.contentDelisted(summary, detail))
    }

    @Test
    fun conflictCodesAndUnknownConflictsAreDistinct() {
        val messages =
            mapOf(
                "PLACE_DELISTED" to "목록에서 빠진 장소라 담을 수 없어요.",
                "CONTENT_DELISTED" to "목록에서 빠진 작품이라 찜할 수 없어요.",
                "POI_CLOSED" to "폐업한 곳이라 코스에 넣을 수 없어요.",
            )
        for ((code, message) in messages) {
            val failure = ApiFailure.of(conflict(code))
            assertEquals(409, failure.statusCode)
            assertEquals(message, failure.message)
            assertTrue(failure.courseSaveMessage.startsWith(message))
            assertTrue(failure.courseSaveMessage.contains("편집 내용은 그대로예요"))
            assertFalse(failure.isRetryable)
        }
        for (code in listOf("DUPLICATE_CART_ITEM", "UNKNOWN", "IDEMPOTENCY_IN_PROGRESS")) {
            assertNull(UnavailableItem.rejection(409, code))
        }
        assertNull(UnavailableItem.rejection(400, "PLACE_DELISTED"))
        assertNull(UnavailableItem.rejection(409, null))
        // 일반 409 는 코스 저장 안내에 숨김·폐업 문구가 섞이지 않는다.
        assertEquals(ApiFailure.of(conflict("UNKNOWN")).message, ApiFailure.of(conflict("UNKNOWN")).courseSaveMessage)
    }

    @Test
    fun onlyDuplicateConflictMarksPlaceAsSavedWhenReloadFails() =
        runBlocking {
            for (code in listOf("DUPLICATE_CART_ITEM", "PLACE_DELISTED", "UNKNOWN")) {
                val client = CartFake(addError = conflict(code), fetchFails = true)
                val cart = CartSession(client)

                val saved = cart.add(99)

                assertFalse(saved, code)
                assertEquals(code == "DUPLICATE_CART_ITEM", cart.contains(99), code)
                when (code) {
                    "PLACE_DELISTED" -> assertEquals("목록에서 빠진 장소라 담을 수 없어요.", cart.toast)
                    "DUPLICATE_CART_ITEM" -> assertEquals("이미 저장된 장소입니다", cart.toast)
                    else -> assertEquals("담지 못했습니다. 잠시 후 다시 시도해 주세요", cart.toast)
                }
            }
        }

    @Test
    fun delistedRowCanBeRemovedFromObservedCart() =
        runBlocking {
            val client = CartFake(items = mutableListOf(cartItem(48, delisted = true), cartItem(49)))
            val cart = CartSession(client)
            cart.refresh()
            assertEquals(UnavailableItem.PLACE, UnavailableItem.notice(cart.items[0]))

            cart.remove(48)

            assertEquals(listOf(49L), cart.items.map { it.placeId })
            assertFalse(cart.contains(48))
            assertEquals(listOf(48L), client.deleted)
        }

    @Test
    fun existingCourseRowsKeepIdsAndNewRowsRemainNew() {
        val place = courseItem(CourseItemSource.place).copy(delisted = true)
        val poi = courseItem(CourseItemSource.poi).copy(id = 22, closed = true)
        val stops = listOf(RouteBridge.stop(place), RouteBridge.stop(poi))
        assertEquals(listOf(UnavailableItem.PLACE, UnavailableItem.CLOSED_POI), stops.map { it.unavailable })
        val course = RouteCourse(serverId = 8, title = "편집", days = listOf(RouteDay(stops = stops)))
        val sent =
            RouteBridge
                .replace(course)
                .days[0]
                .items
        assertEquals(listOf(11L, 22L), sent.map { it.id }, "숨김·폐업 줄도 저장에서 빼지 않는다")
        val readded = stops[0].copy(serverItemId = null)
        assertNull(RouteBridge.item(readded).id, "다시 추가한 줄에 옛 ID를 지어내지 않는다")
    }

    @Test
    fun saveRejectionKeepsTheEditedCourseAndTheRowIds() =
        runBlocking {
            val server =
                object : CourseServer {
                    val sent = mutableListOf<CourseReplace>()

                    override fun get(courseId: Long): CourseDetail = throw IOException("unused")

                    override fun create(body: CourseCreate): CourseDetail = throw IOException("unused")

                    override fun replace(
                        courseId: Long,
                        body: CourseReplace,
                    ): CourseDetail {
                        sent += body
                        throw conflict("PLACE_DELISTED")
                    }

                    override fun delete(courseId: Long) = Unit
                }
            val hidden = RouteBridge.stop(courseItem(CourseItemSource.place).copy(delisted = true))
            val course = RouteCourse(serverId = 8, title = "편집", detailLoaded = true, days = listOf(RouteDay(stops = listOf(hidden))))
            val session = RouteCourseSession(server)

            assertNull(session.save(course))

            val failure = session.saveFailure!!
            assertEquals("PLACE_DELISTED", failure.apiCode)
            assertTrue(failure.courseSaveMessage.contains("편집 내용은 그대로예요"))
            assertEquals(
                listOf(11L),
                server.sent
                    .single()
                    .days[0]
                    .items
                    .map { it.id },
            )
            // 저장 실패는 코스 사본을 건드리지 않는다 — 화면이 들고 있는 편집 내용이 그대로다.
            assertEquals(hidden, course.days[0].stops[0])
        }

    @Test
    fun nextStopAndDistancesSkipUnavailableRowsWithoutRenumbering() {
        val first = stop(1, 127.0)
        val hidden = stop(2, 129.0, UnavailableItem.PLACE)
        val closed = stop(3, 130.0, UnavailableItem.CLOSED_POI)
        val last = stop(4, 127.1)
        val rows = listOf(first, hidden, closed, last)
        assertEquals(1, RouteAvailability.nextUnvisited(rows)?.first)
        val visited = first.copy(visited = true)
        assertEquals(4, RouteAvailability.nextUnvisited(listOf(visited, hidden, closed, last))?.first)
        val direct = RouteGeometry.kilometers(first.place, last.place)
        assertEquals(direct, RouteGeometry.totalKilometers(rows), 0.00001)
        assertEquals(direct, RouteAvailability.nextKilometers(0, rows)!!, 0.00001)
        for (index in 1..3) {
            assertNull(RouteAvailability.nextKilometers(index, rows))
        }
        assertNull(RouteAvailability.nextUnvisited(listOf(hidden, closed)))
        assertEquals(0.0, RouteGeometry.totalKilometers(listOf(hidden, closed)), 0.0)
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.place.id })
    }

    @Test
    fun unavailableRowsInvalidateServerTravelEstimate() {
        val rows = listOf(stop(1, 127.0), stop(2, 128.0, UnavailableItem.PLACE))
        val estimate = RouteDayEstimate(rows, 160)
        assertNull(estimate.total(rows))
        assertEquals(RouteStop.DEFAULT_STAY_MINUTES, RouteAvailability.stayTotal(rows))
        // 전부 갈 수 있는 곳이면 서버의 이동 포함 시간이 그대로 쓰인다.
        val usable = listOf(stop(1, 127.0), stop(2, 128.0))
        assertEquals(160, RouteDayEstimate(usable, 160).total(usable))
    }

    @Test
    fun guideMoveAndRevisePreserveUnavailableStateAndSkipRules() {
        val hidden =
            courseItem(
                CourseItemSource.place,
            ).copy(delisted = true, name = "숨긴 곳", latitude = 37.1, longitude = 127.1, placeId = 5)
        val closed =
            courseItem(CourseItemSource.poi)
                .copy(id = 22, closed = true, name = "폐업한 가게", latitude = 37.2, longitude = 127.2, poiId = 7, placeId = null)
        val visited = stop(4, 127.3).copy(visited = true, serverItemId = 14, place = place(4, 127.3, "방문한 곳"))
        val first = stop(3, 127.0).copy(serverItemId = 13, place = place(3, 127.0, "첫 곳"))
        val original =
            RouteCourse(
                serverId = 8,
                title = "편집",
                detailLoaded = true,
                days = listOf(RouteDay(stops = listOf(first, RouteBridge.stop(hidden), RouteBridge.stop(closed), visited))),
            )
        val plan = RouteGuidePlan.plan(original)
        val reversed =
            plan.copy(
                days =
                    plan.days.map { day ->
                        day.copy(
                            stops =
                                day.stops.reversed().mapIndexed { index, each ->
                                    each.copy(
                                        order =
                                            index + 1,
                                    )
                                },
                        )
                    },
            )

        val revised = RouteGuidePlan.revising(original, reversed)

        val before = original.days[0].stops
        val after = revised.days[0].stops
        assertEquals(listOf(null, UnavailableItem.CLOSED_POI, UnavailableItem.PLACE, null), after.map { it.unavailable })
        assertEquals(listOf<Long?>(14, 22, 11, 13), after.map { it.serverItemId })
        assertTrue(after[0].visited)
        for (rows in listOf(before, after)) {
            val next = RouteAvailability.nextUnvisited(rows)!!.second
            assertTrue(next.canNavigate && !next.visited)
            assertNull(RouteAvailability.nextKilometers(rows.indexOfFirst { it.unavailable != null }, rows))
            val optimized = RouteGeometry.optimized(rows, pinStart = false, pinEnd = false)
            rows.forEachIndexed { index, row -> if (!row.canNavigate) assertEquals(row.id, optimized[index].id) }
        }
        // 서버 번호·진행 상태는 이어받는다.
        assertEquals(8L, revised.serverId)
        assertTrue(revised.detailLoaded)
    }

    @Test
    fun guideRevisionPreservesUnavailableTargetsAcrossDuplicateRows() {
        for ((oldCount, newCount) in listOf(2 to 2, 1 to 2, 2 to 1)) {
            val hidden =
                courseItem(
                    CourseItemSource.place,
                ).copy(delisted = true, placeId = 3, name = "숨긴 곳", latitude = 37.1, longitude = 127.1)
            val closed =
                courseItem(CourseItemSource.poi)
                    .copy(closed = true, poiId = 3, placeId = null, name = "폐업한 가게", latitude = 37.2, longitude = 127.2)
            val days =
                (0 until oldCount).map { day ->
                    val rows =
                        listOf(
                            RouteBridge.stop(hidden.copy(id = 10L + day)).copy(visited = true),
                            RouteBridge.stop(closed.copy(id = 20L + day)).copy(visited = true),
                        )
                    RouteDay(stops = rows + stop(4, 127.5))
                }
            val original = RouteCourse(serverId = 8, title = "편집", detailLoaded = true, days = days)
            val plan = RouteGuidePlan.plan(original)
            val template = plan.days[0].stops
            val replanned =
                plan.copy(
                    days =
                        (1..newCount).map { number ->
                            plan.days[0].copy(
                                day = number,
                                stops =
                                    template.map {
                                        it.copy(
                                            dwellMinutes =
                                                if (it.order ==
                                                    3
                                                ) {
                                                    60
                                                } else {
                                                    it.dwellMinutes
                                                },
                                        )
                                    },
                            )
                        },
                )

            val revised = RouteGuidePlan.revising(original, replanned)

            assertEquals(newCount, revised.days.size)
            for (day in revised.days) {
                assertEquals(
                    listOf(UnavailableItem.PLACE, UnavailableItem.CLOSED_POI, null),
                    day.stops.map { it.unavailable },
                    "$oldCount->$newCount",
                )
                assertEquals(60, day.stops[2].stayMinutes)
                assertEquals(3, RouteAvailability.nextUnvisited(day.stops)?.first)
                assertEquals(0.0, RouteGeometry.totalKilometers(day.stops.take(2)), 0.0)
            }
        }
    }

    @Test
    fun startDoesNotActivateDayWithOnlyUnavailableRemaining() {
        val visited = stop(1, 127.0).copy(visited = true)
        val hidden = stop(2, 128.0, UnavailableItem.PLACE)
        val closed = stop(3, 129.0, UnavailableItem.CLOSED_POI)
        for (rows in listOf(listOf(hidden, closed), listOf(visited, hidden, closed), emptyList())) {
            val course =
                RouteCourse(serverId = 8, title = "여행", days = listOf(RouteDay(stops = listOf(stop(4, 127.1))), RouteDay(stops = rows)))
            assertNull(RouteAvailability.start(course, 1))
            assertFalse(course.isRunning, "다른 일차나 이미 방문한 곳으로 대신 시작하지 않는다")
            assertEquals(rows.isNotEmpty(), RouteAvailability.hasOnlyUnavailableRemaining(rows))
        }
        val completed = RouteAvailability.hasOnlyUnavailableRemaining(listOf(visited))
        assertFalse(completed, "방문 완료는 별도 상태다")
    }

    @Test
    fun startActivatesSelectedDayAtFirstVisitableUnvisitedStop() {
        val visited = stop(4, 127.2).copy(visited = true)
        val available = stop(3, 127.1)
        val course =
            RouteCourse(
                serverId = 8,
                title = "여행",
                days =
                    listOf(
                        RouteDay(stops = listOf(stop(1, 127.0))),
                        RouteDay(stops = listOf(visited, stop(2, 128.0, UnavailableItem.PLACE), available)),
                    ),
            )
        val (started, target) = RouteAvailability.start(course, 1)!!
        assertEquals(available.id, target.id)
        assertTrue(started.isRunning)
        assertFalse(RouteAvailability.hasOnlyUnavailableRemaining(started.days[1].stops))
        // 전부 방문했으면 다시 시작할 첫 방문 가능 행으로.
        val done =
            RouteCourse(
                serverId = 8,
                title = "여행",
                days =
                    listOf(
                        RouteDay(
                            stops =
                                listOf(
                                    stop(5, 127.0, UnavailableItem.PLACE).copy(visited = true),
                                    visited,
                                    available.copy(visited = true),
                                ),
                        ),
                    ),
            )
        assertEquals(visited.id, RouteAvailability.start(done, 0)!!.second.id)
    }

    @Test
    fun optimizationPreservesUnavailableRowsAndUsesOnlyAvailableStops() {
        val first = stop(1, 127.2)
        val hidden = stop(2, 127.0, UnavailableItem.PLACE)
        val nearest = stop(3, 127.01)
        val closed = stop(4, 129.0, UnavailableItem.CLOSED_POI)
        val last = stop(5, 127.1)
        val rows = listOf(first, hidden, nearest, closed, last)
        val here = PlaceSummary(id = 0, name = "현재", latitude = 37.0, longitude = 127.0)
        val starting = RouteGeometry.startingNearest(rows, here)
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), starting.map { it.place.id })
        val optimized = RouteGeometry.optimized(rows, pinStart = false, pinEnd = false)
        assertEquals(hidden.id, optimized[1].id)
        assertEquals(closed.id, optimized[3].id)
        assertEquals(rows.map { it.id }.toSet(), optimized.map { it.id }.toSet())
        val activeOnly = RouteGeometry.optimized(listOf(first, nearest, last), pinStart = false, pinEnd = false)
        assertEquals(RouteGeometry.totalKilometers(activeOnly), RouteGeometry.totalKilometers(optimized), 0.00001)
        // 지금 위치의 기준점도 갈 수 있는 곳만 본다.
        assertNull(RouteGeometry.usableAnchor(here, listOf(hidden, closed)))
        assertNotNull(RouteGeometry.usableAnchor(here, listOf(hidden, nearest)))
    }

    @Test
    fun unavailableRowsCannotBeNavigationTargets() {
        // TripSession 은 Android 컨텍스트가 필요해 JVM 시험에서 만들 수 없다. 시작·다음 목적지 선택이 같은 규칙(`canNavigate`)을 쓴다.
        for (notice in listOf(UnavailableItem.PLACE, UnavailableItem.CLOSED_POI)) {
            val only = stop(1, 127.0, notice)
            assertFalse(only.canNavigate)
            assertNull(RouteAvailability.startTarget(listOf(only)))
            assertNull(RouteAvailability.nextUnvisited(listOf(only)))
        }
        assertTrue(stop(1, 127.0).canNavigate)
        // 새로 추가하는 곳이 들어갈 「바로 다음 차례」 도 갈 수 있는 곳을 기준으로 한다.
        val rows = listOf(stop(1, 127.0, UnavailableItem.PLACE), stop(2, 127.1))
        assertEquals(1, RouteGeometry.nextSlot(rows, null, false))
        assertEquals(
            2,
            RouteGeometry.nextSlot(listOf(stop(1, 127.0, UnavailableItem.PLACE), stop(2, 127.1, UnavailableItem.CLOSED_POI)), null, false),
        )
    }

    @Test
    fun noticeAndRejectionsHaveKoreanAndEnglishStrings() {
        assertEquals("더 이상 소개하지 않는 장소예요", UnavailableItem.PLACE.message)
        assertEquals("더 이상 소개하지 않는 작품이에요", UnavailableItem.CONTENT.message)
        assertEquals("폐업했어요", UnavailableItem.CLOSED_POI.message)
        assertEquals("We no longer feature this place", englishOf("더 이상 소개하지 않는 장소예요"))
        assertEquals("We no longer feature this title", englishOf("더 이상 소개하지 않는 작품이에요"))
        assertEquals("Closed permanently", englishOf("폐업했어요"))
        for (code in listOf("PLACE_DELISTED", "CONTENT_DELISTED", "POI_CLOSED")) {
            val korean = UnavailableItem.rejection(409, code)!!
            assertTrue(englishOf(korean)!!.contains("can’t"), code)
        }
        assertNotNull(englishOf("갈 수 있는 곳이 없어요. 목록에서 빠지거나 폐업한 곳만 남았어요."))
        assertNotNull(englishOf("편집 내용은 그대로예요. 해당 항목을 빼고 다시 저장해 주세요."))
        assertNotNull(englishOf("찜을 바꾸지 못했습니다"))
    }

    @Test
    fun likeUploadOutcomeDoesNotRetryPermanentRejections() {
        assertEquals(LikeStore.Companion.UploadOutcome.GONE, LikeStore.uploadOutcome(ApiFailure.of(status(404, "CONTENT_NOT_FOUND"))))
        assertEquals(LikeStore.Companion.UploadOutcome.DELISTED, LikeStore.uploadOutcome(ApiFailure.of(conflict("CONTENT_DELISTED"))))
        assertEquals(LikeStore.Companion.UploadOutcome.RETRY, LikeStore.uploadOutcome(ApiFailure.of(conflict("UNKNOWN"))))
        assertEquals(LikeStore.Companion.UploadOutcome.RETRY, LikeStore.uploadOutcome(ApiFailure.of(IOException("offline"))))
        assertEquals(LikeStore.Companion.UploadOutcome.RETRY, LikeStore.uploadOutcome(null))
        val summaries = listOf(work(1), work(2).copy(delisted = true), work(3))
        assertEquals(listOf(2L, 3L), LikeStore.shown(summaries, setOf(2L, 3L)).map { it.id }, "하트가 꺼진 작품의 요약은 그리지 않는다")
    }

    // ───── 도구 ─────

    private class CartFake(
        val items: MutableList<CartItem> = mutableListOf(),
        val addError: Exception? = null,
        val fetchFails: Boolean = false,
    ) : CartClient {
        val deleted = mutableListOf<Long>()

        override fun fetch(): List<CartItem> {
            if (fetchFails) throw IOException("offline")
            return items.toList()
        }

        override fun add(
            placeId: Long,
            sourceContentId: Long?,
        ) {
            addError?.let { throw it }
            items += CartItem(placeId = placeId, name = "새 장소", addedAt = OffsetDateTime.parse("2026-10-16T00:00:00+09:00"))
        }

        override fun remove(placeId: Long) {
            deleted += placeId
            items.removeAll { it.placeId == placeId }
        }
    }

    private fun cartItem(
        placeId: Long,
        delisted: Boolean? = null,
    ) = CartItem(placeId = placeId, name = "장소 $placeId", addedAt = added, delisted = delisted)

    private fun status(
        code: Int,
        apiCode: String,
    ) = ClientException(
        "시험",
        code,
        ClientError<Unit>(body = "{\"code\":\"$apiCode\",\"message\":\"server\"}", statusCode = code, headers = emptyMap()),
    )

    private fun conflict(code: String) = status(409, code)

    private fun work(id: Long = 2) = ContentSummary(id = id, category = ContentCategory.drama, title = "작품", placeCount = 1)

    private fun detail(delisted: Boolean?) =
        ContentDetail(id = 2, category = ContentCategory.drama, title = "작품", placeCount = 1, delisted = delisted)

    private fun courseItem(source: CourseItemSource) =
        CourseItem(
            id = 11,
            source = source,
            name = "장소",
            latitude = 37.0,
            longitude = 127.0,
            dwellMinutes = 30,
            placeId = 1,
            poiId = 3,
        )

    private fun place(
        id: Long,
        longitude: Double,
        name: String = "장소",
    ) = PlaceSummary(id = id, name = name, latitude = 37.0, longitude = longitude)

    private fun stop(
        id: Long,
        longitude: Double,
        unavailable: UnavailableItem? = null,
    ) = RouteStop(place = place(id, longitude, "장소 $id"), unavailable = unavailable)
}
